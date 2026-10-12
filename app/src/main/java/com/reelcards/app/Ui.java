package com.reelcards.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small helpers so every screen is built in code with one consistent look (no layout XML needed). */
final class Ui {
    // The app's own screens: quiet and light by day, dark when the phone is (theme()). Ink buttons, a
    // black line over a grey line for headings, Inter throughout. Same tokens as .app in
    // web-demo/src/head.html and the extension popup. Cards keep their host-matched faces (Fonts).
    static int BG, SURFACE, SURFACE2, LINE, TEXT, MUTED, ACCENT, ON_ACCENT, GOOD, BAD, WARN, GOOD_BG, BAD_BG, WARN_BG;
    static boolean dark;
    static { colours(false); }

    static Typeface DISPLAY = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static Typeface BODY = Typeface.create("sans-serif", Typeface.NORMAL);
    static Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

    /** Picks light or dark from the phone's setting and loads the fonts. Call before building a screen. */
    static void theme(Context c) {
        colours((c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES);
        BODY = face(c, "Inter-Regular.ttf", BODY);
        MEDIUM = face(c, "Inter-Medium.ttf", MEDIUM);
        DISPLAY = face(c, "Inter-SemiBold.ttf", DISPLAY);
    }

    private static void colours(boolean night) {
        dark = night;
        if (night) {
            BG = 0xFF0E0E0D; SURFACE = 0xFF181816; SURFACE2 = 0xFF20201E; LINE = 0xFF2B2B28;
            TEXT = 0xFFF2F1EC; MUTED = 0xFF8E8D87; ACCENT = 0xFFF2F1EC; ON_ACCENT = 0xFF111111;
            GOOD = 0xFF3ECF8E; BAD = 0xFFFF6B80; WARN = 0xFFFFB547;
            GOOD_BG = 0xFF142019; BAD_BG = 0xFF2A1A1E; WARN_BG = 0xFF2A2410;
        } else {
            BG = 0xFFFAFAF8; SURFACE = 0xFFFFFFFF; SURFACE2 = 0xFFF2F2EF; LINE = 0xFFE6E5E0;
            TEXT = 0xFF111111; MUTED = 0xFF8A8A85; ACCENT = 0xFF111111; ON_ACCENT = 0xFFFFFFFF;
            GOOD = 0xFF1F9D63; BAD = 0xFFD93A50; WARN = 0xFFB86E00;
            GOOD_BG = 0xFFEAF6EF; BAD_BG = 0xFFFCEDEF; WARN_BG = 0xFFFFF4E0;
        }
    }

    private static Typeface face(Context c, String file, Typeface fallback) {
        try { return Typeface.createFromAsset(c.getAssets(), "fonts/" + file); }
        catch (RuntimeException e) { return fallback; }
    }

    interface PictureLoaded { void loaded(Bitmap b); }

    private static final ExecutorService PICTURES = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** Recently decoded pictures, so a card shown again (or redrawn) draws at once. */
    private static final LruCache<String, Bitmap> DECODED = new LruCache<String, Bitmap>((int) Math.min(32L << 20, Runtime.getRuntime().maxMemory() / 8)) {
        @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
    };

    /**
     * Decodes a stored picture off the main thread, at about the width it's shown, then calls back on
     * the main thread (with null if the file has gone). A picture decoded recently calls back at once.
     */
    static void picture(final String path, final int minW, final PictureLoaded done) {
        final String key = path + "@" + minW;
        Bitmap hit = path == null ? null : DECODED.get(key);
        if (hit != null) { done.loaded(hit); return; }
        PICTURES.execute(new Runnable() {
            @Override public void run() {
                Bitmap got = null;
                try { got = picture(path, minW); } catch (Throwable t) { /* still answer: the card shows without it */ }
                final Bitmap b = got;
                if (b != null) DECODED.put(key, b);
                MAIN.post(new Runnable() { @Override public void run() { done.loaded(b); } });
            }
        });
    }

    /**
     * Decodes a stored picture at about the size it's shown (at least minW pixels wide, and never
     * more than ~6 megapixels), or null if the file has gone or can't be read. Blocking.
     */
    static Bitmap picture(String path, int minW) {
        if (path == null || !new java.io.File(path).isFile()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int s = 1, w = Math.max(1, minW);
            while (o.outWidth / (s * 2) >= w || (long) (o.outWidth / s) * (o.outHeight / s) > 6_000_000L) s *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = s;
            return BitmapFactory.decodeFile(path, d);
        } catch (OutOfMemoryError e) {
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    static TextView title(Context c, String s) { return title(c, s, null); }

    /** A heading, with an optional second line in grey ("Revise without trying / between your reels"). */
    static TextView title(Context c, String s, String sub) { return title(c, s, sub, TEXT, MUTED); }

    static TextView title(Context c, String s, String sub, int main, int muted) {
        TextView t = text(c, s, 26, main, DISPLAY);
        if (sub != null) {
            android.text.SpannableStringBuilder b = new android.text.SpannableStringBuilder(s + "\n" + sub);
            b.setSpan(new android.text.style.ForegroundColorSpan(muted), s.length() + 1, b.length(), 0);
            t.setText(b);
        }
        t.setLetterSpacing(-0.025f);
        t.setLineSpacing(0, 1.12f);
        return t;
    }

    static TextView body(Context c, String s) { return text(c, s, 15, TEXT, BODY); }

    static TextView muted(Context c, String s) { return text(c, s, 13, MUTED, BODY); }

    static TextView label(Context c, String s) {
        TextView t = text(c, s, 13, MUTED, MEDIUM);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    static GradientDrawable outline(int fill, int stroke, int strokePx, float radiusPx) {
        GradientDrawable g = round(fill, radiusPx);
        g.setStroke(strokePx, stroke);
        return g;
    }

    static Button button(Context c, String s, boolean primary) {
        Button b = new Button(c);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setTypeface(MEDIUM);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        b.setStateListAnimator(null);
        GradientDrawable shape = primary ? round(ACCENT, dp(c, 26)) : outline(SURFACE, LINE, dp(c, 1), dp(c, 26));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(dark == primary ? 0x22000000 : 0x33FFFFFF), shape, null));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        return b;
    }

    static TextView linkButton(Context c, String s) {
        TextView t = text(c, s, 14, TEXT, MEDIUM);
        t.setPaintFlags(t.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        t.setPadding(0, dp(c, 12), 0, dp(c, 12));
        t.setMinHeight(dp(c, 44));
        t.setGravity(Gravity.CENTER_VERTICAL);
        return t;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    /** A rounded panel ("card" in the UI sense) with padding. */
    static LinearLayout panel(Context c) {
        LinearLayout l = column(c);
        l.setBackground(outline(SURFACE, LINE, dp(c, 1), dp(c, 16)));
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams topMargin(Context c, int dpTop) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.topMargin = dp(c, dpTop);
        return lp;
    }

    static LinearLayout.LayoutParams weight(float w) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w);
        return lp;
    }

    static void add(LinearLayout parent, View child, int topDp) {
        parent.addView(child, topMargin(parent.getContext(), topDp));
    }

    /** A selectable row with a title, a subtitle and a check state drawn by its border. */
    static final class Choice extends LinearLayout {
        final TextView titleView, subView, mark;
        boolean checked;

        Choice(Context c, String title, String sub) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int p = dp(c, 14);
            setPadding(p, p, p, p);
            setMinimumHeight(dp(c, 60));
            LinearLayout texts = column(c);
            titleView = text(c, title, 16, TEXT, MEDIUM);
            subView = text(c, sub, 13, MUTED, BODY);
            texts.addView(titleView);
            if (sub != null && !sub.isEmpty()) texts.addView(subView);
            addView(texts, weight(1));
            mark = text(c, "", 14, ON_ACCENT, DISPLAY);
            mark.setGravity(Gravity.CENTER);
            addView(mark, new LayoutParams(dp(c, 26), dp(c, 26)));
            setClickable(true);
            setFocusable(true);
            setChecked(false);
        }

        void setChecked(boolean on) {
            checked = on;
            Context c = getContext();
            setBackground(outline(on ? 0xFF24230F : SURFACE, on ? ACCENT : LINE, dp(c, on ? 2 : 1), dp(c, 14)));
            mark.setText(on ? "✓" : "");
            mark.setBackground(on ? round(ACCENT, dp(c, 13)) : outline(Color.TRANSPARENT, LINE, dp(c, 2), dp(c, 13)));
        }
    }
}
