package com.alibaba.mnn.english;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;
import java.util.Map;

/** Downloads are explicitly started here; leaving the page saves progress and pauses networking. */
public final class ModelDownloadActivity extends Activity {
    private ModelDownloads downloads;
    private final Map<String, TextView> statuses = new HashMap<>();
    private final Map<String, Button> buttons = new HashMap<>();
    private final Map<String, Boolean> running = new HashMap<>();
    private final ModelDownloads.Listener observer = (id, message, busy, ready) -> {
        if (isFinishing() || isDestroyed()) return;
        statuses.get(id).setText(message); running.put(id, busy);
        Button button = buttons.get(id); button.setEnabled(!ready); button.setText(ready ? "已下载" : busy ? "暂停下载" : "下载 / 继续");
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state); downloads = ((ReaderApplication) getApplication()).models; Ui ui = new Ui(this);
        ScrollView scroll = new ScrollView(this); LinearLayout root = ui.column(); root.setBackgroundColor(ui.paper);
        root.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(24)); scroll.addView(root); setContentView(scroll);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(ui.dp(24), ui.dp(24) + insets.getSystemWindowInsetTop(), ui.dp(24), ui.dp(24) + insets.getSystemWindowInsetBottom()); return insets;
        });
        root.addView(ui.text("下载语音", 28, false)); ui.gap(root, 12);
        root.addView(ui.text("从 ModelScope 按需下载，完成后可离线发音。建议使用 Wi-Fi。离开本页会暂停，返回后可继续；断网或重启 App 也会保留下载进度。", 15, false));
        for (ModelDownloads.Pack pack : downloads.packs()) {
            ui.gap(root, 24); root.addView(ui.text(pack.name, 21, false));
            root.addView(ui.text("kokoro".equals(pack.id) ? "Kokoro · 约 110 MB · 中文男女声" : "Supertonic 3 · 约 113 MB · 31 种语言、十种音色", 14, true));
            TextView status = ui.text("正在检查本地资源…", 13, true); status.setAccessibilityLiveRegion(TextView.ACCESSIBILITY_LIVE_REGION_POLITE);
            statuses.put(pack.id, status); root.addView(status);
            Button button = ui.button("下载 / 继续", true); buttons.put(pack.id, button); root.addView(button);
            button.setOnClickListener(view -> { if (Boolean.TRUE.equals(running.get(pack.id))) downloads.pause(); else downloads.start(pack.id); });
        }
        ui.gap(root, 24); Button done = ui.button("返回", false); root.addView(done); done.setOnClickListener(view -> finish());
    }
    @Override protected void onStart() { super.onStart(); downloads.observe(observer); }
    @Override protected void onStop() { downloads.pause(); downloads.detach(observer); super.onStop(); }
}
