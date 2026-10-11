package com.alibaba.mnn.english;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Owns model downloads separately from speech and dictionary work. UI never performs network I/O. */
final class ModelDownloads {
    interface Listener { void update(String id, String message, boolean busy, boolean ready); }
    static final class MissingModel extends Exception {
        final String id;
        MissingModel(String id) { super("请先下载" + ("kokoro".equals(id) ? "中文语音" : "英语及其他语言语音")); this.id = id; }
    }
    static final class Pack {
        final String id, name, repository, revision;
        final File root;
        final JSONArray files;
        final long bytes;
        volatile boolean verified;
        Pack(Context context, JSONObject json) throws Exception {
            id = json.getString("id"); name = json.getString("name");
            repository = json.getString("modelscope_repository"); revision = json.getString("modelscope_revision");
            if (!repository.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+") || !revision.matches("[a-f0-9]{40}"))
                throw new IllegalStateException("模型下载版本无效");
            File base = context.getNoBackupFilesDir(); root = new File(base, json.getString("directory"));
            if (!root.getCanonicalPath().startsWith(base.getCanonicalPath() + File.separator)) throw new IllegalStateException("模型目录无效");
            files = json.getJSONArray("files"); long size = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i); File target = new File(root, file.getString("path"));
                if (!target.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                        || file.getLong("bytes") < 1 || !file.getString("sha256").matches("[a-f0-9]{64}"))
                    throw new IllegalStateException("模型文件清单无效");
                size += file.getLong("bytes");
            }
            bytes = size;
        }
        long storedBytes() throws Exception {
            long size = 0;
            for (int i = 0; i < files.length(); i++) {
                JSONObject file = files.getJSONObject(i); File target = new File(root, file.getString("path"));
                size += Math.min(file.getLong("bytes"), target.isFile() ? target.length() : new File(target.getPath() + ".part").length());
            }
            return size;
        }
    }

    private final List<Pack> packs = new ArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private volatile ResumableDownloader transfer;
    private volatile Listener listener;
    private volatile String activeId;
    private final Object lock = new Object();

    ModelDownloads(Context context) {
        try {
            JSONArray array = new JSONObject(ModelAssets.read(context.getAssets().open("model-catalog.json"))).getJSONArray("packs");
            for (int i = 0; i < array.length(); i++) packs.add(new Pack(context, array.getJSONObject(i)));
        } catch (Exception error) { throw new IllegalStateException("模型下载配置无效", error); }
    }

    List<Pack> packs() { return packs; }
    Pack pack(String id) {
        for (Pack pack : packs) if (pack.id.equals(id)) return pack;
        throw new IllegalArgumentException("未知语音资源");
    }
    void observe(Listener observer) {
        listener = observer;
        worker.execute(() -> {
            for (Pack pack : packs) try {
                long ticket = generation.get();
                boolean ready = verify(pack);
                if (!pack.id.equals(activeId)) post(pack.id, ready ? "已下载 · 可离线使用" : progress(pack.storedBytes(), pack.bytes), false, ready, ticket);
            } catch (Exception error) { if (!pack.id.equals(activeId)) post(pack.id, "读取下载进度失败", false, false, generation.get()); }
        });
    }
    void detach(Listener observer) { if (listener == observer) listener = null; }
    private void post(String id, String message, boolean busy, boolean ready, long ticket) {
        Listener observer = listener;
        main.post(() -> { if (ticket == generation.get() && observer != null && listener == observer) observer.update(id, message, busy, ready); });
    }
    private static String progress(long done, long total) {
        return String.format(Locale.ROOT, "%.1f / %.1f MB · %d%%", done / 1e6, total / 1e6, total == 0 ? 0 : done * 100 / total);
    }
    private boolean verify(Pack pack) throws Exception {
        if (pack.verified) return true;
        for (int i = 0; i < pack.files.length(); i++) {
            JSONObject file = pack.files.getJSONObject(i);
            if (!ResumableDownloader.verified(new File(pack.root, file.getString("path")), file.getLong("bytes"), file.getString("sha256"))) return false;
        }
        pack.verified = true; return true;
    }
    File require(String id) throws Exception {
        Pack pack = pack(id);
        if (!verify(pack)) throw new MissingModel(id);
        return pack.root;
    }
    void pause() {
        synchronized (lock) {
            long ticket = generation.incrementAndGet();
            if (transfer != null) transfer.cancel();
            String id = activeId; activeId = null;
            if (id != null) post(id, "已暂停 · 点击继续下载", false, false, ticket);
        }
    }
    void start(String id) {
        String previousId = activeId;
        pause();
        long ticket = generation.incrementAndGet(); activeId = id;
        if (previousId != null && !previousId.equals(id)) post(previousId, "已暂停 · 点击继续下载", false, false, ticket);
        post(id, "正在准备下载…", true, false, ticket);
        worker.execute(() -> {
            Pack pack = pack(id); ResumableDownloader download = new ResumableDownloader();
            synchronized (lock) { if (ticket != generation.get()) return; transfer = download; }
            try {
                long completed = 0; final long[] lastUpdate = {0};
                for (int i = 0; i < pack.files.length(); i++) {
                    if (ticket != generation.get()) throw new CancellationException();
                    JSONObject file = pack.files.getJSONObject(i); String path = file.getString("path");
                    final long previous = completed;
                    String url = "https://modelscope.cn/api/v1/models/" + pack.repository + "/repo?Revision="
                            + pack.revision + "&FilePath=" + URLEncoder.encode(path, "UTF-8");
                    download.download(url, new File(pack.root, path), file.getLong("bytes"), file.getString("sha256"), bytes -> {
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (ticket == generation.get() && now - lastUpdate[0] >= 200) {
                            lastUpdate[0] = now; post(id, progress(previous + bytes, pack.bytes), true, false, ticket);
                        }
                    });
                    completed += file.getLong("bytes");
                }
                if (ticket == generation.get()) { pack.verified = true; post(id, "已下载 · 可离线使用", false, true, ticket); }
            } catch (Exception error) {
                if (ticket == generation.get()) post(id, "下载未完成：" + (error.getMessage() == null ? "网络连接中断" : error.getMessage()) + "。点击继续", false, false, ticket);
            } finally {
                synchronized (lock) { if (transfer == download) transfer = null; if (ticket == generation.get()) activeId = null; }
            }
        });
    }
}
