package com.alibaba.mnn.english;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.io.FileInputStream;

/** Host entrypoint exercises exactly the Android frontend against the actual pinned assets. */
public final class KokoroFrontendSmoke {
    private static InputStreamReader reader(File root, String name) throws Exception {
        return new InputStreamReader(new FileInputStream(new File(root, name)), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        KokoroFrontend frontend = new KokoroFrontend(reader(root, "tokenizer.txt"), reader(root, "lexicon-zh.txt"));
        frontend.loadEnglish(reader(root, "lexicon-us-en.txt"));
        java.util.List<int[]> chunks = frontend.tokens(args[2]);
        try (PrintWriter output = new PrintWriter(args[1], "UTF-8")) {
            if (chunks.size() != 1) throw new IllegalArgumentException("Smoke input needs one chunk");
            for (int id : chunks.get(0)) output.print(id + " ");
        }
        System.out.println("normalized=" + KokoroFrontend.normalize(args[2]) + " token_count=" + chunks.get(0).length);
    }
}
