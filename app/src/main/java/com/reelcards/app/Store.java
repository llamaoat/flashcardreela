package com.reelcards.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.reelcards.engine.Card;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Format;
import com.reelcards.engine.Stats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Holds the single Engine shared by the app screens and the reel watcher (same process, main thread),
 * and saves it as one JSON file in the app's private storage. Nothing leaves the phone.
 */
final class Store {
    private static final String TAG = "Reelcards";
    private static final String FILE = "engine.json";
    private static Engine engine;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean saveQueued;

    static synchronized Engine engine(Context ctx) {
        if (engine == null) {
            engine = new Engine();
            try { load(ctx.getApplicationContext(), engine); }
            catch (Exception e) { Log.w(TAG, "Could not load saved state, starting fresh", e); engine = new Engine(); }
            engine.ankiSink = new AnkiBridge(ctx.getApplicationContext());
        }
        return engine;
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("reelcards", Context.MODE_PRIVATE);
    }

    /** Coalesces saves: many calls within half a second write once. */
    static void saveSoon(final Context ctx) {
        if (saveQueued) return;
        saveQueued = true;
        MAIN.postDelayed(new Runnable() {
            @Override public void run() { saveQueued = false; saveNow(ctx); }
        }, 500);
    }

    static void saveNow(Context ctx) {
        if (engine == null) return;
        final String json;
        try { json = toJson(engine).toString(); }
        catch (Exception e) { Log.w(TAG, "Serialise failed", e); return; }
        final File dir = ctx.getApplicationContext().getFilesDir();
        IO.execute(new Runnable() {
            @Override public void run() {
                try {
                    File tmp = new File(dir, FILE + ".tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    out.write(json.getBytes(Charset.forName("UTF-8")));
                    out.getFD().sync();
                    out.close();
                    if (!tmp.renameTo(new File(dir, FILE))) Log.w(TAG, "rename failed");
                } catch (Exception e) { Log.w(TAG, "Save failed", e); }
            }
        });
    }

    /** The first two cards a student ever sees carry a one-line tip on the gestures. */
    static boolean takeCoachMark(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int left = p.getInt("coachLeft", 2);
        if (left <= 0) return false;
        p.edit().putInt("coachLeft", left - 1).apply();
        return true;
    }

    static void reset(Context ctx) {
        engine = new Engine();
        engine.ankiSink = new AnkiBridge(ctx.getApplicationContext());
        new File(ctx.getFilesDir(), FILE).delete();
        File media = mediaDir(ctx);
        File[] files = media.listFiles();
        if (files != null) for (File f : files) f.delete();
        media.delete();
        prefs(ctx).edit().clear().apply();
    }

    /** Pictures from imported Anki decks. Kept out of Android's backup, which stops altogether once an app passes 25MB. */
    static File mediaDir(Context ctx) { return new File(ctx.getNoBackupFilesDir(), "anki-media"); }

    /** A card's picture file. Cards store its name in the media folder (older saves kept the whole path). */
    static File mediaFile(Context ctx, String ref) {
        if (ref == null) return null;
        File f = new File(ref);
        return f.isAbsolute() ? f : new File(mediaDir(ctx), ref);
    }

    /** An older save's whole path to a picture in the media folder -> just its name, so it survives the folder moving. */
    static String mediaRef(String path) {
        int i = path.lastIndexOf("/anki-media/");
        return i >= 0 ? path.substring(i + "/anki-media/".length()) : path;
    }

    static final long KEEP_NEW_MEDIA_MS = 10 * 60_000L;

    /**
     * Deletes pictures that no card uses any more: a removed deck's, or an import's that wasn't used.
     * Pictures written in the last 10 minutes are kept, since an import may be on screen waiting for
     * "Use these cards" (or still saving them).
     * @param pending cards not in the engine yet whose pictures must stay (an import being set up)
     */
    static void cleanMedia(final Context ctx, List<Card> pending) {
        final java.util.Set<String> keep = new java.util.HashSet<>();
        for (Card c : engine(ctx).cards) { if (c.image != null) keep.add(mediaRef(c.image)); if (c.answerImage != null) keep.add(mediaRef(c.answerImage)); }
        if (pending != null) for (Card c : pending) { if (c.image != null) keep.add(mediaRef(c.image)); if (c.answerImage != null) keep.add(mediaRef(c.answerImage)); }
        final File dir = mediaDir(ctx);
        IO.execute(new Runnable() {
            @Override public void run() {
                File[] files = dir.listFiles();
                if (files == null) return;
                long old = System.currentTimeMillis() - KEEP_NEW_MEDIA_MS;
                int n = 0;
                for (File f : files) if (!keep.contains(f.getName()) && f.lastModified() < old && f.delete()) n++;
                if (n > 0) Log.i(TAG, "deleted " + n + " pictures no card uses");
            }
        });
    }

    // ------------------------------------------------------------------ JSON

    static JSONObject toJson(Engine e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("v", 1);
        JSONObject s = new JSONObject();
        s.put("intensity", e.settings.intensity);
        s.put("examDate", e.settings.examDate);
        s.put("pausedUntil", e.settings.pausedUntil);
        s.put("minutesPerDay", e.settings.minutesPerDay);
        s.put("voiceover", e.settings.voiceover);
        s.put("matchColours", e.settings.matchColours);
        s.put("night", e.settings.night);
        s.put("vibes", new JSONArray(e.settings.vibes));
        o.put("settings", s);

        JSONArray cards = new JSONArray();
        for (Card c : e.cards) {
            JSONObject j = new JSONObject();
            j.put("id", c.id); j.put("deck", c.deck); j.put("f", c.front); j.put("b", c.back); j.put("x", c.extra);
            j.put("st", c.state); j.put("step", c.step); j.put("s", c.stability); j.put("d", c.difficulty); j.put("due", c.due);
            j.put("last", c.lastReview); j.put("reps", c.reps); j.put("lapses", c.lapses); j.put("skips", c.skips);
            if (c.lastGrade != null) j.put("g", c.lastGrade);
            if (c.lastLayout != null) j.put("lay", c.lastLayout);
            if (c.ankiNoteId >= 0) { j.put("anid", c.ankiNoteId); j.put("aord", c.ankiOrd); }
            if (c.saved) j.put("sv", true);
            if (c.image != null) { j.put("img", c.image); j.put("iw", c.imageW); j.put("ih", c.imageH); }
            if (c.answerImage != null) { j.put("aimg", c.answerImage); j.put("aiw", c.answerImageW); j.put("aih", c.answerImageH); }
            if (c.occlusion != null) { j.put("occ", c.occlusion); j.put("occt", c.occlusionTarget); }
            if (c.cloze) j.put("cz", true);
            if (c.sessionBounces > 0) j.put("sb", c.sessionBounces);
            if (c.buriedUntilSession >= 0) j.put("bu", c.buriedUntilSession);
            cards.put(j);
        }
        o.put("cards", cards);

        JSONObject b = new JSONObject();
        JSONObject scores = new JSONObject();
        for (Map.Entry<String, double[]> en : e.bandit.scores.entrySet()) {
            scores.put(en.getKey(), new JSONArray().put(en.getValue()[0]).put(en.getValue()[1]));
        }
        b.put("scores", scores);
        JSONObject recent = new JSONObject();
        for (Map.Entry<String, List<String>> en : e.bandit.recent.entrySet()) recent.put(en.getKey(), new JSONArray(en.getValue()));
        b.put("recent", recent);
        JSONObject used = new JSONObject();
        for (Map.Entry<String, Integer> en : e.bandit.lastUsed.entrySet()) used.put(en.getKey(), en.getValue());
        b.put("lastUsed", used);
        b.put("shown", e.bandit.shown);
        if (e.bandit.lastKey != null) b.put("lastKey", e.bandit.lastKey);
        o.put("bandit", b);

        JSONObject rh = new JSONObject();
        JSONArray ra = new JSONArray(), rb = new JSONArray();
        for (int i = 0; i < e.rhythm.a.length; i++) { ra.put(e.rhythm.a[i]); rb.put(e.rhythm.b[i]); }
        rh.put("a", ra); rh.put("b", rb); rh.put("arm", e.rhythm.arm); rh.put("seeded", e.rhythm.seeded); rh.put("next", e.rhythmArmForNext);
        o.put("rhythm", rh);

        JSONObject cal = new JSONObject();
        cal.put("scale", e.calibration.scale); cal.put("fittedOn", e.calibration.fittedOn);
        JSONArray ev = new JSONArray();
        for (com.reelcards.engine.Calibration.Event x : e.calibration.log) ev.put(new JSONArray().put(x.days).put(x.stability).put(x.recalled ? 1 : 0));
        cal.put("log", ev);
        o.put("calibration", cal);

        JSONObject st = new JSONObject();
        st.put("avgReel", e.stats.avgReelSec); st.put("avgCard", e.stats.avgCardSec);
        st.put("reelN", e.stats.reelSamples); st.put("cardN", e.stats.cardSamples);
        JSONObject days = new JSONObject();
        for (Map.Entry<Long, Stats.Day> en : e.stats.days.entrySet()) {
            Stats.Day d = en.getValue();
            days.put(String.valueOf(en.getKey()), new JSONArray()
                    .put(d.reels).put(d.cards).put(d.reviews).put(d.again).put(d.good).put(d.easy)
                    .put(d.skips).put(d.fastSkips).put(d.newSeen).put(d.reelSec).put(d.cardSec).put(d.hard));
        }
        st.put("days", days);
        JSONObject lay = new JSONObject();
        for (Map.Entry<String, int[]> en : e.stats.layoutCounts.entrySet()) lay.put(en.getKey(), new JSONArray().put(en.getValue()[0]).put(en.getValue()[1]));
        st.put("layouts", lay);
        o.put("stats", st);

        JSONObject ses = new JSONObject();
        ses.put("sessionId", e.sessionId); ses.put("lastSwipeAt", e.lastSwipeAt); ses.put("snoozeUntil", e.snoozeUntil);
        ses.put("cardsToday", e.cardsToday); ses.put("newToday", e.newToday); ses.put("dayKey", e.currentDayKey);
        ses.put("newShown", new JSONArray(e.newShownToday));
        if (e.lastHost != null) ses.put("lastHost", e.lastHost);
        if (e.lastDeckShown != null) ses.put("lastDeck", e.lastDeckShown);
        // where the student is in this session, so a restart mid-session carries on where it was:
        // skipped cards keep their "back in 3 reels" slot, the warm-up isn't repeated, quiet mode keeps counting
        ses.put("reels", e.sessionReels); ses.put("sinceCard", e.reelsSinceCard); ses.put("postponed", e.postponed);
        ses.put("startAt", e.sessionStartAt);
        JSONArray rq = new JSONArray();
        for (Engine.Requeue r : e.requeue) rq.put(new JSONArray().put(r.cardId).put(r.atReel));
        ses.put("requeue", rq);
        JSONArray fast = new JSONArray(), reelSec = new JSONArray();
        for (Boolean f : e.recentFast) fast.put(f.booleanValue());
        for (Double d : e.recentReelSec) reelSec.put(d.doubleValue());
        ses.put("fast", fast); ses.put("reelSec", reelSec);
        JSONArray log = new JSONArray();
        for (String l : e.log) log.put(l);
        ses.put("log", log);
        o.put("session", ses);
        return o;
    }

    static void load(Context ctx, Engine e) throws Exception {
        File f = new File(ctx.getFilesDir(), FILE);
        if (!f.exists()) return;
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
        in.close();
        JSONObject o = new JSONObject(new String(buf.toByteArray(), Charset.forName("UTF-8")));

        JSONObject s = o.optJSONObject("settings");
        if (s != null) {
            e.settings.intensity = s.optString("intensity", e.settings.intensity);
            e.settings.examDate = s.optLong("examDate", 0);
            e.settings.pausedUntil = s.optLong("pausedUntil", 0);
            e.settings.minutesPerDay = s.optInt("minutesPerDay", 0);
            e.settings.voiceover = s.optBoolean("voiceover", true);
            e.settings.matchColours = s.optBoolean("matchColours", false);
            e.settings.night = s.optString("night", "auto");
            JSONArray vb = s.optJSONArray("vibes");
            if (vb != null) for (int i = 0; i < vb.length(); i++) e.settings.vibes.add(vb.getString(i));
        }
        JSONArray cards = o.optJSONArray("cards");
        List<Card> list = new ArrayList<>();
        if (cards != null) {
            for (int i = 0; i < cards.length(); i++) {
                JSONObject j = cards.getJSONObject(i);
                Card c = new Card(j.getString("id"), j.optString("deck"), j.optString("f"), j.optString("b"), j.optString("x"));
                c.state = j.optInt("st"); c.step = j.optInt("step"); c.stability = j.optDouble("s", 0); c.difficulty = j.optDouble("d", 0);
                c.due = j.optLong("due"); c.lastReview = j.optLong("last"); c.reps = j.optInt("reps");
                c.lapses = j.optInt("lapses"); c.skips = j.optInt("skips");
                c.lastGrade = j.has("g") ? j.getString("g") : null;
                c.lastLayout = j.has("lay") ? j.getString("lay") : null;
                c.ankiNoteId = j.optLong("anid", -1); c.ankiOrd = j.optInt("aord", -1);
                c.saved = j.optBoolean("sv", false);
                if (j.has("img")) { c.image = mediaRef(j.getString("img")); c.imageW = j.optInt("iw"); c.imageH = j.optInt("ih"); }
                if (j.has("aimg")) { c.answerImage = mediaRef(j.getString("aimg")); c.answerImageW = j.optInt("aiw"); c.answerImageH = j.optInt("aih"); }
                if (j.has("occ")) { c.occlusion = j.getString("occ"); c.occlusionTarget = j.optInt("occt"); }
                c.cloze = j.optBoolean("cz", false);
                c.sessionBounces = j.optInt("sb", 0);
                c.buriedUntilSession = j.optInt("bu", -1);
                list.add(c);
            }
        }
        e.cards.clear();
        e.cards.addAll(list);

        JSONObject b = o.optJSONObject("bandit");
        if (b != null) {
            JSONObject scores = b.optJSONObject("scores");
            if (scores != null) {
                Iterator<String> it = scores.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray v = scores.getJSONArray(k);
                    e.bandit.scores.put(k, new double[]{v.getDouble(0), v.getDouble(1)});
                }
            }
            JSONObject recent = b.optJSONObject("recent");
            if (recent != null) {
                for (String dim : Format.DIMS) {
                    JSONArray v = recent.optJSONArray(dim);
                    List<String> l = new java.util.ArrayList<>();
                    if (v != null) for (int i = 0; i < v.length(); i++) l.add(v.getString(i));
                    e.bandit.recent.put(dim, l);
                }
            }
            JSONObject used = b.optJSONObject("lastUsed");
            if (used != null) {
                Iterator<String> it = used.keys();
                while (it.hasNext()) { String k = it.next(); e.bandit.lastUsed.put(k, used.getInt(k)); }
            }
            e.bandit.shown = b.optInt("shown");
            e.bandit.lastKey = b.has("lastKey") ? b.getString("lastKey") : null;
        }
        JSONObject rh = o.optJSONObject("rhythm");
        if (rh != null) {
            JSONArray ra = rh.optJSONArray("a"), rb = rh.optJSONArray("b");
            if (ra != null && rb != null && ra.length() == e.rhythm.a.length) {
                for (int i = 0; i < ra.length(); i++) { e.rhythm.a[i] = ra.getDouble(i); e.rhythm.b[i] = rb.getDouble(i); }
            }
            e.rhythm.arm = rh.optInt("arm", 2);
            e.rhythm.seeded = rh.optBoolean("seeded", false);
            e.rhythmArmForNext = rh.optInt("next", -1);
        }
        JSONObject cal = o.optJSONObject("calibration");
        if (cal != null) {
            e.calibration.scale = cal.optDouble("scale", 1.0);
            e.calibration.fittedOn = cal.optInt("fittedOn");
            e.fsrs.personalScale = e.calibration.scale;
            JSONArray ev = cal.optJSONArray("log");
            if (ev != null) for (int i = 0; i < ev.length(); i++) {
                JSONArray x = ev.getJSONArray(i);
                e.calibration.log.add(new com.reelcards.engine.Calibration.Event(x.getDouble(0), x.getDouble(1), x.getInt(2) == 1));
            }
        }

        JSONObject st = o.optJSONObject("stats");
        if (st != null) {
            e.stats.avgReelSec = st.optDouble("avgReel", 12); e.stats.avgCardSec = st.optDouble("avgCard", 6);
            e.stats.reelSamples = st.optInt("reelN"); e.stats.cardSamples = st.optInt("cardN");
            JSONObject days = st.optJSONObject("days");
            if (days != null) {
                Iterator<String> it = days.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = days.getJSONArray(k);
                    Stats.Day d = e.stats.day(Long.parseLong(k));
                    d.reels = a.optInt(0); d.cards = a.optInt(1); d.reviews = a.optInt(2); d.again = a.optInt(3);
                    d.good = a.optInt(4); d.easy = a.optInt(5); d.skips = a.optInt(6); d.fastSkips = a.optInt(7);
                    d.newSeen = a.optInt(8); d.reelSec = a.optDouble(9, 0); d.cardSec = a.optDouble(10, 0); d.hard = a.optInt(11);
                }
            }
            JSONObject lay = st.optJSONObject("layouts");
            if (lay != null) {
                Iterator<String> it = lay.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    JSONArray a = lay.getJSONArray(k);
                    int[] v = e.stats.layout(k);
                    v[0] = a.optInt(0); v[1] = a.optInt(1);
                }
            }
        }

        JSONObject ses = o.optJSONObject("session");
        if (ses != null) {
            e.sessionId = ses.optInt("sessionId"); e.lastSwipeAt = ses.optLong("lastSwipeAt");
            e.snoozeUntil = ses.optLong("snoozeUntil"); e.cardsToday = ses.optInt("cardsToday");
            e.newToday = ses.optInt("newToday"); e.currentDayKey = ses.optLong("dayKey", Long.MIN_VALUE);
            e.lastHost = ses.has("lastHost") ? ses.getString("lastHost") : null;
            e.lastDeckShown = ses.has("lastDeck") ? ses.getString("lastDeck") : null;
            JSONArray ns = ses.optJSONArray("newShown");
            if (ns != null) for (int i = 0; i < ns.length(); i++) e.newShownToday.add(ns.getString(i));
            e.sessionReels = ses.optInt("reels"); e.reelsSinceCard = ses.optInt("sinceCard"); e.postponed = ses.optInt("postponed");
            e.sessionStartAt = ses.optLong("startAt");
            JSONArray rq = ses.optJSONArray("requeue");
            if (rq != null) for (int i = 0; i < rq.length(); i++) {
                JSONArray r = rq.getJSONArray(i);
                e.requeue.add(new Engine.Requeue(r.getString(0), r.getInt(1)));
            }
            JSONArray fast = ses.optJSONArray("fast");
            if (fast != null) for (int i = 0; i < fast.length(); i++) e.recentFast.add(fast.getBoolean(i));
            JSONArray reelSec = ses.optJSONArray("reelSec");
            if (reelSec != null) for (int i = 0; i < reelSec.length(); i++) e.recentReelSec.add(reelSec.getDouble(i));
            JSONArray log = ses.optJSONArray("log");
            if (log != null) for (int i = 0; i < log.length(); i++) e.log.add(log.getString(i));
        }
    }
}
