package com.alibaba.mnn.english;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class Ui {
    final Context context;
    final int paper, surface, ink, muted, green, onGreen, soft, line;
    Ui(Context context) {
        this.context = context;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        paper = Color.parseColor(night ? "#20221F" : "#FAF8F3");
        surface = Color.parseColor(night ? "#2B302D" : "#FFFFFF");
        ink = Color.parseColor(night ? "#EDF3EE" : "#252D29");
        muted = Color.parseColor(night ? "#B6C5BA" : "#5B6961");
        green = Color.parseColor(night ? "#96DBB5" : "#1F664B");
        onGreen = Color.parseColor(night ? "#102E20" : "#FFFFFF");
        soft = Color.parseColor(night ? "#354B3E" : "#E9F3ED");
        line = Color.parseColor(night ? "#48534B" : "#DDE4DF");
    }
    int dp(float n) { return Math.round(n * context.getResources().getDisplayMetrics().density); }
    LinearLayout column() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.VERTICAL); return v; }
    LinearLayout row() { LinearLayout v = new LinearLayout(context); v.setOrientation(LinearLayout.HORIZONTAL); v.setGravity(Gravity.CENTER_VERTICAL); return v; }
    TextView text(String value, int size, boolean secondary) {
        TextView v = new TextView(context); v.setText(value); v.setTextSize(size); v.setTextColor(secondary ? muted : ink);
        v.setLineSpacing(dp(3), 1); return v;
    }
    Button button(String label, boolean primary) {
        Button v = new Button(context); v.setText(label); v.setAllCaps(false); v.setTextSize(14);
        v.setMinHeight(dp(48)); v.setMinimumHeight(dp(48)); v.setTextColor(primary ? onGreen : green);
        v.setPadding(dp(12), dp(6), dp(12), dp(6));
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(line), background(primary ? green : soft, 18), null));
        return v;
    }
    GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    void gap(LinearLayout parent, int height) { View gap = new View(context); parent.addView(gap, new LinearLayout.LayoutParams(1, dp(height))); }
    void divider(LinearLayout parent) { View lineView = new View(context); lineView.setBackgroundColor(line); parent.addView(lineView, new LinearLayout.LayoutParams(-1, dp(1))); }
    void weighted(LinearLayout row, View view) { row.addView(view, new LinearLayout.LayoutParams(0, -2, 1)); }
    static final class CardScroll extends ScrollView {
        private float maxFraction = 0.48f;
        CardScroll(Context context) { super(context); setFillViewport(false); }
        void setMaxFraction(float fraction) { maxFraction = fraction; }
        @Override protected void onMeasure(int width, int height) {
            int max = (int) (getResources().getDisplayMetrics().heightPixels * maxFraction);
            int mode = MeasureSpec.getMode(height), size = MeasureSpec.getSize(height);
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(mode == MeasureSpec.UNSPECIFIED ? max : Math.min(max, size), MeasureSpec.AT_MOST));
        }
    }
}
