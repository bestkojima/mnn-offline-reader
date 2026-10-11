package com.alibaba.mnn.english;

import android.content.Intent;
import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Requires an arm64 device; host compilation does not count as execution. */
@RunWith(AndroidJUnit4.class)
public class ReaderIntegrationTest {
    private Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    @Test public void testProcessTextEntryIsResolvable() {
        Intent input = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .setPackage("com.alibaba.mnn.english").putExtra(Intent.EXTRA_PROCESS_TEXT, "curious");
        assertNotNull(getInstrumentation().getTargetContext().getPackageManager().resolveActivity(input, 0));
    }
    @Test public void testOfflineDictionaryExamplesAndUserDataAreSeparate() throws Exception {
        ModelAssets assets = new ModelAssets(getInstrumentation().getTargetContext());
        DictionaryStore store = new DictionaryStore(assets);
        DictionaryStore.LookupResult result = store.lookup("Curious!");
        assertTrue(result.found()); assertEquals("curious", result.headword);
        assertFalse(result.phonetic.isEmpty()); assertEquals(2, result.examples.size());
        LearningStore learning = new LearningStore(getInstrumentation().getTargetContext());
        String marker = "integration-test-" + System.nanoTime();
        try { assertTrue(learning.toggle(marker, "curious")); assertTrue(learning.saved(marker)); }
        finally { if (learning.saved(marker)) learning.toggle(marker, "curious"); learning.close(); }
        assertEquals(result.headword, store.lookup("curious").headword);
    }
    @Test public void testActualV3Int8SynthesisAndCancelRecovery() throws Exception {
        ModelAssets assets = new ModelAssets(getInstrumentation().getTargetContext());
        long nativeHandle = assets.createEngine();
        try {
            assertEquals(44100, assets.sampleRate());
            ModelAssets.Style style = assets.style("F1");
            SupertonicNative.cancel(nativeHandle);
            short[] audio = SupertonicNative.synthesize(nativeHandle, assets.tokens("curious"), style.ttl, style.ttlShape, style.dp, style.dpShape, 1f);
            assertTrue(audio.length > 1000);
            long energy = 0; for (short sample : audio) energy += (long) sample * sample;
            assertTrue(energy > 0);
            short[] japanese = SupertonicNative.synthesize(nativeHandle, assets.tokens("こんにちは。", "ja"), style.ttl, style.ttlShape, style.dp, style.dpShape, 1f);
            assertTrue(japanese.length > 1000);
            long japaneseEnergy = 0; for (short sample : japanese) japaneseEnergy += (long) sample * sample;
            assertTrue(japaneseEnergy > 0);
        } finally { SupertonicNative.destroy(nativeHandle); }
    }
    @Test public void testBundledLanguageDetectionAndManualOverride() throws Exception {
        LanguageDetector detector = new LanguageDetector();
        assertEquals("en", detector.resolve("The weather is beautiful today and we are walking through the garden.", "auto"));
        assertEquals("es", detector.resolve("Hoy hace buen tiempo y vamos a caminar por el parque con nuestros amigos.", "auto"));
        assertEquals("ja", detector.resolve("今日は天気がいいので友達と一緒に公園に行きます。", "auto"));
        assertEquals("fr", detector.resolve("pain", "fr"));
        assertEquals("zh", detector.resolve("今天天气很好，我们一起去公园散步。", "auto"));
    }
    @Test public void testActualKokoroMandarinWithBothVoices() throws Exception {
        ModelAssets assets = new ModelAssets(getInstrumentation().getTargetContext());
        java.util.List<int[]> chunks = assets.kokoroTokens("你好，今天是2026年。hello");
        long handle = assets.createKokoroEngine();
        try {
            for (String voice : new String[]{"zf_xiaoxiao", "zm_yunxi"}) {
                int[] tokens = chunks.get(0); KokoroNative.cancel(handle);
                short[] audio = KokoroNative.synthesize(handle, tokens, assets.kokoroStyle(voice, tokens.length - 2), 1f);
                assertTrue(audio.length > 1000);
                long energy = 0; for (short sample : audio) energy += (long) sample * sample;
                assertTrue(energy > 0);
            }
        } finally { KokoroNative.destroy(handle); assets.releaseKokoroFrontend(); }
    }
}
