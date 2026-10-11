package com.alibaba.mnn.english;

import org.junit.Test;
import static org.junit.Assert.*;

public class TtsLanguagesTest {
    @Test public void allPublisherLanguagesGetTheirOwnWrapper() {
        assertEquals(31, TtsLanguages.CODES.length);
        assertEquals(31, new java.util.HashSet<>(java.util.Arrays.asList(TtsLanguages.CODES)).size());
        for (String code : TtsLanguages.CODES) {
            assertEquals("<" + code + ">Hello.</" + code + ">", TextNormalizer.normalize("Hello", code));
        }
        assertEquals("ja", TtsLanguages.detected("ja", .98f, .01f));
        assertEquals("pt", TtsLanguages.detected("pt-BR", .9f, .02f));
    }
    @Test public void chineseUsesItsOwnEngineInsteadOfAnEnglishWrapper() {
        assertEquals("zh", TtsLanguages.detected("zh-Hans", .99f, .01f));
        assertTrue(TtsLanguages.supports("zh")); assertFalse(TtsLanguages.supertonicSupports("zh"));
        try { TextNormalizer.normalize("你好", "zh"); fail("Kokoro does not use Supertonic tags"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Supertonic")); }
    }
    @Test(expected = IllegalArgumentException.class) public void shortAmbiguousWordsRequireManualChoice() {
        TtsLanguages.detected("en", .51f, .45f);
    }
    @Test(expected = IllegalArgumentException.class) public void unknownLanguageDoesNotGetTagged() {
        TtsLanguages.detected("und", 1f, 0f);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsNestedLanguageTags() {
        TextNormalizer.normalize("<fr>Bonjour</fr>", "en");
    }
    @Test public void longUnspacedJapaneseAndKoreanDoNotLoseCharacters() {
        for (String code : new String[]{"ja", "ko"}) {
            StringBuilder text = new StringBuilder(); for (int i = 0; i < 70; i++) text.append("こんにちは안녕");
            java.util.List<String> chunks = TextNormalizer.chunks(text.toString(), code);
            for (String chunk : chunks) assertTrue(chunk.codePointCount(0, chunk.length()) <= 120);
            assertEquals(text.toString(), String.join("", chunks));
            for (String chunk : chunks) assertTrue(TextNormalizer.normalize(chunk, code).startsWith("<" + code + ">"));
        }
    }
    @Test public void preservesNonLatinTextThroughNormalization() {
        assertEquals("<ja>こんにちは。</ja>", TextNormalizer.normalize("こんにちは。", "ja"));
        assertEquals("<ar>مرحبا.</ar>", TextNormalizer.normalize("مرحبا", "ar"));
    }
}
