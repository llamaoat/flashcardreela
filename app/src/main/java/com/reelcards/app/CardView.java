package com.reelcards.app;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
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
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.reelcards.engine.CardScript;
import com.reelcards.engine.Grade;
import com.reelcards.engine.Occlusion;
import com.reelcards.engine.Outcome;
import com.reelcards.engine.Show;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * Long answers (2+ points, see AnswerShape) are revealed one point at a time, like a "3 things…"
 * reel, and the student taps the points they got (part marks). A long question's set-up sits in a
 * quoted box. A long explanation goes behind "more" in the caption. If anything would spill out of
 * the safe zone, a fit pass shrinks the text a step at a time.
 *
 * Cards from Anki files can carry pictures. Image occlusion cards lead with the picture, the part
 * to name masked in the accent colour (a tap uncovers it); cloze cards show their gap as a blank.
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
    private Runnable shareHandler;
    private Palette P;
    private final long shownAtUptime = SystemClock.uptimeMillis();
    private long countdownStart = SystemClock.uptimeMillis();
    private ObjectAnimator typingPulse, progress;
    private final GestureDetector gestures;
    private final int slop;
    private VelocityTracker velocity;
    private float downY, lastUpX, lastUpY;
    private boolean dragging, finished, built, touchOnControl;

    // what the student has done so far (the body is redrawn from this)
    private boolean hinted, justRevealed, justHinted, justPicked;
    private int beat, count;
    // long answers: points ticked, the "more" caption, and whether the points have finished dropping in
    private final boolean[] got;
    private boolean moreOpen, settled;
    // fit pass: 0 = full size, 5 = smallest (see FIT)
    private static final float[] FIT = {1f, 0.92f, 0.84f, 0.76f, 0.68f, 0.6f};
    private int fitLevel;
    private float fit = 1f;
    private int regionW, regionH;
    private boolean centredBody;
    private TextView capView;
    private View dim;
    private int chatSkip;          // oldest friend messages dropped so a long chat keeps its newest in view
    private int focusPoint = -1;   // point to give accessibility focus back to after a redraw
    private View focusView;        // that point's view in the latest draw
    private Bitmap picBmp, ansBmp; // decoded once per card (off the main thread), then reused by every redraw
    private boolean picLoading, ansLoading;
    private Boolean picThere, ansThere; // whether the picture files exist (null: not looked yet)
    private PicView picView, ansView;   // the latest drawn, which a picture still decoding is handed to
    private boolean maskShown;          // the occlusion mask has faded in once
    private android.media.MediaPlayer player; // the card's sound, while it plays
    private boolean questionSoundPlayed;
    private int railTop;            // top of the right-hand rail (px): an occlusion picture may run under its column only above this
    private float capDownX, capDownY;
    private int capDownScroll;
    private boolean touchOnCaption;

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
        this.got = new boolean[Math.max(1, s.points.size())];
        this.count = thinkSecs(s);
        setClickable(true);
        setClipChildren(true);
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { reveal(); return true; }
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
        if ("list".equals(l) && !s.multi()) return "bigtext";
        return l;
    }

    /** Seconds on the "think" ring: grows with how long the question takes to read (3 to 7). */
    static int thinkSecs(CardScript s) {
        String main = s.questionMain != null ? s.questionMain : s.question;
        long ms = Grade.readingMs((s.context == null ? "" : s.context) + " " + main);
        return Math.max(3, Math.min(7, (int) Math.ceil((ms - 1000) / 1000.0)));
    }

    /** When each point drops in (ms): quicker for short points, slower for long ones. Last entry = after all. */
    static long[] pointDelays(List<String> points) {
        long[] d = new long[points.size() + 1];
        long t = 0;
        for (int i = 0; i < points.size(); i++) { d[i] = t; t += Math.max(500, Math.min(1500, 380 + 26L * points.get(i).length())); }
        d[points.size()] = t;
        return d;
    }

    private boolean multi() { return s.multi(); }

    /** Once a multi-point answer is showing, the question shrinks up out of the way. */
    private boolean compact() { return multi() && (outcome.revealed || outcome.quizPick >= 0); }

    private int gotCount() {
        if (outcome.liked) return s.points.size();
        int n = 0;
        for (int i = 0; i < s.points.size(); i++) if (got[i]) n++;
        return n;
    }

    /** Opt-in "match my feed's colours": hue (0..360) of the reel just watched; NaN for none. Call before it's shown. */
    CardView withTint(float hue) { tintHue = hue; return this; }

    /** Show the one-line gesture tip (first cards only). Call before it's shown. */
    CardView withCoach(boolean on) { coach = on; return this; }

    /** Runs once the share sheet has opened (the overlay uses it to step out of the sheet's way). */
    CardView withShareHandler(Runnable r) { shareHandler = r; return this; }

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

    /** Text inside the body: shrinks with the fit pass. */
    private TextView tvb(CharSequence text, float sp, int color, Typeface tf) { return tv(text, sp * fit, color, tf); }

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
        regionW = W - rl.leftMargin - rl.rightMargin;
        regionH = H - rl.topMargin - rl.bottomMargin;
        // an occlusion picture may run on under the rail's column (above the rail): the region reaches
        // over there so the card doesn't clip it, while the body keeps the usual width
        if (s.isOcclusion() && !sheet) rl.rightMargin = u(12);
        addView(region, rl);
        final boolean ruled = sheet && "paper".equals(look) && !"chat".equals(layout);
        body = new LinearLayout(c) {
            // ruled paper: a rule under each line of handwriting, so the writing sits on the lines
            @Override protected void dispatchDraw(Canvas cv) { if (ruled) drawRules(cv, this, this); super.dispatchDraw(cv); }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setClipChildren(false);
        boolean centred = !sheet && ("bigtext".equals(layout) || "captions".equals(layout)) && !s.isOcclusion();
        centredBody = centred;
        LayoutParams bl = new LayoutParams(regionW, -2, (centred ? Gravity.CENTER_VERTICAL : Gravity.TOP) | Gravity.START);
        if (centred) bl.bottomMargin = Math.round(H * 0.06f);
        region.addView(body, bl);
        if (sheet && !"chat".equals(layout)) {
            body.setBackground(new SheetDrawable("paper".equals(look), night, P.bg, u(18), u(30)));
            int left = "paper".equals(look) && !night ? u(42) : u(16);
            body.setPadding(left, u(18), u(16), u(16));
            body.setElevation(u(14));
            body.setRotation(-0.6f);
        }

        // dims the card when the caption is opened ("more"); sits under the rail and caption band
        dim = new View(c);
        dim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xEB000000, 0xC7000000, 0x8C000000}));
        dim.setAlpha(0f);
        addView(dim, new LayoutParams(-1, -1));

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
        progress = ObjectAnimator.ofFloat(bar, "scaleX", 0f, 1f);
        // runs for as long as the card takes to read
        StringBuilder ans = new StringBuilder();
        for (String pt : s.points) ans.append(pt).append(' ');
        long ms = Grade.readingMs(s.question) + Grade.readingMs(ans.toString().trim()) + 3000;
        progress.setDuration(Math.max(10000, Math.min(30000, ms)));
        progress.setInterpolator(new LinearInterpolator());
        progress.start();
    }

    // ------------------------------------------------------------------ body (redrawn on every change)

    /**
     * Draws the body from the current state. Fit pass: if it's taller than the safe zone leaves room
     * for, everything in it shrinks a step (FIT) and it's drawn again, before anything reaches the
     * screen; a long chat then drops its oldest friend messages. Starts from full size every time,
     * so the text grows back when there's room again (after a long answer collapses the question).
     */
    private void renderBody() {
        if (body == null) return;
        final boolean jr = justRevealed, jh = justHinted, jp = justPicked;
        fitLevel = 0; fit = FIT[0]; chatSkip = 0;
        placeRegion();
        for (int guard = 0; guard < 20; guard++) {
            justRevealed = jr; justHinted = jh; justPicked = jp;
            drawBody();
            if (fits()) break;
            if (fitLevel < FIT.length - 1) { fitLevel++; fit = FIT[fitLevel]; }
            else if ("chat".equals(layout) && chatSkip < chatThemShown() - 1) chatSkip++;
            else break;
        }
        justRevealed = justHinted = justPicked = false;
        if (s.isOcclusion()) maskShown = true; // later redraws (a hint, the answer) don't fade the mask in again
        body.requestLayout(); // the measures in fits() were probes: let the real layout pass measure again
        if (focusView != null) { // after a tick, screen-reader focus goes back to that point
            final View v = focusView;
            focusView = null;
            focusPoint = -1;
            v.post(new Runnable() { @Override public void run() { v.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null); } });
        }
    }

    /** Light-look sheets sit a little lower for big text, and move up once a long answer is showing. */
    private void placeRegion() {
        if (region == null || !sheet || !("bigtext".equals(layout) || "captions".equals(layout))) return;
        LayoutParams rl = (LayoutParams) region.getLayoutParams();
        int top = st + u(compact() ? 44 : 70);
        if (rl.topMargin == top) return;
        rl.topMargin = top;
        regionH = H - rl.topMargin - rl.bottomMargin;
        region.setLayoutParams(rl);
    }

    private boolean fits() {
        if (regionW <= 0) return true;
        int avail = regionH - (centredBody ? Math.round(H * 0.06f) : 0);
        body.measure(MeasureSpec.makeMeasureSpec(regionW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return body.getMeasuredHeight() <= avail + u(2);
    }

    private int chatThemShown() {
        return (outcome.revealed && multi() && !s.chatThemQuestion.isEmpty() ? s.chatThemQuestion : s.chatThem).size();
    }

    private void drawBody() {
        body.removeAllViews();
        bodyControls.clear();
        if (typingPulse != null) { typingPulse.cancel(); typingPulse = null; }
        switch (layout) {
            case "chat": renderChat(); break;
            case "comment": renderComment(); break;
            case "quiz": renderQuiz(); break;
            case "captions": renderCaptions(); break;
            case "list": renderList(); break;
            default: {
                if (s.isOcclusion()) { // the picture leads, full width at the top; the header sits under it
                    addPicture(false);
                    addQuestion(0.8f);
                    addRevealOrAnswer();
                    break;
                }
                if (!compact()) addToBody(hook(s.hookLine));
                addQuestion("split".equals(layout) ? 0.8f : 1f);
                addPicture(false);
                addSound(false);
                addRevealOrAnswer();
            }
        }
    }

    private void addToBody(View v) { addToBody(v, false); }

    private void addToBody(View v, boolean fullWidth) {
        int gap = body.getChildCount() == 0 ? 0 : Math.round(u(14) * fit);
        body.addView(v, fullWidth ? fullLp(gap) : wrapLp(gap));
    }

    /** The question: a long question's set-up in a quoted box, then the question, then "3 marks". */
    private void addQuestion(float scale) {
        if (s.context != null && !s.context.isEmpty()) addToBody(contextBox(), true);
        String q = s.context != null && !s.context.isEmpty() && s.questionMain != null ? s.questionMain : s.question;
        float pic = s.isOcclusion() ? 0.6f : s.image != null ? 0.7f : 1f; // a picture does the asking
        addToBody(question(q, scale * (compact() ? 0.6f : 1f) * pic));
        if (!"list".equals(layout)) addMarks();
    }

    private void addMarks() {
        if (s.marks <= 0 || compact()) return;
        TextView m = tvb(s.marks + " MARKS", 12, P.acc, black());
        m.setLetterSpacing(0.07f);
        m.setBackground(Ui.outline(sheet ? 0x00000000 : 0x59000000, P.acc, u(2), u(8)));
        m.setPadding(u(10), u(4), u(10), u(4));
        addToBody(m);
    }

    private View contextBox() {
        boolean sub = "subtitle".equals(look);
        TextView t = tvb(s.context, 15, sub ? 0xFFFFFFFF : P.fg, bold());
        t.setLineSpacing(0, 1.32f);
        int fill = sub ? 0xFF111111 : sheet ? 0x1F808080 : P.panel;
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        float r = u(12), small = u(4);
        g.setCornerRadii(new float[]{small, small, r, r, r, r, small, small});
        // accent bar down the left edge, like a quote
        android.graphics.drawable.LayerDrawable box = new android.graphics.drawable.LayerDrawable(new Drawable[]{g, Ui.round(P.acc, 0)});
        box.setLayerInset(1, 0, 0, 0, 0);
        box.setLayerWidth(1, u(3));
        t.setBackground(box);
        t.setPadding(u(15), u(8), u(12), u(8));
        t.setMaxLines(compact() ? 1 : 6);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (compact()) t.setAlpha(0.8f);
        return t;
    }

    private TextView hook(String text) {
        boolean sub = "subtitle".equals(look);
        TextView t = tvb(text, 16.5f, sub ? 0xFFFFFFFF : P.fg, bold());
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
        float size = fitSize(s.cloze ? gapsAsWords(q) : q, tf, tpx(baseSize() * scale * fit * this.fit), column(), sub ? u(22) : u(8), upper());
        CharSequence text = emphasised(q, upper());

        TextView t = tv(text, 10, sub ? 0xFF111111 : P.fg, tf);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        t.setLineSpacing(0, sub ? 1.42f : "neon".equals(look) || "clean".equals(look) ? 1.08f : 1.02f);
        t.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        t.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        if ("neon".equals(look)) { t.setShadowLayer(u(18), 0, 0, P.acc); t.setLetterSpacing(0.012f); }
        if (sub) boxRoom(t);
        if (s.cloze) t.setContentDescription(CardScript.spokenGaps(q));
        if (!"caption".equals(look)) return t;

        // caption look: heavy white captions with a black outline (drawn as a stroked copy behind)
        FrameLayout stack = new FrameLayout(c);
        TextView outline = tv(s.cloze ? gapped(q, true, true) : text.toString(), 10, 0xFF000000, tf); // same words and spacing as the white copy
        outline.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        outline.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
        outline.setLineSpacing(0, 1.02f);
        outline.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
        outline.getPaint().setStyle(Paint.Style.STROKE);
        outline.getPaint().setStrokeJoin(Paint.Join.ROUND);
        outline.getPaint().setStrokeWidth(u(4));
        outline.setShadowLayer(u(10), 0, u(4), 0x73000000);
        outline.setPadding(u(3), u(3), u(3), u(3)); // room for the stroke; both copies padded alike so they line up
        outline.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // screen readers read the white copy only
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
        SpannableStringBuilder b;
        if (s.cloze) b = gapped(q, up, false); // the gap is what stands out; no key word competes with it
        else {
            String[] words = q.split("\\s+");
            int kw = keyWord(words);
            b = new SpannableStringBuilder();
            for (int i = 0; i < words.length; i++) {
                if (i > 0) b.append(' ');
                String w = up ? words[i].toUpperCase(Locale.UK) : words[i];
                int start = b.length();
                b.append(w);
                if (i == kw) styleKeyWord(b, start, b.length());
            }
        }
        if ("subtitle".equals(look)) b.setSpan(new LineBoxSpan(0xFFFFFFFF, u(8), u(9), false), 0, b.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        return b;
    }

    private static final Pattern GAP = Pattern.compile("\\[([^\\]]{0,40})\\]");

    /**
     * Cloze text: each "[…]" (or "[hint]") drawn as a blank to fill in the look's style. ghost keeps
     * the widths but draws nothing, for the caption look's outline copy that sits behind the text.
     */
    private SpannableStringBuilder gapped(String q, boolean up, boolean ghost) {
        String t = up ? q.toUpperCase(Locale.UK) : q;
        SpannableStringBuilder b = new SpannableStringBuilder(t);
        boolean paper = "paper".equals(look);
        Typeface face = "clean".equals(look) ? Fonts.frauncesItalic(getContext()) : null;
        Matcher m = GAP.matcher(t);
        while (m.find()) b.setSpan(new GapSpan(P.acc, paper ? P.acc : P.accFg, paper, ghost, face), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return b;
    }

    /** For sizing: a gap never breaks across lines, so its words count as one. */
    private static String gapsAsWords(String q) {
        Matcher m = GAP.matcher(q);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(m.group().replace(' ', '\u00A0')));
        m.appendTail(b);
        return b.toString();
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
            if (hinted && !"list".equals(layout)) addToBody(hintChip());
            addToBody(revealButton());
        } else if (multi()) {
            addPoints();
            addPicture(true);
            addSound(true);
        } else if (s.isOcclusion()) { // the mask has opened; self-mark with a double-tap
            boolean k = outcome.liked;
            addToBody(selfCheck(k ? "Got it" : "Double-tap if you got it", k));
        } else {
            boolean empty = s.answer == null || s.answer.isEmpty();
            if (!empty || (s.explanation != null && !s.explanation.isEmpty())) { // empty: the answer is a picture
                View a = answerBox();
                addToBody(a, true);
                if (justRevealed) drop(a);
            }
            addPicture(true);
            addSound(true);
        }
    }

    // --- sounds from Anki cards ([sound:…]): the question's plays when the card appears, the answer's when it shows

    /** A "Listen" pill that plays the card's sound again; it's a control, so tapping it doesn't reveal. */
    private void addSound(final boolean answer) {
        if ((answer ? s.answerAudio : s.audio) == null) return;
        TextView b = tvb("▶  " + (answer ? "Hear it" : "Listen"), 14, P.accFg, bold());
        b.setBackground(round(P.acc, u(20)));
        b.setPadding(u(16), u(9), u(18), u(9));
        b.setContentDescription(answer ? "Play the answer's sound" : "Play the question's sound");
        b.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { play(answer); } });
        bodyControls.add(b);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = body.getChildCount() == 0 ? 0 : Math.round(u(12) * fit);
        lp.gravity = Gravity.START;
        body.addView(b, lp);
    }

    /** Plays one of the card's sounds from its stored file, stopping any that's playing. Quietly does nothing if it's gone. */
    void play(boolean answer) {
        stopSound();
        File f = Store.mediaFile(getContext(), answer ? s.answerAudio : s.audio);
        if (f == null || !f.isFile()) return;
        final android.media.MediaPlayer p = new android.media.MediaPlayer();
        player = p;
        try {
            p.setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build());
            p.setDataSource(f.getPath());
            p.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() { @Override public void onPrepared(android.media.MediaPlayer m) { if (player == m) m.start(); } });
            p.setOnCompletionListener(new android.media.MediaPlayer.OnCompletionListener() { @Override public void onCompletion(android.media.MediaPlayer m) { if (player == m) stopSound(); } });
            p.setOnErrorListener(new android.media.MediaPlayer.OnErrorListener() { @Override public boolean onError(android.media.MediaPlayer m, int what, int extra) { if (player == m) stopSound(); return true; } });
            p.prepareAsync();
        } catch (Exception e) {
            stopSound();
        }
    }

    private void stopSound() {
        if (player == null) return;
        try { player.release(); } catch (Exception ignored) { }
        player = null;
    }

    boolean playingSound() { return player != null; }

    /** The round "self check" pill under an answer: "Tap the ones you got", "Got it", … */
    private TextView selfCheck(String text, boolean on) {
        TextView check = tvb((on ? "●  " : "○  ") + text, 12.5f, sheet ? P.fg : 0xFFFFFFFF, black());
        check.setBackground(round(sheet ? 0x29808080 : 0x80000000, u(99)));
        check.setPadding(u(11), u(5), u(11), u(5));
        return check;
    }

    private View hintChip() {
        SpannableStringBuilder b = new SpannableStringBuilder("Hint  ");
        b.setSpan(new ForegroundColorSpan(0xFFE4572E), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        b.append(s.hint == null ? "" : s.hint);
        TextView t = tvb(b, 14, 0xFF111111, bold());
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
        int len = s.answer == null ? 0 : s.answer.length();
        if (len > 0) {
            TextView a1 = tvb(s.answer, len > 40 ? 16.5f : len > 22 ? 18 : 20, P.accFg, len > 40 ? bold() : black());
            a1.setLineSpacing(0, 1.15f);
            box.addView(a1);
        }
        if (s.explanation != null && !s.explanation.isEmpty()) {
            TextView a2 = tvb(s.explanation, 14.5f, P.accFg, regular());
            a2.setLineSpacing(0, 1.3f);
            LinearLayout.LayoutParams lp = fullLp(len > 0 ? u(4) : 0);
            box.addView(a2, lp);
        }
        return box;
    }

    // --- pictures from Anki cards

    /** Starts decoding a picture at the width it's drawn, off the main thread; it's handed to the latest view when ready. */
    private void load(final boolean answer, File file, int w) {
        if (answer ? ansLoading : picLoading) return;
        if (answer) ansLoading = true; else picLoading = true;
        Ui.picture(file.getPath(), w, new Ui.PictureLoaded() {
            @Override public void loaded(Bitmap b) {
                if (answer) { ansBmp = b; if (b == null) ansThere = false; if (ansView != null) ansView.setBitmap(b); }
                else { picBmp = b; if (b == null) picThere = false; if (picView != null) picView.setBitmap(b); }
                // a picture that can't be read is left out, as if it had gone (an occlusion card keeps its masks)
                if (b == null && (answer || !s.isOcclusion()) && isAttachedToWindow()) renderBody();
            }
        });
    }

    /**
     * A card's picture, sized from its stored dimensions so occlusion masks line up exactly: the
     * front's under the question, the back's under the answer. On an image occlusion card it leads,
     * as wide as it can be while staying clear of the rail, with the masks drawn over it.
     */
    private void addPicture(boolean answer) {
        String ref = answer ? s.answerImage : s.image;
        if (ref == null) return;
        boolean io = !answer && s.isOcclusion();
        File file = Store.mediaFile(getContext(), ref);
        if (answer ? ansThere == null : picThere == null) { if (answer) ansThere = file.isFile(); else picThere = file.isFile(); }
        if (!(answer ? ansThere : picThere) && !io) return; // the file's gone: leave it out (an occlusion card still needs its masks)
        Bitmap bmp = answer ? ansBmp : picBmp; // null until it's decoded
        int iw = answer ? s.answerImageW : s.imageW, ih = answer ? s.answerImageH : s.imageH;
        if (iw <= 0 || ih <= 0) { iw = bmp != null ? bmp.getWidth() : 4; ih = bmp != null ? bmp.getHeight() : 3; }
        float r = iw / (float) ih, maxH = u(io ? 240 : answer ? 150 : 220) * fit;
        int col = column(), w = Math.round(Math.min(col, maxH * r));
        if (io && !sheet && region != null) { // may run on under the rail's column, if it ends above the rail
            int top = ((LayoutParams) region.getLayoutParams()).topMargin;
            float clearH = railTop - u(10) - top;
            int wide = Math.round(Math.min(col + sr + u(18), Math.min(maxH, clearH) * r));
            if (wide > w) w = wide;
        }
        int h = Math.max(1, Math.round(w / r));
        PicView v = new PicView(getContext(), bmp, u(14), sheet);
        v.missing = !(answer ? ansThere : picThere);
        if (answer) ansView = v; else picView = v;
        if (bmp == null && !v.missing) load(answer, file, w);
        if (io) {
            v.shapes = s.occlusion;
            v.target = s.occlusionTarget;
            v.others = !hinted; // the hint uncovers the other labels
            v.acc = P.acc;
            v.labelPx = tpx(12);
            v.labelFace = bold();
            v.open = outcome.revealed ? 1f : 0f;
            v.maskIn = !maskShown && !outcome.revealed; // fades in when the card first appears
            v.setContentDescription(outcome.revealed ? "Picture, with the highlighted part uncovered" : "Picture: name the highlighted part");
        } else v.setContentDescription(answer ? "Picture from the answer" : "Picture from the card");
        if (!sheet) v.setElevation(u(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.topMargin = body.getChildCount() == 0 ? 0 : Math.round(u(14) * fit);
        lp.gravity = io ? Gravity.START : Gravity.CENTER_HORIZONTAL;
        body.addView(v, lp);
        if (io && justRevealed) v.animateOpen();
        if (answer && justRevealed) drop(v);
    }

    // --- long answers: points drop in one after another; tap the ones you got

    private Typeface pointFace() {
        if ("clean".equals(look)) return Fonts.fraunces(getContext());
        if ("paper".equals(look)) return Fonts.caveat(getContext());
        return bold();
    }

    private float pointSize() {
        int m = 0;
        for (String p : s.points) m = Math.max(m, p.length());
        float sz = m <= 28 ? 17.5f : m <= 60 ? 15.5f : 14.5f;
        return "paper".equals(look) ? sz * 1.38f : sz;
    }

    /** "Solid: regular pattern" -> "Solid regular pattern" with the label picked out in the look's style. */
    private CharSequence pointText(String p) {
        int colon = p.indexOf(": ");
        if (colon <= 0 || colon > 24) return p;
        SpannableStringBuilder b = new SpannableStringBuilder(p.substring(0, colon)).append(' ').append(p.substring(colon + 2));
        if ("subtitle".equals(look)) b.setSpan(new BackgroundColorSpan(P.acc), 0, colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        else if ("paper".equals(look)) { // one marker stroke per word, so a long label can still wrap
            int a = 0;
            while (a < colon) {
                int e = p.indexOf(' ', a);
                if (e < 0 || e > colon) e = colon;
                if (e > a) b.setSpan(new MarkerSpan((P.acc & 0x00FFFFFF) | 0x73000000), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                a = e + 1;
            }
        }
        else b.setSpan(new ForegroundColorSpan(P.acc), 0, colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (!sheetLook) b.setSpan(new FaceSpan(black()), 0, colon, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); // host sans, heavier
        return b;
    }

    /** Point rows read as checkboxes to screen readers. */
    private static void checkable(View v, final boolean on) {
        v.setAccessibilityDelegate(new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(true);
                info.setChecked(on);
            }
        });
    }

    /** Remembers the view for the point that was just ticked; renderBody() gives it focus once the fit pass is done. */
    private void keepFocus(View v, int i) { if (i == focusPoint) focusView = v; }

    /** A tap on a point that hasn't dropped in yet shows the rest; it doesn't mark it. */
    private void tapPoint(View v, int i) {
        if (!settled && v.getAlpha() < 1f) { reveal(); return; }
        tick(i);
    }

    private int pointFg() { return "subtitle".equals(look) ? 0xFF111111 : P.fg; }

    /** One row: number, text (or a blank for the numbered-list layout), tick. */
    private LinearLayout pointRow(int i, CharSequence text, boolean slot, boolean on) {
        Context c = getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(slot ? Gravity.CENTER_VERTICAL : Gravity.TOP);
        if (!slot) row.setMinimumHeight(u(48)); // a comfortable tap target at any fit step
        int pad = Math.round(u(9) * fit);
        row.setPadding(sheet ? u(2) : u(9), pad, sheet ? u(4) : u(10), pad);
        int fill = sheet ? (on ? (P.acc & 0x00FFFFFF) | 0x29000000 : 0x00000000)
                 : "subtitle".equals(look) ? 0xFFFFFFFF : "caption".equals(look) ? 0x9E000000 : P.panel;
        int stroke = on ? P.acc : "neon".equals(look) ? (P.acc & 0x00FFFFFF) | 0x73000000 : 0x00000000;
        row.setBackground(sheet && !on ? round(0x00000000, u(8)) : Ui.outline(fill, stroke, sheet ? 0 : u(2), u(sheet ? 8 : 14)));
        if (!sheet) row.setElevation(u(3));

        boolean steps = "steps".equals(s.shape);
        TextView n = tvb(String.valueOf(i + 1), "paper".equals(look) ? 25 : 13, "paper".equals(look) ? P.acc : P.accFg, "paper".equals(look) ? Fonts.caveat(c) : black());
        n.setGravity(Gravity.CENTER);
        if (!"paper".equals(look)) {
            GradientDrawable g = new GradientDrawable();
            g.setColor(P.acc);
            if (steps) g.setShape(GradientDrawable.OVAL); else g.setCornerRadius(u(7));
            n.setBackground(g);
            row.addView(n, new LinearLayout.LayoutParams(u(24), u(24)));
        } else row.addView(n, new LinearLayout.LayoutParams(u(18), -2));

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = u(10);
        if (slot && text == null) {
            RedactView r = new RedactView(c, pointFg());
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0, Math.round(tpx(pointSize()) * fit * 0.9f), 1);
            rl.leftMargin = u(10);
            row.addView(r, rl);
            r.fraction = Math.min(0.86f, 0.24f + s.points.get(i).length() * 0.012f);
        } else {
            TextView t = tvb(text, pointSize(), pointFg(), pointFace());
            t.setLineSpacing(0, "paper".equals(look) ? 1.02f : 1.2f);
            if (slot) t.setAlpha(0.9f);
            row.addView(t, tl);
        }
        if (!slot) {
            TickView tick = new TickView(c, on, pointFg());
            LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(u(20), u(20));
            kl.leftMargin = u(8);
            kl.topMargin = u(1);
            row.addView(tick, kl);
        }
        return row;
    }

    /** The answer's points, dropping in one after another, each tappable as "got it". */
    private void addPoints() {
        final long[] d = pointDelays(s.points);
        boolean animate = justRevealed && !settled;
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        list.setClipChildren(false);
        boolean steps = "steps".equals(s.shape);
        for (int i = 0; i < s.points.size(); i++) {
            final int idx = i;
            boolean on = outcome.liked || got[i];
            LinearLayout row = pointRow(i, pointText(s.points.get(i)), false, on);
            row.setContentDescription("Point " + (i + 1) + ": " + s.points.get(i) + ". Tap if you got it");
            checkable(row, on);
            row.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { tapPoint(v, idx); } });
            bodyControls.add(row);
            keepFocus(row, i);
            int gap = Math.round(u(7) * fit);
            if (i > 0 && steps && !sheet) { // a short line joins the steps
                View line = new View(getContext());
                line.setBackgroundColor(P.acc);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(u(2), gap);
                ll.leftMargin = u(9) + u(12) - u(1);
                list.addView(line, ll);
                gap = 0;
            } else if (i > 0 && sheet) { // a faint rule between lines on the notes sheet
                View rule = new View(getContext());
                rule.setBackgroundColor(0x2E808080);
                LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, Math.max(1, u(1)));
                ll.topMargin = gap / 2;
                list.addView(rule, ll);
                gap = gap / 2;
            }
            list.addView(row, fullLp(gap));
            if (animate) popIn(row, d[i]);
        }
        addToBody(list, true);
        int k = gotCount(), n = s.points.size();
        TextView check = selfCheck(k > 0 ? (k >= n ? "Got all " + n : "Got " + k + " of " + n) : "Tap the ones you got", k > 0);
        addToBody(check);
        if (animate) popIn(check, d[n]);
        if (animate) postDelayed(new Runnable() { @Override public void run() { settled = true; } }, d[n] + 600);
    }

    private void popIn(View v, long delay) {
        v.setAlpha(0f);
        v.setTranslationY(u(12));
        v.setScaleX(0.95f); v.setScaleY(0.95f);
        v.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setStartDelay(delay).setDuration(420)
                .setInterpolator(new OvershootInterpolator(1.25f)).start();
    }

    /** Numbered-list layout: one blank per point before the reveal, so you know how many to recall. */
    private void renderList() {
        if (!compact()) addToBody(hook(s.hookLine));
        addQuestion(0.86f);
        if (outcome.revealed) { addPoints(); return; }
        LinearLayout list = new LinearLayout(getContext());
        list.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < s.points.size(); i++) {
            String start = hinted && i < s.pointStarts.size() ? s.pointStarts.get(i) + "…" : null;
            CharSequence text = null;
            if (start != null) {
                SpannableStringBuilder b = new SpannableStringBuilder(start);
                b.setSpan(new android.text.style.StyleSpan(Typeface.ITALIC), 0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text = b;
            }
            LinearLayout row = pointRow(i, text, true, false);
            row.setElevation(0);
            list.addView(row, fullLp(i == 0 ? 0 : Math.round(u(7) * fit)));
        }
        addToBody(list, true);
        addToBody(revealButton());
    }

    // --- captions: the question arrives in short beats, key word highlighted, then the reveal

    private void renderCaptions() {
        if (!compact()) addToBody(hook(s.hookLine));
        if (beat < s.beats.size() && !outcome.revealed && !hinted) {
            String raw = s.beats.get(beat);
            boolean up = !"paper".equals(look) && !"clean".equals(look);
            String[] words = raw.split(" ");
            int hi = beat < s.beatHighlight.size() ? s.beatHighlight.get(beat) : -1;
            SpannableStringBuilder b = s.cloze ? gapped(raw, up, false) : new SpannableStringBuilder();
            for (int i = 0; i < words.length && !s.cloze; i++) {
                if (i > 0) b.append(' ');
                int start = b.length();
                b.append(up ? words[i].toUpperCase(Locale.UK) : words[i]);
                if (i == hi) styleKeyWord(b, start, b.length());
            }
            if ("subtitle".equals(look)) b.setSpan(new LineBoxSpan(0xFFFFFFFF, u(9), u(10), true), 0, b.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
            Typeface tf = "paper".equals(look) ? Fonts.caveat(getContext()) : "clean".equals(look) ? Fonts.fraunces(getContext()) : black();
            float size = fitSize(s.cloze ? gapsAsWords(raw) : raw, tf, tpx("clean".equals(look) ? 34 : 40) * fit, column(), u(22), up);
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
        addQuestion(0.8f);
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
        chat.setClipChildren(false); // the ✓ reaction sits over a bubble's corner
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
        // once a long answer arrives, the small talk drops out so the question stays in view above it
        List<String> them = outcome.revealed && multi() && !s.chatThemQuestion.isEmpty() ? s.chatThemQuestion : s.chatThem;
        // a long thread scrolls its oldest messages away, like a real chat (set by the fit pass)
        for (int i = Math.min(chatSkip, Math.max(0, them.size() - 1)); i < them.size(); i++) addBubble(chat, bubble(them.get(i), false, maxW), false, false);
        if (outcome.revealed && multi()) {
            // one message per point; tap a message you got to react ✓ (part marks). Each bubble sits
            // 5dp inside a taller tap area, so the margins are trimmed to keep the usual 6dp gap
            long[] d = pointDelays(s.points);
            boolean animate = justRevealed && !settled;
            for (int i = 0; i < s.chatMe.size(); i++) {
                final int idx = i;
                boolean on = outcome.liked || (i < got.length && got[i]);
                FrameLayout wrap = new FrameLayout(c);
                wrap.setClipChildren(false);
                wrap.setClipToPadding(false);
                wrap.setPadding(0, u(5), 0, u(5)); // a taller tap target than the bubble itself
                TextView b = bubble(s.chatMe.get(i), true, maxW);
                wrap.addView(b);
                if (on) {
                    TextView tb = tv("✓", 12, P.acc, black());
                    tb.setGravity(Gravity.CENTER);
                    tb.setBackground(Ui.outline(P.panel | 0xFF000000, P.acc, u(2), u(11)));
                    LayoutParams tl = new LayoutParams(u(22), u(22));
                    wrap.addView(tb, tl);
                    tb.setTranslationX(-u(10)); tb.setTranslationY(-u(5));
                }
                wrap.setContentDescription(s.chatMe.get(i) + ". Tap if you got it");
                checkable(wrap, on);
                wrap.setOnClickListener(new OnClickListener() { @Override public void onClick(View v) { tapPoint(v, idx); } });
                bodyControls.add(wrap);
                keepFocus(wrap, i);
                addBubble(chat, wrap, true, false);
                ((LinearLayout.LayoutParams) wrap.getLayoutParams()).topMargin = u(i == 0 ? 1 : -4); // padding already spaces them
                if (animate) popIn(wrap, d[i]);
            }
            int k = gotCount(), n = s.points.size();
            TextView sys = tvb(k > 0 ? (k >= n ? "You got all " + n : "You got " + k + " of " + n) : "Tap the messages you got", 11.5f, P.fg, bold());
            sys.setAlpha(0.75f);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
            sl.gravity = Gravity.CENTER_HORIZONTAL;
            sl.topMargin = u(6);
            chat.addView(sys, sl);
            if (animate) popIn(sys, d[n]);
            if (animate) postDelayed(new Runnable() { @Override public void run() { settled = true; } }, d[n] + 600);
        } else if (outcome.revealed) {
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
        TextView b = tvb(text, 14.5f, mine ? P.accFg : P.fg, regular());
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
        TextView said = tvb(s.commentText, 15, 0xFF111111, bold());
        said.setLineSpacing(0, 1.25f);
        texts.addView(said, fullLp(u(2)));
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.leftMargin = u(9);
        sticker.addView(texts, tl);
        LinearLayout.LayoutParams sl2 = wrapLp(0);
        sl2.rightMargin = Math.round(column() * 0.08f);
        body.addView(sticker, sl2);
        // a long question is carried by the comment itself, so it isn't repeated in big text
        if (s.question.length() <= 90) addQuestion(0.82f); else addMarks();
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
        TextView pq = tv(s.cloze ? gapped(s.question, false, false) : s.question, 16, 0xFF111111, black());
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

    private boolean good() { return outcome.liked || (outcome.quizPick >= 0 && outcome.quizPick == s.correct) || (multi() && gotCount() >= s.points.size()); }

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
        rail.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        railTop = H - lp.bottomMargin - rail.getMeasuredHeight();
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

    /**
     * Once the answer is showing, a long explanation lives in the caption behind "more", the way a
     * long caption does on every reel app; tapping it opens the caption over a dimmed card.
     */
    private void updateCaption() {
        if (capView == null) return;
        boolean answered = outcome.revealed || outcome.quizPick >= 0;
        if (!answered || s.more == null || s.more.isEmpty()) { capView.setText(captionLine()); return; }
        SpannableStringBuilder b = new SpannableStringBuilder();
        boolean longer = s.more.length() > 64;
        if (moreOpen || !longer) {
            b.append(s.more).append(' ');
            int st0 = b.length();
            b.append(tags());
            b.setSpan(new FaceSpan(bold()), st0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (longer) { int l0 = b.length(); b.append("  less"); b.setSpan(new FaceSpan(black()), l0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); }
        } else {
            int sp = s.more.lastIndexOf(' ', 58);
            String cut = s.more.substring(0, sp > 24 ? sp : 58).replaceAll("[\\s,;:.]+$", "");
            b.append(cut).append("… ");
            int m0 = b.length();
            b.append("more");
            b.setSpan(new FaceSpan(black()), m0, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        capView.setText(b);
        capView.setTextSize(TypedValue.COMPLEX_UNIT_PX, tpx(moreOpen ? 15 : 14));
        if (moreOpen) { // up to about half the screen, then it scrolls
            int lines = Math.max(4, Math.round(H * 0.5f / capView.getLineHeight()));
            capView.setMaxLines(lines);
            capView.setEllipsize(null);
            capView.setMovementMethod(new android.text.method.ScrollingMovementMethod());
        } else {
            capView.setMaxLines(2);
            capView.setEllipsize(TextUtils.TruncateAt.END);
            capView.setMovementMethod(null);
            capView.scrollTo(0, 0);
        }
        capView.setClickable(longer);
        capView.setFocusable(longer);
        if (longer && !controls.contains(capView)) controls.add(capView);
        // screen readers read the caption itself; the tap is labelled as an action
        capView.setAccessibilityDelegate(!longer ? null : new AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, moreOpen ? "close the explanation" : "show the full explanation"));
            }
        });
    }

    void toggleMore() {
        if (finished || s.more == null || s.more.length() <= 64) return;
        moreOpen = !moreOpen;
        updateCaption();
        float to = moreOpen ? 1f : 0f;
        if (dim != null) dim.animate().alpha(to).setDuration(220).start();
        float fade = moreOpen ? 0.25f : 1f;
        if (region != null) region.animate().alpha(fade).setDuration(220).start();
        if (vibeBox != null) vibeBox.animate().alpha(fade).setDuration(220).start();
        if (badge != null) badge.animate().alpha(fade).setDuration(220).start();
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
            String deck = show.card.deck == null ? "Study" : show.card.deck.replaceFirst("^GCSE\\s+", "").split(":")[0];
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
        capView = cap;
        cap.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) { capDownX = e.getRawX(); capDownY = e.getRawY(); capDownScroll = v.getScrollY(); }
                return false;
            }
        });
        cap.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // scrolling the open caption isn't a tap on it
                if (v.getScrollY() != capDownScroll) return;
                if (Math.abs(lastUpX - capDownX) > slop || Math.abs(lastUpY - capDownY) > slop) return;
                toggleMore();
            }
        });
        cap.setClickable(false);
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
        TextView t = tv(coachText(), 13, 0xFF111111, black());
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

    /** The first-cards tip, in words that fit what this card wants: a quiz is answered by picking. Mirrors coachText() in render.js. */
    String coachText() {
        if (show.isQuiz()) return "Tap the answer you think is right";
        if (s.isOcclusion()) return "Tap to uncover it · Double-tap if you knew it";
        return "Tap to see the answer · Double-tap if you knew it";
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
        int k = multi() ? gotCount() : 0, n = s.points.size();
        if (outcome.liked) text = hinted ? "With a hint · Hard" : outcome.likedBeforeReveal ? "Knew it · Easy" : "Knew it · Good";
        else if (outcome.quizPick >= 0) text = outcome.quizPick == s.correct ? "Correct" : "Not quite";
        else if (k > 0) {
            String g = k >= n ? (hinted ? "Hard" : "Good") : k * 2 >= n ? "Hard" : "Again";
            text = (k >= n ? "All " + n : k + "/" + n) + " · " + g;
        } else { badge.setVisibility(GONE); return; }
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
        if (finished) return;
        if (moreOpen) { toggleMore(); return; }
        if (outcome.revealed) { // a tap shows the rest of the points straight away
            if (multi() && !settled) { settled = true; renderBody(); }
            return;
        }
        if (show.isQuiz()) return;
        outcome.revealed = true;
        outcome.revealAtMs = elapsed();
        justRevealed = true;
        renderBody();
        updateCaption();
        if (s.answerAudio != null) play(true);
    }

    /** Self-marking a long answer: tap a point you got. Unticking a point on a liked card turns it into part marks. */
    void tick(int i) {
        if (finished || !outcome.revealed || !multi() || i < 0 || i >= s.points.size()) return;
        if (outcome.liked) {
            for (int j = 0; j < s.points.size(); j++) got[j] = true;
            outcome.liked = false;
            outcome.likedBeforeReveal = false;
            if (likeIcon != null) { likeIcon.setFilled(false); likeLabel.setText("Knew it"); }
        }
        got[i] = !got[i];
        focusPoint = i;
        int k = 0;
        for (int j = 0; j < s.points.size(); j++) if (got[j]) k++;
        outcome.pointsGot = k;
        settled = true;
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        renderBody();
        updateBadge();
        updateVibe();
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
        updateCaption();
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
        if ((multi() || s.isOcclusion()) && outcome.revealed) { settled = true; renderBody(); } // all points show as got; "Got it"
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
        updateCaption();
        if (s.answerAudio != null) play(true);
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
            if (shareHandler != null) shareHandler.run();
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
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (s.audio != null && !questionSoundPlayed && !outcome.revealed) { questionSoundPlayed = true; play(false); } // a sound question starts by itself
    }

    @Override
    protected void onDetachedFromWindow() {
        stopSound();
        if (typingPulse != null) { typingPulse.cancel(); typingPulse = null; }
        if (progress != null) { progress.cancel(); progress = null; } // up to 30 s long: don't let it run on a closed card
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
        if (ev.getActionMasked() == MotionEvent.ACTION_UP) { lastUpX = ev.getRawX(); lastUpY = ev.getRawY(); }
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            velocity.clear();
            touchOnControl = onControl(ev);
            touchOnCaption = false;
            if (moreOpen && capView != null) {
                int[] loc = new int[2];
                capView.getLocationOnScreen(loc);
                touchOnCaption = ev.getRawX() >= loc[0] && ev.getRawX() <= loc[0] + capView.getWidth() && ev.getRawY() >= loc[1] && ev.getRawY() <= loc[1] + capView.getHeight();
            }
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
                if (touchOnCaption) return false; // the open caption scrolls
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

    /** Tick for a point: an empty ring, or a filled circle with a check once it's marked "got it". */
    private final class TickView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean on;
        private final int ink;
        private final android.graphics.Path path = new android.graphics.Path();

        TickView(Context c, boolean on, int ink) { super(c); this.on = on; this.ink = ink; }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), stroke = u(2), r = Math.min(w, h) / 2f - stroke / 2f;
            if (!on) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(stroke); p.setColor((ink & 0x00FFFFFF) | 0x73000000);
                c.drawCircle(w / 2, h / 2, r, p);
                return;
            }
            p.setStyle(Paint.Style.FILL); p.setColor(P.acc);
            c.drawCircle(w / 2, h / 2, r + stroke / 2f, p);
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(u(2.5f)); p.setColor(P.accFg);
            p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
            path.reset();
            path.moveTo(w * 0.28f, h * 0.52f); path.lineTo(w * 0.44f, h * 0.68f); path.lineTo(w * 0.73f, h * 0.36f);
            c.drawPath(path, p);
        }
    }

    /** A blank for a point not yet revealed: a striped "censor bar" sized to the answer. */
    static final class RedactView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        float fraction = 0.6f;

        RedactView(Context c, int ink) { super(c); p.setColor((ink & 0x00FFFFFF) | 0x52000000); p.setStrokeWidth(0); }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth() * fraction, h = getHeight(), stripe = h * 0.55f;
            c.save();
            r.set(0, 0, w, h);
            android.graphics.Path clip = new android.graphics.Path();
            clip.addRoundRect(r, h * 0.3f, h * 0.3f, android.graphics.Path.Direction.CW);
            c.clipPath(clip);
            for (float x = -h; x < w + h; x += stripe * 1.8f) {
                android.graphics.Path band = new android.graphics.Path();
                band.moveTo(x, h); band.lineTo(x + stripe, h); band.lineTo(x + stripe + h, 0); band.lineTo(x + h, 0); band.close();
                c.drawPath(band, p);
            }
            c.restore();
        }
    }

    /** A picture with rounded corners; on an image occlusion card, the masks over it (fractions of the picture). */
    static final class PicView extends View {
        private Bitmap bmp;                // null while it's still decoding
        private final boolean flat;
        private final float dp;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final RectF r = new RectF();
        private final Path path = new Path();
        private final float radius;
        private ValueAnimator anim, inAnim;
        private static final android.animation.ArgbEvaluator ARGB = new android.animation.ArgbEvaluator();
        List<Occlusion.Shape> shapes;  // null: a plain picture
        int target, acc;
        boolean others = true;         // cover the other labels too ("occlude inactive")
        float open;                    // 0 = target masked, 1 = uncovered
        boolean missing;               // the picture's file has gone (or can't be read)
        boolean maskIn;                // fade the target mask in once it's on screen (the web demo's "maskin")
        private float in = 1f;         // that fade, 0..1
        float labelPx;
        Typeface labelFace;

        PicView(Context c, Bitmap bmp, final float radius, boolean flat) {
            super(c);
            this.bmp = bmp;
            this.flat = flat;
            this.radius = radius;
            this.dp = c.getResources().getDisplayMetrics().density;
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius); }
            });
            setClipToOutline(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        /** The decoded picture arrives (null: it couldn't be read). */
        void setBitmap(Bitmap b) {
            bmp = b;
            if (b == null) missing = true;
            invalidate();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            if (!maskIn || shapes == null || open > 0f) return;
            maskIn = false;
            in = 0f;
            inAnim = ValueAnimator.ofFloat(0f, 1f);
            inAnim.setDuration(500);
            inAnim.setInterpolator(new DecelerateInterpolator());
            inAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) { in = (Float) a.getAnimatedValue(); invalidate(); }
            });
            inAnim.start();
        }

        /** The target mask fades to an outline, uncovering the label under it. */
        void animateOpen() {
            open = 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(380);
            anim.setInterpolator(new DecelerateInterpolator());
            anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override public void onAnimationUpdate(ValueAnimator a) { open = (Float) a.getAnimatedValue(); invalidate(); }
            });
            anim.start();
        }

        @Override protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
            if (inAnim != null) { inAnim.cancel(); in = 1f; }
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            c.drawRect(0, 0, w, h, p);
            if (bmp != null) {
                r.set(0, 0, w, h);
                c.drawBitmap(bmp, null, r, p);
            } else if (missing) {
                p.setColor(0xFF8A8A8A);
                p.setTextSize(13 * dp);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("Picture not found", w / 2, h / 2, p);
                p.setTextAlign(Paint.Align.LEFT);
            }
            if (shapes != null) {
                for (Occlusion.Shape sh : shapes) {
                    if ("text".equals(sh.kind)) continue;
                    boolean t = sh.ord == target;
                    if (!t && (!sh.oi || !others)) continue;
                    if (!shapePath(sh, w, h)) continue;
                    p.setStyle(Paint.Style.FILL);
                    if (t) p.setColor((acc & 0x00FFFFFF) | (Math.round(255 * (1f - 0.88f * open) * (0.4f + 0.6f * in)) << 24));
                    else p.setColor(0xFFF3E7C9);
                    c.drawPath(path, p);
                    p.setStyle(Paint.Style.STROKE);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    if (t) {
                        p.setStrokeWidth((2.5f + open) * dp);
                        p.setColor((Integer) ARGB.evaluate(open, 0xFF111111, acc | 0xFF000000));
                    } else {
                        p.setStrokeWidth(1.5f * dp);
                        p.setColor(0xFF1D1D1F);
                    }
                    c.drawPath(path, p);
                }
                for (Occlusion.Shape sh : shapes) if ("text".equals(sh.kind) && sh.text != null && !sh.text.isEmpty()) label(c, sh, w, h);
            }
            if (flat) { // on a notes sheet: a hairline edge instead of a shadow
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Math.max(1f, dp));
                p.setColor(0x40808080);
                float in = p.getStrokeWidth() / 2f;
                r.set(in, in, w - in, h - in);
                c.drawRoundRect(r, radius, radius, p);
            }
        }

        private boolean shapePath(Occlusion.Shape sh, float w, float h) {
            path.reset();
            r.set((float) (sh.x * w), (float) (sh.y * h), (float) ((sh.x + sh.w) * w), (float) ((sh.y + sh.h) * h));
            if ("rect".equals(sh.kind)) path.addRect(r, Path.Direction.CW);
            else if ("ellipse".equals(sh.kind)) path.addOval(r, Path.Direction.CW);
            else if ("polygon".equals(sh.kind) && sh.pts != null && sh.pts.length >= 6) {
                path.moveTo((float) (sh.pts[0] * w), (float) (sh.pts[1] * h));
                for (int i = 2; i + 1 < sh.pts.length; i += 2) path.lineTo((float) (sh.pts[i] * w), (float) (sh.pts[i + 1] * h));
                path.close();
            } else return false;
            return true;
        }

        /** A text annotation from the occlusion editor, on a small white tag. */
        private void label(Canvas c, Occlusion.Shape sh, float w, float h) {
            p.setStyle(Paint.Style.FILL);
            p.setTextSize(labelPx > 0 ? labelPx : 11 * dp);
            if (labelFace != null) p.setTypeface(labelFace);
            float tw = p.measureText(sh.text), padX = 5 * dp, padY = 2 * dp, x = (float) sh.x * w, y = (float) sh.y * h;
            r.set(x, y, x + tw + 2 * padX, y + p.getTextSize() * 1.1f + 2 * padY);
            p.setColor(0xE0FFFFFF);
            c.drawRoundRect(r, 4 * dp, 4 * dp, p);
            p.setColor(0xFF111111);
            c.drawText(sh.text, x + padX, y + padY - p.ascent(), p);
            p.setTypeface(null);
        }
    }

    /** A cloze gap: the "[…]" (or "[hint]") drawn as a blank in the accent colour, or underlined on the paper look. */
    static final class GapSpan extends ReplacementSpan {
        private final int bg, fg;
        private final boolean line, ghost;
        private final Typeface face;
        private final RectF r = new RectF();

        GapSpan(int bg, int fg, boolean line, boolean ghost, Typeface face) { this.bg = bg; this.fg = fg; this.line = line; this.ghost = ghost; this.face = face; }

        private float inner(Paint p, CharSequence text, int start, int end) {
            if (end - start < 2) return 0;
            Typeface keep = p.getTypeface();
            if (face != null) p.setTypeface(face);
            float w = p.measureText(text, start + 1, end - 1);
            p.setTypeface(keep);
            return w;
        }

        private float box(Paint p, float inner) { float em = p.getTextSize(); return Math.max(inner + 0.56f * em, (line ? 2.6f : 2.3f) * em); }

        @Override
        public int getSize(Paint p, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) p.getFontMetricsInt(fm);
            return Math.round(box(p, inner(p, text, start, end)) + 0.08f * p.getTextSize());
        }

        @Override
        public void draw(Canvas c, CharSequence text, int start, int end, float x, int top, int y, int bottom, Paint p) {
            if (ghost) return;
            float em = p.getTextSize(), tw = inner(p, text, start, end), w = box(p, tw), left = x + 0.04f * em;
            int keepColor = p.getColor();
            Paint.Style keepStyle = p.getStyle();
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            if (line) {
                float ly = y + p.descent() * 0.35f;
                c.drawRect(left, ly, left + w, ly + 0.09f * em, p);
            } else {
                r.set(left, y + p.ascent() * 0.86f, left + w, y + p.descent() * 0.9f);
                c.drawRoundRect(r, 0.2f * em, 0.2f * em, p);
            }
            if (end - start >= 2) {
                Paint tp = new Paint(p);
                tp.clearShadowLayer();
                tp.setColor(fg);
                if (face != null) tp.setTypeface(face);
                c.drawText(text, start + 1, end - 1, left + (w - tw) / 2f, y, tp);
            }
            p.setColor(keepColor);
            p.setStyle(keepStyle);
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
            float w = measure(p, text, start, e);
            int keep = p.getColor();
            p.setColor(color);
            float x = centred ? left + (right - left - w) / 2f : left; // Android always passes the layout's left edge
            r.set(x - pad, baseline + p.ascent() - pad * 0.25f, x + w + pad, baseline + p.descent() + pad * 0.3f);
            c.drawRoundRect(r, radius, radius, p);
            p.setColor(keep);
        }
    }

    /** Width of a stretch of text as drawn: a replacement span (a cloze gap) counts at its own width. */
    static float measure(Paint p, CharSequence text, int start, int end) {
        if (!(text instanceof Spanned)) return p.measureText(text, start, end);
        final Spanned sp = (Spanned) text;
        ReplacementSpan[] spans = sp.getSpans(start, end, ReplacementSpan.class);
        if (spans.length == 0) return p.measureText(text, start, end);
        java.util.Arrays.sort(spans, new java.util.Comparator<ReplacementSpan>() {
            @Override public int compare(ReplacementSpan a, ReplacementSpan b) { return sp.getSpanStart(a) - sp.getSpanStart(b); }
        });
        float w = 0;
        int at = start;
        for (ReplacementSpan r : spans) {
            int a = Math.max(start, sp.getSpanStart(r)), b = Math.min(end, sp.getSpanEnd(r));
            if (a < at || b <= a) continue;
            w += p.measureText(text, at, a) + r.getSize(p, text, a, b, null);
            at = b;
        }
        return w + p.measureText(text, at, end);
    }

    private final Paint rulePaint = new Paint();

    /** Under every line of every handwritten (Caveat) text in the body, a rule across to the page's right edge. */
    private void drawRules(Canvas cv, ViewGroup g, View page) {
        Typeface hand = Fonts.caveat(getContext());
        for (int i = 0; i < g.getChildCount(); i++) {
            View v = g.getChildAt(i);
            if (v.getVisibility() != VISIBLE) continue;
            if (v instanceof ViewGroup) { drawRules(cv, (ViewGroup) v, page); continue; }
            if (!(v instanceof TextView) || ((TextView) v).getTypeface() != hand || ((TextView) v).getLayout() == null) continue;
            TextView t = (TextView) v;
            float x = 0, y = 0;
            for (View p = t; p != page && p != null; p = (View) p.getParent()) { x += p.getLeft() + p.getTranslationX(); y += p.getTop() + p.getTranslationY(); }
            rulePaint.setColor(night ? 0x14FFFFFF : 0x402F6BFF);
            float d = t.getPaint().descent();
            android.text.Layout l = t.getLayout();
            for (int k = 0; k < l.getLineCount(); k++) {
                float ly = y + t.getTotalPaddingTop() + l.getLineBaseline(k) + d * 0.45f;
                cv.drawRect(x, ly - 1, page.getWidth(), ly, rulePaint);
            }
        }
    }

    /** A notes page: plain (clean look), or paper with a red margin (paper look; chalkboard at night). The rules under the handwriting are drawn by drawRules. */
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
