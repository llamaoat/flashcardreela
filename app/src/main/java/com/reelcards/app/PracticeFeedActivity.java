package com.reelcards.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.reelcards.engine.Engine;
import com.reelcards.engine.Outcome;
import com.reelcards.engine.Show;

import java.util.Locale;

/**
 * A pretend reel feed inside the app. Uses exactly the same engine and card views as the real
 * overlay, so it demos the whole thing without TikTok, Wi-Fi or the accessibility permission.
 * A strip at the top shows what the engine decided and why ("engine notes"), for demos.
 */
public final class PracticeFeedActivity extends Activity {
    private FrameLayout root;
    private View reel;
    private CardView card;
    private TextView notes;
    private int n;
    private Engine engine;
    private boolean showNotes = true;
    private GestureDetector swipe;
    /** Which app's layout to imitate (safe zones), so demos can show all three. */
    private String host = "tiktok";
    static final String[] HOSTS = {"tiktok", "instagram", "youtube"};
    static final String[] HOST_LABELS = {"Style: TikTok", "Style: Reels", "Style: Shorts"};

    static final String[] CREATORS = {"@dan.cooks", "@footy.clips", "@mia.dance", "@techtalk", "@catsofldn", "@gymtok.sam", "@fitcheck", "@minecraft.builds"};
    static final String[] CAPTIONS = {"wait for the ending", "day 12 of learning to cook", "this goal though", "rate this fit", "he really did that", "tutorial in comments"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        engine = Store.engine(this);
        Speaker.init(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        swipe = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onFling(MotionEvent a, MotionEvent e, float vx, float vy) {
                if (a != null && e != null && e.getY() - a.getY() < -Ui.dp(PracticeFeedActivity.this, 60)) { next(); return true; }
                return false;
            }
        });

        reel = nextReelView();
        root.addView(reel);
        buildTopBar();
        say("Swipe up like a normal feed. Cards slot in every few reels.");
    }

    private void buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(Ui.dp(this, 14), Ui.dp(this, 36), Ui.dp(this, 14), Ui.dp(this, 8));
        LinearLayout row = Ui.row(this);
        TextView close = Ui.text(this, "Close", 14, Color.WHITE, Ui.MEDIUM);
        close.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        close.setBackground(Ui.round(0x66000000, Ui.dp(this, 20)));
        close.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { finish(); } });
        row.addView(close);
        final TextView style = Ui.text(this, HOST_LABELS[0], 13, Color.WHITE, Ui.MEDIUM);
        style.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        style.setBackground(Ui.round(0x66000000, Ui.dp(this, 20)));
        style.setContentDescription("Switch which app's layout the cards follow");
        style.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                int i = (java.util.Arrays.asList(HOSTS).indexOf(host) + 1) % HOSTS.length;
                host = HOSTS[i];
                style.setText(HOST_LABELS[i]);
                say("Cards now follow " + HOST_LABELS[i].substring(7) + ": its buttons, caption layout and font, with text kept clear of where that app draws its own.");
            }
        });
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.leftMargin = Ui.dp(this, 8);
        row.addView(style, sl);
        TextView spacer = new TextView(this);
        row.addView(spacer, Ui.weight(1));
        final TextView toggle = Ui.text(this, "Why?", 13, Color.WHITE, Ui.MEDIUM);
        toggle.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        toggle.setBackground(Ui.round(0x66000000, Ui.dp(this, 20)));
        toggle.setContentDescription("Show or hide the engine's reasoning");
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                showNotes = notes.getVisibility() != View.VISIBLE;
                notes.setVisibility(showNotes ? View.VISIBLE : View.GONE);
            }
        });
        row.addView(toggle);
        TextView nextBtn = Ui.text(this, "Next ↑", 13, Ui.ON_ACCENT, Ui.DISPLAY);
        nextBtn.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        nextBtn.setBackground(Ui.round(Ui.ACCENT, Ui.dp(this, 20)));
        nextBtn.setContentDescription("Next reel");
        nextBtn.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { if (card != null) card.finish("swipe"); else next(); } });
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nl.leftMargin = Ui.dp(this, 8);
        row.addView(nextBtn, nl);
        bar.addView(row);
        notes = Ui.text(this, "", 12, Color.WHITE, Ui.MONO);
        notes.setBackground(Ui.round(0xB3000000, Ui.dp(this, 10)));
        notes.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        bar.addView(notes, Ui.topMargin(this, 8));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(bar, lp);
    }

    private void say(String s) { notes.setText(s); }

    /** A stand-in for someone's normal TikTok. */
    private View nextReelView() {
        n++;
        FrameLayout f = new FrameLayout(this);
        float hue = (n * 47) % 360;
        f.setBackgroundColor(Color.HSVToColor(new float[]{hue, 0.45f, 0.28f}));
        LinearLayout mid = Ui.column(this);
        mid.setGravity(Gravity.CENTER);
        TextView big = Ui.text(this, "REEL " + n, 40, 0xE6FFFFFF, Ui.DISPLAY);
        big.setGravity(Gravity.CENTER);
        mid.addView(big);
        TextView small = Ui.text(this, "(someone's normal video plays here)", 14, 0xAAFFFFFF, Ui.BODY);
        small.setGravity(Gravity.CENTER);
        mid.addView(small);
        f.addView(mid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout cap = Ui.column(this);
        cap.addView(Ui.text(this, CREATORS[n % CREATORS.length], 15, Color.WHITE, Ui.DISPLAY));
        cap.addView(Ui.text(this, CAPTIONS[n % CAPTIONS.length], 14, Color.WHITE, Ui.BODY));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        cl.leftMargin = Ui.dp(this, 16); cl.bottomMargin = Ui.dp(this, 34);
        f.addView(cap, cl);
        f.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent ev) { return swipe.onTouchEvent(ev); }
        });
        return f;
    }

    private void next() {
        if (card != null) return;
        final View old = reel;
        watchedHue = n > 0 ? (n * 47) % 360 : Float.NaN; // colour of the reel being swiped away
        View fresh = nextReelView();
        root.addView(fresh, 0);
        reel = fresh;
        if (old != null) {
            old.animate().translationY(-root.getHeight()).setDuration(260).setInterpolator(new DecelerateInterpolator())
                    .withEndAction(new Runnable() { @Override public void run() { root.removeView(old); } }).start();
        }
        Show s = engine.onReelSwipe(System.currentTimeMillis(), host);
        Store.saveSoon(this);
        if (s != null) {
            showCard(s);
        } else if (engine.lastDecision != null) {
            Engine.Decision d = engine.lastDecision;
            say("Reel " + n + ": no card. " + (d.blocked != null ? d.blocked : Engine.shorten(joined(d), 140)));
        }
    }

    private static String joined(Engine.Decision d) {
        StringBuilder b = new StringBuilder();
        for (String r : d.reasons) { if (b.length() > 0) b.append(" · "); b.append(r); }
        return b.toString();
    }

    private float watchedHue = Float.NaN;

    private void showCard(Show s) {
        card = new CardView(this, s, new CardView.Listener() {
            @Override public void onFinished(CardView v, Outcome o) {
                root.removeView(v);
                card = null;
                Speaker.stop();
                Engine.Result r = engine.onCardFinished(v.show, o, System.currentTimeMillis());
                Store.saveSoon(PracticeFeedActivity.this);
                say(r.summary + "\n(the reel you swiped to is underneath, keep swiping)");
                if (showNotes) notes.setVisibility(View.VISIBLE);
            }
        }).withTint(engine.settings.matchColours ? watchedHue : Float.NaN).withCoach(Store.takeCoachMark(this));
        root.addView(card, root.getChildCount() - 1, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        card.enter();
        if (engine.settings.voiceover && s.script.audio == null) Speaker.speak(s.script.voiceover); // a sound question speaks for itself
        notes.setVisibility(View.GONE); // don't cover the card; tap "Why?" to see the reasoning
        StringBuilder b = new StringBuilder();
        for (String w : s.why) { if (b.length() > 0) b.append('\n'); b.append(w); }
        say(b.toString());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Speaker.stop();
        Store.saveNow(this);
    }

    static String pct(double v) { return String.format(Locale.UK, "%d%%", Math.round(v * 100)); }
}
