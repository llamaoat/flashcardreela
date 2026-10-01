package com.reelcards.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * Rail and caption-band icons: generic outline shapes drawn for Reelcards (no platform logos), in
 * the same 24x24 SVG path data as the web demo. Includes a small SVG path parser (M L H V C S Q Z,
 * absolute and relative) so the shapes are identical on both.
 */
final class Icons {
    private Icons() {}

    static final String HEART = "M12 20.5s-7.5-4.6-9.2-9.4C1.6 7.6 3.8 4.2 7.2 4.2c2 0 3.6 1.1 4.8 2.9 1.2-1.8 2.8-2.9 4.8-2.9 3.4 0 5.6 3.4 4.4 6.9-1.7 4.8-9.2 9.4-9.2 9.4z";
    static final String THUMB = "M7 10v11H3V10h4zm0 0 4.5-7.5c1.6 0 2.7 1.3 2.4 2.9L13.2 10H19Q21 10 21 12.3l-1.3 7Q19.4 21 17.7 21H7";
    static final String BUBBLE = "M12 3.5c5 0 9 3.4 9 7.7s-4 7.7-9 7.7c-1 0-2-.1-2.9-.4L4 20.5l1.3-4C3.8 15 3 13.2 3 11.2 3 6.9 7 3.5 12 3.5z";
    static final String BOOKMARK = "M6 3.5h12v17l-6-4.2-6 4.2z";
    static final String SHARE = "M13.5 4.5 21 11l-7.5 6.5v-3.8C8 13.7 5 15.5 3 19.5c.6-5.8 3.6-9.9 10.5-11z";
    static final String SEND = "M21 3.5 3 10.2l7.2 2.6L21 3.5zm0 0-7.6 17-3.2-7.7";
    static final String NOTE = "M7 5L18 3V14.5H16V7.3L9 8.6V17.5H7Z";

    /** One icon on screen. Outline by default; filled when "on" (liked, saved). */
    static final class IconView extends View {
        private final Path src, scaled = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final boolean dots, note;
        private boolean filled;
        private int color = 0xFFFFFFFF, fillColor = 0xFFFF3B5C;

        IconView(Context c, String pathData) {
            super(c);
            src = parse(pathData);
            dots = pathData.equals(BUBBLE);
            note = pathData.equals(NOTE);
            if (note) { src.addCircle(6, 17.5f, 3, Path.Direction.CW); src.addCircle(15, 14.5f, 3, Path.Direction.CW); }
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(LAYER_TYPE_SOFTWARE, null); // shadow layers on paths need software drawing below Android 9
        }

        void setColors(int stroke, int fill) { color = stroke; fillColor = fill; invalidate(); }

        void setFilled(boolean f) { filled = f; invalidate(); }

        void setShadow(boolean on) {
            if (on) paint.setShadowLayer(getResources().getDisplayMetrics().density * 1.5f, 0, getResources().getDisplayMetrics().density, 0x73000000);
            else paint.clearShadowLayer();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) / 24f;
            m.setScale(s, s);
            m.postTranslate((w - 24 * s) / 2f, (h - 24 * s) / 2f);
            src.transform(m, scaled);
            if (note || filled) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(note ? color : fillColor);
                c.drawPath(scaled, paint);
                if (note) return;
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * s);
            paint.setColor(filled ? fillColor : color);
            c.drawPath(scaled, paint);
            if (dots) {
                paint.setStyle(Paint.Style.FILL);
                float ox = (w - 24 * s) / 2f, oy = (h - 24 * s) / 2f;
                for (int i = 0; i < 3; i++) c.drawCircle(ox + (8 + 4 * i) * s, oy + 11.2f * s, 1.1f * s, paint);
            }
        }
    }

    // ------------------------------------------------------------------ SVG path data -> Path

    static Path parse(String d) {
        Path p = new Path();
        int[] pos = {0};
        float x = 0, y = 0, startX = 0, startY = 0, ctrlX = 0, ctrlY = 0;
        char cmd = 'M', last = 0;
        int n = d.length();
        while (true) {
            skip(d, pos);
            if (pos[0] >= n) break;
            char ch = d.charAt(pos[0]);
            if (Character.isLetter(ch)) {
                cmd = ch;
                pos[0]++;
                if (cmd == 'z' || cmd == 'Z') { p.close(); x = startX; y = startY; last = cmd; continue; }
            }
            boolean rel = Character.isLowerCase(cmd);
            float ox = rel ? x : 0, oy = rel ? y : 0;
            switch (Character.toUpperCase(cmd)) {
                case 'M': {
                    x = ox + num(d, pos); y = oy + num(d, pos);
                    p.moveTo(x, y); startX = x; startY = y;
                    cmd = rel ? 'l' : 'L'; // extra pairs after a move are lines
                    break;
                }
                case 'L': x = ox + num(d, pos); y = oy + num(d, pos); p.lineTo(x, y); break;
                case 'H': x = ox + num(d, pos); p.lineTo(x, y); break;
                case 'V': y = oy + num(d, pos); p.lineTo(x, y); break;
                case 'C': {
                    float x1 = ox + num(d, pos), y1 = oy + num(d, pos), x2 = ox + num(d, pos), y2 = oy + num(d, pos);
                    x = ox + num(d, pos); y = oy + num(d, pos);
                    p.cubicTo(x1, y1, x2, y2, x, y); ctrlX = x2; ctrlY = y2;
                    break;
                }
                case 'S': {
                    boolean smooth = last == 'C' || last == 'c' || last == 'S' || last == 's';
                    float x1 = smooth ? 2 * x - ctrlX : x, y1 = smooth ? 2 * y - ctrlY : y;
                    float x2 = ox + num(d, pos), y2 = oy + num(d, pos);
                    x = ox + num(d, pos); y = oy + num(d, pos);
                    p.cubicTo(x1, y1, x2, y2, x, y); ctrlX = x2; ctrlY = y2;
                    break;
                }
                case 'Q': {
                    float x1 = ox + num(d, pos), y1 = oy + num(d, pos);
                    x = ox + num(d, pos); y = oy + num(d, pos);
                    p.quadTo(x1, y1, x, y);
                    break;
                }
                default: throw new IllegalArgumentException("unsupported path command " + cmd);
            }
            last = cmd;
        }
        return p;
    }

    private static void skip(String d, int[] pos) {
        while (pos[0] < d.length()) {
            char c = d.charAt(pos[0]);
            if (c == ' ' || c == ',' || c == '\n' || c == '\t') pos[0]++;
            else break;
        }
    }

    /** Reads one number: "-.4", "2.9", "1e-3". A second "." or a sign starts the next number. */
    private static float num(String d, int[] pos) {
        skip(d, pos);
        int i = pos[0], n = d.length();
        if (i < n && (d.charAt(i) == '-' || d.charAt(i) == '+')) i++;
        boolean dot = false;
        while (i < n) {
            char c = d.charAt(i);
            if (c >= '0' && c <= '9') i++;
            else if (c == '.' && !dot) { dot = true; i++; }
            else if ((c == 'e' || c == 'E') && i + 1 < n) { i++; if (d.charAt(i) == '-' || d.charAt(i) == '+') i++; }
            else break;
        }
        float v = Float.parseFloat(d.substring(pos[0], i));
        pos[0] = i;
        return v;
    }
}
