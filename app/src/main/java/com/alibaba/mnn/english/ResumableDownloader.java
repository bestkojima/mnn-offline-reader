package com.alibaba.mnn.english;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** One transfer at a time. Verified final files and persistent partial files have separate names. */
final class ResumableDownloader {
    interface Progress { void update(long bytes); }
    interface Connections { HttpURLConnection open(URL url) throws IOException; }
    private final Connections connections;
    private volatile boolean cancelled;
    private volatile HttpURLConnection active;

    ResumableDownloader() { this(url -> {
        if (!"https".equals(url.getProtocol())) throw new IOException("下载地址必须使用 HTTPS");
        return (HttpURLConnection) url.openConnection();
    }); }
    ResumableDownloader(Connections connections) { this.connections = connections; }
    void cancel() { cancelled = true; HttpURLConnection connection = active; if (connection != null) connection.disconnect(); }
    private void check() { if (cancelled) throw new CancellationException("下载已暂停"); }

    static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(Character.forDigit((value >> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
        return hash.toString();
    }

    static boolean verified(File file, long bytes, String hash) throws Exception {
        return file.isFile() && file.length() == bytes && sha256(file).equals(hash);
    }

    // Conservative actual free-space check is portable to API 23 and the host verifier; do not reclaim other apps' caches.
    @SuppressWarnings("UsableSpace")
    void download(String address, File target, long expected, String hash, Progress progress) throws Exception {
        if (expected < 1 || !hash.matches("[a-f0-9]{64}")) throw new IOException("下载清单无效");
        check();
        if (verified(target, expected, hash)) { progress.update(expected); return; }
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("无法创建模型目录");
        File part = new File(target.getPath() + ".part");
        if (part.length() > expected && !part.delete()) throw new IOException("无法清理无效下载");
        if (part.length() == expected) {
            if (verified(part, expected, hash)) { publish(part, target); progress.update(expected); return; }
            if (!part.delete()) throw new IOException("无法清理损坏下载");
        }
        long offset = part.length();
        long remaining = expected - offset;
        if (target.getParentFile().getUsableSpace() < remaining + 8 * 1024 * 1024) throw new IOException("存储空间不足，请清理后继续下载");
        progress.update(offset);
        HttpURLConnection connection = open(address, offset);
        try {
            int code = connection.getResponseCode();
            check();
            if (code != 200 && code != 206) throw new IOException("下载服务返回 HTTP " + code + "，可稍后继续");
            String encoding = connection.getContentEncoding();
            if (encoding != null && !"identity".equalsIgnoreCase(encoding)) throw new IOException("下载服务返回了压缩数据");
            long responseBytes;
            if (code == 206) {
                String header = connection.getHeaderField("Content-Range");
                Matcher range = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)").matcher(header == null ? "" : header);
                if (!range.matches() || Long.parseLong(range.group(1)) != offset
                        || Long.parseLong(range.group(3)) != expected || Long.parseLong(range.group(2)) >= expected
                        || Long.parseLong(range.group(2)) < offset) throw new IOException("断点响应无效，已保留进度");
                responseBytes = Long.parseLong(range.group(2)) - offset + 1;
            } else {
                // A server may ignore Range. Restart rather than append a full response to partial data.
                offset = 0; responseBytes = expected;
                if (target.getParentFile().getUsableSpace() + part.length() < expected + 8 * 1024 * 1024)
                    throw new IOException("存储空间不足，请清理后继续下载");
            }
            String length = connection.getHeaderField("Content-Length");
            if (length != null && Long.parseLong(length) != responseBytes) throw new IOException("下载长度与固定版本不符");
            long received = 0;
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(part, offset > 0)) {
                try {
                    byte[] buffer = new byte[65536]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        check();
                        if (received + count > responseBytes || offset + received + count > expected) throw new IOException("下载数据超出预期大小");
                        output.write(buffer, 0, count); received += count;
                        progress.update(offset + received);
                    }
                } finally { output.getFD().sync(); }
            }
            check();
            if (received != responseBytes || part.length() != expected) throw new IOException("下载中断，点击继续可从已保存进度恢复");
            if (!verified(part, expected, hash)) {
                if (!part.delete()) throw new IOException("模型校验失败，无法清理损坏文件");
                throw new IOException("模型校验失败，请重新下载");
            }
            check(); publish(part, target); progress.update(expected);
        } catch (IOException error) { check(); throw error; }
        finally { active = null; connection.disconnect(); }
    }

    private HttpURLConnection open(String address, long offset) throws Exception {
        URL url = new URL(address);
        for (int redirect = 0; redirect <= 5; redirect++) {
            check(); HttpURLConnection connection = connections.open(url); active = connection;
            connection.setConnectTimeout(15000); connection.setReadTimeout(20000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", "MnnReader/0.2");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int code = connection.getResponseCode();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("下载重定向无效");
                    url = new URL(url, location); connection.disconnect(); active = null;
                } else return connection;
            } catch (Exception error) { connection.disconnect(); active = null; check(); throw error; }
        }
        throw new IOException("下载重定向过多");
    }

    private static void publish(File part, File target) throws IOException {
        if (!part.renameTo(target)) throw new IOException("无法保存模型，请检查存储空间");
    }
}
