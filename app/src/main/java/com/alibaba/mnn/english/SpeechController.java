package com.alibaba.mnn.english;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.LruCache;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** A single native owner and request generation prevent stale synthesis from playing. */
final class SpeechController {
    interface Listener { void onState(String message, boolean busy); }
    private final ModelAssets assets;
    private final LanguageDetector languages;
    private final AudioManager audioManager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicLong generation = new AtomicLong();
    private final Object nativeLock = new Object(), audioLock = new Object();
    private long engine;
    private long kokoroEngine;
    private volatile Listener activeListener;
    private AudioTrack track;
    private final AudioAttributes attributes = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
    private final LruCache<String, short[]> cache = new LruCache<String, short[]>(10 * 1024 * 1024) {
        @Override protected int sizeOf(String key, short[] value) { return value.length * 2; }
    };

    SpeechController(Context context, ModelAssets assets, LanguageDetector languages) {
        this.assets = assets;
        this.languages = languages;
        audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        BroadcastReceiver noisy = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) {
                long ticket = generation.get();
                Listener listener = activeListener;
                if (stop(ticket) && listener != null) main.post(() -> {
                    if (generation.get() == ticket + 1) listener.onState("播放已停止", false);
                });
            }
        };
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(noisy, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(noisy, filter);
    }

    long speak(String text, String voice, String chineseVoice, float speed, String language, Listener listener) {
        long ticket = generation.incrementAndGet();
        activeListener = listener;
        cancelNativeAndAudio();
        worker.execute(() -> run(ticket, text, voice, chineseVoice, speed, language, listener));
        return ticket;
    }

    boolean stop(long ticket) {
        if (ticket != 0 && generation.compareAndSet(ticket, ticket + 1)) { activeListener = null; cancelNativeAndAudio(); return true; }
        return false;
    }

    private void cancelNativeAndAudio() {
        synchronized (nativeLock) {
            if (engine != 0) SupertonicNative.cancel(engine);
            if (kokoroEngine != 0) KokoroNative.cancel(kokoroEngine);
        }
        synchronized (audioLock) {
            if (track != null && track.getState() == AudioTrack.STATE_INITIALIZED) {
                try { track.pause(); track.flush(); } catch (IllegalStateException ignored) {}
            }
        }
    }

    void releaseInBackground() {
        long ticket = generation.incrementAndGet();
        activeListener = null;
        cancelNativeAndAudio();
        worker.execute(() -> {
            if (ticket != generation.get()) return;
            cache.evictAll();
            synchronized (nativeLock) {
                if (engine != 0) { SupertonicNative.destroy(engine); engine = 0; }
                if (kokoroEngine != 0) { KokoroNative.destroy(kokoroEngine); kokoroEngine = 0; }
                assets.releaseKokoroFrontend();
            }
        });
    }

    private boolean current(long ticket) { return generation.get() == ticket; }
    private void status(long ticket, Listener listener, String message, boolean busy) {
        main.post(() -> { if (current(ticket)) listener.onState(message, busy); });
    }

    private void run(long ticket, String text, String voice, String chineseVoice, float speed, String requestedLanguage, Listener listener) {
        AudioManager.OnAudioFocusChangeListener focusListener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                    || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
                if (stop(ticket)) main.post(() -> {
                    if (generation.get() == ticket + 1) listener.onState("播放已暂停", false);
                });
            }
        };
        AudioFocusRequest focus = null;
        boolean focusHeld = false;
        try {
            if (!current(ticket)) return;
            if (text.length() > 4000 || text.trim().isEmpty()) throw new IllegalArgumentException("请选择 1～4000 个字符");
            status(ticket, listener, "正在检测语言…", true);
            String language = languages.resolve(text, requestedLanguage);
            boolean chinese = "zh".equals(language);
            if (!current(ticket)) return;
            status(ticket, listener, TtsLanguages.name(language) + (chinese ? " · Kokoro" : " · Supertonic") + " · 准备离线语音…", true);
            java.util.List<int[]> chineseChunks = chinese ? assets.kokoroTokens(text) : null;
            java.util.List<String> chunks = chinese ? null : TextNormalizer.chunks(text, language);
            int sampleRate = chinese ? 24000 : assets.sampleRate();
            if (!current(ticket)) return;
            if (chinese) {
                synchronized (nativeLock) { if (engine != 0) { SupertonicNative.destroy(engine); engine = 0; } }
                if (kokoroEngine == 0) {
                    long loaded = assets.createKokoroEngine(); synchronized (nativeLock) { kokoroEngine = loaded; }
                }
            } else {
                synchronized (nativeLock) {
                    if (kokoroEngine != 0) { KokoroNative.destroy(kokoroEngine); kokoroEngine = 0; }
                    assets.releaseKokoroFrontend();
                }
                if (engine == 0) { long loaded = assets.createEngine(); synchronized (nativeLock) { engine = loaded; } }
            }
            ModelAssets.Style style = chinese ? null : assets.style(voice);
            if (!current(ticket)) return;
            int focusResult;
            if (Build.VERSION.SDK_INT >= 26) {
                focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes).setOnAudioFocusChangeListener(focusListener, main).build();
                focusResult = audioManager.requestAudioFocus(focus);
            } else focusResult = audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            if (focusResult != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) throw new IllegalStateException("Audio focus unavailable");
            focusHeld = true;
            int chunkCount = chinese ? chineseChunks.size() : chunks.size();
            for (int index = 0; index < chunkCount; index++) {
                if (!current(ticket)) return;
                int[] chineseTokens = chinese ? chineseChunks.get(index) : null;
                String key = chinese ? "kokoro-v1.0|" + chineseVoice + "|" + speed + "|" + java.util.Arrays.toString(chineseTokens)
                        : "supertonic-v3|" + voice + "|" + speed + "|" + TextNormalizer.normalize(chunks.get(index), language);
                short[] pcm = cache.get(key);
                if (pcm == null) {
                    status(ticket, listener, TtsLanguages.name(language) + " · 正在生成发音…", true);
                    pcm = chinese ? KokoroNative.synthesize(kokoroEngine, chineseTokens, assets.kokoroStyle(chineseVoice, chineseTokens.length - 2), speed)
                            : SupertonicNative.synthesize(engine, assets.tokens(chunks.get(index), language), style.ttl, style.ttlShape, style.dp, style.dpShape, speed);
                    if (!current(ticket)) return;
                    if (pcm == null || pcm.length == 0) throw new IllegalStateException("Empty audio");
                    cache.put(key, pcm);
                }
                status(ticket, listener, "正在朗读…", true);
                play(ticket, pcm, sampleRate);
            }
            status(ticket, listener, "播放完成", false);
        } catch (Exception | LinkageError error) {
            if (current(ticket)) {
                Log.e("EnglishReader", "Speech failed", error);
                status(ticket, listener, error instanceof IllegalArgumentException || error instanceof ModelDownloads.MissingModel
                        ? error.getMessage() : "发音失败，请重试或缩小选区", false);
            }
        } finally {
            if (focusHeld) {
                if (Build.VERSION.SDK_INT >= 26 && focus != null) audioManager.abandonAudioFocusRequest(focus);
                else audioManager.abandonAudioFocus(focusListener);
            }
        }
    }

    private void play(long ticket, short[] pcm, int rate) throws Exception {
        int buffer = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (buffer < 1) throw new IllegalStateException("Unsupported audio format");
        AudioTrack output = new AudioTrack.Builder().setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(buffer, rate / 5 * 2)).setTransferMode(AudioTrack.MODE_STREAM).build();
        try {
            if (!current(ticket)) return;
            synchronized (audioLock) { track = output; output.play(); }
            int offset = 0;
            long deadline = SystemClock.elapsedRealtime() + pcm.length * 1000L / rate + 5000;
            while (current(ticket) && offset < pcm.length) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Audio playback timeout");
                int count = output.write(pcm, offset, Math.min(4096, pcm.length - offset), AudioTrack.WRITE_NON_BLOCKING);
                if (count < 0) throw new IllegalStateException("Audio playback failed");
                offset += count;
                if (count == 0) SystemClock.sleep(10);
            }
            while (current(ticket) && (output.getPlaybackHeadPosition() & 0xffffffffL) < pcm.length) {
                if (SystemClock.elapsedRealtime() > deadline) throw new IllegalStateException("Audio playback timeout");
                SystemClock.sleep(20);
            }
        } finally {
            synchronized (audioLock) { if (track == output) track = null; output.release(); }
        }
    }
}
