package com.reelcards.app;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers so every screen is built in code with one consistent look (no layout XML needed). */
final class Ui {
    static final int BG = 0xFF0F1014;
    static final int SURFACE = 0xFF17181E;
    static final int SURFACE2 = 0xFF1E1F27;
    static final int LINE = 0xFF2A2C37;
    static final int TEXT = 0xFFECEBE6;
    static final int MUTED = 0xFF9C9CA9;
    static final int ACCENT = 0xFFFFE14D;
    static final int ON_ACCENT = 0xFF141208;
    static final int GOOD = 0xFF3ECF8E;
    static final int BAD = 0xFFFF5A76;
    static final int WARN = 0xFFFFB547;

    static final Typeface DISPLAY = Typeface.create("sans-serif-black", Typeface.NORMAL);
    static final Typeface BODY = Typeface.create("sans-serif", Typeface.NORMAL);
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    static final Typeface MONO = Typeface.create("monospace", Typeface.NORMAL);

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

    static TextView title(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.UK), 30, TEXT, DISPLAY);
        t.setLetterSpacing(0.01f);
        t.setLineSpacing(0, 1.0f);
        return t;
    }

    static TextView body(Context c, String s) { return text(c, s, 15, TEXT, BODY); }

    static TextView muted(Context c, String s) { return text(c, s, 13, MUTED, BODY); }

    static TextView label(Context c, String s) {
        TextView t = text(c, s.toUpperCase(java.util.Locale.UK), 11, MUTED, MEDIUM);
        t.setLetterSpacing(0.08f);
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
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(primary ? DISPLAY : MEDIUM);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        b.setStateListAnimator(null);
        GradientDrawable shape = primary ? round(ACCENT, dp(c, 14)) : outline(SURFACE2, LINE, dp(c, 1), dp(c, 14));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), shape, null));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        return b;
    }

    static TextView linkButton(Context c, String s) {
        TextView t = text(c, s, 14, ACCENT, MEDIUM);
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
