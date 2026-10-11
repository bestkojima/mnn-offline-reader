package com.alibaba.mnn.english;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lexicon-based Mandarin/English G2P. No language tags enter the Kokoro model. */
final class KokoroFrontend {
    private final Map<String, String> chinese = new HashMap<>(), english = new HashMap<>();
    private final Map<Integer, Integer> vocabulary = new HashMap<>();
    private int longestWord = 1;
    private boolean englishLoaded;

    KokoroFrontend(Reader tokenizer, Reader lexicon) throws IOException {
        try (BufferedReader reader = new BufferedReader(tokenizer)) {
            if (!"430 1".equals(reader.readLine())) throw new IOException("Invalid Kokoro tokenizer");
            reader.readLine(); reader.readLine();
            int count = Integer.parseInt(reader.readLine());
            for (int id = 0; id < count; id++) {
                String line = reader.readLine(); if (line == null) throw new IOException("Truncated tokenizer");
                if (!line.isEmpty()) {
                    String token = new String(decodeBase64(line), java.nio.charset.StandardCharsets.UTF_8);
                    if (token.codePointCount(0, token.length()) == 1) vocabulary.put(token.codePointAt(0), id);
                }
            }
        }
        loadLexicon(lexicon, chinese);
        for (String key : chinese.keySet()) longestWord = Math.max(longestWord, key.length());
    }

    // Small pure-Java decoder keeps this class usable in host tests and on Android API 23.
    private static byte[] decodeBase64(String value) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        int bits = 0, accumulator = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i); if (c == '=') break;
            int digit = alphabet.indexOf(c); if (digit < 0) throw new IOException("Invalid base64 vocabulary");
            accumulator = (accumulator << 6) | digit; bits += 6;
            if (bits >= 8) { bits -= 8; out.write((accumulator >> bits) & 255); }
        }
        return out.toByteArray();
    }

    private static void loadLexicon(Reader input, Map<String, String> target) throws IOException {
        try (BufferedReader reader = new BufferedReader(input)) {
            String line;
            while ((line = reader.readLine()) != null) {
                int separator = line.indexOf(' ');
                if (separator < 1) continue;
                String word = line.substring(0, separator);
                if (!target.containsKey(word)) target.put(word, line.substring(separator + 1).replace(" ", "").trim());
            }
        }
    }

    void loadEnglish(Reader input) throws IOException { loadLexicon(input, english); englishLoaded = true; }
    boolean needsEnglish(String text) { return !englishLoaded && normalize(text).matches("(?s).*[A-Za-z].*"); }

    static String normalize(String text) {
        if (text.isEmpty() || text.length() > 4000) throw new IllegalArgumentException("请选择 1～4000 个字符");
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace('，', ',').replace('。', '.').replace('！', '!').replace('？', '?')
                .replace('；', ';').replace('：', ':').replace('“', '"').replace('”', '"')
                .replace('‘', '\'').replace('’', '\'').replace('、', ',');
        Matcher dates = Pattern.compile("(?<![0-9])([0-9]{4})[-/]([0-9]{1,2})[-/]([0-9]{1,2})(?![0-9])").matcher(value);
        StringBuffer dated = new StringBuffer();
        while (dates.find()) dates.appendReplacement(dated, digits(dates.group(1)) + "年" + integer(dates.group(2)) + "月" + integer(dates.group(3)) + "日");
        dates.appendTail(dated); value = dated.toString().replace("¥", "人民币").replace("℃", "摄氏度").replace("°C", "摄氏度");
        Matcher numbers = Pattern.compile("[0-9]+(?:\\.[0-9]+)?%?").matcher(value);
        StringBuffer result = new StringBuffer();
        while (numbers.find()) {
            String raw = numbers.group(), number = raw.endsWith("%") ? raw.substring(0, raw.length() - 1) : raw;
            boolean year = numbers.end() < value.length() && value.charAt(numbers.end()) == '年';
            int decimal = number.indexOf('.');
            String spoken = decimal >= 0 ? integer(number.substring(0, decimal)) + "点" + digits(number.substring(decimal + 1))
                    : year || number.length() >= 7 || (number.length() > 1 && number.charAt(0) == '0') ? digits(number) : integer(number);
            if (raw.endsWith("%")) spoken = "百分之" + spoken;
            numbers.appendReplacement(result, spoken);
        }
        numbers.appendTail(result);
        return result.toString().replaceAll("\\s+", " ").trim();
    }

    private static String digits(String value) {
        StringBuilder result = new StringBuilder(); for (int i = 0; i < value.length(); i++) result.append("零一二三四五六七八九".charAt(value.charAt(i) - '0'));
        return result.toString();
    }

    private static String integer(String value) {
        if (value.length() >= 7) return digits(value);
        int number = Integer.parseInt(value);
        if (number >= 10000) return small(number / 10000) + "万" + (number % 10000 == 0 ? "" : number % 10000 < 1000 ? "零" + small(number % 10000) : small(number % 10000));
        return small(number);
    }

    private static String small(int number) {
        if (number == 0) return "零";
        StringBuilder result = new StringBuilder(); int divisor = 1000; boolean zero = false;
        String[] units = {"千", "百", "十", ""};
        for (String unit : units) {
            int digit = number / divisor; number %= divisor;
            if (digit > 0) {
                if (zero) result.append('零');
                if (!(result.length() == 0 && divisor == 10 && digit == 1)) result.append("零一二三四五六七八九".charAt(digit));
                result.append(unit); zero = false;
            } else if (result.length() > 0 && number > 0) zero = true;
            divisor /= 10;
        }
        return result.toString();
    }

    List<int[]> tokens(String text) {
        String value = normalize(text); List<int[]> chunks = new ArrayList<>(); List<Integer> current = new ArrayList<>(); boolean spoken = false;
        for (int offset = 0; offset < value.length();) {
            int cp = value.codePointAt(offset); int next = offset + Character.charCount(cp); String phones; boolean punctuation = false;
            if ((cp >= 0x1f300 && cp <= 0x1faff) || (cp >= 0x2600 && cp <= 0x27bf) || cp == 0xfe0f || cp == 0x200d) { offset = next; continue; }
            if (Character.isWhitespace(cp)) { offset = next; continue; }
            if (cp >= 'A' && cp <= 'Z' || cp >= 'a' && cp <= 'z') {
                int end = next; while (end < value.length() && (latin(value.charAt(end)) || value.charAt(end) == '\'' && end + 1 < value.length() && latin(value.charAt(end + 1)))) end++;
                String word = value.substring(offset, end).toLowerCase(Locale.ROOT); phones = english.get(word);
                if (phones == null) throw new IllegalArgumentException("中文朗读词典未收录英文词：" + word + "；请单独选中使用英语朗读");
                next = end;
                spoken = true;
            } else if (".,!?;:-—…\"'()[]{}".indexOf(cp) >= 0) {
                punctuation = true;
                if (current.isEmpty()) { offset = next; continue; }
                phones = "-—…".indexOf(cp) >= 0 ? "," : "\"'()[]{}".indexOf(cp) >= 0 ? "" : new String(Character.toChars(cp));
            } else {
                phones = null;
                for (int length = Math.min(longestWord, value.length() - offset); length > 0; length--) {
                    if (offset + length < value.length() && Character.isLowSurrogate(value.charAt(offset + length))) continue;
                    phones = chinese.get(value.substring(offset, offset + length));
                    if (phones != null) { next = offset + length; break; }
                }
                if (phones == null) throw new IllegalArgumentException("中文朗读词典未收录：" + new String(Character.toChars(cp)) + "；请缩小选区");
                phones = thirdToneSandhi(phones); spoken = true;
            }
            List<Integer> unit = new ArrayList<>();
            for (int p = 0; p < phones.length();) {
                int phone = phones.codePointAt(p); p += Character.charCount(phone);
                Integer id = vocabulary.get(phone);
                if (id == null || id == 0) throw new IllegalArgumentException("Kokoro 词典包含不支持的音素");
                unit.add(id);
            }
            if (unit.size() > 190) throw new IllegalArgumentException("中文词条过长，请缩小选区");
            if (current.size() + unit.size() + 1 > 200) {
                finish(chunks, current); current = new ArrayList<>();
                if (punctuation) { offset = next; continue; }
            }
            current.addAll(unit);
            if (!unit.isEmpty()) current.add(vocabulary.get((int) ' '));
            if (".!?;".indexOf(cp) >= 0 && current.size() > 1) { finish(chunks, current); current = new ArrayList<>(); }
            offset = next;
        }
        finish(chunks, current);
        if (chunks.isEmpty() || !spoken) throw new IllegalArgumentException("没有可朗读的文字");
        return chunks;
    }

    private static boolean latin(char c) { return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'; }

    private static String thirdToneSandhi(String phones) {
        StringBuilder result = new StringBuilder(phones); int previous = -1;
        for (int i = 0; i < result.length(); i++) {
            if ("→↗↓↘".indexOf(result.charAt(i)) < 0) continue;
            if (previous >= 0 && result.charAt(previous) == '↓' && result.charAt(i) == '↓') result.setCharAt(previous, '↗');
            previous = i;
        }
        return result.toString();
    }

    private static void finish(List<int[]> chunks, List<Integer> ids) {
        if (ids.isEmpty()) return;
        int[] result = new int[ids.size() + 2]; for (int i = 0; i < ids.size(); i++) result[i + 1] = ids.get(i);
        chunks.add(result);
    }
}
