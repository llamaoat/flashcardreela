package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * The moving hero on the welcome screen and dashboard: a soft orb of drifting colour on the left and a
 * wheel of words rolling along a curve beside it, the middle one bracketed with a grey note
 * ("[ Enzymes ] 9 due"). Mirrors motion() in web-demo/src/app.js. Draws only while attached; with
 * animations switched off in the phone's settings it draws one still frame.
 */
final class MotionView extends View {
    private final String[][] words;
    private final boolean small;
    private final boolean still;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final long start = SystemClock.uptimeMillis();
    private static final int[] BLOBS = {0xF2F26A3D, 0xF2F4B79A, 0xF26E8F55, 0xF2FBE2D3, 0xF2E0472A};
    private static final float[] SIZES = {.9f, .75f, .55f, .65f, .7f};

    MotionView(Context c, String[][] words, boolean small) {
        super(c);
        this.words = words;
        this.small = small;
        this.still = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        text.setTypeface(Ui.MEDIUM);
        text.setTextSize(Ui.dp(c, small ? 17 : 18));
        text.setLetterSpacing(-0.015f);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas cv) {
        if (words.length == 0) return;
        float t = SystemClock.uptimeMillis() - start, d = getResources().getDisplayMetrics().density;
        float h = getHeight(), cy = h / 2f;
        orb(cv, t, -d * (small ? 50 : 64), cy, d * (small ? 75 : 95));

        int n = words.length;
        float step = d * (small ? 27 : 30), r = d * (small ? 260 : 320), bx = d * (small ? 118 : 146);
        float off = (t / 1400f) % n;
        float fade = h / step / 2f + .5f;
        for (int i = 0; i < n; i++) {
            float k = ((i - off) % n + n) % n;
            if (k > n / 2f) k -= n;
            float alpha = Math.max(0f, 1f - Math.abs(k) / fade);
            if (alpha <= 0f) continue;
            float y = k * step;
            double a = Math.asin(Math.max(-1f, Math.min(1f, y / r)));
            float x = bx + r * (float) (1 - Math.cos(a));
            boolean sel = Math.abs(k) < .5f;
            cv.save();
            cv.translate(x, cy + y);
            cv.rotate((float) Math.toDegrees(a * .8));
            float base = -(text.ascent() + text.descent()) / 2f;
            if (sel) {
                float lx = -text.measureText("[ ");
                draw(cv, "[ ", lx, base, Ui.MUTED, alpha);
                float w = draw(cv, words[i][0], 0, base, Ui.TEXT, alpha);
                float bw = draw(cv, "]", w, base, Ui.MUTED, alpha);
                draw(cv, words[i][1], w + bw + 8 * d, base, Ui.MUTED, alpha);
            } else {
                draw(cv, words[i][0], 0, base, Ui.TEXT, alpha);
            }
            cv.restore();
        }
        if (!still) postInvalidateOnAnimation();
    }

    private float draw(Canvas cv, String s, float x, float y, int colour, float alpha) {
        text.setColor(colour);
        text.setAlpha(Math.round(Color.alpha(colour) * alpha));
        cv.drawText(s, x, y, text);
        return text.measureText(s);
    }

    private void orb(Canvas cv, float t, float cx, float cy, float rad) {
        cv.save();
        clip.reset();
        clip.addCircle(cx, cy, rad, Path.Direction.CW);
        cv.clipPath(clip);
        paint.setShader(null);
        paint.setColor(0xFFF08A62);
        cv.drawCircle(cx, cy, rad, paint);
        for (int i = 0; i < BLOBS.length; i++) {
            double a = t / (2600.0 + i * 700) + i * 1.7;
            float x = cx + (float) Math.cos(a) * rad * .5f, y = cy + (float) Math.sin(a * 1.3) * rad * .45f;
            float r = rad * (.37f + .58f * SIZES[i]);
            paint.setShader(new RadialGradient(x, y, r, BLOBS[i], BLOBS[i] & 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawCircle(x, y, r, paint);
        }
        paint.setShader(new RadialGradient(cx - rad * .32f, cy - rad * .4f, rad * 1.4f,
                new int[]{0x33FFFFFF, 0x00000000, 0x473C140A}, new float[]{0f, .55f, .75f}, Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, rad, paint);
        paint.setShader(null);
        cv.restore();
    }
}
