package com.alibaba.mnn.english;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); Ui ui = new Ui(this);
        ScrollView scroll = new ScrollView(this); LinearLayout root = ui.column();
        root.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(24)); root.setBackgroundColor(ui.paper); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(ui.dp(24), ui.dp(24) + insets.getSystemWindowInsetTop(), ui.dp(24), ui.dp(24) + insets.getSystemWindowInsetBottom()); return insets;
        });
        root.addView(ui.text("离线发音", 30, false)); ui.gap(root, 10);
        root.addView(ui.text("在其他应用长按选中文字，点“发音”（可能在“更多”里）。也可以分享文字到这里。", 16, false));
        ui.gap(root, 24); root.addView(ui.text("试一试长按选字", 14, true)); ui.gap(root, 8);
        TextView sample = ui.text("A curious child asked how the clock worked.", 22, false); sample.setTextIsSelectable(true); root.addView(sample);
        ui.gap(root, 24); root.addView(ui.text("或输入文字", 14, true));
        EditText input = new EditText(this); input.setText(R.string.sample_word); input.setTextSize(18); input.setTextColor(ui.ink); input.setMaxLines(6); input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE); root.addView(input);
        Button open = ui.button("查词与发音", true); root.addView(open); open.setOnClickListener(v -> open(input.getText().toString()));
        ui.gap(root, 12); Button bookmarks = ui.button("生词本", false); root.addView(bookmarks); bookmarks.setOnClickListener(v -> showBookmarks());
        ui.gap(root, 12); Button models = ui.button("下载语音", false); root.addView(models);
        models.setOnClickListener(v -> startActivity(new Intent(this, ModelDownloadActivity.class)));
        ui.gap(root, 24); root.addView(ui.text("语音模型从 ModelScope 按需下载，支持暂停和断点继续，完成后可离线发音。英语词库随安装包提供，支持离线查询释义、音标和例句。", 13, true));
        root.addView(ui.text("音色代表不同声音，不代表英式或美式口音。未收录例句的词仍可查词和发音。", 13, true));
        ui.gap(root, 12); Button sources = ui.button("数据来源与许可", false); root.addView(sources);
        sources.setOnClickListener(v -> showSources());
    }
    private void open(String text) { startActivity(new Intent(this, PronounceActivity.class).putExtra(Intent.EXTRA_TEXT, text)); }
    private void showBookmarks() {
        ReaderApplication app = (ReaderApplication) getApplication();
        app.databaseWorker.execute(() -> {
            try {
                java.util.List<String> words = app.learning.bookmarks();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("生词本").setNegativeButton("关闭", null);
                    if (words.isEmpty()) dialog.setMessage("查词时点“加入生词本”，就能在这里找到。");
                    else dialog.setItems(words.toArray(new String[0]), (d, position) -> open(words.get(position)));
                    dialog.show();
                });
            } catch (Exception error) { runOnUiThread(() -> android.widget.Toast.makeText(this, "生词本读取失败", android.widget.Toast.LENGTH_SHORT).show()); }
        });
    }
    private void showSources() {
        ReaderApplication app = (ReaderApplication) getApplication();
        app.databaseWorker.execute(() -> {
            try {
                String notices = ModelAssets.read(getAssets().open("NOTICE.txt"));
                String license = ModelAssets.read(getAssets().open("licenses/SUPERTONIC-LICENSE"));
                String apache = ModelAssets.read(getAssets().open("licenses/KOKORO-LICENSE"));
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) new AlertDialog.Builder(this).setTitle("数据来源与许可").setMessage(notices + "\n\n" + license + "\n\n" + apache).setPositiveButton("关闭", null).show(); });
            } catch (Exception error) { runOnUiThread(() -> android.widget.Toast.makeText(this, "许可说明读取失败", android.widget.Toast.LENGTH_SHORT).show()); }
        });
    }
}
