package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Cards reviewed (ink) and skipped (grey) per day for the last 14 days. */
final class BarChartView extends View {
    private int[] reviews = new int[0], skips = new int[0];
    private String[] labels = new String[0];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    BarChartView(Context c) { super(c); }

    void setData(int[] reviews, int[] skips, String[] labels) {
        this.reviews = reviews; this.skips = skips; this.labels = labels;
        setContentDescription("Cards reviewed per day, last " + reviews.length + " days");
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = reviews.length;
        if (n == 0) return;
        float d = getResources().getDisplayMetrics().density;
        float w = getWidth(), h = getHeight();
        float labelH = 16 * d, topPad = 14 * d;
        float plotH = h - labelH - topPad;
        int max = 1;
        for (int i = 0; i < n; i++) max = Math.max(max, reviews[i] + skips[i]);
        float slot = w / n, bw = Math.min(slot * 0.62f, 22 * d);

        p.setColor(Ui.LINE);
        c.drawRect(0, topPad + plotH, w, topPad + plotH + d, p);

        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(10 * d);
        for (int i = 0; i < n; i++) {
            float cx = slot * i + slot / 2;
            float rh = plotH * reviews[i] / max, sh = plotH * skips[i] / max;
            float base = topPad + plotH;
            if (rh > 0) {
                p.setColor(Ui.ACCENT);
                r.set(cx - bw / 2, base - rh, cx + bw / 2, base);
                c.drawRoundRect(r, 3 * d, 3 * d, p);
            }
            if (sh > 0) {
                p.setColor(0xFF4A4C5A);
                r.set(cx - bw / 2, base - rh - sh, cx + bw / 2, base - rh - (rh > 0 ? d : 0));
                c.drawRoundRect(r, 3 * d, 3 * d, p);
            }
            if (reviews[i] > 0) {
                p.setColor(Ui.TEXT);
                c.drawText(String.valueOf(reviews[i]), cx, Math.max(10 * d, base - rh - sh - 3 * d), p);
            }
            if (i == n - 1 || i % 2 == (n - 1) % 2) {
                p.setColor(Ui.MUTED);
                c.drawText(labels[i], cx, h - 3 * d, p);
            }
        }
    }
}
