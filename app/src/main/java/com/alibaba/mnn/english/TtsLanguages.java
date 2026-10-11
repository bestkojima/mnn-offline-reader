package com.alibaba.mnn.english;

import java.util.Locale;

/** Publisher's v3 language contract. Unknown languages never silently become English. */
final class TtsLanguages {
    static final String[] CODES = {"en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"};
    private static final String[] NAMES = {"英语", "韩语", "日语", "阿拉伯语", "保加利亚语", "捷克语", "丹麦语", "德语", "希腊语", "西班牙语", "爱沙尼亚语", "芬兰语", "法语", "印地语", "克罗地亚语", "匈牙利语", "印尼语", "意大利语", "立陶宛语", "拉脱维亚语", "荷兰语", "波兰语", "葡萄牙语", "罗马尼亚语", "俄语", "斯洛伐克语", "斯洛文尼亚语", "瑞典语", "土耳其语", "乌克兰语", "越南语"};
    private TtsLanguages() {}

    static boolean supports(String code) {
        return "zh".equals(code) || supertonicSupports(code);
    }

    static boolean supertonicSupports(String code) {
        for (String supported : CODES) if (supported.equals(code)) return true;
        return false;
    }

    static String name(String code) {
        for (int i = 0; i < CODES.length; i++) if (CODES[i].equals(code)) return NAMES[i];
        if ("zh".equals(code)) return "中文";
        return code;
    }

    static String[] choices() {
        String[] result = new String[CODES.length + 2]; result[0] = "自动检测";
        for (int i = 0; i < CODES.length; i++) result[i + 1] = NAMES[i] + " (" + CODES[i] + ")";
        result[result.length - 1] = "中文 (zh)";
        return result;
    }

    static String detected(String code, float confidence, float runnerUp) {
        if (code == null || "und".equals(code) || Float.isNaN(confidence) || Float.isInfinite(confidence)
                || confidence < 0.5f || confidence - runnerUp < 0.15f) {
            throw new IllegalArgumentException("语言不确定，请在语言菜单中手动选择；短单词容易产生歧义");
        }
        String base = code.toLowerCase(Locale.ROOT).split("-")[0];
        if (!supports(base)) throw new IllegalArgumentException("检测到" + name(base) + "；当前朗读模型暂不支持该语言");
        return base;
    }
}
