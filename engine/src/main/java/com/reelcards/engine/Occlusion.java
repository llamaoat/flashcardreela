package com.reelcards.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Image occlusion masks for a card made from Anki's Image Occlusion note type (or the older add-on).
 * Coordinates are fractions of the picture (0..1), so they work at any display size.
 * Stored on a Card as one string: shapes joined by ";", each "kind|ord|oi|coords", e.g.
 * "r|1|1|0.08|0.41|0.22|0.09;p|3|1|0.7,0.7 0.82,0.66 0.86,0.8".
 */
public final class Occlusion {
    private Occlusion() {}

    public static final class Shape {
        public String kind;          // rect | ellipse | polygon | text
        public int ord;              // which card it belongs to (c1 = 1)
        public boolean oi;           // "occlude inactive": still covered on the other cards
        public double x, y, w, h;    // box, for rect, ellipse and text (text: x, y only)
        public double[] pts;         // polygon: x0, y0, x1, y1, …
        public String text = "";     // text annotations
    }

    public static String format(List<Shape> shapes) {
        StringBuilder b = new StringBuilder();
        for (Shape s : shapes) {
            if (b.length() > 0) b.append(';');
            b.append(s.kind.charAt(0)).append('|').append(s.ord).append('|').append(s.oi ? 1 : 0).append('|');
            if ("polygon".equals(s.kind)) {
                for (int i = 0; i + 1 < s.pts.length; i += 2) { if (i > 0) b.append(' '); b.append(n(s.pts[i])).append(',').append(n(s.pts[i + 1])); }
            } else if ("text".equals(s.kind)) {
                b.append(n(s.x)).append('|').append(n(s.y)).append('|').append(s.text.replace("|", "/").replace(";", ","));
            } else b.append(n(s.x)).append('|').append(n(s.y)).append('|').append(n(s.w)).append('|').append(n(s.h));
        }
        return b.toString();
    }

    public static List<Shape> parse(String text) {
        List<Shape> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        for (String part : text.split(";")) {
            String[] f = part.split("\\|", -1);
            if (f.length < 4) continue;
            Shape s = new Shape();
            switch (f[0]) {
                case "r": s.kind = "rect"; break;
                case "e": s.kind = "ellipse"; break;
                case "p": s.kind = "polygon"; break;
                case "t": s.kind = "text"; break;
                default: continue;
            }
            try {
                s.ord = Integer.parseInt(f[1]);
                s.oi = "1".equals(f[2]);
                if ("polygon".equals(s.kind)) {
                    String[] xy = f[3].trim().split("\\s+");
                    s.pts = new double[xy.length * 2];
                    for (int i = 0; i < xy.length; i++) { String[] a = xy[i].split(","); s.pts[2 * i] = Double.parseDouble(a[0]); s.pts[2 * i + 1] = Double.parseDouble(a[1]); }
                } else if ("text".equals(s.kind)) {
                    s.x = Double.parseDouble(f[3]); s.y = Double.parseDouble(f[4]); s.text = f.length > 5 ? f[5] : "";
                } else {
                    s.x = Double.parseDouble(f[3]); s.y = Double.parseDouble(f[4]); s.w = Double.parseDouble(f[5]); s.h = Double.parseDouble(f[6]);
                }
            } catch (RuntimeException e) { continue; }
            out.add(s);
        }
        return out;
    }

    static String n(double v) {
        String s = String.format(Locale.ROOT, "%.6f", v);
        s = s.replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }
}
