package com.alibaba.mnn.english;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class TextNormalizerTest {
    @Test public void usesEnglishTagsAndPreservesSentencePunctuation() {
        assertEquals("<en>curious.</en>", TextNormalizer.normalize("curious"));
        assertEquals("<en>How does it work?</en>", TextNormalizer.normalize("How does it work?"));
    }
    @Test public void handlesTypographyExpressionsAndRemovedEmoji() {
        assertEquals("<en>\"I'm curious\".</en>", TextNormalizer.normalize("“I’m curious”😀."));
        assertEquals("<en>for example, a clock.</en>", TextNormalizer.normalize(" e.g.,  a clock "));
        assertEquals("<en>at home.</en>", TextNormalizer.normalize("@home"));
    }
    @Test public void lookupIsLocaleIndependentAndKeepsInternalApostrophes() {
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            assertEquals("interesting", TextNormalizer.lookupKey("“INTERESTING!”"));
            assertEquals("can't", TextNormalizer.lookupKey("Can’t"));
        } finally { java.util.Locale.setDefault(original); }
    }
    @Test public void chunksAreBoundedWithoutLosingWordsOrSplittingAnAbbreviation() {
        String text = "Dr. Smith is curious about this clock. ";
        StringBuilder large = new StringBuilder(); for (int i = 0; i < 40; i++) large.append(text);
        List<String> chunks = TextNormalizer.chunks(large.toString());
        for (String chunk : chunks) assertTrue(chunk.length() <= 240);
        assertEquals(large.toString().trim(), String.join(" ", chunks));
        assertEquals(1, TextNormalizer.chunks(text).size());
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsEmptyNormalizedText() { TextNormalizer.normalize("😀 "); }
    @Test(expected = IllegalArgumentException.class) public void rejectsOversizedExternalInput() {
        char[] characters = new char[4001]; java.util.Arrays.fill(characters, 'a'); TextNormalizer.chunks(new String(characters));
    }
}
