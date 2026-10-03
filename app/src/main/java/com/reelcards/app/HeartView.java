package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The "like" heart, drawn as a path so it looks the same on every phone. */
final class HeartView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private boolean filled;
    private int strokeColor = 0xFFFFFFFF;
    static final int RED = 0xFFFF3B5C;

    HeartView(Context c) {
        super(c);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    void setFilled(boolean f) { filled = f; invalidate(); }

    void setStrokeColor(int c) { strokeColor = c; invalidate(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float s = Math.min(w, h) / 24f;
        float ox = (w - 24 * s) / 2f, oy = (h - 24 * s) / 2f;
        path.reset();
        // heart in a 24x24 box
        path.moveTo(ox + 12 * s, oy + 21 * s);
        path.cubicTo(ox + 5 * s, oy + 15.5f * s, ox + 2 * s, oy + 12 * s, ox + 2 * s, oy + 8.2f * s);
        path.cubicTo(ox + 2 * s, oy + 5 * s, ox + 4.4f * s, oy + 3 * s, ox + 7.2f * s, oy + 3 * s);
        path.cubicTo(ox + 9.2f * s, oy + 3 * s, ox + 11 * s, oy + 4.1f * s, ox + 12 * s, oy + 5.8f * s);
        path.cubicTo(ox + 13 * s, oy + 4.1f * s, ox + 14.8f * s, oy + 3 * s, ox + 16.8f * s, oy + 3 * s);
        path.cubicTo(ox + 19.6f * s, oy + 3 * s, ox + 22 * s, oy + 5 * s, ox + 22 * s, oy + 8.2f * s);
        path.cubicTo(ox + 22 * s, oy + 12 * s, ox + 19 * s, oy + 15.5f * s, ox + 12 * s, oy + 21 * s);
        path.close();
        if (filled) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(RED);
        } else {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * s);
            paint.setColor(strokeColor);
        }
        c.drawPath(path, paint);
    }
}
