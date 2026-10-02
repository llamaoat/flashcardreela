package com.reelcards.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.util.Log;

import com.reelcards.engine.Card;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Grade;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to AnkiDroid through its official content provider API (FlashCardsContract).
 *  - reads which cards Anki has scheduled right now for the chosen decks
 *  - sends each reel grade back as an Anki answer (ease 1 = Again, 3 = Good, 4 = Easy)
 * Anki stays in charge of the schedule for these cards, and its normal sync takes them to
 * AnkiWeb / desktop. All calls run off the main thread and fail quietly if Anki isn't reachable.
 */
final class AnkiBridge implements Engine.GradeSink {
    static final String TAG = "ReelcardsAnki";
    static final String PACKAGE = "com.ichi2.anki";
    static final String AUTHORITY = "com.ichi2.anki.flashcards";
    static final String PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    static final Uri DECKS = Uri.parse("content://" + AUTHORITY + "/decks");
    static final Uri SCHEDULE = Uri.parse("content://" + AUTHORITY + "/schedule");
    static final Uri NOTES = Uri.parse("content://" + AUTHORITY + "/notes");

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static long lastRefill;

    private final Context ctx;

    AnkiBridge(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    static boolean hasPermission(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    static final class Deck {
        long id; String name; int learn, review, fresh;
        String counts() { return (learn + review) + " due · " + fresh + " new"; }
    }

    /** Lists AnkiDroid decks. Blocking: call from a background thread. */
    static List<Deck> decks(Context c) {
        List<Deck> out = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = c.getContentResolver().query(DECKS, new String[]{"deck_id", "deck_name", "deck_count"}, null, null, null);
            if (cur == null) return out;
            while (cur.moveToNext()) {
                Deck d = new Deck();
                d.id = cur.getLong(0);
                d.name = cur.getString(1);
                try {
                    JSONArray counts = new JSONArray(cur.getString(2));
                    d.learn = counts.optInt(0); d.review = counts.optInt(1); d.fresh = counts.optInt(2);
                } catch (Exception ignored) { }
                out.add(d);
            }
        } catch (Exception e) {
            Log.w(TAG, "decks failed", e);
        } finally { if (cur != null) cur.close(); }
        return out;
    }

    /** Selected decks, stored as "id|name;id|name". */
    static List<Deck> selected(Context c) {
        List<Deck> out = new ArrayList<>();
        String s = Store.prefs(c).getString("anki_decks", "");
        for (String part : s.split(";")) {
            int bar = part.indexOf('|');
            if (bar <= 0) continue;
            try {
                Deck d = new Deck();
                d.id = Long.parseLong(part.substring(0, bar));
                d.name = part.substring(bar + 1);
                out.add(d);
            } catch (NumberFormatException ignored) { }
        }
        return out;
    }

    static String selectedNames(Context c) {
        StringBuilder b = new StringBuilder();
        String s = Store.prefs(c).getString("anki_decks", "");
        for (String part : s.split(";")) {
            int bar = part.indexOf('|');
            if (bar <= 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(part.substring(bar + 1));
        }
        return b.toString();
    }

    static void saveSelection(Context c, List<Deck> decks) {
        StringBuilder b = new StringBuilder();
        for (Deck d : decks) {
            if (b.length() > 0) b.append(';');
            b.append(d.id).append('|').append(d.name.replace(";", ",").replace("|", "/"));
        }
        Store.prefs(c).edit().putString("anki_decks", b.toString()).apply();
    }

    /**
     * Tops up the engine with cards Anki has scheduled now. Throttled to once a minute unless forced.
     * Safe to call often (from every reel swipe).
     */
    static void refill(final Context c, final Engine engine, boolean force) {
        final List<Deck> ids = selected(c);
        if (ids.isEmpty() || !installed(c) || !hasPermission(c)) return;
        long now = System.currentTimeMillis();
        if (!force && now - lastRefill < 60_000) return;
        int waiting = 0;
        for (Card k : engine.cards) if (k.isAnki() && k.due <= now) waiting++;
        if (!force && waiting >= 5) return;
        lastRefill = now;
        IO.execute(new Runnable() {
            @Override public void run() {
                final List<Card> fetched = new ArrayList<>();
                ContentResolver cr = c.getContentResolver();
                for (Deck d : ids) fetched.addAll(fetchScheduled(cr, d.id, d.name, 10));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        long t = System.currentTimeMillis();
                        List<Card> add = new ArrayList<>();
                        for (Card f : fetched) {
                            Card existing = engine.find(f.id);
                            if (existing != null) { existing.due = t; existing.front = f.front; existing.back = f.back; }
                            else add.add(f);
                        }
                        engine.addCards(add);
                        Store.saveSoon(c);
                    }
                });
            }
        });
    }

    static List<Card> fetchScheduled(ContentResolver cr, long deckId, String deckName, int limit) {
        List<Card> out = new ArrayList<>();
        Cursor cur = null;
        try {
            cur = cr.query(SCHEDULE, null, "limit=?, deckID=?", new String[]{String.valueOf(limit), String.valueOf(deckId)}, null);
            if (cur == null) return out;
            int iNote = cur.getColumnIndex("note_id"), iOrd = cur.getColumnIndex("ord");
            while (cur.moveToNext()) {
                long noteId = cur.getLong(iNote);
                int ord = cur.getInt(iOrd);
                Card c = readCard(cr, noteId, ord, deckName);
                if (c != null) out.add(c);
            }
        } catch (Exception e) {
            Log.w(TAG, "schedule query failed", e);
        } finally { if (cur != null) cur.close(); }
        return out;
    }

    static Card readCard(ContentResolver cr, long noteId, int ord, String deckName) {
        Uri uri = NOTES.buildUpon().appendPath(String.valueOf(noteId)).appendPath("cards").appendPath(String.valueOf(ord)).build();
        Cursor cur = null;
        try {
            cur = cr.query(uri, null, null, null, null);
            if (cur == null || !cur.moveToFirst()) return null;
            String q = col(cur, "question_simple"), a = col(cur, "answer_pure");
            if (q == null) q = col(cur, "question");
            if (a == null) a = col(cur, "answer");
            String deck = deckName == null || deckName.isEmpty() ? "Anki" : deckName;
            String front = plain(q), back = plain(a);
            if (front.isEmpty() || back.isEmpty()) return null;
            Card c = new Card("anki:" + noteId + ":" + ord, deck, front, back, "");
            c.ankiNoteId = noteId;
            c.ankiOrd = ord;
            c.state = Card.REVIEW;
            c.due = System.currentTimeMillis();
            return c;
        } catch (Exception e) {
            Log.w(TAG, "card query failed", e);
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 ? null : c.getString(i);
    }

    /** Anki card HTML -> plain text that fits on a reel. */
    static String plain(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<style.*?</style>", " ")
                .replaceAll("(?is)<script.*?</script>", " ")
                .replaceAll("\\[sound:[^\\]]*\\]", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)<hr[^>]*>", "\n");
        String t = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString();
        t = t.replace(' ', ' ').replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
        if (t.length() > 900) t = t.substring(0, 899) + "…"; // long answers are split into points on the card
        return t;
    }

    // ------------------------------------------------------------------ grades back to Anki

    @Override
    public void onGrade(final Card c, final Grade g, final long timeTakenMs) {
        if (!c.isAnki() || !hasPermission(ctx)) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    ContentValues v = new ContentValues();
                    v.put("note_id", c.ankiNoteId);
                    v.put("ord", c.ankiOrd);
                    v.put("answer_ease", g.ease);
                    v.put("time_taken", timeTakenMs);
                    int n = ctx.getContentResolver().update(SCHEDULE, v, null, null);
                    Log.i(TAG, "answered " + c.id + " ease=" + g.ease + " rows=" + n);
                } catch (Exception e) {
                    Log.w(TAG, "answer failed", e);
                }
            }
        });
    }
}
