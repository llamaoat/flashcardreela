package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
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
    private final int swimmer = Ui.dark ? 0xFFEE8A63 : 0xFFE5683A;
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
    // The colour field behind the sea (mirrors PAL/field() in web-demo/src/app.js): a deep base with three soft
    // blobs of colour drifting over it. Every 30 s it eases into the next palette (ocean, dusk, lagoon, indigo),
    // starting at one that follows the hour, so the colours move on as time goes on. Always dark enough for
    // the white dashes and words.
    private static final int[][][] PAL = {
            {{11, 33, 64}, {29, 78, 216}, {34, 184, 207}, {125, 211, 252}},
            {{26, 16, 56}, {91, 63, 208}, {190, 60, 170}, {96, 165, 250}},
            {{8, 38, 46}, {14, 159, 142}, {45, 212, 191}, {56, 189, 248}},
            {{13, 28, 70}, {67, 56, 202}, {14, 165, 233}, {167, 139, 250}}};
    // light mode: airy and white, with soft cyan and bright blue drifting through; dark ink goes on top
    private static final int[][][] PAL_LIGHT = {
            {{238, 244, 249}, {110, 205, 240}, {59, 130, 246}, {255, 255, 255}},
            {{243, 240, 250}, {196, 181, 253}, {251, 207, 232}, {147, 197, 253}},
            {{236, 248, 246}, {153, 238, 226}, {103, 220, 240}, {147, 197, 253}},
            {{238, 241, 252}, {165, 180, 252}, {103, 220, 240}, {255, 255, 255}}};
    static final int LIGHT_BASE = 0xFFEEF4F9;   // the light sea's page colour, for the bars behind it
    private static final float PAL_MS = 30000f;
    private final boolean light = !Ui.dark;
    private final int ink = light ? 0xFF0B2140 : 0xFFFFFFFF, inkMid = light ? 0x990B2140 : 0x99FFFFFF, inkSoft = light ? 0x8C0B2140 : 0x8CFFFFFF;
    private final float palStart = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) / 24f * PAL.length * PAL_MS;
    private final float[][] cur = new float[4][3];
    private float cyFrac = .5f;           // where the wheel and swimmer sit, as a fraction of the height
    private int scrim, fade;              // welcome: solid under the words at the bottom; dashboard: melts into the page
    private android.graphics.LinearGradient fadeShader;

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
        text.setShadowLayer(10 * d, 0, d, light ? 0xF2FFFFFF : 0xF20B2140);
        setBackgroundColor(light ? LIGHT_BASE : SEA);
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
        palette(t);
        field(cv, t, w, h);
        sea(cv, t, w, h, w * .2f, h * cyFrac);
        if (scrim != 0) { // solid in the palette's own base colour under the words
            paint.setShader(new android.graphics.LinearGradient(0, h, 0, 0, new int[]{col(cur[0], 1f), col(cur[0], .9f), col(cur[0], 0f)},
                    new float[]{0f, .30f, .62f}, Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
        }
        if (fadeShader != null) { paint.setShader(fadeShader); cv.drawRect(0, h * .54f, w, h, paint); paint.setShader(null); }
        if (words.length > 0) wheel(cv, t, w, h);
        if (!still) postInvalidateOnAnimation();
    }

    private void palette(float t) {
        float u = (t + palStart) / PAL_MS, f0 = u - (float) Math.floor(u), f = f0 * f0 * (3 - 2 * f0);
        int i = (int) Math.floor(u);
        int[][][] pal = light ? PAL_LIGHT : PAL;
        int[][] a = pal[i % pal.length], b = pal[(i + 1) % pal.length];
        for (int k = 0; k < 4; k++) for (int j = 0; j < 3; j++) cur[k][j] = a[k][j] + (b[k][j] - a[k][j]) * f;
    }

    private static int col(float[] c, float alpha) { return Color.argb(Math.round(alpha * 255), Math.round(c[0]), Math.round(c[1]), Math.round(c[2])); }

    private void field(Canvas cv, float t, float w, float h) {
        cv.drawColor(col(cur[0], 1f));
        float big = Math.max(w, h) * .62f;
        for (int i = 0; i < 3; i++) {
            double a = t / (11000.0 + i * 4300) + i * 2.1;
            float x = w * (.5f + .4f * (float) Math.cos(a)), y = h * (.42f + .32f * (float) Math.sin(a * 1.3 + i));
            paint.setShader(new RadialGradient(x, y, big * (i == 2 ? .6f : 1f), col(cur[i + 1], i == 2 ? .55f : .8f), col(cur[i + 1], 0f), Shader.TileMode.CLAMP));
            cv.drawRect(0, 0, w, h, paint);
        }
        paint.setShader(null);
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
            float l = len * (1 - pull * .5f), sw = 1.05f;
            int col = light ? Color.argb(Math.round((.22f + j * .28f) * 255), 11, 33, 64) : Color.argb(Math.round((.46f + j * .3f) * 255), 240, 248, 255);
            if (k < 0 && k > -22 * d) { l = (1 + j * 1.5f) * d; sw = 1.7f + j; col = light ? 0xCC1D4ED8 : 0xCCFFFFFF; }
            else if (k <= -22 * d && k > -300 * d) { if (j < .3f) continue; l = (2.5f + j * 4) * d; sw = 2.5f; col = light ? 0x802563EB : 0x8CFFFFFF; a *= .4; }
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
        dash.setColor(swimmer);
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
                draw(cv, "[ ", -text.measureText("[ "), base, inkSoft, alpha);
                float tw = draw(cv, words[i][0], 0, base, ink, alpha);
                float bw = draw(cv, "]", tw, base, inkSoft, alpha);
                draw(cv, words[i][1], tw + bw + 8 * d, base, inkMid, alpha);
            } else {
                draw(cv, words[i][0], 0, base, ink, alpha);
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
