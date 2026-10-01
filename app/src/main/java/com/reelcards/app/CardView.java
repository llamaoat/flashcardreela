package com.reelcards.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineBackgroundSpan;
import android.text.style.MetricAffectingSpan;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.reelcards.engine.CardScript;
import com.reelcards.engine.Outcome;
import com.reelcards.engine.Show;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * One flashcard drawn as a reel in the app it appears over. Same gestures as a real reel:
 *  - tap: reveal the answer · double-tap anywhere or the heart: "I knew it" (fast = Easy, slow = Good)
 *  - rail buttons: hint (speech bubble), save (bookmark), share · swipe up: next
 *
 * The design follows the host app: its safe zones (TikTok, Instagram Reels and YouTube Shorts keep
 * different edges for their own buttons and captions), its right-hand rail, its caption band and
 * its typeface. On top of that sit the engine's choices: a look (text treatment borrowed from creator
 * tools), a layout, a hook and a looping background, plus a small badge themed to what the student
 * watches (a football scorebug, a game HUD, …). Light looks float as a notes "sheet" over dark
 * footage so the card still reads as a reel, not a slide; after 10pm they switch to dark versions.
 *
 * Mirrors web-demo/src/render.js. The engine decides everything; this only draws a Show and reports
 * an Outcome (with timings, for response-time grading).
 */
final class CardView extends FrameLayout {

    interface Listener { void onFinished(CardView v, Outcome o); }

    // ------------------------------------------------------------------ looks

    /** Colours for one look. Light looks have a night version (no bright white at 1am). */
    static final class Palette {
        int bg, fg, acc, accFg, panel, bubble;
        boolean light;

        Palette(int bg, int fg, int acc, int accFg, int panel, int bubble, boolean light) {
            this.bg = bg; this.fg = fg; this.acc = acc; this.accFg = accFg; this.panel = panel; this.bubble = bubble; this.light = light;
        }
    }

    static Palette palette(String look, boolean night, float tintHue) {
        Palette p;
        switch (look) {
            case "subtitle": p = new Palette(0xFF101014, 0xFFFFFFFF, 0xFFFFE14D, 0xFF101014, 0xB8000000, 0xFF2A2A30, false); break;
            case "clean": p = night ? new Palette(0xFF141312, 0xFFEDE7DA, 0xFF8FB0FF, 0xFF0D0D10, 0xFF26231F, 0xFF2D2A25, false)
                                    : new Palette(0xFFF3F1EC, 0xFF16161A, 0xFF2F6BFF, 0xFFFFFFFF, 0xFFE2DFD6, 0xFFFFFFFF, true); break;
            case "neon": p = new Palette(0xFF0B0620, 0xFFF4F0FF, 0xFFFF4FD8, 0xFF0B0620, 0xD9231A45, 0xFF342866, false); break;
            case "paper": p = night ? new Palette(0xFF1F2A24, 0xFFEEF2E6, 0xFFFFD166, 0xFF1F2A24, 0xFF2B3A31, 0xFF2F3F35, false) // chalkboard
                                    : new Palette(0xFFFFF6D6, 0xFF2B2118, 0xFFE4572E, 0xFFFFFFFF, 0xFFF1E5B8, 0xFFFFFBEA, true); break;
            default: p = new Palette(0xFF0E0E10, 0xFFFFFFFF, 0xFFFFE14D, 0xFF0E0E10, 0x8C000000, 0xFF2A2A30, false); // caption
        }
        if (!Float.isNaN(tintHue) && !p.light) { // opt-in: match the colour of the reel just watched
            p.bg = hsl(tintHue, 0.42f, 0.12f, 1f);
            p.panel = hsl(tintHue, 0.40f, 0.08f, 0.72f);
            p.bubble = hsl(tintHue, 0.30f, 0.22f, 1f);
        }
        return p;
    }

    static int hsl(float h, float s, float l, float a) {
        float c = (1 - Math.abs(2 * l - 1)) * s, hp = ((h % 360) + 360) % 360 / 60f, x = c * (1 - Math.abs(hp % 2 - 1)), m = l - c / 2;
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; } else if (hp < 2) { r = x; g = c; } else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; } else if (hp < 5) { r = x; b = c; } else { r = c; b = x; }
        return Color.argb(Math.round(a * 255), Math.round((r + m) * 255), Math.round((g + m) * 255), Math.round((b + m) * 255));
    }

    /**
     * Safe-zone insets as fractions of the screen for each host app (from creators' 1080x1920
     * safe-zone guides): {top, bottom, left, right}.
     */
    static float[] safeZone(String host) {
        if ("instagram".equals(host)) return new float[]{210f / 1920, 310f / 1920, 40f / 1080, 84f / 1080};
        if ("youtube".equals(host)) return new float[]{120f / 1920, 300f / 1920, 48f / 1080, 96f / 1080};
        return new float[]{108f / 1920, 320f / 1920, 60f / 1080, 120f / 1080}; // TikTok and others
    }

    static String hostStyle(String host) { return "instagram".equals(host) || "youtube".equals(host) ? host : "tiktok"; }

    // ------------------------------------------------------------------ state

    final Show show;
    final Outcome outcome = new Outcome();
    private final Listener listener;
    private final String host, look, layout;
    private final CardScript s;
    private final boolean sheetLook, sheet, night;
    private float tintHue = Float.NaN;
    private boolean coach;
    private Palette P;
    private final long shownAtUptime = SystemClock.uptimeMillis();
    private long countdownStart = SystemClock.uptimeMillis();
    private ObjectAnimator typingPulse;
    private final GestureDetector gestures;
    private final int slop;
    private VelocityTracker velocity;
    private float downY;
    private boolean dragging, finished, built, touchOnControl;

    // what the student has done so far (the body is redrawn from this)
    private boolean hinted, justRevealed, justHinted, justPicked;
    private int beat, count = 3;

    // geometry, in px, set once the card knows its size
    private int W, H, st, sb, sl, sr;
    private float k = 1f; // scale for small previews (the welcome screen)

    private FrameLayout region;
    private LinearLayout body;
    private LinearLayout vibeBox;
    private TextView badge;
    private Icons.IconView likeIcon, saveIcon;
    private TextView likeLabel, saveLabel;
    private final List<View> controls = new ArrayList<>();      // rail buttons
    private final List<View> bodyControls = new ArrayList<>();  // reveal button, poll options, typing bubble (redrawn)

    CardView(Context c, Show show, Listener listener) {
        super(c);
        this.show = show;
        this.listener = listener;
        this.s = show.script;
        this.host = hostStyle(show.host);
        this.look = show.format.look;
        this.layout = effectiveLayout(show);
        this.sheetLook = "clean".equals(look) || "paper".equals(look);
        this.sheet = sheetLook && !"quiz".equals(layout);
        this.night = sheetLook && Store.engine(c).settings.nightAt(Calendar.getInstance().get(Calendar.HOUR_OF_DAY));
        this.slop = ViewConfiguration.get(c).getScaledTouchSlop();
        setClickable(true);
        setClipChildren(true);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (!show.isQuiz()) reveal(); return true; }
            @Override public boolean onDoubleTapEvent(MotionEvent e) {
                // like on the second finger-up, and only if that touch didn't turn into a swipe
                if (e.getActionMasked() == MotionEvent.ACTION_UP && !dragging) like(e.getX(), e.getY());
                return true;
            }
        });
    }

    /** Layouts fall back to big text when the script has nothing for them (e.g. no options for a quiz). */
    private static String effectiveLayout(Show sh) {
        String l = sh.format.layout;
        CardScript s = sh.script;
        if ("quiz".equals(l) && s.correct < 0) return "bigtext";
        if ("captions".equals(l) && s.beats.isEmpty()) return "bigtext";
        if ("comment".equals(l) && s.commentText == null) return "bigtext";
        if ("chat".equals(l) && s.chatName == null) return "bigtext";
        return l;
    }

    /** Opt-in "match my feed's colours": hue (0..360) of the reel just watched; NaN for none. Call before it's shown. */
    CardView withTint(float hue) { tintHue = hue; return this; }

    /** Show the one-line gesture tip (first cards only). Call before it's shown. */
    CardView withCoach(boolean on) { coach = on; return this; }

    // ------------------------------------------------------------------ units

    private float density() { return getResources().getDisplayMetrics().density; }

    /** CSS px in the web demo = dp here, scaled down for small previews. */
    private int u(float v) { return Math.round(v * density() * k); }

    private float tpx(float sp) {
        float fs = Math.min(1.3f, getResources().getConfiguration().fontScale);
        return sp * density() * fs * k;
    }

    private long elapsed() { return Math.max(1, SystemClock.uptimeMillis() - shownAtUptime); }

    private TextView tv(CharSequence text, float sp, int color, Typeface tf) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, tpx(sp));
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0, 1.22f);
        return t;
    }

    private Typeface black() { return Fonts.hostBlack(getContext(), host); }
    private Typeface bold() { return Fonts.hostBold(getContext(), host); }
    private Typeface regular() { return Fonts.hostRegular(getContext(), host); }

    private static GradientDrawable round(int color, float r) { return Ui.round(color, r); }

    private LinearLayout.LayoutParams wrapLp(int topGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topGap;
        return lp;
    }

    private LinearLayout.LayoutParams fullLp(int topGap) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = topGap;
        return lp;
    }

    // ------------------------------------------------------------------ build (once the size is known)

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (built || w == 0 || h == 0) return;
        built = true;
        W = w; H = h;
        k = Math.max(0.72f, Math.min(1f, h / (740f * density())));
        float[] z = safeZone(host);
        st = Math.round(z[0] * H); sb = Math.round(z[1] * H); sl = Math.round(z[2] * W); sr = Math.round(z[3] * W);
        P = palette(look, night, tintHue);
        post(new Runnable() { @Override public void run() { build(); } }); // add children outside the layout pass
    }

    private void build() {
        if (finished) return;
        countdownStart = SystemClock.uptimeMillis();
        Context c = getContext();
        setBackgroundColor(sheetLook ? (Float.isNaN(tintHue) ? 0xFF0F0F12 : hsl(tintHue, 0.42f, 0.12f, 1f)) : P.bg);
        String bg = show.format.bg;

        if ("split".equals(layout)) { // split-screen always has footage on top
            FrameLayout top = new FrameLayout(c);
            top.addView(new ReelBackground(c, "plain".equals(bg) ? "gameplay" : bg), new LayoutParams(-1, -1));
            View edge = new View(c);
            edge.setBackgroundColor(0xFF000000);
            top.addView(edge, new LayoutParams(-1, u(3), Gravity.BOTTOM));
            addView(top, new LayoutParams(-1, Math.round(H * 0.42f)));
        } else if (!"plain".equals(bg)) {
            ReelBackground footage = new ReelBackground(c, bg);
            footage.setAlpha(sheetLook ? 0.6f : "subtitle".equals(look) ? 0.75f : 0.42f);
            addView(footage, new LayoutParams(-1, -1));
        }
        addScrim();
        buildVibe();

        region = new FrameLayout(c);
        region.setClipChildren(false);
        LayoutParams rl = new LayoutParams(-1, -1);
        rl.leftMargin = sl + u(12);
        rl.rightMargin = sr + u(30);
        rl.topMargin = "split".equals(layout) ? Math.round(H * 0.44f) : st + u(sheet && ("bigtext".equals(layout) || "captions".equals(layout)) ? 70 : 44);
        rl.bottomMargin = sb + u("split".equals(layout) ? 96 : 104);
        addView(region, rl);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        boolean centred = !sheet && ("bigtext".equals(layout) || "captions".equals(layout));
        LayoutParams bl = new LayoutParams(-1, -2, centred ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        if (centred) bl.bottomMargin = Math.round(H * 0.06f);
        region.addView(body, bl);
        if (sheet && !"chat".equals(layout)) {
            body.setBackground(new SheetDrawable("paper".equals(look), night, P.bg, u(18), u(30)));
            int left = "paper".equals(look) && !night ? u(42) : u(16);
            body.setPadding(left, u(18), u(16), u(16));
            body.setElevation(u(14));
            body.setRotation(-0.6f);
        }

        buildRail();
        buildBand();

        badge = tv("", 12, P.accFg, black());
        badge.setBackground(round(P.acc, u(20)));
        badge.setPadding(u(11), u(5), u(11), u(5));
        badge.setVisibility(GONE);
        LayoutParams bp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        bp.topMargin = st + u(4); bp.rightMargin = sr + u(4);
        addView(badge, bp);

        buildProgress();
        renderBody();
        if ("captions".equals(layout)) scheduleBeat();
        startCountdown();
        if (coach) showCoach();
    }

    private void addScrim() {
        View bottom = new View(getContext());
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0x9E000000, 0x00000000}));
        addView(bottom, new LayoutParams(-1, Math.round(H * 0.34f), Gravity.BOTTOM));
        View top = new View(getContext());
        top.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x59000000, 0x00000000}));
        addView(top, new LayoutParams(-1, Math.round(H * 0.18f), Gravity.TOP));
    }

    private void buildProgress() {
        View track = new View(getContext());
        track.setBackgroundColor(0x47FFFFFF);
        addView(track, new LayoutParams(-1, u(3), Gravity.BOTTOM));
        View bar = new View(getContext());
        bar.setBackgroundColor("youtube".equals(host) ? 0xFFFF2D55 : 0xFFFFFFFF);
        bar.setPivotX(0);
        bar.setScaleX(0);
        addView(bar, new LayoutParams(-1, u(3), Gravity.BOTTOM));
        ObjectAnimator a = ObjectAnimator.ofFloat(bar, "scaleX", 0f, 1f);
        a.setDuration(12000);
        a.setInterpolator(new LinearInterpolator());
        a.start();
    }

    // ------------------------------------------------------------------ body (redrawn on every change)

    private void renderBody() {
        if (body == null) return;
        body.removeAllViews();
        bodyControls.clear();
        if (typingPulse != null) { typingPulse.cancel(); typingPulse = null; }
        switch (layout) {
            case "chat": renderChat(); break;
            case "comment": renderComment(); break;
            case "quiz": renderQuiz(); break;
            case "captions": renderCaptions(); break;
            default: {
                addToBody(hook(s.hookLine));
                addToBody(question(s.question, "split".equals(layout) ? 0.8f : 1f));
                addRevealOrAnswer();
            }
        }
        justRevealed = justHinted = justPicked = false;
    }

    private void addToBody(View v) { addToBody(v, false); }

    private void addToBody(View v, boolean fullWidth) {
        int gap = body.getChildCount() == 0 ? 0 : u(14);
        body.addView(v, fullWidth ? fullLp(gap) : wrapLp(gap));
    }

    private TextView hook(String text) {
        boolean sub = "subtitle".equals(look);
        TextView t = tv(text, 16.5f, sub ? 0xFFFFFFFF : P.fg, bold());
        t.setBackground(round(sub ? 0xFF111111 : P.panel, u(10)));
        t.setPadding(u(13), u(7), u(13), u(7));
        return t;
    }

    /** The key word in a question: the longest word of 5+ letters (what auto-captions highlight). */
    static int keyWord(String[] words) {
        int best = -1, len = 0;
        for (int i = 0; i < words.length; i++) {
            int n = 0;
            for (int j = 0; j < words[i].length(); j++) if (Character.isLetterOrDigit(words[i].charAt(j))) n++;
            if (n > len && n >= 5) { len = n; best = i; }
        }
        return best;
    }

    private boolean upper() { return "caption".equals(look) || "neon".equals(look); }

    private Typeface questionFace() {
        switch (look) {
            case "clean": return Fonts.fraunces(getContext());
            case "neon": return Fonts.anton(getContext());
            case "paper": return Fonts.caveat(getContext());
            case "subtitle": return bold();
            default: return black();
        }
    }

    private float baseSize() {
        switch (look) {
            case "subtitle": return 27;
            case "clean": return 31;
            case "neon": return 36;
            case "paper": return 40;
            default: return 34;
        }
    }

    /** Width available for text inside the body (px). */
    private int column() {
        int w = W - (sl + u(12)) - (sr + u(30));
        if (sheet && !"chat".equals(layout)) w -= ("paper".equals(look) && !night ? u(42) : u(16)) + u(16);
        return Math.max(u(120), w);
    }

    /** Largest size (px) at which the longest word still fits the column, capped at the look's size. */
    private float fitSize(String text, Typeface tf, float sizePx, int col, int sidePad, boolean up) {
        String[] words = text.split("\\s+");
        String longest = "";
        for (String w : words) if (w.length() > longest.length()) longest = w;
        if (up) longest = longest.toUpperCase(Locale.UK);
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        if ("neon".equals(look)) p.setLetterSpacing(0.012f);
        float size = sizePx;
        p.setTextSize(size);
        while (size > tpx(14) && p.measureText(longest) + sidePad > col) { size -= density(); p.setTextSize(size); }
        return size;
    }

    /** The question, set in the look's typeface with its key word picked out. Words never break. */
    private View question(String q, float scale) {
        Context c = getContext();
        int len = q.length();
        float fit = len > 110 ? 0.7f : len > 80 ? 0.8f : len > 55 ? 0.9f : 1f;
        Typeface tf = questionFace();
        boolean sub = "subtitle".equals(look);
        float size = fitSize(q, tf, tpx(baseSize() * scale * fit), column(), sub ? u(22) : u(8), upper());
        CharSequence text = emphasised(q, upper());

        TextView t = tv(text, 10, sub ? 0xFF111111 : P.fg, tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setLineSpacing(0, sub ? 1.42f : "neon".equals(look) || "clean".equals(look) ? 1.08f : 1.02f);
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        if ("neon".equals(look)) { t.setShadowLayer(u(18), 0, 0, P.acc); t.setLetterSpacing(0.012f); }
        if (sub) boxRoom(t);
        if (!"caption".equals(look)) return t;

        // caption look: heavy white captions with a black outline (drawn as a stroked copy behind)
        FrameLayout stack = new FrameLayout(c);
        TextView outline = tv(text.toString(), 10, 0xFF000000, tf); // same words and spacing as the white copy
        outline.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        outline.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        outline.setLineSpacing(0, 1.02f);
        outline.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        outline.getPaint().setStyle(Paint.Style.STROKE);
        outline.getPaint().setStrokeJoin(Paint.Join.ROUND);
        outline.getPaint().setStrokeWidth(u(4));
        outline.setShadowLayer(u(10), 0, u(4), 0x73000000);
        outline.setPadding(u(3), u(3), u(3), u(3)); // room for the stroke; both copies padded alike so they line up
        t.setPadding(u(3), u(3), u(3), u(3));
        stack.addView(outline, new LayoutParams(-1, -2));
        stack.addView(t, new LayoutParams(-1, -2));
        return stack;
    }

    /**
     * Subtitle look: the per-line boxes are drawn a little outside the text. TextView clips its
     * drawing to the padding edge, except that a shadow widens the clip by its radius, so a
     * fully transparent shadow gives the boxes room without changing how anything looks.
     */
    private void boxRoom(TextView t) {
        t.setPadding(u(10), u(3), u(10), u(5));
        t.setShadowLayer(u(12), 0, 0, 0x00000000);
    }

    /** Question text with the key word styled for the look. */
    private CharSequence emphasised(String q, boolean up) {
        String[] words = q.split("\\s+");
        int kw = keyWord(words);
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (i > 0) b.append(' ');
            String w = up ? words[i].toUpperCase(Locale.UK) : words[i];
            int start = b.length();
            b.append(w);
            if (i == kw) styleKeyWord(b, start, b.length());
        }
        if ("subtitle".equals(look)) b.setSpan(new LineBoxSpan(0xFFFFFFFF, u(8), u(9), false), 0, b.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return b;
    }

    private void styleKeyWord(SpannableStringBuilder b, int start, int end) {
        switch (look) {
            case "subtitle":
                b.setSpan(new BackgroundColorSpan(P.acc), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case "clean":
                b.setSpan(new FaceSpan(Fonts.frauncesItalic(getContext())), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                b.setSpan(new ForegroundColorSpan(P.acc), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            case "paper":
                b.setSpan(new MarkerSpan((P.acc & 0x00FFFFFF) | 0x8C000000), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            default: // caption, neon
                b.setSpan(new ForegroundColorSpan(P.acc), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void addRevealOrAnswer() {
        if (!outcome.revealed) {
            if (hinted) addToBody(hintChip());
            addToBody(revealButton());
        } else {
            View a = answerBox();
            addToBody(a, true);
            if (justRevealed) drop(a);
        }
    }

    private View hintChip() {
        SpannableStringBuilder b = new SpannableStringBuilder("Hint  ");
        b.setSpan(new ForegroundColorSpan(0xFFE4572E), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(s.hint == null ? "" : s.hint);
        TextView t = tv(b, 14, 0xFF111111, bold());
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFFFFFFFF);
        float r = u(12), small = u(4);
        g.setCornerRadii(new float[]{r, r, r, r, r, r, small, small});
        t.setBackground(g);
        t.setPadding(u(11), u(7), u(11), u(7));
        t.setElevation(u(4));
        if (justHinted) drop(t);
        return t;
    }

    private RingView ring;

    private View revealButton() {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(u(56));
        ring = new RingView(c);
        row.addView(ring, new LinearLayout.LayoutParams(u(52), u(52)));
        TextView pt = tv(s.revealPrompt, 14.5f, P.fg, bold());
        if (!sheet) pt.setShadowLayer(u(3), 0, u(1), 0x80000000);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
        pl.leftMargin = u(10);
        row.addView(pt, pl);
        row.setContentDescription("Reveal the answer");
        row.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { reveal(); } });
        bodyControls.add(row);
        return row;
    }

    private View answerBox() {
        LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(P.acc, u(16)));
        box.setPadding(u(15), u(13), u(15), u(14));
        box.setElevation(u(6));
        TextView a1 = tv(s.answer, 20, P.accFg, black());
        a1.setLineSpacing(0, 1.15f);
        box.addView(a1);
        if (s.explanation != null && !s.explanation.isEmpty()) {
            TextView a2 = tv(s.explanation, 14.5f, P.accFg, regular());
            a2.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams lp = fullLp(u(4));
            box.addView(a2, lp);
        }
        return box;
    }

    // --- captions: the question arrives in short beats, key word highlighted, then the reveal

    private void renderCaptions() {
        addToBody(hook(s.hookLine));
        if (beat < s.beats.size() && !outcome.revealed && !hinted) {
            String raw = s.beats.get(beat);
            boolean up = !"paper".equals(look) && !"clean".equals(look);
            String[] words = raw.split(" ");
            int hi = beat < s.beatHighlight.size() ? s.beatHighlight.get(beat) : -1;
            SpannableStringBuilder b = new SpannableStringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) b.append(' ');
                int start = b.length();
                b.append(up ? words[i].toUpperCase(Locale.UK) : words[i]);
                if (i == hi) styleKeyWord(b, start, b.length());
            }
            if ("subtitle".equals(look)) b.setSpan(new LineBoxSpan(0xFFFFFFFF, u(9), u(10), true), 0, b.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            Typeface tf = "paper".equals(look) ? Fonts.caveat(getContext()) : "clean".equals(look) ? Fonts.fraunces(getContext()) : black();
            float size = fitSize(raw, tf, tpx("clean".equals(look) ? 34 : 40), column(), u(22), up);
            TextView t = tv(b, 10, "subtitle".equals(look) ? 0xFF111111 : P.fg, tf);
            t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
            t.setLineSpacing(0, "subtitle".equals(look) ? 1.4f : 1.02f);
            t.setGravity(Gravity.CENTER);
            if ("caption".equals(look) || "neon".equals(look)) t.setShadowLayer(u(4), u(2), u(2), 0xE6000000);
            if ("subtitle".equals(look)) boxRoom(t);
            LinearLayout.LayoutParams lp = fullLp(Math.round(H * 0.06f));
            body.addView(t, lp);
            t.setScaleX(0.86f); t.setScaleY(0.86f); t.setAlpha(0.2f);
            t.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).start();
            LinearLayout dots = new LinearLayout(getContext());
            dots.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < s.beats.size(); i++) {
                View d = new View(getContext());
                d.setBackground(round(i <= beat ? 0xFFFFFFFF : 0x59FFFFFF, u(2)));
                LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(u(18), u(3));
                if (i > 0) dl.leftMargin = u(4);
                dots.addView(d, dl);
            }
            LinearLayout.LayoutParams dl = wrapLp(u(14));
            dl.gravity = Gravity.CENTER_HORIZONTAL;
            body.addView(dots, dl);
            return;
        }
        addToBody(question(s.question, 0.8f));
        addRevealOrAnswer();
    }

    private void scheduleBeat() {
        if (beat >= s.beats.size()) return;
        long ms = 350L + 330L * s.beats.get(beat).split(" ").length; // ~3 words a second, like auto-captions
        postDelayed(new Runnable() {
            @Override public void run() {
                if (finished || outcome.revealed || hinted) return;
                beat++;
                renderBody();
                scheduleBeat();
            }
        }, ms);
    }

    // --- chat: a friend asks in a revision group chat, the reply is the answer

    private void renderChat() {
        Context c = getContext();
        LinearLayout chat = new LinearLayout(c);
        chat.setOrientation(LinearLayout.VERTICAL);
        chat.setBackground(round(P.panel, u(22)));
        chat.setPadding(u(12), u(12), u(12), u(12));
        chat.setElevation(u(8));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(0, 0, 0, u(8));
        TextView av = tv(s.chatName.substring(0, 1), 14, 0xFFFFFFFF, bold());
        av.setGravity(Gravity.CENTER);
        GradientDrawable avg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFFFF8A3D, 0xFFFF3B5C});
        avg.setShape(GradientDrawable.OVAL);
        av.setBackground(avg);
        head.addView(av, new LinearLayout.LayoutParams(u(30), u(30)));
        LinearLayout names = new LinearLayout(c);
        names.setOrientation(LinearLayout.VERTICAL);
        names.addView(tv(s.chatName, 14, P.fg, bold()));
        TextView sub = tv("revision chat · now", 11, P.fg, regular());
        sub.setAlpha(0.6f);
        names.addView(sub);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2);
        nl.leftMargin = u(10);
        head.addView(names, nl);
        chat.addView(head);
        View rule = new View(c);
        rule.setBackgroundColor(0x33808080);
        chat.addView(rule, new LinearLayout.LayoutParams(-1, Math.max(1, u(1))));

        int maxW = Math.round((column() - u(24)) * 0.84f);
        for (String line : s.chatThem) addBubble(chat, bubble(line, false, maxW), false, false);
        if (outcome.revealed) {
            for (String line : s.chatMe) addBubble(chat, bubble(line, true, maxW), true, justRevealed);
            if (s.chatAfter != null) addBubble(chat, bubble(s.chatAfter, false, maxW), false, justRevealed);
        } else {
            if (hinted) { TextView h = bubble((s.hint == null ? "" : s.hint) + "…", true, maxW); h.setAlpha(0.8f); addBubble(chat, h, true, justHinted); }
            TextView typing = tv("•  •  •", 18, P.accFg, black());
            GradientDrawable g = new GradientDrawable();
            g.setColor(P.acc);
            float r = u(18), small = u(5);
            g.setCornerRadii(new float[]{r, r, r, r, small, small, r, r});
            typing.setBackground(g);
            typing.setGravity(Gravity.CENTER);
            typing.setMinHeight(u(44));
            typing.setPadding(u(18), u(6), u(18), u(6));
            typing.setContentDescription("Show the reply");
            typing.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { reveal(); } });
            typingPulse = ObjectAnimator.ofFloat(typing, "alpha", 1f, 0.65f);
            typingPulse.setDuration(500); typingPulse.setRepeatCount(ValueAnimator.INFINITE); typingPulse.setRepeatMode(ValueAnimator.REVERSE);
            typingPulse.start();
            bodyControls.add(typing);
            addBubble(chat, typing, true, false);
        }
        addToBody(chat, true);
    }

    private TextView bubble(String text, boolean mine, int maxW) {
        TextView b = tv(text, 14.5f, mine ? P.accFg : P.fg, regular());
        b.setLineSpacing(0, 1.3f);
        GradientDrawable g = new GradientDrawable();
        g.setColor(mine ? P.acc : P.bubble);
        float r = u(18), small = u(5);
        g.setCornerRadii(mine ? new float[]{r, r, r, r, small, small, r, r} : new float[]{r, r, r, r, r, r, small, small});
        b.setBackground(g);
        b.setPadding(u(12), u(8), u(12), u(8));
        b.setMaxWidth(maxW);
        return b;
    }

    private void addBubble(LinearLayout chat, View b, boolean mine, boolean animate) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = u(6);
        chat.addView(b, lp);
        if (animate) drop(b);
    }

    // --- comment: "replying to @user's comment", the card answers a viewer's question

    private void renderComment() {
        Context c = getContext();
        LinearLayout sticker = new LinearLayout(c);
        sticker.setOrientation(LinearLayout.HORIZONTAL);
        sticker.setBackground(round(0xFFFFFFFF, u(14)));
        sticker.setPadding(u(12), u(10), u(14), u(12));
        sticker.setElevation(u(6));
        sticker.setRotation(-1.5f);
        View av = new View(c);
        GradientDrawable avg = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6FD3FF, 0xFF2F6BFF});
        avg.setShape(GradientDrawable.OVAL);
        av.setBackground(avg);
        sticker.addView(av, new LinearLayout.LayoutParams(u(26), u(26)));
        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(tv("Reply to " + s.commentUser + "’s comment", 11.5f, 0xFF666666, bold()));
        TextView said = tv(s.commentText, 15, 0xFF111111, bold());
        said.setLineSpacing(0, 1.25f);
        texts.addView(said, fullLp(u(2)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = u(9);
        sticker.addView(texts, tl);
        LinearLayout.LayoutParams sl2 = wrapLp(0);
        sl2.rightMargin = Math.round(column() * 0.08f);
        body.addView(sticker, sl2);
        addToBody(question(s.question, 0.82f));
        addRevealOrAnswer();
    }

    // --- quiz: a two-option poll sticker

    private final List<TextView> optionRows = new ArrayList<>();

    private void renderQuiz() {
        Context c = getContext();
        addToBody(hook(s.hookLine));
        LinearLayout poll = new LinearLayout(c);
        poll.setOrientation(LinearLayout.VERTICAL);
        poll.setBackground(round(0xFFFFFFFF, u(18)));
        poll.setPadding(u(12), u(12), u(12), u(12));
        poll.setElevation(u(10));
        TextView pq = tv(s.question, 16, 0xFF111111, black());
        pq.setLineSpacing(0, 1.25f);
        pq.setPadding(u(4), u(2), u(4), u(4));
        poll.addView(pq);
        if (hinted && outcome.quizPick < 0) {
            View note = pollNote("Hint", s.hint == null ? "" : s.hint);
            poll.addView(note, fullLp(u(8)));
            if (justHinted) drop(note);
        }
        optionRows.clear();
        for (int i = 0; i < s.options.size(); i++) {
            final int idx = i;
            LinearLayout opt = new LinearLayout(c);
            opt.setOrientation(LinearLayout.HORIZONTAL);
            opt.setGravity(Gravity.CENTER_VERTICAL);
            opt.setMinimumHeight(u(50));
            opt.setPadding(u(12), u(8), u(12), u(8));
            int border = 0xFFE3E3E8, fill = 0xFFF4F4F6, letterBg = 0xFF111111;
            float alpha = 1f;
            if (outcome.quizPick >= 0) {
                if (i == s.correct) { border = 0xFF1FA463; fill = 0xFFE3F7EC; letterBg = 0xFF1FA463; }
                else if (i == outcome.quizPick) { border = 0xFFE23B55; fill = 0xFFFDE7EB; letterBg = 0xFFE23B55; }
                else alpha = 0.45f;
            }
            opt.setBackground(Ui.outline(fill, border, u(2), u(12)));
            opt.setAlpha(alpha);
            TextView letter = tv(i == 0 ? "A" : i == 1 ? "B" : "C", 12.5f, 0xFFFFFFFF, black());
            letter.setGravity(Gravity.CENTER);
            GradientDrawable lg = new GradientDrawable();
            lg.setShape(GradientDrawable.OVAL);
            lg.setColor(letterBg);
            letter.setBackground(lg);
            opt.addView(letter, new LinearLayout.LayoutParams(u(26), u(26)));
            TextView label = tv(s.options.get(i), 15.5f, 0xFF111111, bold());
            LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1);
            ll.leftMargin = u(10);
            opt.addView(label, ll);
            opt.setContentDescription("Option " + (char) ('A' + i) + ": " + s.options.get(i));
            opt.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { pick(idx); } });
            bodyControls.add(opt);
            optionRows.add(label);
            poll.addView(opt, fullLp(u(8)));
        }
        if (outcome.quizPick >= 0) {
            boolean right = outcome.quizPick == s.correct;
            View note = pollNote(right ? "You got it." : "Classic trap. It's " + s.answer + ".", s.explanation == null ? "" : s.explanation);
            poll.addView(note, fullLp(u(8)));
            if (justPicked) drop(note);
        }
        addToBody(poll, true);
    }

    private View pollNote(String lead, String rest) {
        SpannableStringBuilder b = new SpannableStringBuilder(lead);
        b.setSpan(new FaceSpan(black()), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.setSpan(new ForegroundColorSpan(lead.equals("Hint") ? 0xFFE4572E : 0xFF111111), 0, lead.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(' ').append(rest);
        TextView t = tv(b, 13.5f, 0xFF333333, regular());
        t.setLineSpacing(0, 1.35f);
        t.setPadding(u(4), 0, u(4), 0);
        return t;
    }

    // ------------------------------------------------------------------ vibe badge (what they watch)

    private void buildVibe() {
        if (s.vibe == null) return;
        vibeBox = new LinearLayout(getContext());
        vibeBox.setOrientation(LinearLayout.HORIZONTAL);
        vibeBox.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        lp.leftMargin = sl + u(12);
        lp.topMargin = st + u(6);
        addView(vibeBox, lp);
        updateVibe();
    }

    private boolean good() { return outcome.liked || (outcome.quizPick >= 0 && outcome.quizPick == s.correct); }

    private boolean bad() { return outcome.quizPick >= 0 && outcome.quizPick != s.correct; }

    private TextView chip(String text, int bg, int fg, Typeface tf, float radius) {
        TextView t = tv(text, 12, fg, tf);
        t.setLetterSpacing(0.025f);
        t.setBackground(round(bg, radius));
        t.setPadding(u(8), u(4), u(8), u(4));
        return t;
    }

    private void updateVibe() {
        if (vibeBox == null) return;
        vibeBox.removeAllViews();
        Context c = getContext();
        String v = s.vibe;
        if ("football".equals(v)) { // broadcast scorebug
            LinearLayout score = new LinearLayout(c);
            score.setOrientation(LinearLayout.HORIZONTAL);
            score.setBackground(round(0xFF101418, u(6)));
            score.setClipToOutline(true);
            score.addView(chip("YOU", 0xFF1FA463, 0xFFFFFFFF, bold(), 0));
            score.addView(chip((good() ? 1 : 0) + "–" + (bad() ? 1 : 0), 0xFFFFFFFF, 0xFF101418, black(), 0));
            score.addView(chip("EXAM", 0xFF101418, 0xFFFFFFFF, bold(), 0));
            score.setElevation(u(4));
            vibeBox.addView(score);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = u(6);
            vibeBox.addView(chip("90+3′", 0xFF101418, 0xFFFFFFFF, bold(), u(99)), lp);
        } else if ("gaming".equals(v)) { // game HUD
            LinearLayout hud = new LinearLayout(c);
            hud.setOrientation(LinearLayout.HORIZONTAL);
            hud.setGravity(Gravity.CENTER_VERTICAL);
            hud.setBackground(round(0x8C000000, u(6)));
            hud.setPadding(u(8), u(4), u(8), u(4));
            hud.addView(tv("LVL 7", 12, 0xFF9DFF6F, Ui.MONO));
            FrameLayout bar = new FrameLayout(c);
            bar.setBackground(round(0xFF2B3A2B, u(3)));
            View fill = new View(c);
            fill.setBackground(round(0xFF9DFF6F, u(3)));
            bar.addView(fill, new LayoutParams(Math.round(u(60) * (good() ? 0.78f : 0.35f)), -1));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(u(60), u(6));
            bl.leftMargin = u(6);
            hud.addView(bar, bl);
            vibeBox.addView(hud);
        } else {
            String text;
            switch (v) {
                case "music": text = "▮▮▮ revision mix"; break;
                case "fitness": text = good() ? "PR ✓" : "SET 1 · REP 1"; break;
                case "beauty": text = "✦ GRWM · revision edition"; break;
                case "food": text = "let it cook · 0:03"; break;
                case "comedy": text = "no laughing"; break;
                default: return;
            }
            vibeBox.addView(chip(text, 0xE6FFFFFF, 0xFF111111, bold(), u(99)));
        }
    }

    // ------------------------------------------------------------------ rail (right) and caption band (bottom)

    private String deckInitial() {
        String d = show.card.deck == null ? "R" : show.card.deck.replaceFirst("(?i)^(GCSE|A-level)\\s+", "");
        return d.isEmpty() ? "R" : d.substring(0, 1).toUpperCase(Locale.UK);
    }

    private LinearLayout railButton(String icon, String label, String description) {
        Context c = getContext();
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setMinimumWidth(u(50));
        b.setMinimumHeight(u(50));
        Icons.IconView iv = new Icons.IconView(c, icon);
        iv.setShadow(true);
        b.addView(iv, new LinearLayout.LayoutParams(u(32), u(32)));
        TextView t = tv(label, 11, 0xFFFFFFFF, bold());
        t.setShadowLayer(u(2), 0, u(1), 0x99000000);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.topMargin = u(2);
        b.addView(t, tl);
        b.setContentDescription(description);
        b.setTag(new Object[]{iv, t});
        controls.add(b);
        return b;
    }

    private void buildRail() {
        Context c = getContext();
        LinearLayout rail = new LinearLayout(c);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setGravity(Gravity.CENTER_HORIZONTAL);
        rail.setClipChildren(false);
        if ("tiktok".equals(host)) { // creator avatar with the follow "+"
            FrameLayout av = new FrameLayout(c);
            av.setClipChildren(false);
            TextView dot = tv(deckInitial(), 18, P.accFg, black());
            dot.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(P.acc);
            g.setStroke(u(2), 0xFFFFFFFF);
            dot.setBackground(g);
            av.addView(dot, new LayoutParams(u(44), u(44)));
            TextView plus = tv("+", 14, 0xFFFFFFFF, black());
            plus.setGravity(Gravity.CENTER);
            plus.setIncludeFontPadding(false);
            GradientDrawable pg = new GradientDrawable();
            pg.setShape(GradientDrawable.OVAL);
            pg.setColor(0xFFFF3B5C);
            plus.setBackground(pg);
            LayoutParams pl = new LayoutParams(u(18), u(18), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            pl.bottomMargin = -u(9);
            av.addView(plus, pl);
            LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(u(44), u(44));
            al.bottomMargin = u(20);
            rail.addView(av, al);
        }
        LinearLayout like = railButton("youtube".equals(host) ? Icons.THUMB : Icons.HEART, "Knew it", "Like: I knew it");
        likeIcon = (Icons.IconView) ((Object[]) like.getTag())[0];
        likeLabel = (TextView) ((Object[]) like.getTag())[1];
        likeIcon.setColors(0xFFFFFFFF, 0xFFFF3B5C);
        like.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                View p = (View) v.getParent();
                like(p.getX() + v.getX() + v.getWidth() / 2f, p.getY() + v.getY() + v.getHeight() / 2f);
            }
        });
        LinearLayout hint = railButton(Icons.BUBBLE, "Hint", "Hint");
        hint.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { hint(); } });
        LinearLayout save = railButton(Icons.BOOKMARK, "Save", "Save for later");
        saveIcon = (Icons.IconView) ((Object[]) save.getTag())[0];
        saveLabel = (TextView) ((Object[]) save.getTag())[1];
        saveIcon.setColors(0xFFFFFFFF, P.acc);
        if (show.card.saved) { saveIcon.setFilled(true); saveLabel.setText("Saved"); }
        save.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { save(); } });
        LinearLayout share = railButton("instagram".equals(host) ? Icons.SEND : Icons.SHARE, "Share", "Share with a friend");
        share.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { share(); } });
        View[] items = {like, hint, save, share};
        for (int i = 0; i < items.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.topMargin = u(10);
            rail.addView(items[i], lp);
        }
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        lp.rightMargin = Math.max(u(6), sr - u(48));
        lp.bottomMargin = sb - u(2);
        addView(rail, lp);
    }

    private String tags() {
        String deck = show.card.deck == null ? "study" : show.card.deck;
        String[] parts = deck.toLowerCase(Locale.UK).replaceAll("[^a-z0-9 ]", "").trim().split("\\s+");
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(2, parts.length); i++) { if (i > 0) b.append(' '); b.append('#').append(parts[i]); }
        return b.toString();
    }

    private CharSequence captionLine() {
        SpannableStringBuilder b = new SpannableStringBuilder(s.caption == null ? "" : s.caption);
        b.append(' ');
        int start = b.length();
        b.append(tags());
        b.setSpan(new FaceSpan(bold()), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    private View whoRow(boolean dot, String name, boolean pill, boolean filledPill) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (dot) {
            TextView d = tv(deckInitial(), 12, P.accFg, black());
            d.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(P.acc);
            d.setBackground(g);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(u(28), u(28));
            dl.rightMargin = u(8);
            row.addView(d, dl);
        }
        TextView n = tv(name, 15, 0xFFFFFFFF, black());
        n.setShadowLayer(u(2), 0, u(1), 0x8C000000);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(n, new LinearLayout.LayoutParams(-2, -2, 0));
        if (pill) {
            String deck = show.card.deck == null ? "Study" : show.card.deck.replaceFirst("^GCSE\\s+", "");
            TextView p = tv(deck, 12, filledPill ? 0xFF0F0F0F : 0xFFFFFFFF, bold());
            p.setSingleLine(true);
            p.setBackground(filledPill ? round(0xFFFFFFFF, u(99)) : Ui.outline(0x00000000, 0xFFFFFFFF, Math.max(1, Math.round(u(1.5f))), u(8)));
            p.setPadding(u(10), u(3), u(10), u(3));
            LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(-2, -2);
            pl.leftMargin = u(8);
            row.addView(p, pl);
        }
        return row;
    }

    private View soundRow(String text) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Icons.IconView note = new Icons.IconView(c, Icons.NOTE);
        note.setShadow(true);
        row.addView(note, new LinearLayout.LayoutParams(u(14), u(14)));
        TextView t = tv(text, 12.5f, 0xF2FFFFFF, regular());
        t.setShadowLayer(u(2), 0, u(1), 0x8C000000);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = u(6);
        row.addView(t, tl);
        return row;
    }

    /** Account, caption, hashtags and sound line, in the host app's own arrangement. */
    private void buildBand() {
        Context c = getContext();
        LinearLayout band = new LinearLayout(c);
        band.setOrientation(LinearLayout.VERTICAL);
        String sound = s.sound == null ? "original sound · " + s.account.substring(1) : s.sound;
        TextView cap = tv(captionLine(), 14, 0xFFFFFFFF, regular());
        cap.setShadowLayer(u(2), 0, u(1), 0x8C000000);
        cap.setMaxLines(2);
        cap.setEllipsize(TextUtils.TruncateAt.END);
        if ("youtube".equals(host)) {
            cap.setTypeface(bold());
            band.addView(cap);
            band.addView(whoRow(true, s.account, true, true), fullLp(u(5)));
            band.addView(soundRow(sound), fullLp(u(5)));
        } else if ("instagram".equals(host)) {
            band.addView(whoRow(true, s.account.substring(1), true, false));
            band.addView(cap, fullLp(u(5)));
            band.addView(soundRow(sound.replace("original sound", "Original audio")), fullLp(u(5)));
        } else {
            band.addView(whoRow(false, s.account, false, false));
            band.addView(cap, fullLp(u(5)));
            band.addView(soundRow(sound), fullLp(u(5)));
        }
        LayoutParams lp = new LayoutParams(-1, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = sl + u(10);
        lp.rightMargin = sr + u(22);
        lp.bottomMargin = Math.max(u(10), sb - u(58));
        addView(band, lp);
    }

    // ------------------------------------------------------------------ transient overlays

    private void showCoach() {
        TextView t = tv("Tap to see the answer · Double-tap if you knew it", 13, 0xFF111111, black());
        t.setBackground(round(0xF5FFFFFF, u(16)));
        t.setPadding(u(14), u(9), u(14), u(9));
        t.setElevation(u(8));
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        lp.leftMargin = sl + u(10);
        lp.rightMargin = sr + u(64);
        lp.bottomMargin = sb + u(72);
        addView(t, lp);
        t.setAlpha(0f);
        t.setTranslationY(u(8));
        t.animate().alpha(1f).translationY(0).setDuration(400).setStartDelay(300).start();
        fadeAway(t, 3800);
    }

    private void fadeAway(final View v, long after) {
        v.postDelayed(new Runnable() {
            @Override public void run() {
                v.animate().alpha(0f).setDuration(350).withEndAction(new Runnable() { @Override public void run() { removeView(v); } }).start();
            }
        }, after);
    }

    private void toast(String msg) {
        if (!built) return;
        TextView t = tv(msg, 13, 0xFFFFFFFF, bold());
        t.setBackground(round(0xE6111111, u(99)));
        t.setPadding(u(14), u(8), u(14), u(8));
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = st + u(40);
        addView(t, lp);
        drop(t);
        fadeAway(t, 1800);
    }

    private void updateBadge() {
        if (badge == null) return;
        String text;
        if (outcome.liked) text = hinted ? "With a hint · Hard" : outcome.likedBeforeReveal ? "Knew it · Easy" : "Knew it · Good";
        else if (outcome.quizPick >= 0) text = outcome.quizPick == s.correct ? "Correct" : "Not quite";
        else return;
        boolean was = badge.getVisibility() == VISIBLE;
        badge.setText(text);
        badge.setVisibility(VISIBLE);
        if (!was) drop(badge);
    }

    private void xp() {
        if (!"gaming".equals(s.vibe)) return;
        final TextView t = tv("+25 XP", 22, 0xFF9DFF6F, Ui.MONO);
        t.setTypeface(Typeface.create(Ui.MONO, Typeface.BOLD));
        t.setShadowLayer(0.01f, 0, u(2), 0xFF000000);
        LayoutParams lp = new LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        lp.topMargin = Math.round(H * 0.38f);
        addView(t, lp);
        t.setAlpha(0f);
        t.animate().alpha(1f).translationY(-u(20)).setDuration(200).withEndAction(new Runnable() {
            @Override public void run() {
                t.animate().alpha(0f).translationY(-u(60)).setDuration(800).withEndAction(new Runnable() { @Override public void run() { removeView(t); } }).start();
            }
        }).start();
    }

    private void drop(View v) {
        v.setTranslationY(-u(10));
        v.setScaleX(0.94f); v.setScaleY(0.94f); v.setAlpha(0f);
        v.animate().translationY(0).scaleX(1f).scaleY(1f).alpha(1f).setDuration(300).setInterpolator(new OvershootInterpolator(1.6f)).start();
    }

    private void startCountdown() {
        postDelayed(new Runnable() {
            @Override public void run() {
                if (outcome.revealed || finished || count <= 0) return;
                count--;
                if (ring != null) ring.invalidate();
                if (count > 0) postDelayed(this, 1000);
            }
        }, 1000);
    }

    // ------------------------------------------------------------------ actions

    void reveal() {
        if (outcome.revealed || finished || show.isQuiz()) return;
        outcome.revealed = true;
        outcome.revealAtMs = elapsed();
        justRevealed = true;
        renderBody();
    }

    void pick(int i) {
        if (outcome.quizPick >= 0 || finished) return;
        outcome.quizPick = i;
        outcome.pickAtMs = elapsed();
        outcome.revealed = true;
        if (outcome.revealAtMs == 0) outcome.revealAtMs = outcome.pickAtMs;
        justPicked = true;
        renderBody();
        updateVibe();
        updateBadge();
        if (i == s.correct) xp();
    }

    void like(float x, float y) {
        if (finished) return;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        burst(x, y);
        if (outcome.liked) return;
        outcome.liked = true;
        outcome.likeAtMs = elapsed();
        boolean before = !outcome.revealed && outcome.quizPick < 0;
        outcome.likedBeforeReveal = before;
        if (likeIcon != null) { likeIcon.setFilled(true); likeLabel.setText(before ? "Easy" : "Good"); }
        updateBadge();
        updateVibe();
        if (outcome.quizPick < 0 || outcome.quizPick == s.correct) xp();
        if (before && !show.isQuiz()) postDelayed(new Runnable() { @Override public void run() { revealAfterLike(); } }, 600);
    }

    /** After a "knew it" like the answer is shown to confirm; the like's timing is what gets graded. */
    private void revealAfterLike() {
        if (outcome.revealed || finished) return;
        outcome.revealed = true;
        outcome.revealAtMs = elapsed();
        justRevealed = true;
        renderBody();
    }

    void hint() {
        if (finished) return;
        if (hinted || outcome.revealed || outcome.quizPick >= 0) {
            toast(outcome.revealed || outcome.quizPick >= 0 ? "Answer's already showing" : "Hint's up there ↑");
            return;
        }
        hinted = true;
        justHinted = true;
        outcome.hintAtMs = elapsed();
        renderBody();
    }

    void save() {
        if (finished || outcome.saved) return;
        if (show.card.saved) { toast("Already saved"); return; }
        outcome.saved = true;
        if (saveIcon != null) { saveIcon.setFilled(true); saveLabel.setText("Saved"); }
        toast("Saved: it'll come back within a day");
    }

    /** Sends the question (not the answer) to a friend through the phone's share sheet. */
    void share() {
        if (finished) return;
        outcome.shared = true;
        String text = "Can you answer this? “" + s.question + "” · made with Reelcards";
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, "Send this question to…");
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(chooser);
        } catch (RuntimeException e) { // no share targets, or the system blocked it: copy instead
            ClipboardManager cm = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Reelcards question", text));
            toast("Copied: paste it to a friend");
        }
    }

    private void burst(float x, float y) {
        final Icons.IconView h = new Icons.IconView(getContext(), "youtube".equals(host) ? Icons.THUMB : Icons.HEART);
        h.setColors(0xFFFF3B5C, 0xFFFF3B5C);
        h.setFilled(true);
        int size = u(110);
        addView(h, new LayoutParams(size, size));
        h.setX(x - size / 2f);
        h.setY(y - size / 2f);
        h.setScaleX(0.3f); h.setScaleY(0.3f);
        h.setRotation(-12);
        h.animate().scaleX(1.12f).scaleY(1.12f).rotation(4).setDuration(180).withEndAction(new Runnable() {
            @Override public void run() {
                h.animate().scaleX(1f).scaleY(1f).rotation(0).alpha(0f).setDuration(420).withEndAction(new Runnable() {
                    @Override public void run() { removeView(h); }
                }).start();
            }
        }).start();
    }

    /** Slide in from the bottom, like the next reel. */
    void enter() {
        setTranslationY(getResources().getDisplayMetrics().heightPixels);
        animate().translationY(0).setDuration(300).setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** Swipe away (animated) and report. */
    void finish(final String exit) {
        if (finished) return;
        finished = true;
        outcome.exit = exit;
        outcome.dwellMs = elapsed();
        animate().translationY(-getHeight() - u(40)).setDuration(240).setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() { @Override public void run() { listener.onFinished(CardView.this, outcome); } }).start();
    }

    /** Remove immediately (left the app, screen off, timeout) and report. */
    void finishNow(String exit) {
        if (finished) return;
        finished = true;
        outcome.exit = exit;
        outcome.dwellMs = elapsed();
        animate().cancel();
        listener.onFinished(this, outcome);
    }

    boolean isFinished() { return finished; }

    @Override
    protected void onDetachedFromWindow() {
        if (typingPulse != null) { typingPulse.cancel(); typingPulse = null; }
        super.onDetachedFromWindow();
    }

    // ------------------------------------------------------------------ touch: taps + vertical drag

    private boolean onControl(MotionEvent ev) {
        int[] loc = new int[2];
        List<View> all = new ArrayList<>(controls);
        all.addAll(bodyControls);
        for (View v : all) {
            if (v.getWindowToken() == null || !v.isShown()) continue;
            v.getLocationOnScreen(loc);
            if (ev.getRawX() >= loc[0] && ev.getRawX() <= loc[0] + v.getWidth() && ev.getRawY() >= loc[1] && ev.getRawY() <= loc[1] + v.getHeight()) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (velocity == null) velocity = VelocityTracker.obtain();
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            velocity.clear();
            touchOnControl = onControl(ev);
            // inside a scrolling screen (the welcome preview), keep vertical swipes for the card
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        // track velocity in screen coordinates: while dragging, the card itself moves under the finger
        MotionEvent screen = MotionEvent.obtain(ev);
        screen.setLocation(ev.getRawX(), ev.getRawY());
        velocity.addMovement(screen);
        screen.recycle();
        if (!touchOnControl) gestures.onTouchEvent(ev); // a tap on a button is only that button
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getRawY(); dragging = false; return false;
            case MotionEvent.ACTION_MOVE:
                if (!dragging && downY - ev.getRawY() > slop * 2) { dragging = true; return true; }
                return dragging;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (finished) return true;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getRawY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getRawY() - downY;
                if (dy < -slop) dragging = true;
                if (dragging) setTranslationY(Math.min(0, dy));
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                float dy = ev.getRawY() - downY;
                float vy = 0;
                if (velocity != null) { velocity.computeCurrentVelocity(1000); vy = velocity.getYVelocity(); velocity.recycle(); velocity = null; }
                if (dragging && (dy < -getHeight() * 0.2f || vy < -1400)) finish("swipe");
                else if (dragging) animate().translationY(0).setDuration(180).start();
                dragging = false;
                return true;
            }
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------ small drawing helpers

    /** Countdown ring: 3, 2, 1, ? with a white arc sweeping round three times, then stopping. */
    private final class RingView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        RingView(Context c) { super(c); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), stroke = u(3), r = Math.min(w, h) / 2f - stroke / 2f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(sheet ? (night ? 0x4D000000 : 0xB3FFFFFF) : 0x40000000);
            c.drawCircle(w / 2, h / 2, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setColor(P.acc);
            c.drawCircle(w / 2, h / 2, r, p);
            long t = SystemClock.uptimeMillis() - countdownStart;
            if (t < 3000) {
                p.setColor(0xFFFFFFFF);
                oval.set(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r);
                c.drawArc(oval, -90 + (t / 3000f) * 1080f, 90, false, p);
                postInvalidateOnAnimation();
            }
            p.setStyle(Paint.Style.FILL);
            p.setColor(P.acc);
            p.setTypeface(black());
            p.setTextSize(tpx(22));
            p.setTextAlign(Paint.Align.CENTER);
            String label = count > 0 ? String.valueOf(count) : "?";
            c.drawText(label, w / 2, h / 2 - (p.ascent() + p.descent()) / 2, p);
        }
    }

    /** Sets a typeface on a stretch of text (TypefaceSpan(Typeface) needs Android 9). */
    static final class FaceSpan extends MetricAffectingSpan {
        private final Typeface tf;
        FaceSpan(Typeface tf) { this.tf = tf; }
        @Override public void updateDrawState(TextPaint p) { p.setTypeface(tf); }
        @Override public void updateMeasureState(TextPaint p) { p.setTypeface(tf); }
    }

    /** Highlighter-pen stroke across the lower half of a word (the paper look's key word). */
    static final class MarkerSpan extends ReplacementSpan {
        private final int color;
        MarkerSpan(int color) { this.color = color; }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            return Math.round(p.measureText(text, start, end));
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            float w = p.measureText(text, start, end);
            int keep = p.getColor();
            p.setColor(color);
            c.drawRect(x - 2, y + p.ascent() * 0.42f, x + w + 2, y + p.descent() * 0.55f, p);
            p.setColor(keep);
            c.drawText(text, start, end, x, y, p);
        }
    }

    /** One rounded box behind each line of text (the creator-tool "text background" style). */
    static final class LineBoxSpan implements LineBackgroundSpan {
        private final int color;
        private final float radius, pad;
        private final boolean centred;
        private final RectF r = new RectF();
        LineBoxSpan(int color, float radius, float pad, boolean centred) { this.color = color; this.radius = radius; this.pad = pad; this.centred = centred; }

        @Override
        public void drawBackground(Canvas c, Paint p, int left, int right, int top, int baseline, int bottom,
                                   CharSequence text, int start, int end, int lnum) {
            int e = end;
            while (e > start && Character.isWhitespace(text.charAt(e - 1))) e--;
            if (e <= start) return;
            float w = p.measureText(text, start, e);
            int keep = p.getColor();
            p.setColor(color);
            float x = centred ? left + (right - left - w) / 2f : left; // Android always passes the layout's left edge
            r.set(x - pad, baseline + p.ascent() - pad * 0.25f, x + w + pad, baseline + p.descent() + pad * 0.3f);
            c.drawRoundRect(r, radius, radius, p);
            p.setColor(keep);
        }
    }

    /** A notes page: plain (clean look), or ruled paper with a red margin (paper look; chalkboard at night). */
    static final class SheetDrawable extends Drawable {
        private final boolean ruled, night;
        private final int color;
        private final float radius, lineGap;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final android.graphics.Path clip = new android.graphics.Path();

        SheetDrawable(boolean ruled, boolean night, int color, float radius, float lineGap) {
            this.ruled = ruled; this.night = night; this.color = color; this.radius = radius; this.lineGap = lineGap;
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            r.set(b);
            p.setColor(color);
            c.drawRoundRect(r, radius, radius, p);
            if (!ruled) return;
            c.save();
            clip.reset();
            clip.addRoundRect(r, radius, radius, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            p.setColor(night ? 0x14FFFFFF : 0x402F6BFF);
            for (float y = b.top + lineGap; y < b.bottom; y += lineGap) c.drawRect(b.left, y - 1, b.right, y, p);
            if (!night) {
                p.setColor(0x80E4572E);
                float x = b.left + lineGap;
                c.drawRect(x, b.top, x + lineGap / 15f, b.bottom, p);
            }
            c.restore();
        }

        @Override public void getOutline(Outline o) { o.setRoundRect(getBounds(), radius); }
        @Override public void setAlpha(int a) { p.setAlpha(a); }
        @Override public void setColorFilter(ColorFilter cf) { p.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
