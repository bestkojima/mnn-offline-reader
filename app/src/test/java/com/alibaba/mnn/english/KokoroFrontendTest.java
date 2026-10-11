package com.alibaba.mnn.english;

import java.io.StringReader;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class KokoroFrontendTest {
    private KokoroFrontend frontend() throws Exception {
        // Tiny independent phoneme fixture: padding=0, space=1, n=2, tone3=3, tone2=4, x=5.
        return new KokoroFrontend(new StringReader("430 1\n0 0 0\n\n6\nJA==\nIA==\nbg==\n4oaT\n4oaX\neA==\n"),
                new StringReader("你 n ↓\n好 x ↓\n你好 n ↓ x ↓\n行长 n ↗\n行 x ↓\n长 n ↓\n"));
    }
    @Test public void dictionaryPhraseAndThirdToneSandhiAreAppliedBeforeTokenization() throws Exception {
        assertArrayEquals(new int[]{0, 2, 4, 5, 3, 1, 0}, frontend().tokens("你好").get(0));
        assertArrayEquals(new int[]{0, 2, 4, 1, 0}, frontend().tokens("行长").get(0));
    }
    @Test public void datesDecimalPercentAndLeadingZerosAreNormalized() {
        assertEquals("二零二六年十月十一日,价格十二点五元,百分之二十,零零一二三", KokoroFrontend.normalize("2026-10-11，价格12.5元，20%，00123"));
        assertEquals("一万零一百零一", KokoroFrontend.normalize("10101"));
        assertEquals("二零二六年", KokoroFrontend.normalize("2026年"));
    }
    @Test public void englishLookupAndUnknownWordsDoNotSilentlyLoseText() throws Exception {
        KokoroFrontend frontend = frontend(); assertTrue(frontend.needsEnglish("你好 hello"));
        assertTrue(frontend.needsEnglish("ＨＥＬＬＯ"));
        frontend.loadEnglish(new StringReader("hello n\n"));
        assertFalse(frontend.needsEnglish("hello")); assertEquals(1, frontend.tokens("你好 hello").size());
        assertEquals(1, frontend.tokens("“hello”😀").size());
        try { frontend.tokens("你好 unknownword"); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("unknownword")); }
    }
    @Test public void phonemeChunksStayBoundedAndRetainEveryDictionaryUnit() throws Exception {
        StringBuilder large = new StringBuilder(); for (int i = 0; i < 200; i++) large.append("你好");
        List<int[]> chunks = frontend().tokens(large.toString()); int symbols = 0;
        for (int[] chunk : chunks) { assertTrue(chunk.length <= 202); assertEquals(0, chunk[0]); assertEquals(0, chunk[chunk.length - 1]); symbols += chunk.length - 2; }
        assertEquals(200 * 5, symbols);
    }
    @Test(expected = IllegalArgumentException.class) public void rejectsUnknownChineseWithoutSkippingIt() throws Exception { frontend().tokens("你龘"); }
    @Test(expected = IllegalArgumentException.class) public void punctuationAloneDoesNotGenerateAudio() throws Exception { frontend().tokens("，，"); }
}
