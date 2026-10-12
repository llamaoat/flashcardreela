package com.reelcards.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/**
 * A picture filling the screen that can be pinched, dragged and double-tapped (CardView.zoom). It
 * starts fitted to the screen; fitted is the smallest it gets. A sharper copy can replace the first
 * bitmap without the view jumping (setBitmap keeps what's on screen).
 */
final class ZoomView extends View {
    private static final float MAX = 6f; // times the fitted size
    private Bitmap bmp;
    private final Matrix m = new Matrix();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final float[] v = new float[9];
    private final ScaleGestureDetector scale;
    private final GestureDetector taps;
    private float lastX, lastY;
    private boolean placed;

    ZoomView(Context c) {
        super(c);
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { zoomBy(d.getScaleFactor(), d.getFocusX(), d.getFocusY()); return true; }
        });
        taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { toggle(e.getX(), e.getY()); return true; }
        });
        setContentDescription("Picture. Pinch or double-tap to zoom, drag to move.");
    }

    /** The picture to show; a bigger version of the same picture keeps the current zoom and position. */
    void setBitmap(Bitmap b) {
        if (b == null) return;
        if (bmp != null && placed && bmp.getWidth() > 0 && bmp.getHeight() > 0)
            m.preScale(bmp.getWidth() / (float) b.getWidth(), bmp.getHeight() / (float) b.getHeight());
        boolean first = bmp == null;
        bmp = b;
        if (first || !placed) place();
        else clamp();
        invalidate();
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { place(); invalidate(); }

    private float fit() { return Math.min(getWidth() / (float) bmp.getWidth(), getHeight() / (float) bmp.getHeight()); }

    private float current() { m.getValues(v); return v[Matrix.MSCALE_X]; }

    /** Fitted and centred. */
    private void place() {
        if (bmp == null || getWidth() == 0 || getHeight() == 0) return;
        float f = fit();
        m.reset();
        m.postScale(f, f);
        m.postTranslate((getWidth() - bmp.getWidth() * f) / 2f, (getHeight() - bmp.getHeight() * f) / 2f);
        placed = true;
    }

    private void zoomBy(float factor, float fx, float fy) {
        if (bmp == null) return;
        float f = fit(), now = current();
        float target = Math.max(f, Math.min(f * MAX, now * factor));
        m.postScale(target / now, target / now, fx, fy);
        clamp();
        invalidate();
    }

    /** Double-tap: zoom in on that spot, or back out to fitted. */
    private void toggle(float x, float y) {
        if (bmp == null) return;
        float f = fit();
        if (current() > f * 1.05f) place();
        else { float to = Math.min(f * 3f, f * MAX); m.postScale(to / current(), to / current(), x, y); }
        clamp();
        invalidate();
    }

    /** Keeps the picture on screen: centred while it's smaller than the view, never dragged past its edges when bigger. */
    private void clamp() {
        if (bmp == null) return;
        r.set(0, 0, bmp.getWidth(), bmp.getHeight());
        m.mapRect(r);
        float dx = 0, dy = 0, w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2f - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2f - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        scale.onTouchEvent(ev);
        taps.onTouchEvent(ev);
        int action = ev.getActionMasked();
        int skip = action == MotionEvent.ACTION_POINTER_UP ? ev.getActionIndex() : -1;
        float sx = 0, sy = 0;
        int n = 0;
        for (int i = 0; i < ev.getPointerCount(); i++) {
            if (i == skip) continue;
            sx += ev.getX(i);
            sy += ev.getY(i);
            n++;
        }
        if (n == 0) return true;
        sx /= n;
        sy /= n;
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            lastX = sx;
            lastY = sy;
        } else if (action == MotionEvent.ACTION_MOVE && bmp != null) {
            m.postTranslate(sx - lastX, sy - lastY);
            lastX = sx;
            lastY = sy;
            clamp();
            invalidate();
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas c) {
        if (bmp == null) return;
        r.set(0, 0, bmp.getWidth(), bmp.getHeight());
        m.mapRect(r);
        p.setColor(0xFFFFFFFF);
        c.drawRect(r, p); // a picture with a see-through background still reads
        c.drawBitmap(bmp, m, p);
    }
}
