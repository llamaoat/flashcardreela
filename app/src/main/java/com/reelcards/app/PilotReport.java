package com.reelcards.app;

import android.content.Context;

import com.reelcards.engine.Calibration;
import com.reelcards.engine.Card;
import com.reelcards.engine.Engine;
import com.reelcards.engine.Stats;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

/**
 * "Share my numbers" for a pilot study (docs/PILOT.md): the counts the engine already keeps, as JSON
 * the student can send to a teacher or researcher. Only counts: no card text, no deck or file names,
 * no apps, no times of day. Nothing is sent unless the student taps Share and picks where it goes.
 * tools/pilot-summary.py adds up a class's reports.
 */
final class PilotReport {
    private PilotReport() {}

    static final int VERSION = 1;

    /** A random code for this install, so a researcher can tell two reports apart without knowing whose they are. */
    static String studentCode(Context c) {
        String code = Store.prefs(c).getString("pilot_code", null);
        if (code == null) {
            code = UUID.randomUUID().toString().substring(0, 8);
            Store.prefs(c).edit().putString("pilot_code", code).apply();
        }
        return code;
    }

    static JSONObject build(Context c, Engine e, long now) throws Exception {
        JSONObject o = new JSONObject();
        o.put("report", "reelcards-pilot");
        o.put("version", VERSION);
        o.put("student", studentCode(c));
        o.put("exported", date(e.dayKey(now)));

        JSONObject setup = new JSONObject();
        setup.put("intensity", e.settings.intensity);
        setup.put("examInDays", e.examDaysLeft(now));
        setup.put("interests", e.settings.vibes.size());
        int ready = 0, notes = 0, ankiFile = 0, ankiDroid = 0, learned = 0;
        for (Card k : e.cards) {
            if (k.isAnki()) ankiDroid++;
            else if (k.id.startsWith("apkg:")) ankiFile++;
            else if (k.id.startsWith("notes-")) notes++;
            else ready++;
            if (k.state == Card.REVIEW) learned++;
        }
        JSONObject cards = new JSONObject();
        cards.put("readyMade", ready).put("notes", notes).put("ankiFile", ankiFile).put("ankiDroid", ankiDroid).put("learned", learned);
        setup.put("cards", cards);
        o.put("setup", setup);

        JSONArray days = new JSONArray();
        int reels = 0, shown = 0, answered = 0, skips = 0, fastSkips = 0;
        for (Map.Entry<Long, Stats.Day> en : e.stats.days.entrySet()) {
            Stats.Day d = en.getValue();
            JSONObject j = new JSONObject();
            j.put("date", date(en.getKey()));
            j.put("reels", d.reels).put("cards", d.cards).put("answered", d.reviews).put("skipped", d.skips).put("fastSkips", d.fastSkips)
             .put("again", d.again).put("hard", d.hard).put("good", d.good).put("easy", d.easy).put("newSeen", d.newSeen)
             .put("minutes", Math.round(d.minutes() * 10) / 10.0);
            days.put(j);
            reels += d.reels; shown += d.cards; answered += d.reviews; skips += d.skips; fastSkips += d.fastSkips;
        }
        o.put("days", days);

        JSONObject totals = new JSONObject();
        totals.put("reels", reels).put("cards", shown).put("answered", answered).put("skipped", skips).put("fastSkips", fastSkips);
        totals.put("answeredPerReel", reels == 0 ? JSONObject.NULL : round3(answered / (double) reels));
        totals.put("skipRate", shown == 0 ? JSONObject.NULL : round3(skips / (double) shown));
        o.put("totals", totals);

        // measured recall: reviews of cards last seen a day or more before, against what FSRS predicted
        int n = 0, remembered = 0;
        double predicted = 0;
        for (Calibration.Event ev : e.calibration.log) {
            n++;
            if (ev.recalled) remembered++;
            predicted += e.fsrs.retrievability(ev.days, ev.stability);
        }
        JSONObject recall = new JSONObject();
        recall.put("reviewsAfterADay", n);
        recall.put("remembered", remembered);
        recall.put("rate", n == 0 ? JSONObject.NULL : round3(remembered / (double) n));
        recall.put("predicted", n == 0 ? JSONObject.NULL : round3(predicted / n));
        o.put("recall", recall);

        JSONObject layouts = new JSONObject();
        for (Map.Entry<String, int[]> en : e.stats.layoutCounts.entrySet()) layouts.put(en.getKey(), new JSONArray().put(en.getValue()[0]).put(en.getValue()[1]));
        o.put("layouts", layouts); // layout -> [shown, skipped]
        return o;
    }

    static String date(long dayKey) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.UK);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date(dayKey * Engine.DAY));
    }

    static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
