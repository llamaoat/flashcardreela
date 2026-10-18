package com.reelcards.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.database.Cursor;
import android.provider.OpenableColumns;
import android.widget.ImageView;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.reelcards.engine.Card;
import com.reelcards.engine.CopyWriter;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Format;
import com.reelcards.engine.NotesParser;
import com.reelcards.engine.Outcome;
import com.reelcards.engine.SampleDecks;
import com.reelcards.engine.anki.AnkiPackage;
import com.reelcards.engine.Show;
import com.reelcards.engine.Stats;

import java.text.SimpleDateFormat;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Setup wizard (4 short steps) and the dashboard. Every screen is built in code.
 *
 * Setup: 1 pick what you're revising  2 exam date + how often  3 switch on the one permission
 *        4 try it (test card, open TikTok/Instagram/YouTube, or the practice feed)
 */
public final class MainActivity extends Activity {
    private static final int REQ_ANKI = 7, REQ_APKG = 8;

    private Engine engine;
    private Runnable back;
    private String screen = "";
    private boolean quick;          // set up with Quick start: the wizard was skipped, so its step labels are too
    private boolean awaitingSwitch; // tapped "Agree and open settings": if the switch isn't on when they come back, say what to try
    private boolean blockedHint;    // show the "didn't switch on" help at the top of the permission page, once
    private boolean addMode;      // "Add cards" from the dashboard instead of first-run setup
    private boolean examFromDashboard, permissionFromDashboard;

    // choices made during setup
    private final Set<String> chosenDecks = new LinkedHashSet<>();
    private final List<Card> notesCards = new ArrayList<>();
    private final List<Card> apkgCards = new ArrayList<>();
    private String apkgName = "";
    private String notesDeck = "My notes";
    private String pendingNotes = "";
    private boolean confirmReset;
    private String restoreNote;   // shown on the dashboard after a damaged save was recovered

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.theme(this);
        bars();
        engine = Store.engine(this);
        restoreNote = Store.prefs(this).getString("restore_note", null);
        if (restoreNote != null) Store.prefs(this).edit().remove("restore_note").apply();
        Store.cleanMedia(this, null); // pictures from an import that was never used, or a deck since removed
        if (Store.prefs(this).getBoolean("onboarded", false)) showDashboard();
        else showWelcome();
    }

    /** System bar colours and icons to match light or dark. */
    private void bars() { bars(0); }

    /** Bar colours and icons for a page: over the sea (1 welcome, 2 dashboard) the top is navy with light icons. */
    private void bars(int sea) {
        getWindow().setStatusBarColor(sea == 0 ? Ui.BG : MotionView.SEA);
        getWindow().setNavigationBarColor(sea == 1 ? MotionView.SEA : Ui.BG);
        int flags = 0;
        if (!Ui.dark && sea != 1) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (!Ui.dark && sea == 0) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    /** Dark mode switched while open (uiMode is in configChanges, so an import survives it): restyle. */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        boolean was = Ui.dark;
        Ui.theme(this);
        if (was == Ui.dark) return;
        bars();
        if ("dashboard".equals(screen)) showDashboard();
        else if ("welcome".equals(screen)) showWelcome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if ("permission".equals(screen) && accessibilityOn()) { awaitingSwitch = false; showTry(); }
        else if ("permission".equals(screen) && awaitingSwitch) { awaitingSwitch = false; blockedHint = true; showPermission(); }
        else if ("dashboard".equals(screen)) { AnkiBridge.refill(this, engine, false); showDashboard(); }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Store.saveNow(this);
    }

    @Override
    public void onBackPressed() {
        if (back != null) back.run();
        else super.onBackPressed();
    }

    // ------------------------------------------------------------------ screen scaffolding

    private int dp(float v) { return Ui.dp(this, v); }

    private LinearLayout page(String name, Runnable onBack) { return page(name, onBack, 0, null); }

    /**
     * @param sea 0: a plain page. 1: the welcome screen, with the moving sea filling the whole screen behind it.
     *            2: the dashboard, with the sea across the top, melting into the page and scrolling away with it.
     */
    private LinearLayout page(String name, Runnable onBack, int sea, String[][] words) {
        screen = name;
        back = onBack;
        bars(sea);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(sea == 0 ? Ui.BG : Color.TRANSPARENT);
        sv.setFillViewport(true);
        LinearLayout col = Ui.column(this);
        col.setPadding(dp(20), dp(28), dp(20), dp(36));
        sv.addView(col, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (sea == 0) setContentView(sv);
        else {
            FrameLayout fl = new FrameLayout(this);
            fl.setBackgroundColor(sea == 1 ? MotionView.SEA : Ui.BG);
            if (sea == 1) {
                fl.addView(new MotionView(this, words, false).setCentre(.30f).setScrim(MotionView.SEA), new FrameLayout.LayoutParams(-1, -1));
            } else {
                final MotionView mv = new MotionView(this, words, true).setCentre(.42f).setFade(Ui.BG);
                fl.addView(mv, new FrameLayout.LayoutParams(-1, dp(300)));
                sv.setOnScrollChangeListener(new View.OnScrollChangeListener() {
                    @Override public void onScrollChange(View v, int x, int y, int ox, int oy) { mv.setTranslationY(-y); }
                });
            }
            fl.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            setContentView(fl);
        }
        if (Build.VERSION.SDK_INT >= 35) Api35.fitSystemBars(sv);
        if (Build.VERSION.SDK_INT >= 33) Api33.syncBack(this);
        return col;
    }

    /** Android 15+ draws every app edge to edge: keep each page clear of the system bars and the keyboard. */
    private static final class Api35 {
        static void fitSystemBars(View root) {
            root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                    android.graphics.Insets i = in.getInsets(android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                    v.setPadding(i.left, i.top, i.right, i.bottom);
                    return in;
                }
            });
            root.requestApplyInsets();
        }
    }

    private Object backCallback; // Android 13+: registered while the page has somewhere to go back to

    /**
     * Android 13+ handles back (and its predictive animation) through callbacks; from Android 16,
     * onBackPressed isn't called at all. While a page has a back step, a callback takes it; on the
     * first page there's none, so back leaves the app as usual.
     */
    private static final class Api33 {
        static void syncBack(final MainActivity a) {
            boolean want = a.back != null;
            if (want && a.backCallback == null) {
                android.window.OnBackInvokedCallback cb = new android.window.OnBackInvokedCallback() {
                    @Override public void onBackInvoked() { if (a.back != null) a.back.run(); }
                };
                a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                a.backCallback = cb;
            } else if (!want && a.backCallback != null) {
                a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((android.window.OnBackInvokedCallback) a.backCallback);
                a.backCallback = null;
            }
        }
    }

    private void steps(LinearLayout col, int step) {
        Ui.add(col, Ui.label(this, "Step " + step + " of 4"), 0);
        LinearLayout bar = Ui.row(this);
        for (int i = 1; i <= 4; i++) {
            View seg = new View(this);
            seg.setBackground(Ui.round(i <= step ? Ui.ACCENT : Ui.LINE, dp(2)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(4), 1);
            if (i > 1) lp.leftMargin = dp(6);
            bar.addView(seg, lp);
        }
        Ui.add(col, bar, 8);
    }

    private Button primary(LinearLayout col, String text, View.OnClickListener l, int top) {
        Button b = Ui.button(this, text, true);
        b.setOnClickListener(l);
        Ui.add(col, b, top);
        return b;
    }

    private Button secondary(LinearLayout col, String text, View.OnClickListener l, int top) {
        Button b = Ui.button(this, text, false);
        b.setOnClickListener(l);
        Ui.add(col, b, top);
        return b;
    }

    private static View.OnClickListener go(final Runnable r) {
        return new View.OnClickListener() { @Override public void onClick(View v) { r.run(); } };
    }

    // ------------------------------------------------------------------ the moving hero (MotionView)

    private static final String[][] TOPICS = {{"Osmosis", "12 due"}, {"Photosynthesis", "8 due"}, {"Respiration", "5 due"}, {"Enzymes", "9 due"},
            {"Mitosis", "3 due"}, {"Ionic bonds", "7 due"}, {"Electrolysis", "4 due"}, {"Moles", "6 due"}, {"Acids", "11 due"}, {"Forces", "2 due"},
            {"Waves", "5 due"}, {"El tiempo", "8 due"}, {"La familia", "6 due"}, {"Diffusion", "3 due"}, {"DNA", "9 due"}, {"Alkenes", "4 due"},
            {"Momentum", "7 due"}, {"Circuits", "5 due"}};

    /** Each deck with what's waiting in it, then today's numbers, for the dashboard's wheel (as motionWords() in app.js). */
    private String[][] dashWords(long now) {
        java.util.Map<String, int[]> decks = new java.util.LinkedHashMap<>();
        int due = 0;
        for (Card c : engine.cards) {
            int[] v = decks.get(c.deck);
            if (v == null) decks.put(c.deck, v = new int[2]);
            if (c.isNew()) v[1]++;
            else if (c.due <= now) { v[0]++; due++; }
        }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, int[]> e : decks.entrySet()) {
            int[] v = e.getValue();
            out.add(new String[]{CopyWriter.deckLabel(e.getKey()), v[0] > 0 ? v[0] + " due" : v[1] > 0 ? v[1] + " new" : "all done"});
        }
        long today = engine.dayKey(now);
        int done = engine.stats.day(today).reviews, streak = engine.stats.streak(today);
        out.add(new String[]{"Today", done + (done == 1 ? " card" : " cards")});
        out.add(new String[]{"Streak", streak + (streak == 1 ? " day" : " days")});
        out.add(new String[]{"Due now", due + (due == 1 ? " card" : " cards")});
        double rr = engine.stats.recallRate(today, 7);
        if (rr >= 0) out.add(new String[]{"Remembered", Math.round(rr * 100) + "%"});
        java.util.List<String[]> w = new java.util.ArrayList<>(out);
        while (w.size() < 12) w.addAll(out);
        return w.toArray(new String[0][]);
    }

    // ------------------------------------------------------------------ welcome

    private void showWelcome() {
        LinearLayout col = page("welcome", null, 1, TOPICS);
        final int white = 0xFFFFFFFF, soft = 0xB3FFFFFF;
        Ui.add(col, Ui.text(this, "Reelcards", 15, white, Ui.DISPLAY), 0);
        col.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(240))); // the sea and its wheel show through here
        Ui.add(col, Ui.title(this, "Revise without trying", "between your reels", white, 0x9EFFFFFF), 4);
        Ui.add(col, Ui.text(this, "Your flashcards slip in between TikTok, Instagram and YouTube Shorts, looking just like another reel. Double-tap if you know it. Swipe if you don't.", 15, soft, Ui.BODY), 10);

        final FrameLayout preview = new FrameLayout(this);
        preview.setBackground(Ui.round(0xFF12294D, dp(22)));
        preview.setClipToOutline(true);
        Ui.add(col, Ui.text(this, "Try this one: tap to reveal, double-tap if you knew it, swipe up for another.", 13, soft, Ui.BODY), 18);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(470));
        pl.topMargin = dp(8);
        col.addView(preview, pl);
        loadPreview(preview);

        Button go = primary(col, "Get started", go(new Runnable() { @Override public void run() { quickStart(); } }), 18);
        go.setTextColor(MotionView.SEA); // a white pill on the sea
        go.setBackground(new RippleDrawable(ColorStateList.valueOf(0x220B2140), Ui.round(white, dp(26)), null));
        TextView own = Ui.linkButton(this, "Choose my decks and exam date first");
        own.setTextColor(white);
        own.setOnClickListener(go(new Runnable() { @Override public void run() { addMode = false; showDecks(); } }));
        Ui.add(col, own, 2);
        Ui.add(col, Ui.text(this, "Free. Works offline. Nothing you watch is saved or sent anywhere.", 13, 0x99FFFFFF, Ui.BODY), 10);
    }

    /** A live, playable sample card using a throwaway engine (doesn't touch the student's data). */
    private void loadPreview(final FrameLayout holder) {
        Engine demo = new Engine();
        demo.addCards(SampleDecks.get(SampleDecks.BIOLOGY));
        demo.rnd = new java.util.Random(System.nanoTime());
        Show s = demo.forceShow(System.currentTimeMillis());
        if (s == null) return;
        CardView v = new CardView(this, s, new CardView.Listener() {
            @Override public void onFinished(CardView view, Outcome o) {
                holder.removeView(view);
                loadPreview(holder);
            }
        }).withCoach(true);
        holder.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * "Get started": one tap in place of the questions: GCSE Biology and Chemistry, "Normal", no exam date, straight to the switch.
     * The disclosure and the Agree tap are still on that page. Everything here can be changed from the dashboard.
     */
    private void quickStart() {
        if (engine.cards.isEmpty()) {
            engine.addCards(SampleDecks.get(SampleDecks.BIOLOGY));
            engine.addCards(SampleDecks.get(SampleDecks.CHEMISTRY));
        }
        quick = true;
        Store.saveNow(this);
        if (accessibilityOn()) showTry(); else showPermission();
    }

    // ------------------------------------------------------------------ step 1: decks

    private void showDecks() {
        LinearLayout col = page("decks", new Runnable() { @Override public void run() { if (addMode) showDashboard(); else showWelcome(); } });
        if (!addMode) steps(col, 1);
        Ui.add(col, Ui.title(this, addMode ? "Add cards" : "What are you revising?"), addMode ? 0 : 18);
        Ui.add(col, Ui.body(this, "Pick a ready-made deck, paste your own notes, or use your Anki decks. You can mix them."), 8);

        Ui.add(col, Ui.label(this, "Ready-made decks"), 22);
        for (final String name : SampleDecks.names()) {
            boolean have = hasDeck(name);
            final Ui.Choice ch = new Ui.Choice(this, name, have ? "Already added" : SampleDecks.size(name) + " cards");
            ch.setChecked(have || chosenDecks.contains(name));
            if (!have) ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (chosenDecks.contains(name)) chosenDecks.remove(name); else chosenDecks.add(name);
                    ch.setChecked(chosenDecks.contains(name));
                }
            });
            Ui.add(col, ch, 8);
        }

        Ui.add(col, Ui.label(this, "Your own cards"), 22);
        Ui.Choice notes = new Ui.Choice(this, "Paste your notes",
                notesCards.isEmpty() ? "Turns lines like “Osmosis - diffusion of water” into cards" : notesCards.size() + " cards ready from “" + notesDeck + "”");
        notes.setChecked(!notesCards.isEmpty());
        notes.setOnClickListener(go(new Runnable() { @Override public void run() { showNotes(); } }));
        Ui.add(col, notes, 8);

        Ui.Choice apkg = new Ui.Choice(this, "Open an Anki file (.apkg)",
                apkgCards.isEmpty() ? "Shared decks, or decks exported from Anki on a computer" : apkgCards.size() + " cards ready from “" + apkgName + "”");
        apkg.setChecked(!apkgCards.isEmpty());
        apkg.setOnClickListener(go(new Runnable() { @Override public void run() { pickApkg(); } }));
        Ui.add(col, apkg, 8);

        boolean ankiInstalled = AnkiBridge.installed(this);
        String ankiNames = AnkiBridge.selectedNames(this);
        Ui.Choice anki = new Ui.Choice(this, "Your Anki decks",
                !ankiInstalled ? "Needs the free AnkiDroid app" : ankiNames.isEmpty() ? "Reviews sync back to Anki automatically" : "Using: " + ankiNames);
        anki.setChecked(!ankiNames.isEmpty() && ankiInstalled);
        anki.setOnClickListener(go(new Runnable() { @Override public void run() { startAnki(); } }));
        Ui.add(col, anki, 8);

        final TextView error = Ui.text(this, "", 13, Ui.BAD, Ui.MEDIUM);
        Ui.add(col, error, 10);
        primary(col, addMode ? "Add to my cards" : "Next", new View.OnClickListener() {
            @Override public void onClick(View v) {
                int added = 0;
                for (String n : chosenDecks) added += engine.addCards(SampleDecks.get(n));
                added += engine.addCards(notesCards);
                added += engine.addCards(apkgCards);
                chosenDecks.clear();
                notesCards.clear();
                apkgCards.clear();
                boolean anyAnki = !AnkiBridge.selectedNames(MainActivity.this).isEmpty();
                if (engine.cards.isEmpty() && !anyAnki) { error.setText("Pick at least one deck, or paste some notes."); return; }
                Store.saveNow(MainActivity.this);
                if (addMode) showDashboard(); else showExam();
            }
        }, 4);
    }

    private boolean hasDeck(String name) {
        for (Card c : engine.cards) if (name.equals(c.deck)) return true;
        return false;
    }

    // ------------------------------------------------------------------ Anki files (.apkg)

    /** The system file picker: no storage permission needed. */
    private void pickApkg() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try { startActivityForResult(i, REQ_APKG); }
        catch (RuntimeException e) { showApkgResult(null, null, "This phone has no file picker to open the deck with."); }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == REQ_APKG && result == RESULT_OK && data != null && data.getData() != null) importApkg(data.getData());
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) { String n = c.getString(0); if (n != null) return n; }
        } catch (RuntimeException ignored) {
        } finally { if (c != null) c.close(); }
        String last = uri.getLastPathSegment();
        return last == null ? "Anki deck.apkg" : last;
    }

    private int importRun; // only the latest import, still on its "Reading…" page, may show a result

    /** Copies the picked file into the cache, reads it off the main thread, then shows what it found. */
    private void importApkg(final Uri uri) {
        final String name = displayName(uri);
        final int run = ++importRun;
        LinearLayout col = page("apkg", new Runnable() { @Override public void run() { showDecks(); } });
        Ui.add(col, Ui.title(this, "Your Anki deck"), 0);
        Ui.add(col, Ui.body(this, "Reading “" + name + "”…"), 8);
        final File cache = getCacheDir();
        final File dir = Store.mediaDir(this);
        new Thread(new Runnable() {
            @Override public void run() {
                File tmp = null;
                AnkiPackage.Result r = null;
                String error = null;
                java.util.zip.ZipFile zip = null;
                try {
                    tmp = File.createTempFile("import", ".apkg", cache);
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IOException("Couldn't open the file.");
                    try {
                        OutputStream out = new FileOutputStream(tmp);
                        try {
                            byte[] buf = new byte[65536];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        } finally { out.close(); }
                    } finally { in.close(); }
                    dir.mkdirs();
                    zip = new java.util.zip.ZipFile(tmp);
                    r = AnkiPackage.read(zip, name, System.currentTimeMillis(), new AnkiPackage.Media() {
                        @Override public String save(String media, byte[] data, String type) {
                            // named by content, so two decks with an "image.png" each never overwrite each other
                            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                            crc.update(data);
                            String safe = media.replaceAll("[^A-Za-z0-9._-]", "_");
                            if (safe.length() > 60) safe = safe.substring(safe.length() - 60);
                            File f = new File(dir, Long.toHexString(crc.getValue()) + "-" + Integer.toHexString(data.length) + "-" + safe);
                            // the card keeps just the name (Store.mediaFile finds it), so it survives the folder moving
                            if (f.isFile() && f.length() == data.length) { f.setLastModified(System.currentTimeMillis()); return f.getName(); } // saved by an earlier import
                            try { FileOutputStream o = new FileOutputStream(f); try { o.write(data); } finally { o.close(); } return f.getName(); }
                            catch (IOException e) { return null; }
                        }
                    });
                } catch (java.util.zip.ZipException e) {
                    error = "That isn't an Anki file. Export a deck from Anki as a .apkg (or .colpkg) and try again.";
                } catch (OutOfMemoryError e) {
                    error = "This deck is too big to open on this phone.";
                } catch (StackOverflowError e) {
                    error = "Couldn't read that deck: it's damaged or nested too deeply.";
                } catch (Exception e) {
                    String m = e.getMessage();
                    error = m != null && m.length() < 120 ? m : "Couldn't read that deck.";
                } finally {
                    if (zip != null) try { zip.close(); } catch (IOException ignored) { }
                    if (tmp != null) tmp.delete();
                }
                final AnkiPackage.Result result = r;
                final String err = error;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        // dropped if the student left the page, picked another file, or the screen was rebuilt
                        if (isFinishing() || isDestroyed() || run != importRun || !"apkg".equals(screen)) return;
                        showApkgResult(result, name, err);
                    }
                });
            }
        }).start();
    }

    private void showApkgResult(final AnkiPackage.Result r, final String name, String error) {
        LinearLayout col = page("apkg", new Runnable() { @Override public void run() { showDecks(); } });
        Ui.add(col, Ui.title(this, "Your Anki deck"), 0);
        if (r == null) {
            Ui.add(col, Ui.text(this, error == null ? "Couldn't read that deck." : error, 14, Ui.BAD, Ui.MEDIUM), 10);
            Ui.add(col, Ui.muted(this, "Reelcards reads .apkg and .colpkg files from any Anki version, with no account. Audio and suspended cards are left out."), 8);
        } else {
            LinearLayout p = Ui.panel(this);
            // the same file again after a phone move: its pictures were just saved again, so cards waiting for them come back
            Engine eng = Store.engine(this);
            int waiting = eng.hidden.size(), back = waiting - Store.checkMedia(this, eng);
            if (back > 0) {
                TextView ok = Ui.text(this, "Pictures are back for " + back + (back == 1 ? " card" : " cards") + ". They'll show in your feed again.", 15, Ui.GOOD, Ui.MEDIUM);
                Ui.add(col, ok, 10);
            }
            p.addView(Ui.text(this, r.cards.size() + " cards from “" + name + "”", 18, Ui.TEXT, Ui.DISPLAY));
            Ui.add(p, Ui.muted(this, r.stats.summary()), 4);
            for (int i = 0; i < Math.min(6, r.cards.size()); i++) {
                Card c = r.cards.get(i);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setBackground(Ui.round(Ui.SURFACE2, dp(10)));
                row.setPadding(dp(10), dp(8), dp(10), dp(8));
                if (c.image != null) {
                    final ImageView iv = new ImageView(this);
                    iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    Ui.picture(Store.mediaFile(this, c.image).getPath(), dp(64), new Ui.PictureLoaded() {
                        @Override public void loaded(android.graphics.Bitmap b) { iv.setImageBitmap(b); }
                    });
                    iv.setClipToOutline(true);
                    iv.setBackground(Ui.round(0xFFFFFFFF, dp(6)));
                    LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(48), dp(36));
                    il.rightMargin = dp(10);
                    row.addView(iv, il);
                }
                String text = (c.occlusion != null ? c.front + " (image occlusion)" : c.front + (c.back.isEmpty() ? "" : "\n→ " + c.back.replace("\n", "; ")))
                        + (c.audio != null || c.answerAudio != null ? "  🔊" : "");
                row.addView(Ui.text(this, text, 14, Ui.TEXT, Ui.BODY), new LinearLayout.LayoutParams(0, -2, 1));
                Ui.add(p, row, 8);
            }
            if (r.cards.size() > 6) Ui.add(p, Ui.muted(this, "…and " + (r.cards.size() - 6) + " more"), 6);
            if (!r.cards.isEmpty()) {
                Button use = Ui.button(this, "Use these " + r.cards.size() + " cards", true);
                use.setOnClickListener(go(new Runnable() {
                    @Override public void run() { apkgCards.clear(); apkgCards.addAll(r.cards); apkgName = name; showDecks(); }
                }));
                Ui.add(p, use, 12);
            }
            Ui.add(col, p, 12);
        }
        TextView again = Ui.linkButton(this, r == null ? "Choose an .apkg file" : "Open a different file");
        again.setOnClickListener(go(new Runnable() { @Override public void run() { pickApkg(); } }));
        Ui.add(col, again, 8);
        TextView how = Ui.muted(this, "In Anki on a computer: File → Export → “Anki Deck Package (.apkg)”. Shared decks from AnkiWeb work too.");
        Ui.add(col, how, 8);
    }

    // ------------------------------------------------------------------ notes -> cards

    static final String EXAMPLE_NOTES =
            "Osmosis - diffusion of water across a partially permeable membrane\n"
            + "Mitochondria: site of aerobic respiration\n"
            + "Enzymes are biological catalysts.\n"
            + "Q: Which organ makes bile?\n"
            + "A: The liver\n"
            + "Photosynthesis = carbon dioxide + water → glucose + oxygen\n"
            + "Chlorophyll - green pigment that absorbs light\n";

    private void showNotes() {
        LinearLayout col = page("notes", new Runnable() { @Override public void run() { showDecks(); } });
        Ui.add(col, Ui.title(this, "Paste your notes"), 0);
        Ui.add(col, Ui.body(this, "One fact per line. These all work:"), 8);
        TextView ex = Ui.text(this, "Osmosis - diffusion of water\nMitochondria: site of respiration\nEnzymes are biological catalysts.\nQ: Which organ makes bile?\nA: The liver\nCopied from Quizlet or Anki (tab-separated)", 13, Ui.MUTED, Ui.MONO);
        ex.setBackground(Ui.round(Ui.SURFACE, dp(12)));
        ex.setPadding(dp(12), dp(10), dp(12), dp(10));
        Ui.add(col, ex, 8);

        // AI-made cards with no AI in the app: a prompt for a chatbot the student already uses
        LinearLayout ai = Ui.panel(this);
        ai.addView(Ui.text(this, "Make cards with AI", 16, Ui.TEXT, Ui.MEDIUM));
        Ui.add(ai, Ui.muted(this, "Copy our prompt into a chatbot you already use, paste your notes under it, then paste its reply in Notes below. "
                + "Your notes go to that chatbot, not to Reelcards."), 4);
        final TextView copied = Ui.muted(this, "");
        Button copy = Ui.button(this, "Copy the AI prompt", false);
        copy.setOnClickListener(go(new Runnable() {
            @Override public void run() {
                android.content.ClipboardManager cb = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb == null) return;
                cb.setPrimaryClip(android.content.ClipData.newPlainText("Reelcards prompt", NotesParser.AI_PROMPT));
                copied.setText("Copied. Paste it into the chatbot, add your notes underneath, and send. Then copy the reply and paste it below.");
                copied.setTextColor(Ui.GOOD);
            }
        }));
        Ui.add(ai, copy, 10);
        Ui.add(ai, copied, 6);
        Ui.add(col, ai, 10);

        final EditText name = new EditText(this);
        name.setText(notesDeck);
        name.setSingleLine(true);
        name.setTextColor(Ui.TEXT);
        name.setHint("Deck name, e.g. Biology paper 1");
        name.setHintTextColor(Ui.MUTED);
        name.setBackground(Ui.outline(Ui.SURFACE, Ui.LINE, dp(1), dp(12)));
        name.setPadding(dp(12), dp(12), dp(12), dp(12));
        name.setId(View.generateViewId());
        Ui.add(col, Ui.label(this, "Deck name"), 18);
        Ui.add(col, name, 6);

        final EditText box = new EditText(this);
        box.setText(pendingNotes);
        box.setMinLines(8);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        box.setTextColor(Ui.TEXT);
        box.setHint("Paste or type your notes here");
        box.setHintTextColor(Ui.MUTED);
        box.setBackground(Ui.outline(Ui.SURFACE, Ui.LINE, dp(1), dp(12)));
        box.setPadding(dp(12), dp(12), dp(12), dp(12));
        box.setId(View.generateViewId());
        Ui.add(col, Ui.label(this, "Notes"), 14);
        Ui.add(col, box, 6);
        TextView example = Ui.linkButton(this, "Fill with example notes");
        example.setOnClickListener(go(new Runnable() { @Override public void run() { box.setText(EXAMPLE_NOTES); } }));
        Ui.add(col, example, 2);

        final LinearLayout result = Ui.column(this);
        Ui.add(col, result, 6);
        primary(col, "Make cards", new View.OnClickListener() {
            @Override public void onClick(View v) {
                pendingNotes = box.getText().toString();
                String deck = name.getText().toString().trim();
                notesDeck = deck.isEmpty() ? "My notes" : deck;
                final NotesParser.Result r = NotesParser.parse(pendingNotes, notesDeck);
                result.removeAllViews();
                if (r.cards.isEmpty()) {
                    result.addView(Ui.text(MainActivity.this, "No cards yet. Try one fact per line, like “Osmosis - diffusion of water”.", 14, Ui.BAD, Ui.MEDIUM));
                    return;
                }
                LinearLayout p = Ui.panel(MainActivity.this);
                p.addView(Ui.text(MainActivity.this, "Made " + r.cards.size() + " cards", 18, Ui.TEXT, Ui.DISPLAY));
                if (r.skippedLines > 0) Ui.add(p, Ui.muted(MainActivity.this, r.skippedLines + (r.skippedLines == 1 ? " line didn't" : " lines didn't") + " look like a fact, so we left " + (r.skippedLines == 1 ? "it" : "them") + " out."), 4);
                for (int i = 0; i < Math.min(6, r.cards.size()); i++) {
                    Card c = r.cards.get(i);
                    TextView t = Ui.text(MainActivity.this, c.front + "\n→ " + c.back, 14, Ui.TEXT, Ui.BODY);
                    t.setBackground(Ui.round(Ui.SURFACE2, dp(10)));
                    t.setPadding(dp(10), dp(8), dp(10), dp(8));
                    Ui.add(p, t, 8);
                }
                if (r.cards.size() > 6) Ui.add(p, Ui.muted(MainActivity.this, "…and " + (r.cards.size() - 6) + " more"), 6);
                Button use = Ui.button(MainActivity.this, "Use these " + r.cards.size() + " cards", true);
                use.setOnClickListener(go(new Runnable() {
                    @Override public void run() { notesCards.clear(); notesCards.addAll(r.cards); pendingNotes = ""; showDecks(); }
                }));
                Ui.add(p, use, 12);
                result.addView(p);
            }
        }, 10);
    }

    // ------------------------------------------------------------------ Anki

    private void startAnki() {
        if (!AnkiBridge.installed(this)) {
            LinearLayout col = page("anki", new Runnable() { @Override public void run() { showDecks(); } });
            Ui.add(col, Ui.title(this, "Use your Anki decks"), 0);
            Ui.add(col, Ui.body(this, "Install AnkiDroid (free), open it once so your decks load, then come back here. Every reel you answer is saved as a review in Anki, and syncs to your laptop like normal."), 10);
            primary(col, "Get AnkiDroid", go(new Runnable() {
                @Override public void run() {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + AnkiBridge.PACKAGE))); }
                    catch (Exception e) { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + AnkiBridge.PACKAGE))); }
                }
            }), 18);
            secondary(col, "Back", go(new Runnable() { @Override public void run() { showDecks(); } }), 10);
            return;
        }
        if (!AnkiBridge.hasPermission(this)) {
            requestPermissions(new String[]{AnkiBridge.PERMISSION}, REQ_ANKI);
            return;
        }
        showAnkiDecks();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code != REQ_ANKI) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) showAnkiDecks();
        else {
            LinearLayout col = page("anki", new Runnable() { @Override public void run() { showDecks(); } });
            Ui.add(col, Ui.title(this, "Anki access needed"), 0);
            Ui.add(col, Ui.body(this, "To read your due cards and save reviews, Reelcards needs AnkiDroid's permission. Tap below and choose Allow."), 10);
            primary(col, "Ask again", go(new Runnable() { @Override public void run() { startAnki(); } }), 18);
            secondary(col, "Back", go(new Runnable() { @Override public void run() { showDecks(); } }), 10);
        }
    }

    private void showAnkiDecks() {
        final LinearLayout col = page("anki", new Runnable() { @Override public void run() { showDecks(); } });
        Ui.add(col, Ui.title(this, "Pick Anki decks"), 0);
        Ui.add(col, Ui.body(this, "Anki keeps scheduling these cards. Reelcards shows whatever Anki says is due and sends your answers back."), 8);
        final TextView loading = Ui.muted(this, "Reading your decks…");
        Ui.add(col, loading, 16);
        new Thread(new Runnable() {
            @Override public void run() {
                final List<AnkiBridge.Deck> decks = AnkiBridge.decks(MainActivity.this);
                runOnUiThread(new Runnable() { @Override public void run() { fillAnkiDecks(col, loading, decks); } });
            }
        }).start();
    }

    private void fillAnkiDecks(LinearLayout col, TextView loading, final List<AnkiBridge.Deck> decks) {
        if (!"anki".equals(screen)) return;
        if (decks.isEmpty()) {
            loading.setText("Couldn't read your decks. Open AnkiDroid once, then try again.");
            secondary(col, "Try again", go(new Runnable() { @Override public void run() { showAnkiDecks(); } }), 12);
            return;
        }
        col.removeView(loading);
        final List<AnkiBridge.Deck> picked = new ArrayList<>();
        for (AnkiBridge.Deck d : AnkiBridge.selected(this)) for (AnkiBridge.Deck x : decks) if (x.id == d.id) picked.add(x);
        for (final AnkiBridge.Deck d : decks) {
            final Ui.Choice ch = new Ui.Choice(this, d.name, d.counts());
            ch.setChecked(picked.contains(d));
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (picked.contains(d)) picked.remove(d); else picked.add(d);
                    ch.setChecked(picked.contains(d));
                }
            });
            Ui.add(col, ch, 8);
        }
        primary(col, "Use these decks", go(new Runnable() {
            @Override public void run() {
                AnkiBridge.saveSelection(MainActivity.this, picked);
                AnkiBridge.refill(MainActivity.this, engine, true);
                showDecks();
            }
        }), 16);
    }

    // ------------------------------------------------------------------ step 2: exam + intensity

    private void showExam() {
        LinearLayout col = page("exam", new Runnable() { @Override public void run() { if (examFromDashboard) { examFromDashboard = false; showDashboard(); } else showDecks(); } });
        if (!examFromDashboard) steps(col, 2);
        Ui.add(col, Ui.title(this, "Your exam and your feed"), examFromDashboard ? 0 : 18);
        Ui.add(col, Ui.body(this, "Cards get more frequent as the exam gets closer, and nothing gets scheduled past exam day."), 8);
        Ui.add(col, Ui.label(this, "When's your exam?"), 20);

        final List<Ui.Choice> examChoices = new ArrayList<>();
        final int[] days = {7, 14, 30, 90, -2, -1};
        String[] labels = {"In 1 week", "In 2 weeks", "In about a month", "In about 3 months", "Pick a date", "No exam yet"};
        final long[] chosen = {engine.settings.examDate};
        for (int i = 0; i < days.length; i++) {
            final int idx = i;
            String sub = "";
            if (days[i] == -2 && engine.settings.examDate > 0) sub = "Currently " + dateText(engine.settings.examDate);
            final Ui.Choice ch = new Ui.Choice(this, labels[i], sub);
            examChoices.add(ch);
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (days[idx] == -2) { pickDate(chosen, examChoices, ch); return; }
                    chosen[0] = days[idx] < 0 ? 0 : endOfDayIn(days[idx]);
                    for (Ui.Choice c : examChoices) c.setChecked(c == ch);
                }
            });
            Ui.add(col, ch, 8);
        }
        if (engine.settings.examDate > 0) examChoices.get(4).setChecked(true);

        // what they watch: themes the cards so they fit the feed (a starting taste; it keeps learning)
        Ui.add(col, Ui.label(this, "What do you watch most? Pick any"), 24);
        Ui.add(col, Ui.muted(this, "Cards borrow the look of those reels: pitch backgrounds for football, gameplay for gaming, and so on. Only you say this; your feed is never read."), 4);
        final java.util.Set<String> vibes = new java.util.LinkedHashSet<>(engine.settings.vibes);
        LinearLayout vibeRow = null;
        for (int i = 0; i < Format.VIBES.length; i++) {
            if (i % 3 == 0) { vibeRow = Ui.row(this); Ui.add(col, vibeRow, 8); }
            final String v = Format.VIBES[i];
            final TextView chip = Ui.text(this, Format.label(v), 14, Ui.TEXT, Ui.MEDIUM);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(dp(44));
            chip.setPadding(dp(6), 0, dp(6), 0);
            chip.setContentDescription(Format.label(v));
            styleChip(chip, vibes.contains(v));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View x) {
                    if (vibes.contains(v)) vibes.remove(v); else vibes.add(v);
                    styleChip(chip, vibes.contains(v));
                }
            });
            LinearLayout.LayoutParams cl = Ui.weight(1);
            if (i % 3 != 0) cl.leftMargin = dp(6);
            vibeRow.addView(chip, cl);
        }
        if (Format.VIBES.length % 3 != 0) for (int k = Format.VIBES.length % 3; k < 3; k++) { View gap = new View(this); LinearLayout.LayoutParams gl = Ui.weight(1); gl.leftMargin = dp(6); vibeRow.addView(gap, gl); }

        Ui.add(col, Ui.label(this, "How often should cards appear?"), 24);
        final List<Ui.Choice> freq = new ArrayList<>();
        final String[] modes = {com.reelcards.engine.Settings.CHILL, com.reelcards.engine.Settings.NORMAL, com.reelcards.engine.Settings.CRAM};
        final String[] modeSub = {"Starts at 1 card every 8 reels, up to 60 a day", "Starts at 1 card every 5 reels, up to 150 a day (recommended)", "Starts at 1 card every 3 reels, up to 300 a day"};
        final String[] chosenMode = {engine.settings.intensity};
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            final Ui.Choice ch = new Ui.Choice(this, cap(m), modeSub[i]);
            ch.setChecked(m.equals(chosenMode[0]));
            freq.add(ch);
            ch.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { chosenMode[0] = m; for (Ui.Choice c : freq) c.setChecked(c == ch); }
            });
            Ui.add(col, ch, 8);
        }

        primary(col, examFromDashboard ? "Save" : "Next", go(new Runnable() {
            @Override public void run() {
                engine.settings.examDate = chosen[0];
                if (!chosenMode[0].equals(engine.settings.intensity)) { engine.rhythm.seeded = false; engine.rhythmArmForNext = -1; }
                engine.settings.intensity = chosenMode[0];
                engine.setVibes(vibes);
                Store.saveNow(MainActivity.this);
                if (examFromDashboard) { examFromDashboard = false; showDashboard(); }
                else if (accessibilityOn()) showTry();
                else showPermission();
            }
        }), 22);
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setTextColor(on ? Ui.ON_ACCENT : Ui.TEXT);
        chip.setTypeface(on ? Ui.DISPLAY : Ui.MEDIUM);
        chip.setBackground(on ? Ui.round(Ui.ACCENT, dp(22)) : Ui.outline(Ui.SURFACE, Ui.LINE, dp(1), dp(22)));
    }

    private void pickDate(final long[] chosen, final List<Ui.Choice> all, final Ui.Choice ch) {
        Calendar c = Calendar.getInstance();
        if (chosen[0] > 0) c.setTimeInMillis(chosen[0]); else c.add(Calendar.DAY_OF_YEAR, 30);
        DatePickerDialog d = new DatePickerDialog(this, new DatePickerDialog.OnDateSetListener() {
            @Override public void onDateSet(DatePicker view, int y, int m, int day) {
                Calendar k = Calendar.getInstance();
                k.set(y, m, day, 23, 59, 0);
                chosen[0] = k.getTimeInMillis();
                for (Ui.Choice x : all) x.setChecked(x == ch);
                ch.subView.setText(dateText(chosen[0]));
                if (ch.subView.getParent() == null) ((LinearLayout) ch.titleView.getParent()).addView(ch.subView);
            }
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        d.getDatePicker().setMinDate(System.currentTimeMillis());
        d.show();
    }

    private static long endOfDayIn(int days) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.DAY_OF_YEAR, days);
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 0);
        return c.getTimeInMillis();
    }

    private static String dateText(long t) { return new SimpleDateFormat("EEE d MMM yyyy", Locale.UK).format(new Date(t)); }

    // ------------------------------------------------------------------ step 3: the one permission

    /** Puts the one setting back as it was. Switching it on again means a trip to Accessibility settings (the "Switch on" button). */
    private void switchOff() {
        if (!ReelWatcherService.switchOff()) { openAccessibilitySettings(); return; } // it isn't running: show where the switch is
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { if ("dashboard".equals(screen)) showDashboard(); }
        }, 700); // give Android a moment to update the setting
    }

    /** Where the app was installed from (API 30+, kept in its own class so older phones never load it). */
    private static final class Api30 {
        static String installer(MainActivity a) {
            try { return a.getPackageManager().getInstallSourceInfo(a.getPackageName()).getInstallingPackageName(); }
            catch (Exception e) { return null; }
        }
    }

    /**
     * Android 13+ greys out the accessibility switch ("restricted settings") for an app that didn't come from a
     * store, so a file install from GitHub hits it. True when that may happen here.
     */
    private boolean maybeRestricted() {
        return Build.VERSION.SDK_INT >= 33 && !"com.android.vending".equals(Api30.installer(this));
    }

    private boolean accessibilityOn() {
        if (ReelWatcherService.isRunning()) return true;
        String on = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        ComponentName me = new ComponentName(this, ReelWatcherService.class);
        return on.contains(me.flattenToString()) || on.contains(me.flattenToShortString());
    }

    private void showPermission() {
        LinearLayout col = page("permission", new Runnable() {
            @Override public void run() { if (permissionFromDashboard) { permissionFromDashboard = false; showDashboard(); } else showExam(); }
        });
        if (!permissionFromDashboard) { if (quick) Ui.add(col, Ui.label(this, "One last step"), 0); else steps(col, 3); }
        Ui.add(col, Ui.title(this, "Switch it on"), permissionFromDashboard ? 0 : 18);
        Ui.add(col, Ui.body(this, "Reelcards needs to notice when you swipe to a new reel, and draw a card on top. Android calls this an accessibility service. It's the only permission we ask for."), 8);
        if (quick && !permissionFromDashboard) Ui.add(col, Ui.muted(this, "We picked GCSE Biology and Chemistry, no exam date and “Normal” cards for you. You can change all of that on the dashboard afterwards."), 8);
        final boolean stuck = blockedHint; // came back from Settings and it isn't on: the likely reason goes first
        blockedHint = false;
        final boolean fromFile = !stuck && maybeRestricted();
        if (stuck) Ui.add(col, restrictedPanel("It didn't switch on", true), 16);

        // Google Play's prominent disclosure: what the accessibility service sees, and what's done with it,
        // shown before Android's settings, with the student agreeing by tapping a button
        LinearLayout promise = Ui.panel(this);
        promise.addView(Ui.text(this, "What Reelcards sees, and what it does with it", 15, Ui.TEXT, Ui.DISPLAY));
        for (String[] line : DISCLOSURE) {
            LinearLayout r = Ui.column(this);
            r.addView(Ui.text(this, line[0], 14, Ui.TEXT, Ui.MEDIUM));
            r.addView(Ui.text(this, line[1], 14, Ui.MUTED, Ui.BODY));
            Ui.add(promise, r, 10);
        }
        TextView policy = Ui.linkButton(this, "Read the privacy policy");
        policy.setOnClickListener(go(new Runnable() { @Override public void run() { openPrivacyPolicy(); } }));
        Ui.add(promise, policy, 6);
        Ui.add(col, promise, 16);

        Ui.add(col, Ui.muted(this, "Tapping “Agree” means you're happy for Reelcards to use the accessibility service as described above. You can switch it off any time in Settings, or with “Switch Reelcards off” on the dashboard."), 18);
        if (fromFile) Ui.add(col, Ui.muted(this, "Installed from a file? Android may grey the switch out. The steps for that are just below."), 8);
        primary(col, "Agree and open settings", go(new Runnable() {
            @Override public void run() {
                Store.prefs(MainActivity.this).edit().putLong("disclosure_agreed", System.currentTimeMillis()).apply();
                awaitingSwitch = true;
                openAccessibilitySettings();
            }
        }), 10);
        TextView no = Ui.linkButton(this, "No thanks");
        no.setOnClickListener(go(new Runnable() { @Override public void run() { if (back != null) back.run(); } }));
        Ui.add(col, no, 4);

        Ui.add(col, Ui.label(this, "What happens next"), 20);
        String[] how = {"Android opens Accessibility settings", "Find Reelcards (often under “Installed apps” or “Downloaded apps”) and tap it", "Turn it on, then tap Allow", "Come back here. We'll notice"};
        numberedSteps(col, how);
        if (fromFile) Ui.add(col, restrictedPanel("Installed from a file? Read this first", false), 16);
        else if (!stuck) Ui.add(col, restrictedPanel("Switch greyed out?", false), 16);

        TextView skip = Ui.linkButton(this, "Not now: try the practice feed instead");
        skip.setOnClickListener(go(new Runnable() { @Override public void run() { startActivity(new Intent(MainActivity.this, PracticeFeedActivity.class)); } }));
        Ui.add(col, skip, 8);
        if (!permissionFromDashboard) {
            TextView later = Ui.linkButton(this, "Finish setup without it");
            later.setOnClickListener(go(new Runnable() { @Override public void run() { finishSetup(); } }));
            Ui.add(col, later, 0);
        }
    }

    /** Numbered rows (a number in a coloured circle, then the words). */
    private void numberedSteps(LinearLayout into, String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            LinearLayout r = Ui.row(this);
            TextView num = Ui.text(this, String.valueOf(i + 1), 14, Ui.ON_ACCENT, Ui.DISPLAY);
            num.setGravity(Gravity.CENTER);
            num.setBackground(Ui.round(Ui.ACCENT, dp(13)));
            r.addView(num, new LinearLayout.LayoutParams(dp(26), dp(26)));
            TextView t = Ui.body(this, lines[i]);
            LinearLayout.LayoutParams tl = Ui.weight(1);
            tl.leftMargin = dp(12);
            r.addView(t, tl);
            Ui.add(into, r, 10);
        }
    }

    /**
     * What to do when Android blocks the switch for a file install ("restricted settings", Android 13+). The
     * "Allow restricted settings" entry can be missing until the switch has been tried once, so that comes first.
     * @param agreed true once they've tapped "Agree and open settings": only then does this offer its own way into
     *               Accessibility. Google Play needs the disclosure and that tap before Android's settings open.
     */
    private LinearLayout restrictedPanel(String title, boolean agreed) {
        LinearLayout help = Ui.panel(this);
        help.addView(Ui.text(this, title, 15, Ui.TEXT, Ui.DISPLAY));
        Ui.add(help, Ui.text(this, "Android 13 and newer block this for apps installed from a file instead of the Play Store. You only need to do this once:", 14, Ui.MUTED, Ui.BODY), 6);
        numberedSteps(help, new String[] {agreed ? "Open Accessibility and tap Reelcards. If a “Restricted setting” box appears, tap OK."
                        : "Tap “Agree and open settings”, then tap Reelcards. If a “Restricted setting” box appears, tap OK.",
                "Come back here and tap Open app info.", "Tap ⋮ (top right), choose Allow restricted settings, and confirm.",
                "Open Accessibility again and switch Reelcards on."});
        Button info = Ui.button(this, "Open app info", false);
        info.setOnClickListener(go(new Runnable() {
            @Override public void run() {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
            }
        }));
        Ui.add(help, info, 10);
        if (agreed) {
            Button acc = Ui.button(this, "Open Accessibility", false);
            acc.setOnClickListener(go(new Runnable() { @Override public void run() { awaitingSwitch = true; openAccessibilitySettings(); } }));
            Ui.add(help, acc, 8);
        }
        return help;
    }

    /** The prominent disclosure for Google Play: what the accessibility service sees, uses, keeps and never does. */
    static final String[][] DISCLOSURE = {
        {"What it sees", "Which app is open, and when a list scrolls in TikTok, Instagram, YouTube, Snapchat, Facebook, X, Reddit or Pinterest: the scrolling view's size, position and internal name (like “reel_viewer”)."},
        {"What it does with it", "Notices when you swipe to a new reel, shows one of your flashcards on top, and takes it down when you leave the app."},
        {"What it keeps", "Counts on your phone, like how many reels you swipe and the seconds between swipes, so it learns when to show a card. Reelcards has no internet access, so nothing is sent anywhere or shared."},
        {"What it never does", "Read your messages, comments, captions or anything else on screen, or record what you watch or who posted it."},
        {"Only if you turn it on", "“Match my feed's colours” takes one screenshot per reel, shrinks it to a single colour in memory and deletes it straight away."},
    };

    static final String PRIVACY_URL = "https://llamaoat.github.io/flashcardreela/privacy.html";

    /** The hosted privacy policy, in the browser (Reelcards itself has no internet access). */
    private void openPrivacyPolicy() {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL))); }
        catch (Exception e) { /* no browser: the disclosure above says the same */ }
    }

    private void openAccessibilitySettings() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        // On many phones these extras scroll to and highlight our entry.
        String component = new ComponentName(this, ReelWatcherService.class).flattenToString();
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", component);
        i.putExtra(":settings:fragment_args_key", component);
        i.putExtra(":settings:show_fragment_args", args);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { startActivity(i); }
        catch (Exception e) { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); }
    }

    // ------------------------------------------------------------------ step 4: try it

    private boolean autoCardDone; // "Get started" shows one test card by itself, the first time the switch is on

    /** Tries a few times: the service can take a moment to connect after the switch goes on. */
    private void autoTestCard(final int tries) {
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                if (!"try".equals(screen)) return;
                if (!ReelWatcherService.showTestCard() && tries < 6) autoTestCard(tries + 1);
            }
        }, tries == 0 ? 700 : 400);
    }

    private void showTry() {
        if (permissionFromDashboard) { permissionFromDashboard = false; showDashboard(); return; }
        if (quick && !autoCardDone) { autoCardDone = true; autoTestCard(0); }
        LinearLayout col = page("try", new Runnable() { @Override public void run() { showExam(); } });
        if (quick) Ui.add(col, Ui.label(this, "All set"), 0); else steps(col, 4);
        Ui.add(col, Ui.title(this, "It's on. Try it"), 18);
        Ui.add(col, Ui.body(this, "Tap below for a test card right here. Then open your usual app and scroll: after a short warm-up, a card slips in every " + engine.settings.baseGap() + " reels."), 8);
        primary(col, "Show me a test card", go(new Runnable() {
            @Override public void run() {
                if (!ReelWatcherService.showTestCard()) startActivity(new Intent(MainActivity.this, PracticeFeedActivity.class));
            }
        }), 18);
        String[][] apps = {{"com.zhiliaoapp.musically", "Open TikTok"}, {"com.ss.android.ugc.trill", "Open TikTok"},
                {"com.instagram.android", "Open Instagram Reels"}, {"com.google.android.youtube", "Open YouTube Shorts"}};
        boolean tiktokShown = false;
        for (final String[] a : apps) {
            final Intent launch = getPackageManager().getLaunchIntentForPackage(a[0]);
            if (launch == null) continue;
            if (a[1].equals("Open TikTok")) { if (tiktokShown) continue; tiktokShown = true; }
            secondary(col, a[1], go(new Runnable() { @Override public void run() { startActivity(launch); } }), 10);
        }
        secondary(col, "Practice feed (no other app needed)", go(new Runnable() {
            @Override public void run() { startActivity(new Intent(MainActivity.this, PracticeFeedActivity.class)); }
        }), 10);
        primary(col, "Done", go(new Runnable() { @Override public void run() { finishSetup(); } }), 22);
    }

    private void finishSetup() {
        Store.prefs(this).edit().putBoolean("onboarded", true).apply();
        quick = false;
        showDashboard();
    }

    // ------------------------------------------------------------------ dashboard

    private void showDashboard() {
        addMode = false;
        final long now = System.currentTimeMillis();
        boolean sea = !engine.cards.isEmpty();
        LinearLayout col = page("dashboard", null, sea ? 2 : 0, sea ? dashWords(now) : null);
        LinearLayout head = Ui.row(this);
        head.addView(Ui.text(this, "Reelcards", 15, sea ? 0xFFFFFFFF : Ui.TEXT, Ui.DISPLAY), Ui.weight(1));
        Ui.add(col, head, 0);
        if (sea) col.addView(new View(this), new LinearLayout.LayoutParams(-1, dp(190))); // the sea and its wheel show through here
        if (restoreNote != null) { // after a damaged save (Store.engine), until the app is next opened
            TextView n = Ui.text(this, restoreNote, 14, Ui.TEXT, Ui.BODY);
            n.setBackground(Ui.outline(Ui.WARN_BG, Ui.WARN, dp(1), dp(14)));
            n.setPadding(dp(14), dp(12), dp(14), dp(12));
            Ui.add(col, n, 12);
        }
        // pictures missing (a new or restored phone: pictures aren't in Android's backup): say so, offer the fix
        Engine eng = Store.engine(this);
        if (Store.checkMedia(this, eng) > 0) {
            java.util.Set<String> decks = new java.util.LinkedHashSet<>();
            for (Card c : eng.cards) if (eng.hidden.contains(c.id)) decks.add(c.deck);
            LinearLayout mp = Ui.panel(this);
            mp.setBackground(Ui.outline(Ui.WARN_BG, Ui.WARN, dp(1), dp(14)));
            mp.addView(Ui.text(this, "Pictures missing for " + eng.hidden.size() + (eng.hidden.size() == 1 ? " card" : " cards"), 15, Ui.TEXT, Ui.MEDIUM));
            Ui.add(mp, Ui.muted(this, "From " + android.text.TextUtils.join(", ", decks) + ". This happens after moving to a new phone: Android doesn't back up "
                    + "pictures. Open the same Anki file again to bring them back. Until then, those cards are kept out of your feed."), 4);
            Button again = Ui.button(this, "Open the Anki file", true);
            again.setOnClickListener(go(new Runnable() { @Override public void run() { pickApkg(); } }));
            Ui.add(mp, again, 10);
            Ui.add(col, mp, 12);
        }

        // status
        boolean on = accessibilityOn();
        boolean paused = engine.settings.pausedUntil > now;
        LinearLayout status = Ui.panel(this);
        if (!on) {
            status.setBackground(Ui.outline(Ui.BAD_BG, Ui.BAD, dp(1), dp(16)));
            status.addView(Ui.text(this, "Cards are off", 17, Ui.TEXT, Ui.DISPLAY));
            Ui.add(status, Ui.text(this, "Switch on Reelcards in Accessibility settings so cards can appear between reels.", 14, Ui.MUTED, Ui.BODY), 4);
            Button fix = Ui.button(this, "Switch on", true);
            fix.setOnClickListener(go(new Runnable() { @Override public void run() { permissionFromDashboard = true; showPermission(); } }));
            Ui.add(status, fix, 12);
        } else if (paused) {
            status.addView(Ui.text(this, "Paused until " + new SimpleDateFormat("HH:mm", Locale.UK).format(new Date(engine.settings.pausedUntil)), 17, Ui.TEXT, Ui.DISPLAY));
            Button resume = Ui.button(this, "Resume now", true);
            resume.setOnClickListener(go(new Runnable() { @Override public void run() { engine.settings.pausedUntil = 0; Store.saveNow(MainActivity.this); showDashboard(); } }));
            Ui.add(status, resume, 12);
        } else {
            status.setBackground(Ui.outline(Ui.GOOD_BG, Ui.GOOD, dp(1), dp(16)));
            status.addView(Ui.text(this, "On", 17, Ui.GOOD, Ui.DISPLAY));
            String quiet = engine.snoozeUntil > now ? " Quiet mode for a few minutes after lots of fast skips." : "";
            Ui.add(status, Ui.text(this, "Cards appear in TikTok, Instagram, YouTube Shorts, Snapchat, Facebook, X, Reddit and Pinterest. " + Settings_label() + "." + quiet, 14, Ui.MUTED, Ui.BODY), 4);
        }
        if (on) {
            TextView off = Ui.linkButton(this, "Switch Reelcards off");
            off.setOnClickListener(go(new Runnable() { @Override public void run() { switchOff(); } }));
            Ui.add(status, off, 4);
            Ui.add(status, Ui.muted(this, "Reelcards only uses this one Android setting. Switching it off stops it completely, and uninstalling removes it."), 0);
        }
        Ui.add(col, status, 16);

        // today
        long today = engine.dayKey(now);
        Stats.Day d = engine.stats.day(today);
        double recall = engine.stats.recallRate(today, 7);
        Ui.add(col, Ui.label(this, "Today"), 22);
        LinearLayout r1 = Ui.row(this), r2 = Ui.row(this);
        tile(r1, String.valueOf(d.reviews), "cards done", true);
        tile(r1, String.valueOf(d.skips), "skipped", false);
        tile(r2, recall < 0 ? "–" : Math.round(recall * 100) + "%", "recall, last 7 days", true);
        tile(r2, String.valueOf(engine.stats.streak(today)), "day streak", false);
        Ui.add(col, r1, 8);
        Ui.add(col, r2, 8);

        // forecast
        Stats.Forecast f = engine.stats.forecast(engine, now);
        LinearLayout fp = Ui.panel(this);
        fp.addView(Ui.label(this, "Forecast"));
        StringBuilder fs = new StringBuilder();
        fs.append("At ").append(f.minutesPerDay).append(" min of reels a day").append(f.measured ? " (your average)" : " (a guess until we've measured a few days)")
          .append(", you'll see about ").append(f.cardsPerDay).append(" cards a day").append(f.capped ? " (your daily limit)" : "")
          .append(": about ").append(f.minutesOnCards).append(" min of revision you barely notice.");
        if (f.newLeft > 0) {
            fs.append("\n\n").append(f.newLeft).append(" cards not seen yet. At about ").append(f.newPerDay).append(" new a day, you'll have seen them all in ")
              .append(f.daysToCover).append(f.daysToCover == 1 ? " day." : " days.");
            if (f.examDaysLeft > 0) {
                if (f.onTrack) fs.append(" That's before your exam in ").append(f.examDaysLeft).append(" days.");
                else fs.append(" Your exam is in ").append(f.examDaysLeft).append(" days, so try Cram mode or about ").append(f.minutesNeeded).append(" min a day.");
            }
        } else if (!engine.cards.isEmpty()) fs.append("\n\nYou've seen every card. Now it's all reviews.");
        TextView ft = Ui.body(this, fs.toString());
        Ui.add(fp, ft, 6);
        Ui.add(col, fp, 12);

        // chart
        LinearLayout cp = Ui.panel(this);
        cp.addView(Ui.label(this, "Last 14 days · dark = reviewed, light = skipped"));
        BarChartView chart = new BarChartView(this);
        int n = 14;
        int[] rev = new int[n], sk = new int[n];
        String[] lab = new String[n];
        SimpleDateFormat dd = new SimpleDateFormat("d", Locale.UK);
        for (int i = 0; i < n; i++) {
            long k = today - (n - 1 - i);
            Stats.Day x = engine.stats.days.get(k);
            rev[i] = x == null ? 0 : x.reviews;
            sk[i] = x == null ? 0 : x.skips;
            lab[i] = dd.format(new Date(now - (n - 1 - i) * Engine.DAY));
        }
        chart.setData(rev, sk, lab);
        LinearLayout.LayoutParams chl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(150));
        chl.topMargin = dp(10);
        cp.addView(chart, chl);
        Ui.add(col, cp, 12);

        // decks
        LinearLayout dp_ = Ui.panel(this);
        dp_.addView(Ui.label(this, "Your cards"));
        for (final String deck : engine.decks()) {
            int nw = 0, learning = 0, learned = 0, due = 0;
            for (Card c : engine.cards) {
                if (!deck.equals(c.deck)) continue;
                if (c.state == Card.NEW) nw++;
                else if (c.state == Card.REVIEW) learned++;
                else learning++;
                if (c.state != Card.NEW && c.due <= now) due++;
            }
            LinearLayout row = Ui.row(this);
            LinearLayout texts = Ui.column(this);
            texts.addView(Ui.text(this, deck, 16, Ui.TEXT, Ui.MEDIUM));
            texts.addView(Ui.muted(this, learned + " learned · " + learning + " learning · " + nw + " new · " + due + " due now"));
            row.addView(texts, Ui.weight(1));
            final TextView rm = Ui.text(this, "Remove", 13, Ui.MUTED, Ui.MEDIUM);
            rm.setPadding(dp(10), dp(12), 0, dp(12));
            rm.setOnClickListener(new View.OnClickListener() {
                boolean armed;
                @Override public void onClick(View v) {
                    if (!armed) { armed = true; rm.setText("Tap to confirm"); rm.setTextColor(Ui.BAD); return; }
                    engine.removeDeck(deck);
                    AnkiBridge.unselect(MainActivity.this, deck); // an AnkiDroid deck would otherwise come back on the next refill
                    Store.saveNow(MainActivity.this);
                    Store.cleanMedia(MainActivity.this, null); // its pictures go too
                    showDashboard();
                }
            });
            row.addView(rm);
            Ui.add(dp_, row, 10);
        }
        String ankiNames = AnkiBridge.selectedNames(this);
        if (!ankiNames.isEmpty()) Ui.add(dp_, Ui.muted(this, "Anki decks: " + ankiNames + ". Answers go straight back to AnkiDroid."), 10);
        for (String deck : AnkiBridge.pictureDecks(this)) {
            if (AnkiBridge.deckId(this, deck) < 0) continue;
            Ui.add(dp_, Ui.muted(this, "Some “" + deck + "” cards need a picture, and AnkiDroid doesn't share pictures with other apps, so those stay in AnkiDroid. "
                    + "To get them here, export the deck from Anki as a .apkg and open it with Add cards."), 6);
        }
        if (engine.cards.isEmpty() && ankiNames.isEmpty()) Ui.add(dp_, Ui.muted(this, "No cards yet."), 8);
        Button add = Ui.button(this, "Add cards", false);
        add.setOnClickListener(go(new Runnable() { @Override public void run() { addMode = true; showDecks(); } }));
        Ui.add(dp_, add, 12);
        Ui.add(col, dp_, 12);

        // saved (bookmarked on a card reel)
        java.util.List<Card> saved = engine.savedCards();
        if (!saved.isEmpty()) {
            LinearLayout svp = Ui.panel(this);
            svp.addView(Ui.label(this, "Saved · " + saved.size()));
            Ui.add(svp, Ui.muted(this, "Cards you bookmarked. Each one comes back within a day."), 4);
            for (int i = 0; i < Math.min(8, saved.size()); i++) {
                Card c = saved.get(i);
                LinearLayout item = Ui.column(this);
                item.addView(Ui.text(this, c.front, 15, Ui.TEXT, Ui.MEDIUM));
                item.addView(Ui.muted(this, c.back + " · " + c.deck));
                Ui.add(svp, item, 10);
            }
            if (saved.size() > 8) Ui.add(svp, Ui.muted(this, "and " + (saved.size() - 8) + " more"), 8);
            Ui.add(col, svp, 12);
        }

        // settings
        LinearLayout sp = Ui.panel(this);
        sp.addView(Ui.label(this, "How often"));
        final String[] modes = {com.reelcards.engine.Settings.CHILL, com.reelcards.engine.Settings.NORMAL, com.reelcards.engine.Settings.CRAM, com.reelcards.engine.Settings.DEMO};
        LinearLayout seg = Ui.row(this);
        for (int i = 0; i < modes.length; i++) {
            final String m = modes[i];
            boolean sel = m.equals(engine.settings.intensity);
            TextView t = Ui.text(this, cap(m), 14, sel ? Ui.ON_ACCENT : Ui.TEXT, sel ? Ui.DISPLAY : Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(44));
            t.setBackground(sel ? Ui.round(Ui.ACCENT, dp(10)) : Ui.outline(Ui.SURFACE2, Ui.LINE, dp(1), dp(10)));
            t.setOnClickListener(go(new Runnable() { @Override public void run() { if (!m.equals(engine.settings.intensity)) { engine.rhythm.seeded = false; engine.rhythmArmForNext = -1; } engine.settings.intensity = m; Store.saveNow(MainActivity.this); showDashboard(); } }));
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i > 0) lp.leftMargin = dp(6);
            seg.addView(t, lp);
        }
        Ui.add(sp, seg, 8);
        Ui.add(sp, Ui.muted(this, com.reelcards.engine.Settings.intensityLabel(engine.settings.intensity) + (engine.settings.isDemo() ? ". For showing people how it works." : "")), 6);

        LinearLayout examRow = Ui.row(this);
        int ex = engine.examDaysLeft(now);
        examRow.addView(Ui.body(this, engine.settings.examDate <= 0 ? "No exam date set" : "Exam " + dateText(engine.settings.examDate) + (ex >= 0 ? " · " + ex + " days" : "")), Ui.weight(1));
        TextView change = Ui.text(this, "Change", 14, Ui.ACCENT, Ui.MEDIUM);
        change.setPadding(dp(10), dp(12), 0, dp(12));
        change.setOnClickListener(go(new Runnable() { @Override public void run() { examFromDashboard = true; showExam(); } }));
        examRow.addView(change);
        Ui.add(sp, examRow, 12);

        LinearLayout vibeRow2 = Ui.row(this);
        StringBuilder vs = new StringBuilder();
        for (String v : engine.settings.vibes) { if (vs.length() > 0) vs.append(", "); vs.append(Format.label(v)); }
        vibeRow2.addView(Ui.body(this, vs.length() == 0 ? "Themes: none picked" : "Themes: " + vs), Ui.weight(1));
        TextView changeV = Ui.text(this, "Change", 14, Ui.ACCENT, Ui.MEDIUM);
        changeV.setPadding(dp(10), dp(12), 0, dp(12));
        changeV.setOnClickListener(go(new Runnable() { @Override public void run() { examFromDashboard = true; showExam(); } }));
        vibeRow2.addView(changeV);
        Ui.add(sp, vibeRow2, 4);

        final Ui.Choice voice = new Ui.Choice(this, "Voiceover", "Reads the hook and question aloud, like creators do");
        voice.setChecked(engine.settings.voiceover);
        voice.setOnClickListener(go(new Runnable() {
            @Override public void run() { engine.settings.voiceover = !engine.settings.voiceover; voice.setChecked(engine.settings.voiceover); Store.saveNow(MainActivity.this); }
        }));
        Ui.add(sp, voice, 10);

        boolean canMatch = android.os.Build.VERSION.SDK_INT >= 30;
        final Ui.Choice match = new Ui.Choice(this, "Match my feed's colours",
                canMatch ? "Tints dark card styles to the reel you just watched. Uses one screenshot per card, shrunk to a single colour on your phone, never saved or sent"
                         : "Needs Android 11 or newer");
        match.setChecked(canMatch && engine.settings.matchColours);
        match.setEnabled(canMatch);
        if (!canMatch) match.setAlpha(0.5f);
        match.setOnClickListener(go(new Runnable() {
            @Override public void run() { engine.settings.matchColours = !engine.settings.matchColours; match.setChecked(engine.settings.matchColours); Store.saveNow(MainActivity.this); }
        }));
        Ui.add(sp, match, 8);

        Ui.add(sp, Ui.muted(this, "Night mode: light card styles turn dark"), 12);
        final String[] nights = {"auto", "on", "off"};
        final String[] nightLabels = {"10pm–6am", "Always", "Never"};
        LinearLayout nseg = Ui.row(this);
        for (int i = 0; i < nights.length; i++) {
            final String m = nights[i];
            boolean sel = m.equals(engine.settings.night);
            TextView t = Ui.text(this, nightLabels[i], 14, sel ? Ui.ON_ACCENT : Ui.TEXT, sel ? Ui.DISPLAY : Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setMinHeight(dp(44));
            t.setBackground(sel ? Ui.round(Ui.ACCENT, dp(10)) : Ui.outline(Ui.SURFACE2, Ui.LINE, dp(1), dp(10)));
            t.setOnClickListener(go(new Runnable() { @Override public void run() { engine.settings.night = m; Store.saveNow(MainActivity.this); showDashboard(); } }));
            LinearLayout.LayoutParams lp = Ui.weight(1);
            if (i > 0) lp.leftMargin = dp(6);
            nseg.addView(t, lp);
        }
        Ui.add(sp, nseg, 6);

        if (!paused) {
            secondary(sp, "Pause cards for 1 hour", go(new Runnable() {
                @Override public void run() { engine.settings.pausedUntil = System.currentTimeMillis() + 60 * Engine.MIN; Store.saveNow(MainActivity.this); showDashboard(); }
            }), 10);
        }
        Ui.add(col, sp, 12);

        // what the engine learned
        LinearLayout ep = Ui.panel(this);
        ep.addView(Ui.label(this, "What the engine has learned about you"));
        boolean any = false;
        for (String dim : Format.DIMS) {
            String fav = engine.bandit.favourite(dim);
            if (fav == null) continue;
            any = true;
            Ui.add(ep, Ui.body(this, Format.dimLabel(dim) + ": " + Format.label(fav) + String.format(Locale.UK, "  (kept watching %d%% of the time)", Math.round(engine.bandit.mean(dim, fav) * 100))), 6);
        }
        if (!any) Ui.add(ep, Ui.muted(this, "Nothing yet. After a few cards it learns which looks you actually watch."), 6);
        Ui.add(ep, Ui.body(this, engine.rhythmDescription()), 10);
        String calib = engine.calibration.describe();
        Ui.add(ep, Ui.body(this, calib != null ? calib : "Memory: after about " + com.reelcards.engine.Calibration.MIN_REVIEWS + " reviews spread over days, it learns how fast you forget and adjusts every interval."), 8);
        java.util.List<Card> leeches = engine.leeches();
        if (!leeches.isEmpty()) {
            StringBuilder lb = new StringBuilder("Tricky cards (forgotten 6+ times). Try rewriting them shorter or splitting them up:");
            for (int i = 0; i < Math.min(5, leeches.size()); i++) lb.append("\n• ").append(Engine.shorten(leeches.get(i).front, 60));
            Ui.add(ep, Ui.text(this, lb.toString(), 14, Ui.WARN, Ui.BODY), 10);
        }
        StringBuilder sk2 = new StringBuilder();
        for (String l : Format.LAYOUTS) {
            int[] v = engine.stats.layout(l);
            if (v[0] == 0) continue;
            if (sk2.length() > 0) sk2.append(" · ");
            sk2.append(Format.label(l)).append(" ").append(Math.round(100.0 * v[1] / v[0])).append("% skipped");
        }
        if (sk2.length() > 0) Ui.add(ep, Ui.muted(this, sk2.toString()), 8);
        if (!engine.log.isEmpty()) {
            Ui.add(ep, Ui.label(this, "Latest decisions"), 14);
            int shown = 0;
            for (String line : engine.log) {
                if (shown++ >= 6) break;
                Ui.add(ep, Ui.text(this, line, 12, Ui.MUTED, Ui.MONO), 4);
            }
        }
        Ui.add(col, ep, 12);

        // tools
        Ui.add(col, Ui.label(this, "Try and tune"), 22);
        secondary(col, "Show a test card", go(new Runnable() {
            @Override public void run() { if (!ReelWatcherService.showTestCard()) startActivity(new Intent(MainActivity.this, PracticeFeedActivity.class)); }
        }), 8);
        secondary(col, "Practice feed", go(new Runnable() { @Override public void run() { startActivity(new Intent(MainActivity.this, PracticeFeedActivity.class)); } }), 8);
        secondary(col, "Reel detection log", go(new Runnable() { @Override public void run() { showLog(); } }), 8);
        secondary(col, "Share my numbers (for a study)", go(new Runnable() { @Override public void run() { showPilot(); } }), 8);
        secondary(col, "Privacy policy", go(new Runnable() { @Override public void run() { openPrivacyPolicy(); } }), 8);
        final Button reset = Ui.button(this, confirmReset ? "Tap again to erase everything" : "Reset everything", false);
        if (confirmReset) reset.setTextColor(Ui.BAD);
        reset.setOnClickListener(go(new Runnable() {
            @Override public void run() {
                if (!confirmReset) { confirmReset = true; showDashboard(); return; }
                confirmReset = false;
                Store.reset(MainActivity.this);
                engine = Store.engine(MainActivity.this);
                showWelcome();
            }
        }));
        Ui.add(col, reset, 8);
    }

    private String Settings_label() { return com.reelcards.engine.Settings.intensityLabel(engine.settings.intensity); }

    private void tile(LinearLayout row, String big, String small, boolean first) {
        LinearLayout t = Ui.panel(this);
        t.addView(Ui.text(this, big, 26, Ui.TEXT, Ui.DISPLAY));
        t.addView(Ui.muted(this, small));
        LinearLayout.LayoutParams lp = Ui.weight(1);
        if (!first) lp.leftMargin = dp(8);
        row.addView(t, lp);
    }

    // ------------------------------------------------------------------ pilot study (docs/PILOT.md)

    /** Shows exactly what would be shared (counts only), and shares it only when the student taps Share. */
    private void showPilot() {
        LinearLayout col = page("pilot", new Runnable() { @Override public void run() { showDashboard(); } });
        Ui.add(col, Ui.title(this, "Share your numbers"), 0);
        Ui.add(col, Ui.body(this, "For a study of how well Reelcards works. Below is everything that's shared: counts, like reels swiped and cards answered each day, "
                + "and how many cards you remembered. No cards, no deck or file names, no apps. It only goes where you send it."), 8);
        String json;
        try { json = PilotReport.build(this, engine, System.currentTimeMillis()).toString(2); }
        catch (Exception e) { json = null; }
        if (json == null) { Ui.add(col, Ui.text(this, "Couldn't put the numbers together.", 14, Ui.BAD, Ui.MEDIUM), 10); return; }
        final String report = json;
        TextView box = Ui.text(this, report, 12, Ui.MUTED, Ui.MONO);
        box.setBackground(Ui.round(Ui.SURFACE, dp(12)));
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setTextIsSelectable(true);
        Ui.add(col, box, 12);
        primary(col, "Share", go(new Runnable() {
            @Override public void run() {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_SUBJECT, "Reelcards study: " + PilotReport.studentCode(MainActivity.this))
                        .putExtra(Intent.EXTRA_TEXT, report);
                try { startActivity(Intent.createChooser(send, "Share your numbers")); } catch (Exception ignored) { }
            }
        }), 14);
        Ui.add(col, Ui.muted(this, "Your code, " + PilotReport.studentCode(this) + ", is random. It tells reports apart without saying who you are."), 8);
    }

    // ------------------------------------------------------------------ detection log

    private void showLog() {
        LinearLayout col = page("log", new Runnable() { @Override public void run() { showDashboard(); } });
        Ui.add(col, Ui.title(this, "Reel detection log"), 0);
        Ui.add(col, Ui.body(this, "What the watcher saw. Scroll in TikTok, Instagram or YouTube, come back, and check each swipe shows as REEL SWIPE. If an app update breaks detection, the view names here tell you what to add to ReelDetector.java."), 8);
        Ui.add(col, Ui.muted(this, ReelWatcherService.isRunning() ? "Watcher is running · " + ReelDetector.countedTotal + " swipes counted since it started" : "Watcher is off"), 10);
        secondary(col, "Refresh", go(new Runnable() { @Override public void run() { showLog(); } }), 10);
        List<String> lines;
        synchronized (ReelDetector.LOG) { lines = new ArrayList<>(ReelDetector.LOG); }
        if (lines.isEmpty()) Ui.add(col, Ui.muted(this, "Nothing yet."), 14);
        for (String l : lines) {
            TextView t = Ui.text(this, l, 12, l.contains("REEL SWIPE") ? Ui.ACCENT : Ui.MUTED, Ui.MONO);
            t.setBackground(Ui.round(Ui.SURFACE, dp(8)));
            t.setPadding(dp(10), dp(6), dp(10), dp(6));
            Ui.add(col, t, 6);
        }
    }

    private static String cap(String s) { return TextUtils.isEmpty(s) ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
}
