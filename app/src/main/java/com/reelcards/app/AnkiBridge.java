package com.reelcards.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.reelcards.engine.Card;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Grade;
import com.reelcards.engine.anki.AnkiPackage;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Talks to AnkiDroid through its official content provider API (FlashCardsContract).
 *  - reads which cards Anki has scheduled right now for the chosen decks, and each card's note and
 *    note type, so cards follow the same rules as an imported .apkg (cloze gaps become blanks)
 *  - sends each reel grade back as an Anki answer (ease 1 = Again, 3 = Good, 4 = Easy), unless the
 *    card was answered in AnkiDroid meanwhile
 * Anki stays in charge of the schedule for these cards, and its normal sync takes them to
 * AnkiWeb / desktop. All calls run off the main thread and fail quietly if Anki isn't reachable.
 * AnkiDroid doesn't let other apps read its pictures, so cards whose question needs one stay in
 * AnkiDroid, and the dashboard suggests exporting that deck as a .apkg instead.
 */
final class AnkiBridge implements Engine.GradeSink {
    static final String TAG = "ReelcardsAnki";
    static final String PACKAGE = "com.ichi2.anki";
    static final String AUTHORITY = "com.ichi2.anki.flashcards";
    static final String PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE";
    static final Uri DECKS = Uri.parse("content://" + AUTHORITY + "/decks");
    static final Uri SCHEDULE = Uri.parse("content://" + AUTHORITY + "/schedule");
    static final Uri NOTES = Uri.parse("content://" + AUTHORITY + "/notes");
    static final Uri MODELS = Uri.parse("content://" + AUTHORITY + "/models");

    static final int FETCH = 25;   // scheduled cards asked for per deck on a refill
    static final int KEEP = 10;    // ...of which at most this many usable ones are taken
    static final int CHECK = 100;  // how far down AnkiDroid's queue to look before sending a grade

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

    /** Stops using an AnkiDroid deck (the dashboard's Remove), so the next refill doesn't bring its cards back. */
    static void unselect(Context c, String deck) {
        List<Deck> keep = new ArrayList<>();
        for (Deck d : selected(c)) if (!deckLabel(d.name).equals(deck)) keep.add(d);
        saveSelection(c, keep);
    }

    /** The deck name shown on a card from this AnkiDroid deck. */
    static String deckLabel(String deckName) { return deckName == null || deckName.isEmpty() ? "Anki" : deckName; }

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
                final List<Fetch> fetches = new ArrayList<>();
                ContentResolver cr = c.getContentResolver();
                Map<Long, Model> models = new HashMap<>();
                for (Deck d : ids) fetches.add(fetchScheduled(cr, d.id, d.name, FETCH, KEEP, models));
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        long t = System.currentTimeMillis();
                        // a deck removed while this refill was running stays removed
                        Set<String> still = new HashSet<>();
                        for (Deck d : selected(c)) still.add(deckLabel(d.name));
                        Set<String> pictures = pictureDecks(c);
                        List<Card> add = new ArrayList<>();
                        for (Fetch f : fetches) {
                            if (!still.contains(f.deck)) continue;
                            for (Card k : f.cards) {
                                Card existing = engine.find(k.id);
                                if (existing != null) { existing.due = t; existing.front = k.front; existing.back = k.back; existing.extra = k.extra; existing.cloze = k.cloze; }
                                else add.add(k);
                            }
                            if (f.complete) {
                                // AnkiDroid listed every card it has due in this deck: one held here that it
                                // didn't list was answered in AnkiDroid meanwhile, so it waits to be handed back
                                for (Card k : engine.cards) if (k.isAnki() && f.deck.equals(k.deck) && k.due <= t && !f.due.contains(k.id)) k.due = t + 365 * Engine.DAY;
                            }
                            if (f.pictures > 0) pictures.add(f.deck); else if (f.ok) pictures.remove(f.deck);
                        }
                        engine.addCards(add);
                        Store.prefs(c).edit().putStringSet("anki_picture_decks", pictures).apply();
                        Store.saveSoon(c);
                    }
                });
            }
        });
    }

    /** What one refill read for one deck. */
    static final class Fetch {
        String deck;
        final List<Card> cards = new ArrayList<>();
        final Set<String> due = new HashSet<>(); // ids of every card AnkiDroid listed as due
        boolean ok, complete;                    // the query worked; it listed all of the deck's due cards
        int pictures;                            // cards left in AnkiDroid because their question needs a picture
    }

    static Fetch fetchScheduled(ContentResolver cr, long deckId, String deckName, int limit, int keep, Map<Long, Model> models) {
        Fetch f = new Fetch();
        f.deck = deckLabel(deckName);
        List<long[]> keys = scheduled(cr, deckId, limit);
        if (keys == null) return f;
        f.ok = true;
        f.complete = keys.size() < limit;
        for (long[] k : keys) {
            f.due.add(cardId(k[0], (int) k[1]));
            if (f.cards.size() >= keep) continue;
            AnkiPackage.NoteCard r = readCard(cr, k[0], (int) k[1], deckName, models);
            if (r.card != null) f.cards.add(r.card);
            else if (r.needsPicture) f.pictures++;
        }
        return f;
    }

    /** The next cards AnkiDroid would show in a deck, as {note id, card number}; null if it couldn't be asked. */
    static List<long[]> scheduled(ContentResolver cr, long deckId, int limit) {
        Cursor cur = null;
        try {
            cur = cr.query(SCHEDULE, null, "limit=?, deckID=?", new String[]{String.valueOf(limit), String.valueOf(deckId)}, null);
            if (cur == null) return null;
            int iNote = cur.getColumnIndex("note_id"), iOrd = cur.getColumnIndex("ord");
            List<long[]> out = new ArrayList<>();
            while (cur.moveToNext()) out.add(new long[]{cur.getLong(iNote), cur.getInt(iOrd)});
            return out;
        } catch (Exception e) {
            Log.w(TAG, "schedule query failed", e);
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    static String cardId(long noteId, int ord) { return "anki:" + noteId + ":" + ord; }

    /** A note type as AnkiDroid describes it. */
    static final class Model {
        String name;
        boolean cloze;
        final List<String> fields = new ArrayList<>();
        final List<String[]> templates = new ArrayList<>(); // {name, question, answer} by card number
    }

    /**
     * One scheduled card. Built from its note and note type, like an imported .apkg; if AnkiDroid is too
     * old to describe those (or the rules don't cover the note type), from the card as AnkiDroid renders it.
     */
    static AnkiPackage.NoteCard readCard(ContentResolver cr, long noteId, int ord, String deckName, Map<Long, Model> models) {
        AnkiPackage.NoteCard nc = fromNote(cr, noteId, ord, deckName, models);
        if (nc == null || (nc.card == null && !nc.needsPicture && !nc.audioOnly)) {
            nc = new AnkiPackage.NoteCard();
            nc.card = rendered(cr, noteId, ord, deckName);
        }
        Card c = nc.card;
        if (c != null) {
            if (c.front.length() > 900) c.front = c.front.substring(0, 899) + "…";
            if (c.back.length() > 900) c.back = c.back.substring(0, 899) + "…"; // long answers are split into points on the card
            c.id = cardId(noteId, ord);
            c.deck = deckLabel(deckName);
            c.ankiNoteId = noteId;
            c.ankiOrd = ord;
            c.state = Card.REVIEW;
            c.due = System.currentTimeMillis();
        }
        return nc;
    }

    static AnkiPackage.NoteCard fromNote(ContentResolver cr, long noteId, int ord, String deckName, Map<Long, Model> models) {
        Cursor cur = null;
        try {
            cur = cr.query(NOTES.buildUpon().appendPath(String.valueOf(noteId)).build(), null, null, null, null);
            if (cur == null || !cur.moveToFirst()) return null;
            String mid = col(cur, "mid"), flds = col(cur, "flds"), tags = col(cur, "tags");
            if (mid == null || flds == null) return null;
            Model m = model(cr, Long.parseLong(mid), models);
            if (m == null) return null;
            return AnkiPackage.noteCard(m.name, m.cloze, m.fields, m.templates, flds, tags, deckName, deckLabel(deckName), ord);
        } catch (Exception e) {
            Log.w(TAG, "note query failed", e);
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    /** A note type's name, kind, fields and templates; cached for one refill. */
    static Model model(ContentResolver cr, long mid, Map<Long, Model> cache) {
        if (cache.containsKey(mid)) return cache.get(mid);
        Model m = null;
        Cursor cur = null;
        try {
            Uri u = MODELS.buildUpon().appendPath(String.valueOf(mid)).build();
            cur = cr.query(u, null, null, null, null);
            if (cur != null && cur.moveToFirst()) {
                m = new Model();
                m.name = col(cur, "name");
                m.cloze = "1".equals(col(cur, "type"));
                String names = col(cur, "field_names");
                if (names != null) Collections.addAll(m.fields, names.split("\u001f", -1));
            }
            if (cur != null) cur.close();
            cur = null;
            if (m != null) {
                cur = cr.query(u.buildUpon().appendPath("templates").build(), null, null, null, null);
                while (cur != null && cur.moveToNext()) {
                    int t = Integer.parseInt(col(cur, "ord"));
                    while (m.templates.size() <= t) m.templates.add(null);
                    m.templates.set(t, new String[]{col(cur, "card_template_name"), col(cur, "question_format"), col(cur, "answer_format")});
                }
                if (m.fields.isEmpty() || m.templates.isEmpty()) m = null;
            }
        } catch (Exception e) {
            Log.w(TAG, "note type query failed", e);
            m = null;
        } finally { if (cur != null) cur.close(); }
        cache.put(mid, m);
        return m;
    }

    /** The card as AnkiDroid renders it (question and answer HTML), for when its note can't be read. */
    static Card rendered(ContentResolver cr, long noteId, int ord, String deckName) {
        Uri uri = NOTES.buildUpon().appendPath(String.valueOf(noteId)).appendPath("cards").appendPath(String.valueOf(ord)).build();
        Cursor cur = null;
        try {
            cur = cr.query(uri, null, null, null, null);
            if (cur == null || !cur.moveToFirst()) return null;
            String q = col(cur, "question_simple"), a = col(cur, "answer_pure");
            if (q == null) q = col(cur, "question");
            if (a == null) a = col(cur, "answer");
            String front = plain(q), back = plain(a);
            if (front.isEmpty() || back.isEmpty()) return null;
            return new Card(cardId(noteId, ord), deckLabel(deckName), front, back, "");
        } catch (Exception e) {
            Log.w(TAG, "card query failed", e);
            return null;
        } finally { if (cur != null) cur.close(); }
    }

    static String col(Cursor c, String name) {
        int i = c.getColumnIndex(name);
        return i < 0 ? null : c.getString(i);
    }

    /** Anki card HTML -> plain lines, the same way an imported deck's text is cleaned (line breaks kept). */
    static String plain(String html) {
        String t = AnkiPackage.clean(html);
        if (t.length() > 900) t = t.substring(0, 899) + "…"; // long answers are split into points on the card
        return t;
    }

    /** Decks with cards whose question needs a picture (left in AnkiDroid), for the dashboard's tip. */
    static Set<String> pictureDecks(Context c) {
        return new HashSet<>(Store.prefs(c).getStringSet("anki_picture_decks", Collections.<String>emptySet()));
    }

    static long deckId(Context c, String deck) {
        for (Deck d : selected(c)) if (deckLabel(d.name).equals(deck)) return d.id;
        return -1;
    }

    // ------------------------------------------------------------------ grades back to Anki

    @Override
    public void onGrade(final Card c, final Grade g, final long timeTakenMs) {
        if (!c.isAnki() || !hasPermission(ctx)) return;
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    // answered in AnkiDroid while Reelcards held it? Then a second answer would count twice
                    long deck = deckId(ctx, c.deck);
                    List<long[]> due = deck < 0 ? null : scheduled(ctx.getContentResolver(), deck, CHECK);
                    if (due != null) {
                        boolean still = false;
                        for (long[] k : due) if (k[0] == c.ankiNoteId && k[1] == c.ankiOrd) still = true;
                        if (!still) { Log.i(TAG, "not sent: " + c.id + " was already answered in AnkiDroid"); return; }
                    }
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
