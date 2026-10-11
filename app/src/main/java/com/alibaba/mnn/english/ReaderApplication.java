package com.alibaba.mnn.english;

import android.app.Application;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderApplication extends Application {
    final ExecutorService databaseWorker = Executors.newSingleThreadExecutor();
    DictionaryStore dictionary;
    LearningStore learning;
    SpeechController speech;
    ModelDownloads models;
    final LanguageDetector languages = new LanguageDetector();
    @Override public void onCreate() {
        super.onCreate();
        models = new ModelDownloads(this);
        ModelAssets assets = new ModelAssets(this);
        dictionary = new DictionaryStore(assets);
        learning = new LearningStore(this);
        speech = new SpeechController(this, assets, languages);
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_UI_HIDDEN) speech.releaseInBackground();
    }
}
