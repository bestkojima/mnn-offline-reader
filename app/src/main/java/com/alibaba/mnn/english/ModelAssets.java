package com.alibaba.mnn.english;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Disk work runs off the UI thread. TTS resources are downloaded separately and verified before loading. */
final class ModelAssets {
    private final Context context;
    private File modelRoot;
    private File kokoroRoot;
    private KokoroFrontend kokoroFrontend;
    private final Map<String, float[]> kokoroVoices = new HashMap<>();
    private int[] indexer;
    private JSONObject config;
    private final Map<String, Style> styles = new HashMap<>();

    ModelAssets(Context context) { this.context = context.getApplicationContext(); }

    static String read(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream result = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) result.write(buffer, 0, count);
            return new String(result.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void copyChecked(String asset, File target, String expected) throws Exception {
        if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IllegalStateException("存储空间不足");
        File staging = new File(target.getPath() + ".part");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = context.getAssets().open(asset); FileOutputStream output = new FileOutputStream(staging)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); digest.update(buffer, 0, count); }
            output.getFD().sync();
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!hash.toString().equals(expected)) { staging.delete(); throw new IllegalStateException("离线资源校验失败"); }
        if (!staging.renameTo(target)) throw new IllegalStateException("无法安装离线资源，请检查可用空间");
    }

    synchronized File dictionary() throws Exception {
        JSONObject manifest = new JSONObject(read(context.getAssets().open("dictionary-manifest.json")));
        String hash = manifest.getString("dictionary_sha256");
        File target = new File(context.getNoBackupFilesDir(), "dictionary-" + hash + ".db");
        if (!target.isFile() || target.length() != manifest.getLong("bytes")) copyChecked("dictionary.db", target, hash);
        return target;
    }

    synchronized File models() throws Exception {
        if (modelRoot != null) return modelRoot;
        JSONObject manifest = modelManifest("supertonic");
        if (!"v3".equals(manifest.getString("version")) || !"int8".equals(manifest.getString("precision"))) {
            throw new IllegalStateException("需要 Supertonic 3 int8 资源");
        }
        File root = ((ReaderApplication) context).models.require("supertonic");
        config = new JSONObject(read(new FileInputStream(new File(root, "mnn_models/tts.json"))));
        JSONArray mapping = new JSONArray(read(new FileInputStream(new File(root, "mnn_models/unicode_indexer.json"))));
        indexer = new int[mapping.length()];
        for (int i = 0; i < indexer.length; i++) indexer[i] = mapping.getInt(i);
        modelRoot = root;
        return root;
    }

    int sampleRate() throws Exception { models(); return config.getJSONObject("ae").getInt("sample_rate"); }
    long createEngine() throws Exception {
        File root = models();
        JSONObject ae = config.getJSONObject("ae"), ttl = config.getJSONObject("ttl");
        return SupertonicNative.create(new File(root, "mnn_models/int8").getPath(), ae.getInt("sample_rate"),
                ae.getInt("base_chunk_size"), ttl.getInt("chunk_compress_factor"), ttl.getInt("latent_dim"));
    }

    synchronized File kokoroModels() throws Exception {
        if (kokoroRoot != null) return kokoroRoot;
        JSONObject manifest = modelManifest("kokoro");
        if (!"kokoro-v1.0".equals(manifest.getString("version")) || manifest.getInt("sample_rate") != 24000)
            throw new IllegalStateException("需要固定 Kokoro v1.0 资源");
        File root = ((ReaderApplication) context).models.require("kokoro");
        kokoroRoot = root; return root;
    }

    private JSONObject modelManifest(String id) throws Exception {
        JSONArray packs = new JSONObject(read(context.getAssets().open("model-catalog.json"))).getJSONArray("packs");
        for (int i = 0; i < packs.length(); i++) if (id.equals(packs.getJSONObject(i).getString("id"))) return packs.getJSONObject(i);
        throw new IllegalStateException("模型清单缺失");
    }

    long createKokoroEngine() throws Exception { return KokoroNative.create(new File(kokoroModels(), "tts.mnn").getPath()); }

    java.util.List<int[]> kokoroTokens(String text) throws Exception {
        File root = kokoroModels();
        if (kokoroFrontend == null) kokoroFrontend = new KokoroFrontend(
                new java.io.InputStreamReader(new FileInputStream(new File(root, "tokenizer.txt")), StandardCharsets.UTF_8),
                new java.io.InputStreamReader(new FileInputStream(new File(root, "lexicon-zh.txt")), StandardCharsets.UTF_8));
        if (kokoroFrontend.needsEnglish(text)) kokoroFrontend.loadEnglish(
                new java.io.InputStreamReader(new FileInputStream(new File(root, "lexicon-us-en.txt")), StandardCharsets.UTF_8));
        return kokoroFrontend.tokens(text);
    }

    float[] kokoroStyle(String voice, int tokenCount) throws Exception {
        if (!voice.equals("zf_xiaoxiao") && !voice.equals("zm_yunxi")) throw new IllegalArgumentException("未知中文音色");
        if (tokenCount < 1 || tokenCount > 200) throw new IllegalArgumentException("中文音素片段过长");
        float[] table = kokoroVoices.get(voice);
        if (table == null) {
            File file = new File(kokoroModels(), "voices/" + voice + ".bin");
            if (file.length() != 510 * 256 * 4) throw new IllegalStateException("中文音色数据无效");
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.DataInputStream input = new java.io.DataInputStream(new FileInputStream(file))) { input.readFully(bytes); }
            table = new float[bytes.length / 4];
            java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(table);
            kokoroVoices.put(voice, table);
        }
        return java.util.Arrays.copyOfRange(table, tokenCount * 256, (tokenCount + 1) * 256);
    }

    void releaseKokoroFrontend() { kokoroFrontend = null; kokoroVoices.clear(); }

    int[] tokens(String text) throws Exception {
        return tokens(text, "en");
    }

    int[] tokens(String text, String language) throws Exception {
        models();
        String normalized = TextNormalizer.normalize(text, language);
        int[] points = new int[normalized.codePointCount(0, normalized.length())];
        for (int offset = 0, i = 0; offset < normalized.length(); i++) {
            int cp = normalized.codePointAt(offset); points[i] = cp; offset += Character.charCount(cp);
        }
        for (int i = 0; i < points.length; i++) {
            if (points[i] >= indexer.length || indexer[points[i]] < 0) throw new IllegalArgumentException("包含模型不支持的字符，请缩小选区或更换文字");
            points[i] = indexer[points[i]];
        }
        return points;
    }

    Style style(String voice) throws Exception {
        if (!voice.matches("[FM][1-5]")) throw new IllegalArgumentException("未知音色");
        models();
        Style cached = styles.get(voice);
        if (cached != null) return cached;
        JSONObject json = new JSONObject(read(new FileInputStream(new File(modelRoot, "voice_styles/" + voice + ".json"))));
        Style result = new Style(json);
        styles.put(voice, result);
        return result;
    }

    static final class Style {
        final int[] ttlShape, dpShape;
        final float[] ttl, dp;
        Style(JSONObject json) throws Exception {
            ttlShape = dimensions(json.getJSONObject("style_ttl").getJSONArray("dims"));
            dpShape = dimensions(json.getJSONObject("style_dp").getJSONArray("dims"));
            ttl = data(json.getJSONObject("style_ttl").getJSONArray("data"), ttlShape);
            dp = data(json.getJSONObject("style_dp").getJSONArray("data"), dpShape);
        }
        private static int[] dimensions(JSONArray dims) throws Exception {
            if (dims.length() != 3 || dims.getInt(0) != 1 || dims.getInt(1) < 1 || dims.getInt(1) > 256
                    || dims.getInt(2) < 1 || dims.getInt(2) > 1024) throw new IllegalStateException("音色维度无效");
            return new int[]{1, dims.getInt(1), dims.getInt(2)};
        }
        private static float[] data(JSONArray array, int[] dims) throws Exception {
            float[] values = new float[dims[1] * dims[2]];
            int count = flatten(array, values, 0);
            if (count != values.length) throw new IllegalStateException("音色数据不完整");
            return values;
        }
        private static int flatten(JSONArray array, float[] values, int offset) throws Exception {
            for (int i = 0; i < array.length(); i++) {
                Object item = array.get(i);
                if (item instanceof JSONArray) offset = flatten((JSONArray) item, values, offset);
                else {
                    if (!(item instanceof Number) || offset >= values.length) throw new IllegalStateException("音色数据无效");
                    values[offset++] = ((Number) item).floatValue();
                }
            }
            return offset;
        }
    }
}
