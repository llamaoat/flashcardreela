package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

import java.util.Random;

/**
 * The moving hero on the welcome screen and dashboard: a sea of short dashes drifting on a slow current,
 * a small orange swimmer they turn towards, and waves of white foam rolling across (the feed washing past
 * you), with a wheel of words rolling along a curve over it, the middle one bracketed with a grey note
 * ("[ Enzymes ] 9 due"). Mirrors motion() and sea() in web-demo/src/app.js. Draws only while attached;
 * with animations switched off in the phone's settings it draws one still frame.
 */
final class MotionView extends View {
    static final int SEA = 0xFF0B2140;
    private static final int SWIMMER = 0xFFEE8A63;
    private final String[][] words;
    private final boolean small;
    private final boolean still;
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(); // the scrim and the fade
    private final RectF oval = new RectF();
    private final long start = SystemClock.uptimeMillis();
    private float[] pts = new float[0]; // x, y, length, jitter, phase for each dash
    private final float d;
    private float cyFrac = .5f;           // where the wheel and swimmer sit, as a fraction of the height
    private int scrim, fade;              // welcome: solid under the words at the bottom; dashboard: melts into the page
    private android.graphics.LinearGradient scrimShader, fadeShader;

    MotionView(Context c, String[][] words, boolean small) {
        super(c);
        this.words = words;
        this.small = small;
        this.still = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f;
        d = c.getResources().getDisplayMetrics().density;
        dash.setStrokeCap(Paint.Cap.ROUND);
        text.setTypeface(Ui.MEDIUM);
        text.setTextSize(Ui.dp(c, small ? 17 : 18));
        text.setLetterSpacing(-0.015f);
        text.setShadowLayer(10 * d, 0, d, 0xF20B2140);
        setBackgroundColor(SEA);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** The wheel and the swimmer sit this far down (0..1). */
    MotionView setCentre(float f) { cyFrac = f; return this; }

    /** Fills the sea's lower part with this colour, solid at the bottom, so words read over it. */
    MotionView setScrim(int colour) { scrim = colour; return this; }

    /** Melts the sea's lower edge into the page behind it. */
    MotionView setFade(int colour) { fade = colour; return this; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        if (scrim != 0) scrimShader = new android.graphics.LinearGradient(0, h, 0, 0, new int[]{scrim, (scrim & 0x00FFFFFF) | 0xE6000000, scrim & 0x00FFFFFF},
                new float[]{0f, .30f, .62f}, Shader.TileMode.CLAMP);
        if (fade != 0) fadeShader = new android.graphics.LinearGradient(0, h * .54f, 0, h, fade & 0x00FFFFFF, fade | 0xFF000000, Shader.TileMode.CLAMP);
        Random r = new Random(7);
        float g = 9 * d;
        int cols = (int) (w / g) + 3, rows = (int) (h / g) + 3;
        pts = new float[cols * rows * 5];
        int i = 0;
        for (int y = 0; y < rows; y++)
            for (int x = 0; x < cols; x++) {
                pts[i++] = (x - 1) * g + r.nextFloat() * g * .8f;
                pts[i++] = (y - 1) * g + r.nextFloat() * g * .8f;
                pts[i++] = (4 + r.nextFloat() * 4) * d;
                pts[i++] = r.nextFloat();
                pts[i++] = r.nextFloat() * 6.28f;
            }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float t = SystemClock.uptimeMillis() - start;
        float w = getWidth(), h = getHeight();
        sea(cv, t, w, h, w * .2f, h * cyFrac);
        if (scrimShader != null) { paint.setShader(scrimShader); cv.drawRect(0, 0, w, h, paint); paint.setShader(null); }
        if (fadeShader != null) { paint.setShader(fadeShader); cv.drawRect(0, h * .54f, w, h, paint); paint.setShader(null); }
        if (words.length > 0) wheel(cv, t, w, h);
        if (!still) postInvalidateOnAnimation();
    }

    private void sea(Canvas cv, float t, float w, float h, float sx, float sy) {
        float front = ((t * .06f * d) % (w + 520 * d)) - 160 * d;
        for (int i = 0; i < pts.length; i += 5) {
            float px = pts[i], py = pts[i + 1], len = pts[i + 2], j = pts[i + 3];
            double a = -.6 + Math.sin(py / d * .009 + t * .00035) * .22 + Math.cos(px / d * .007 - t * .00025) * .18;
            float dx = px - sx, dy = py - sy, dist = (float) Math.hypot(dx, dy), pull = Math.max(0f, 1f - dist / (120 * d));
            if (pull > 0) a = a * (1 - pull) + Math.atan2(dy, dx) * pull;
            if (dist < 14 * d) continue;
            float dr = (float) Math.sin(t * .0016 + pts[i + 4]) * 1.4f * d;
            float x = px + (float) Math.cos(a) * dr, y = py + (float) Math.sin(a) * dr;
            float k = x - (front + (float) (Math.sin(y / d * .02 + t * .0009) * 30 + Math.sin(y / d * .066) * 10) * d);
            float l = len * (1 - pull * .5f), sw = .9f;
            int col = Color.argb(Math.round((.4f + j * .3f) * 255), 150, 188, 220);
            if (k < 0 && k > -22 * d) { l = (1 + j * 1.5f) * d; sw = 1.7f + j; col = 0xCCFFFFFF; }
            else if (k <= -22 * d && k > -300 * d) { if (j < .3f) continue; l = (2.5f + j * 4) * d; sw = 2.5f; col = 0x8CFFFFFF; a *= .4; }
            float ca = (float) Math.cos(a) * l / 2, sa = (float) Math.sin(a) * l / 2;
            dash.setColor(col);
            dash.setStrokeWidth(sw * d);
            cv.drawLine(x - ca, y - sa, x + ca, y + sa, dash);
        }
        // the swimmer
        float s = (float) Math.sin(t * .006);
        cv.save();
        cv.translate(sx, sy);
        cv.scale(d, d);
        cv.rotate(-20);
        dash.setColor(SWIMMER);
        dash.setStyle(Paint.Style.FILL);
        oval.set(-8, -4.5f, 8, 4.5f);
        cv.drawOval(oval, dash);
        cv.drawCircle(9.5f, -.5f, 3.5f, dash);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(3);
        cv.drawLine(4, -3, 15 + s * 2.5f, -6 + s * 4, dash);
        cv.drawLine(4, 3, 14 - s * 2.5f, 9 - s * 3, dash);
        cv.drawLine(-7, -2, -16, -4 + s * 2, dash);
        cv.drawLine(-7, 2, -16, 4.5f - s * 2, dash);
        cv.restore();
    }

    private void wheel(Canvas cv, float t, float w, float h) {
        int n = words.length;
        float cy = h * cyFrac, step = d * (small ? 27 : 30), r = d * (small ? 260 : 320), bx = w * .42f;
        float off = (t / 1400f) % n, reach = Math.min(cy, h - cy) / step + .5f;
        float base = -(text.ascent() + text.descent()) / 2f;
        for (int i = 0; i < n; i++) {
            float k = ((i - off) % n + n) % n;
            if (k > n / 2f) k -= n;
            float alpha = Math.max(0f, 1f - Math.abs(k) / reach);
            if (alpha <= 0f) continue;
            float y = k * step;
            double a = Math.asin(Math.max(-1f, Math.min(1f, y / r)));
            cv.save();
            cv.translate(bx + r * (float) (1 - Math.cos(a)), cy + y);
            cv.rotate((float) Math.toDegrees(a * .8));
            if (Math.abs(k) < .5f) {
                draw(cv, "[ ", -text.measureText("[ "), base, 0x8CFFFFFF, alpha);
                float tw = draw(cv, words[i][0], 0, base, 0xFFFFFFFF, alpha);
                float bw = draw(cv, "]", tw, base, 0x8CFFFFFF, alpha);
                draw(cv, words[i][1], tw + bw + 8 * d, base, 0x99FFFFFF, alpha);
            } else {
                draw(cv, words[i][0], 0, base, 0xFFFFFFFF, alpha);
            }
            cv.restore();
        }
    }

    private float draw(Canvas cv, String s, float x, float y, int colour, float alpha) {
        text.setColor(colour);
        text.setAlpha(Math.round(Color.alpha(colour) * alpha));
        cv.drawText(s, x, y, text);
        return text.measureText(s);
    }
}
