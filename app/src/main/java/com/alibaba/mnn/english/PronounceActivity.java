package com.alibaba.mnn.english;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

public final class PronounceActivity extends Activity {
    private ReaderApplication app;
    private Ui ui;
    private String selected, bookmarkKey;
    private LinearLayout details;
    private TextView status;
    private TextView languageStatus;
    private String requestedLanguage = "auto", detectedLanguage;
    private int detectionGeneration;
    private Button play, save, download;
    private String playingText;
    private String voice;
    private String chineseVoice;
    private LinearLayout supertonicVoiceGroup, chineseVoiceGroup;
    private float speed;
    private boolean busy;
    private long speechTicket;
    private int lookupGeneration;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        app = (ReaderApplication) getApplication(); ui = new Ui(this);
        voice = getPreferences(MODE_PRIVATE).getString("voice", "F1");
        chineseVoice = getPreferences(MODE_PRIVATE).getString("chineseVoice", "zf_xiaoxiao");
        if (!chineseVoice.equals("zf_xiaoxiao") && !chineseVoice.equals("zm_yunxi")) chineseVoice = "zf_xiaoxiao";
        speed = getPreferences(MODE_PRIVATE).getFloat("speed", 1f);
        if (!voice.matches("[FM][1-5]")) voice = "F1";
        if (speed != 0.75f && speed != 1f && speed != 1.25f) speed = 1f;
        setFinishOnTouchOutside(true);
        showInput(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); stopSpeech(); showInput(intent); }

    private void showInput(Intent intent) {
        CharSequence input;
        try {
            input = intent.getCharSequenceExtra(Intent.ACTION_PROCESS_TEXT.equals(intent.getAction()) ? Intent.EXTRA_PROCESS_TEXT : Intent.EXTRA_TEXT);
        } catch (RuntimeException malformed) { input = null; }
        selected = input == null ? "" : input.toString().trim();
        if (selected.isEmpty() || selected.length() > 4000) {
            Toast.makeText(this, selected.isEmpty() ? "请选择文字" : "选中文字过长，请缩小选区", Toast.LENGTH_LONG).show(); finish(); return;
        }
        playingText = null; busy = false; bookmarkKey = TextNormalizer.lookupKey(selected);
        requestedLanguage = "auto"; detectedLanguage = null;
        buildCard(); loadLookup(); detectInputLanguage();
    }

    private void buildCard() {
        LinearLayout root = ui.column(); root.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20));
        root.setBackground(ui.background(ui.surface, 24));
        LinearLayout top = ui.row(); ui.weighted(top, ui.text("离线发音 · 英语词库", 12, true));
        Button close = ui.button("×", false); close.setContentDescription("关闭查词卡片");
        top.addView(close, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))); close.setOnClickListener(v -> finish()); root.addView(top);
        Ui.CardScroll scroll = new Ui.CardScroll(this); LinearLayout content = ui.column();
        TextView word = ui.text(selected, selected.length() > 30 ? 22 : 32, false); word.setTextIsSelectable(true); content.addView(word);
        details = ui.column(); content.addView(details); scroll.addView(content); root.addView(scroll);
        ui.gap(root, 14); play = ui.button("发音", true); play.setOnClickListener(v -> { if (busy) stopSpeech(); else speak(selected); }); root.addView(play);
        download = ui.button("下载语音", false); root.addView(download);
        download.setOnClickListener(v -> startActivity(new Intent(this, ModelDownloadActivity.class)));
        ui.gap(root, 12); LinearLayout settings = ui.row();
        String[] voices = {"F1", "F2", "F3", "F4", "F5", "M1", "M2", "M3", "M4", "M5"};
        LinearLayout voiceGroup = ui.column(); voiceGroup.addView(ui.text("音色", 12, true));
        supertonicVoiceGroup = voiceGroup;
        Spinner voicePicker = picker(voices); voicePicker.setContentDescription("选择发音音色"); voiceGroup.addView(voicePicker); ui.weighted(settings, voiceGroup);
        for (int i = 0; i < voices.length; i++) if (voices[i].equals(voice)) voicePicker.setSelection(i);
        voicePicker.setOnItemSelectedListener(new SelectionListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            if (!voice.equals(voices[position])) { stopSpeech(); voice = voices[position]; getPreferences(MODE_PRIVATE).edit().putString("voice", voice).apply(); }
        }});
        chineseVoiceGroup = ui.column(); chineseVoiceGroup.addView(ui.text("中文音色", 12, true));
        Spinner chinesePicker = picker(new String[]{"女声", "男声"}); chinesePicker.setContentDescription("选择中文发音音色");
        chineseVoiceGroup.addView(chinesePicker); ui.weighted(settings, chineseVoiceGroup); chineseVoiceGroup.setVisibility(View.GONE);
        chinesePicker.setSelection(chineseVoice.equals("zm_yunxi") ? 1 : 0);
        chinesePicker.setOnItemSelectedListener(new SelectionListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            String chosen = position == 0 ? "zf_xiaoxiao" : "zm_yunxi";
            if (!chosen.equals(chineseVoice)) { stopSpeech(); chineseVoice = chosen; getPreferences(MODE_PRIVATE).edit().putString("chineseVoice", chosen).apply(); }
        }});
        LinearLayout speedGroup = ui.column(); speedGroup.addView(ui.text("语速", 12, true));
        Spinner speedPicker = picker(new String[]{"0.75×", "1.0×", "1.25×"}); speedPicker.setContentDescription("选择朗读语速"); speedGroup.addView(speedPicker); ui.weighted(settings, speedGroup);
        speedPicker.setSelection(speed == 0.75f ? 0 : speed == 1.25f ? 2 : 1);
        speedPicker.setOnItemSelectedListener(new SelectionListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            float chosen = new float[]{0.75f, 1f, 1.25f}[position];
            if (chosen != speed) { stopSpeech(); speed = chosen; getPreferences(MODE_PRIVATE).edit().putFloat("speed", speed).apply(); }
        }});
        root.addView(settings); ui.gap(root, 12);
        LinearLayout languageGroup = ui.column(); languageGroup.addView(ui.text("朗读语言", 12, true));
        Spinner languagePicker = picker(TtsLanguages.choices()); languagePicker.setContentDescription("选择朗读语言或自动检测"); languageGroup.addView(languagePicker);
        languageStatus = ui.text("正在离线检测语言…", 12, true); languageGroup.addView(languageStatus); root.addView(languageGroup); ui.gap(root, 12);
        languagePicker.setOnItemSelectedListener(new SelectionListener() { @Override public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
            String chosen = position == 0 ? "auto" : position == TtsLanguages.CODES.length + 1 ? "zh" : TtsLanguages.CODES[position - 1];
            if (!chosen.equals(requestedLanguage)) {
                stopSpeech(); requestedLanguage = chosen;
                if ("auto".equals(chosen)) detectInputLanguage();
                else { ++detectionGeneration; languageStatus.setText(getString(R.string.language_manual, TtsLanguages.name(chosen))); loadLookup(); }
            }
        }});
        save = ui.button("加入生词本", false); save.setEnabled(false);
        save.setVisibility(bookmarkKey.matches("[a-z][a-z '\\-]*") ? View.VISIBLE : View.GONE);
        save.setOnClickListener(v -> toggleBookmark()); root.addView(save);
        ui.gap(root, 8); status = ui.text("正在查询本地词库…", 12, true); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(status);
        Ui.CardScroll outer = new Ui.CardScroll(this); outer.setMaxFraction(0.9f); outer.addView(root);
        outer.setBackground(ui.background(ui.surface, 24));
        setContentView(outer); getWindow().setLayout(Math.min(getResources().getDisplayMetrics().widthPixels - ui.dp(24), ui.dp(420)), WindowManager.LayoutParams.WRAP_CONTENT);
        getWindow().setGravity(Gravity.CENTER);
    }

    private Spinner picker(String[] choices) {
        Spinner picker = new Spinner(this); ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); picker.setAdapter(adapter); picker.setMinimumHeight(ui.dp(48)); return picker;
    }

    private void loadLookup() {
        final int version = ++lookupGeneration; final String text = selected;
        final String language = "auto".equals(requestedLanguage) ? detectedLanguage : requestedLanguage;
        chineseVoiceGroup.setVisibility("zh".equals(language) ? View.VISIBLE : View.GONE);
        supertonicVoiceGroup.setVisibility("zh".equals(language) ? View.GONE : View.VISIBLE);
        if (language != null && !"en".equals(language)) {
            details.removeAllViews(); ui.gap(details, 12);
            details.addView(ui.text("可朗读" + TtsLanguages.name(language) + "；释义、音标和例句词库目前仅提供英语", 14, true));
            save.setVisibility(View.GONE); return;
        }
        save.setVisibility(bookmarkKey.matches("[a-z][a-z '\\-]*") ? View.VISIBLE : View.GONE);
        app.databaseWorker.execute(() -> {
            try {
                DictionaryStore.LookupResult result = app.dictionary.lookup(text);
                String key = result.found() ? TextNormalizer.lookupKey(result.headword) : TextNormalizer.lookupKey(text);
                boolean saved = app.learning.saved(key); app.learning.record(text);
                runOnUiThread(() -> {
                    if (!alive(version)) return;
                    bookmarkKey = key; details.removeAllViews();
                    if (result.found()) {
                        details.addView(ui.text("英语词库释义", 12, true));
                        if (!TextNormalizer.lookupKey(text).equals(key)) details.addView(ui.text("词形对应：" + result.headword, 13, true));
                        if (!result.phonetic.isEmpty()) details.addView(ui.text("音标 /" + result.phonetic + "/", 14, true));
                        ui.gap(details, 12);
                        for (String meaning : result.meanings) details.addView(ui.text(meaning, 16, false));
                        if (!result.examples.isEmpty()) { ui.gap(details, 16); ui.divider(details); ui.gap(details, 12); details.addView(ui.text("精选例句", 14, false)); }
                        for (DictionaryStore.Example example : result.examples) addExample(example);
                        if (result.examples.isEmpty()) { ui.gap(details, 12); details.addView(ui.text("这个词暂未收录精选例句", 12, true)); }
                    } else {
                        ui.gap(details, 12);
                        details.addView(ui.text(text.contains(" ") ? "已选择一段文字，可直接朗读" : "本地词库未收录，仍可发音", 14, true));
                    }
                    save.setText(saved ? "已加入生词本" : "加入生词本"); save.setEnabled(true);
                    if (!busy) status.setText("点击发音");
                });
            } catch (Exception error) { runOnUiThread(() -> { if (alive(version) && !busy) status.setText("词库读取失败；仍可尝试发音"); }); }
        });
    }

    private void detectInputLanguage() {
        final int version = ++detectionGeneration;
        languageStatus.setText("正在离线检测语言…");
        app.languages.detect(selected).addOnSuccessListener(language -> {
            if (version != detectionGeneration || isFinishing() || isDestroyed() || !"auto".equals(requestedLanguage)) return;
            detectedLanguage = language; languageStatus.setText(getString(R.string.language_detected, TtsLanguages.name(language))); loadLookup();
        }).addOnFailureListener(error -> {
            if (version != detectionGeneration || isFinishing() || isDestroyed() || !"auto".equals(requestedLanguage)) return;
            detectedLanguage = null;
            languageStatus.setText(error instanceof IllegalArgumentException ? error.getMessage() : "语言检测失败，请手动选择语言");
        });
    }

    private void addExample(DictionaryStore.Example example) {
        ui.gap(details, 12); LinearLayout row = ui.row(); LinearLayout text = ui.column();
        TextView english = ui.text(example.english, 15, false); english.setTextIsSelectable(true); text.addView(english); text.addView(ui.text(example.chinese, 13, true));
        ui.weighted(row, text); Button audio = ui.button("读", false); audio.setContentDescription("朗读例句：" + example.english);
        row.addView(audio, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))); audio.setOnClickListener(v -> speak(example.english, "en")); details.addView(row);
    }

    private void toggleBookmark() {
        final int version = lookupGeneration; final String key = bookmarkKey, text = selected; save.setEnabled(false);
        app.databaseWorker.execute(() -> {
            try { boolean saved = app.learning.toggle(key, text); runOnUiThread(() -> { if (alive(version)) { save.setText(saved ? "已加入生词本" : "加入生词本"); save.setEnabled(true); } }); }
            catch (Exception error) { runOnUiThread(() -> { if (alive(version)) { save.setEnabled(true); Toast.makeText(this, "无法保存生词，请检查可用空间", Toast.LENGTH_SHORT).show(); } }); }
        });
    }

    private void speak(String text) {
        String language = "auto".equals(requestedLanguage) && detectedLanguage != null ? detectedLanguage : requestedLanguage;
        speak(text, language);
    }

    private void speak(String text, String language) {
        if (busy && text.equals(playingText)) { stopSpeech(); return; }
        playingText = text; busy = true; play.setText("停止");
        speechTicket = app.speech.speak(text, voice, chineseVoice, speed, language, (message, active) -> {
            if (isFinishing() || isDestroyed()) return;
            status.setText(message); busy = active; play.setText(active ? "停止" : "发音"); if (!active) playingText = null;
        });
    }

    private void stopSpeech() {
        if (app != null) app.speech.stop(speechTicket);
        speechTicket = 0; busy = false; playingText = null;
        if (play != null) play.setText("发音"); if (status != null) status.setText("播放已停止");
    }
    private boolean alive(int version) { return version == lookupGeneration && !isFinishing() && !isDestroyed(); }
    @Override protected void onStop() { stopSpeech(); super.onStop(); }
    @Override protected void onDestroy() { ++lookupGeneration; ++detectionGeneration; super.onDestroy(); }
    private abstract static class SelectionListener implements AdapterView.OnItemSelectedListener {
        @Override public void onNothingSelected(AdapterView<?> parent) {}
    }
}
