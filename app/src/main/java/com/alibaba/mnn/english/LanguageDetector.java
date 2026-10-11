package com.alibaba.mnn.english;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.nl.languageid.IdentifiedLanguage;
import com.google.mlkit.nl.languageid.LanguageIdentification;
import com.google.mlkit.nl.languageid.LanguageIdentifier;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Bundled on-device model, available without a Play Services model download. */
final class LanguageDetector {
    private LanguageIdentifier client;

    private synchronized LanguageIdentifier client() {
        if (client == null) client = LanguageIdentification.getClient();
        return client;
    }

    Task<String> detect(String text) {
        Task<List<IdentifiedLanguage>> detection;
        try { detection = client().identifyPossibleLanguages(text); }
        catch (RuntimeException error) { return Tasks.forException(error); }
        return detection.continueWith(task -> {
            List<IdentifiedLanguage> languages = task.getResult();
            String code = "und"; float best = 0, second = 0;
            for (IdentifiedLanguage language : languages) {
                float confidence = language.getConfidence();
                if (confidence > best) { second = best; best = confidence; code = language.getLanguageTag(); }
                else if (confidence > second) second = confidence;
            }
            return TtsLanguages.detected(code, best, second);
        });
    }

    // Called only on the serial synthesis worker, never on the UI thread.
    String resolve(String text, String requested) throws Exception {
        if (!"auto".equals(requested)) {
            if (!TtsLanguages.supports(requested)) throw new IllegalArgumentException("请选择模型支持的语言");
            return requested;
        }
        try { return Tasks.await(detect(text), 10, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof IllegalArgumentException) throw (IllegalArgumentException) error.getCause();
            throw new IllegalArgumentException("语言检测失败，请手动选择语言", error);
        } catch (java.util.concurrent.TimeoutException error) {
            throw new IllegalArgumentException("语言检测超时，请手动选择语言", error);
        }
    }
}
