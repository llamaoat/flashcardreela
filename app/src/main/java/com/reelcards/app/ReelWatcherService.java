package com.reelcards.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import com.reelcards.engine.Engine;
import com.reelcards.engine.Grade;
import com.reelcards.engine.Outcome;
import com.reelcards.engine.Show;

/**
 * The one permission students switch on.
 *
 * Watches for "swiped to the next reel" in TikTok, Instagram, YouTube Shorts, Snapchat, Facebook, X, Reddit and Pinterest,
 * asks the engine whether it's time for a card, and if so draws a CardView on top of the app as an
 * accessibility overlay (TYPE_ACCESSIBILITY_OVERLAY, so no separate "draw over other apps" permission).
 * Swiping the card away drops the student straight back into their feed.
 */
public final class ReelWatcherService extends AccessibilityService {

    private static ReelWatcherService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ReelDetector detector = new ReelDetector();
    private WindowManager wm;
    private CardView overlay;
    private AudioManager audio;
    private AudioFocusRequest focus;
    private String currentPkg = "";
    private String cardPkg = "";   // the app the card is shown over
    private boolean sharing;       // the card has stepped aside for the share sheet
    private Runnable timeout;

    /**
     * Where the cards sat in the feed, so swiping back to one puts it on screen again. `pos` counts reels
     * (+1 on, -1 back); a card shown on landing at reel p sits just before it, and going back from p to
     * p-1 passes through its slot. Only the last few are kept, and it starts again when the app changes.
     */
    private static final class Slot {
        final int pos; final Show show; Outcome out; Grade grade;
        Slot(int pos, Show show) { this.pos = pos; this.show = show; }
        boolean graded() { return grade != null; }
        boolean shared() { return out != null && out.shared && grade == null; }
    }
    private final java.util.ArrayList<Slot> slots = new java.util.ArrayList<>();
    private int pos;
    private Slot current;      // the slot of the card on screen, null for a test card
    private boolean revisiting;

    static boolean isRunning() { return instance != null; }

    /**
     * Switches the watcher off from inside the app, so the one setting Reelcards asks for can be put back with a
     * tap. Android doesn't let an app switch itself on, so turning it on again is done in Accessibility settings.
     */
    static boolean switchOff() {
        if (instance == null) return false;
        instance.disableSelf();
        return true;
    }

    /** Setup wizard / dashboard: show a card right now over whatever is on screen. */
    static boolean showTestCard() {
        if (instance == null) return false;
        instance.showNow();
        return true;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        Speaker.init(this);
        Engine e = Store.engine(this);
        AnkiBridge.refill(this, e, true);
        ReelDetector.log(getPackageName(), "", null, "Reelcards switched on");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getPackageName() == null) return;
        String pkg = e.getPackageName().toString();
        int type = e.getEventType();

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (overlay != null && sharing) {
                // the share sheet, and whichever app the question is sent with, open while the card
                // waits; it comes back when the student returns to the app it was shown over (the
                // whole window, not a pop-up "pane" inside it, which would put it back over the sheet)
                if (pkg.equals(cardPkg) && e.getContentChangeTypes() == AccessibilityEvent.CONTENT_CHANGE_TYPE_UNDEFINED) comeBack();
                return;
            }
            if (!pkg.equals(getPackageName()) && !isSystemOverlay(pkg)) {
                // left the reel app (home button, notification, another app): take the card down
                if (overlay != null && !ReelDetector.isReelApp(pkg) && !pkg.equals(currentPkg)) overlay.finishNow("left_app");
                // just left AnkiDroid: cards answered there are dropped here, so they don't come up twice
                if (AnkiBridge.PACKAGE.equals(currentPkg) && !pkg.equals(currentPkg)) AnkiBridge.refill(this, Store.engine(this), true);
                if (!pkg.equals(currentPkg)) { slots.clear(); pos = 0; }
                currentPkg = pkg;
            }
            return;
        }
        if (type != AccessibilityEvent.TYPE_VIEW_SCROLLED) return;
        if (overlay != null && sharing && pkg.equals(cardPkg)) { overlay.finishNow("shared"); return; } // back and scrolling on: done with it
        if (!ReelDetector.isReelApp(pkg) || overlay != null) return;

        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (!detector.isSwipe(e, screenH)) return;

        if (detector.direction < 0) { // back to the reel before: a reel isn't a new one, but it may have a card in between
            pos--;
            for (Slot sl : slots) if (sl.pos == pos + 1) { main.removeCallbacks(settledCapture); present(sl.show, pkg, sl); return; }
            return;
        }
        pos++;
        Engine engine = Store.engine(this);
        AnkiBridge.refill(this, engine, false);
        final Show s = engine.onReelSwipe(System.currentTimeMillis(), ReelDetector.hostKey(pkg));
        if (s == null) Store.saveSoon(this); // a card on its way saves when it's done: serialising everything while it slides in would drop frames
        main.removeCallbacks(settledCapture);
        if (s != null) {
            // let the swipe animation finish, then slide the card in where the next reel would be
            final String over = pkg;
            main.postDelayed(new Runnable() { @Override public void run() { present(s, over, null); } }, 250);
        } else {
            // opt-in colour matching: note the colour of this reel once it has settled on screen,
            // so the next card can match the reel the student was actually watching
            main.postDelayed(settledCapture, 700);
        }
    }

    private static boolean isSystemOverlay(String pkg) {
        return pkg.equals("com.android.systemui") || pkg.contains("inputmethod") || pkg.contains("keyboard");
    }

    private void showNow() {
        if (overlay != null) return;
        Show s = Store.engine(this).forceShow(System.currentTimeMillis(), ReelDetector.isReelApp(currentPkg) ? ReelDetector.hostKey(currentPkg) : "tiktok");
        if (s != null) present(s, getPackageName(), null); // asked for from Reelcards' own screens
    }

    private float feedHue = Float.NaN;
    private final Runnable settledCapture = new Runnable() { @Override public void run() { captureFeedHue(); } };

    /**
     * Opt-in "match my feed's colours" (Android 11+): one screenshot of the settled reel, shrunk to
     * 24x48 pixels and reduced to a single hue in memory, then thrown away. Never saved, never sent.
     */
    private void captureFeedHue() {
        if (overlay != null || !ReelDetector.isReelApp(currentPkg)) return;
        if (Build.VERSION.SDK_INT < 30 || !Store.engine(this).settings.matchColours) { feedHue = Float.NaN; return; }
        Api30.capture(this);
    }

    /** API 30+ calls kept in their own class so older phones never load them. */
    private static final class Api30 {
        static void capture(final ReelWatcherService svc) {
            try {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new TakeScreenshotCallback() {
                    @Override public void onSuccess(ScreenshotResult r) {
                        try {
                            Bitmap shot = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                            svc.feedHue = shot == null ? Float.NaN : FeedColour.hueOfScreenshot(shot);
                            if (shot != null) shot.recycle();
                        } catch (RuntimeException e) {
                            svc.feedHue = Float.NaN;
                        } finally {
                            r.getHardwareBuffer().close();
                        }
                    }
                    @Override public void onFailure(int errorCode) { svc.feedHue = Float.NaN; }
                });
            } catch (RuntimeException ignored) { svc.feedHue = Float.NaN; /* screenshots not allowed here */ }
        }

        static void coverSystemBars(WindowManager.LayoutParams lp) { lp.setFitInsetsTypes(0); }
    }

    private void present(Show s, String overPkg, Slot back) {
        if (overlay != null || wm == null) return;
        CardView cv = new CardView(this, s, new CardView.Listener() {
            @Override public void onFinished(CardView view, Outcome o) { done(view, o); }
        }).withTint(feedHue).withShareHandler(new Runnable() {
            @Override public void run() { stepAside(); }
        });
        if (back == null) cv.withCoach(Store.takeCoachMark(this));
        else cv.withRevisit(revisitNote(back), back.out, back.graded() || back.shared());
        final CardView v = cv;
        revisiting = back != null;
        if (back != null) current = back;
        else if (overPkg.equals(getPackageName())) current = null; // a test card isn't part of the feed
        else {
            for (int i = slots.size() - 1; i >= 0; i--) if (slots.get(i).pos == pos) slots.remove(i); // a card shown here again replaces the old one
            current = new Slot(pos, s); slots.add(current); while (slots.size() > 8) slots.remove(0);
        }
        cardPkg = overPkg;
        sharing = false;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, // overlay windows draw in software without this, which makes the slide-in choppy
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) Api30.coverSystemBars(lp); // full screen, like the reel underneath
        try {
            wm.addView(v, lp);
        } catch (Exception ex) {
            ReelDetector.log(getPackageName(), "", null, "could not draw card: " + ex.getMessage());
            return;
        }
        overlay = v;
        v.enter(back != null);
        pauseReelAudio();
        if (back == null && Store.engine(this).settings.voiceover && s.script.audio == null) Speaker.speak(s.script.voiceover); // a sound question speaks for itself
        timeout = new Runnable() { @Override public void run() { if (overlay == v) v.finishNow("timeout"); } };
        main.postDelayed(timeout, 90_000);
    }

    /**
     * Share: the overlay is drawn above every app, so it would hide the share sheet. The card steps
     * aside (invisible, touches pass through to the sheet) and comes back when the student returns.
     * If they come back and scroll on instead, or don't come back before the timeout, it ends there.
     */
    private void stepAside() {
        if (overlay == null || sharing) return;
        sharing = true;
        Speaker.stop();
        overlay.setVisibility(View.INVISIBLE);
        setTouchable(false);
    }

    private void comeBack() {
        if (overlay == null || !sharing) return;
        sharing = false;
        overlay.setVisibility(View.VISIBLE);
        setTouchable(true);
    }

    private void setTouchable(boolean on) {
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) overlay.getLayoutParams();
            if (on) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            wm.updateViewLayout(overlay, lp);
        } catch (Exception ignored) { /* the window has gone */ }
    }

    private void done(CardView v, Outcome o) {
        sharing = false;
        if (timeout != null) main.removeCallbacks(timeout);
        try { wm.removeView(v); } catch (Exception ignored) { }
        if (overlay == v) overlay = null;
        Speaker.stop();
        resumeReelAudio();
        Engine engine = Store.engine(this);
        Slot sl = current; current = null;
        boolean looking = revisiting && sl != null && (sl.graded() || sl.shared()); // already done: just looking back
        boolean answeredNow = o.revealed || o.liked || o.quizPick >= 0 || o.pointsGot > 0;
        revisiting = false;
        if (looking || (sl != null && sl.out != null && !answeredNow)) { // skipped again: not a second skip
            ReelDetector.log(getPackageName(), "", null, "looked back at a card" + (looking ? " already done" : " skipped again"));
        } else {
            Engine.Result r = engine.onCardFinished(v.show, o, System.currentTimeMillis());
            ReelDetector.log(getPackageName(), "", null, "card: " + r.summary);
            if (sl != null) { sl.out = o; sl.grade = r.grade; if (r.grade != null) engine.dropRequeue(v.show.card); }
        }
        Store.saveSoon(this);
        main.removeCallbacks(settledCapture);
        main.postDelayed(settledCapture, 700); // the reel that was under the card is now on screen
    }

    /** The line shown on a card the student came back to. */
    private static String revisitNote(Slot sl) {
        if (sl.graded()) return "You did this card already · " + cap(sl.grade.label());
        if (sl.shared()) return "You shared this card already";
        return "You skipped this one. Have a go?";
    }

    private static String cap(String t) { return t.isEmpty() ? t : Character.toUpperCase(t.charAt(0)) + t.substring(1); }

    /** Most reel players pause when another app takes audio focus, and resume when it's given back. */
    private void pauseReelAudio() {
        if (audio == null) return;
        try {
            focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .build();
            audio.requestAudioFocus(focus);
        } catch (Exception ignored) { focus = null; }
    }

    private void resumeReelAudio() {
        if (audio != null && focus != null) {
            try { audio.abandonAudioFocusRequest(focus); } catch (Exception ignored) { }
            focus = null;
        }
    }

    @Override
    public void onInterrupt() {
        if (overlay != null) overlay.finishNow("interrupted");
    }

    @Override
    public void onDestroy() {
        if (overlay != null) overlay.finishNow("service_stopped");
        instance = null;
        super.onDestroy();
    }
}
