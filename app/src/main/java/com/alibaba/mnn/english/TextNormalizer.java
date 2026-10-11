package com.alibaba.mnn.english;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** V3 preprocessing; exactly one language wrapper is added after normalization. */
public final class TextNormalizer {
    private TextNormalizer() {}

    public static String lookupKey(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).trim()
                .replace('’', '\'').toLowerCase(Locale.ROOT)
                .replaceAll("^[\\p{P}\\s]+|[\\p{P}\\s]+$", "");
    }

    public static String normalize(String text) {
        return normalize(text, "en");
    }

    public static String normalize(String text, String language) {
        if (!TtsLanguages.supertonicSupports(language)) throw new IllegalArgumentException("Supertonic 不支持该语言：" + language);
        if (text.matches("(?is).*<\\s*/?\\s*[a-z]{2}(?:-[a-z]+)?\\s*>.*"))
            throw new IllegalArgumentException("请提供原始文字，语言标记由应用自动添加");
        String value = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder cleaned = new StringBuilder();
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset);
            offset += Character.charCount(cp);
            if ((cp >= 0x1f300 && cp <= 0x1faff) || (cp >= 0x1f1e6 && cp <= 0x1f1ff)
                    || (cp >= 0x2600 && cp <= 0x27bf) || "̧̨̩̪̫̬̭̮̯̂̃̄̅̆̇̈̊̋̌♥☆♡©\\".indexOf(cp) >= 0) continue;
            if ("–‑—".indexOf(cp) >= 0) cleaned.append('-');
            else if ("_[]|/#→←".indexOf(cp) >= 0) cleaned.append(' ');
            else if ("“”".indexOf(cp) >= 0) cleaned.append('"');
            else if ("‘’´`".indexOf(cp) >= 0) cleaned.append('\'');
            else cleaned.appendCodePoint(cp);
        }
        value = cleaned.toString().replace("@", " at ").replace("e.g.,", "for example, ")
                .replace("i.e.,", "that is, ").replaceAll(" ([,.!?;:'])", "$1");
        while (value.contains("\"\"") || value.contains("''")) value = value.replace("\"\"", "\"").replace("''", "'");
        value = value.replaceAll("\\s+", " ").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("没有可朗读的文字");
        if (!value.matches("(?s).*[.!?;:,'\"’)\\]}…。」』】〉》›»]$")) value += ".";
        return "<" + language + ">" + value + "</" + language + ">";
    }

    public static List<String> chunks(String text) {
        return chunks(text, "en");
    }

    public static List<String> chunks(String text, String language) {
        if (!TtsLanguages.supertonicSupports(language)) throw new IllegalArgumentException("Supertonic 不支持该语言：" + language);
        if (text.length() > 4000) throw new IllegalArgumentException("一次最多朗读 4000 个字符，请缩小选区");
        if (text.trim().isEmpty()) throw new IllegalArgumentException("请先选择文字");
        // Japanese/Korean often have no spaces. Bound chunks without discarding characters.
        if ("ja".equals(language) || "ko".equals(language)) {
            List<String> result = new ArrayList<>();
            String value = text.trim(); int begin = 0;
            while (begin < value.length()) {
                int end = value.offsetByCodePoints(begin, Math.min(120, value.codePointCount(begin, value.length())));
                result.add(value.substring(begin, end)); begin = end;
            }
            return result;
        }
        String[] words = text.trim().split("\\s+");
        List<String> result = new ArrayList<>();
        StringBuilder chunk = new StringBuilder();
        for (String word : words) {
            if (word.length() > 240) throw new IllegalArgumentException("文字中有过长片段，请缩小选区");
            if (chunk.length() + word.length() + 1 > 240) { result.add(chunk.toString()); chunk.setLength(0); }
            if (chunk.length() > 0) chunk.append(' ');
            chunk.append(word);
        }
        if (chunk.length() > 0) result.add(chunk.toString());
        if (result.isEmpty()) throw new IllegalArgumentException("请先选择文字");
        return result;
    }
}
