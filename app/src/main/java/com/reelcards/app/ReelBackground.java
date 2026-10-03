package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

/**
 * Looping background drawn in code: stand-ins for the looping footage a finished app would use
 * (satisfying clips, endless-runner gameplay, city walks, a football pitch, a music visualiser).
 * Drawn, so the APK stays tiny and works offline. Matches the web demo's backgrounds.
 */
final class ReelBackground extends View {
    private final String type;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Path track = new android.graphics.Path();
    private final long start = SystemClock.uptimeMillis();
    private final boolean animate;
    private boolean attached;
    private final float d; // 1dp in px

    ReelBackground(Context c, String type) {
        super(c);
        this.type = type;
        this.d = c.getResources().getDisplayMetrics().density;
        float scale = 1f;
        try { scale = Settings.Global.getFloat(c.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f); }
        catch (Exception ignored) { }
        this.animate = scale > 0f; // respect "Remove animations"
    }

    // gradients are rebuilt only when the size changes, not every frame
    private final LinearGradient[] grads = new LinearGradient[8];
    private final float[][] gradKeys = new float[8][];

    private LinearGradient grad(int slot, float x0, float y0, float x1, float y1, int c0, int c1) {
        float[] key = {x0, y0, x1, y1, c0, c1};
        if (grads[slot] == null || !java.util.Arrays.equals(gradKeys[slot], key)) {
            grads[slot] = new LinearGradient(x0, y0, x1, y1, c0, c1, Shader.TileMode.CLAMP);
            gradKeys[slot] = key;
        }
        return grads[slot];
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); attached = true; invalidate(); }

    @Override protected void onDetachedFromWindow() { attached = false; super.onDetachedFromWindow(); }

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        float t = animate ? (SystemClock.uptimeMillis() - start) / 1000f : 0f;
        switch (type) {
            case "satisfying": drawStripes(c, w, h, t); break;
            case "gameplay": drawGameplay(c, w, h, t); break;
            case "citywalk": drawCity(c, w, h, t); break;
            case "pitch": drawPitch(c, w, h, t); break;
            case "glow": drawGlow(c, w, h, t); break;
            case "equalizer": drawEqualizer(c, w, h, t); break;
            default: return; // plain: nothing
        }
        if (attached && animate) postInvalidateOnAnimation();
    }

    private void drawStripes(Canvas c, int w, int h, float t) {
        c.drawColor(0xFFC9A4F2);
        paint.setShader(null);
        paint.setColor(0xFFB88DE8);
        float stripe = 22 * d, period = 44 * d;
        float off = (t * 36 * d) % period;
        float diag = (float) Math.hypot(w, h);
        c.save();
        c.rotate(-45, w / 2f, h / 2f);
        for (float x = -diag; x < diag * 1.5f; x += period) {
            c.drawRect(x + off, -diag, x + off + stripe, diag * 2, paint);
        }
        c.restore();
        // a soft "blade" sweeping down, like a soap-cutting clip
        paint.setColor(0x55FFFFFF);
        float y = (t * 90 * d) % (h + 60 * d) - 30 * d;
        c.drawRect(0, y, w, y + 6 * d, paint);
    }

    /** Endless-runner gameplay: sky, a three-lane track rushing towards you, a runner hopping. */
    private void drawGameplay(Canvas c, int w, int h, float t) {
        float horizon = h * 0.34f;
        paint.setShader(grad(1, 0, 0, 0, horizon, 0xFF64C7FF, 0xFFC9ECFF));
        c.drawRect(0, 0, w, horizon, paint);
        paint.setShader(null);
        paint.setColor(0xFF2D313B);
        c.drawRect(0, horizon, w, h, paint);
        float cx = w / 2f, spread = w * 0.9f; // half-width of the track at the bottom edge
        // track surface
        track.reset();
        track.moveTo(cx - 10 * d, horizon);
        track.lineTo(cx + 10 * d, horizon);
        track.lineTo(cx + spread, h);
        track.lineTo(cx - spread, h);
        track.close();
        paint.setColor(0xFF3A3F4B);
        c.drawPath(track, paint);
        // sleepers rushing towards the camera (spacing grows with depth)
        paint.setColor(0xFF4B5160);
        for (int i = 0; i < 9; i++) {
            float ph = frac(i / 9f + t * 0.55f);
            float y = horizon + (h - horizon) * ph * ph;
            float half = 10 * d + (spread - 10 * d) * ph * ph;
            c.drawRect(cx - half, y, cx + half, y + (2 + 10 * ph * ph) * d, paint);
        }
        // rails: two per lane, converging on the horizon
        paint.setColor(0xFFB9C0CC);
        paint.setStrokeWidth(3 * d);
        for (int lane = -3; lane <= 3; lane += 2) {
            float bx = cx + lane * spread / 3f;
            c.drawLine(cx + lane * 3 * d, horizon, bx, h, paint);
        }
        // the runner: a pink block with a black outline, hopping
        float bw = 30 * d, bh = 38 * d, bx = w * 0.46f, hop = (float) Math.abs(Math.sin(t * Math.PI / 0.9)) * 34 * d;
        float by = h * 0.73f - bh - hop;
        paint.setColor(0x59000000);
        c.drawOval(bx + 2 * d, h * 0.73f - 6 * d, bx + bw - 2 * d, h * 0.73f + 4 * d, paint);
        paint.setColor(0xFF111111);
        c.drawRoundRect(bx - 3 * d, by - 3 * d, bx + bw + 3 * d, by + bh + 3 * d, 10 * d, 10 * d, paint);
        paint.setColor(0xFFFF4FD8);
        c.drawRoundRect(bx, by, bx + bw, by + bh, 8 * d, 8 * d, paint);
    }

    /** Music visualiser: a row of bars pulsing to a beat that isn't there. */
    private void drawEqualizer(Canvas c, int w, int h, float t) {
        paint.setShader(grad(2, 0, 0, 0, h, 0xFF150B26, 0xFF2A0F3D));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        paint.setColor(0xCCFF4FD8);
        float left = w * 0.06f, right = w * 0.94f, base = h * 0.78f, max = h * 0.40f;
        float bar = 10 * d, gap = 6 * d;
        int i = 0;
        for (float x = left; x + bar <= right; x += bar + gap, i++) {
            double beat = 0.55 + 0.45 * Math.sin(t * 4.6 + i * 0.9) * Math.cos(t * 1.7 + i * 0.37);
            float bh = (float) (max * (0.15 + 0.85 * Math.abs(beat)));
            c.drawRect(x, base - bh, x + bar, base, paint);
        }
    }

    private void drawCity(Canvas c, int w, int h, float t) {
        paint.setShader(grad(3, 0, 0, 0, h, 0xFF1B2233, 0xFF2D2438));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        for (int i = 0; i < 28; i++) {
            float fx = frac(i * 0.6180339f), fy = frac(i * 0.3819660f + 0.17f);
            float speed = (20 + 40 * frac(i * 0.7548776f)) * d;
            float x = fx * w;
            float y = (fy * h + t * speed) % h;
            paint.setColor(i % 3 == 0 ? 0x806FD3FF : 0x80FFB547);
            c.drawCircle(x, y, (3 + 4 * frac(i * 0.5698403f)) * d, paint);
        }
        // silhouettes of buildings
        paint.setColor(0xFF121722);
        for (int i = 0; i < 7; i++) {
            float bw = w / 7f;
            float bh = h * (0.12f + 0.16f * frac(i * 0.37f + 0.1f));
            c.drawRect(i * bw, h - bh, i * bw + bw * 0.9f, h, paint);
        }
    }

    /** Football pitch: mowing stripes and lines, slowly panning like a broadcast camera. */
    private void drawPitch(Canvas c, int w, int h, float t) {
        paint.setShader(null);
        float band = 46 * d;
        float off = (t * 18 * d) % (2 * band);
        c.drawColor(0xFF2E7D32);
        paint.setColor(0xFF388E3C);
        for (float y = -2 * band + off; y < h; y += 2 * band) c.drawRect(0, y, w, y + band, paint);
        paint.setColor(0xCCFFFFFF);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3 * d);
        float cy = h * 0.5f + (float) Math.sin(t * 0.4) * 30 * d;
        c.drawLine(0, cy, w, cy, paint);
        c.drawCircle(w / 2f, cy, Math.min(w, h) * 0.18f, paint);
        c.drawRect(w * 0.2f, cy + h * 0.3f, w * 0.8f, cy + h * 0.55f, paint);
        paint.setStyle(Paint.Style.FILL);
        // the ball
        paint.setColor(0xFFFFFFFF);
        float bx = (float) ((Math.sin(t * 0.9) * 0.3 + 0.5) * w), by = cy - (float) Math.abs(Math.sin(t * 2.1)) * 50 * d;
        c.drawCircle(bx, by, 7 * d, paint);
    }

    /** Soft bokeh glow, like a beauty or music video backdrop. */
    private void drawGlow(Canvas c, int w, int h, float t) {
        paint.setShader(grad(4, 0, 0, w, h, 0xFF2A1233, 0xFF120D24));
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
        int[] cols = {0x55FF7AB6, 0x55B388FF, 0x55FFD27A, 0x4480D8FF};
        for (int i = 0; i < 14; i++) {
            float fx = frac(i * 0.6180339f + 0.1f), fy = frac(i * 0.4142135f + 0.3f);
            float x = fx * w + (float) Math.sin(t * 0.3 + i) * 20 * d;
            float y = fy * h + (float) Math.cos(t * 0.25 + i * 1.3) * 26 * d;
            paint.setColor(cols[i % cols.length]);
            c.drawCircle(x, y, (26 + 40 * frac(i * 0.7548776f)) * d, paint);
        }
    }

    private static float frac(float v) { return v - (float) Math.floor(v); }
}
