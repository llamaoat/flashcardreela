import com.reelcards.engine.*;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * Checks for the Reelcards engine, with no test framework needed.
 *   ./gradlew :engine:check          (or)
 *   javac -d out $(find engine/src -name '*.java') && java -cp out EngineChecks
 */
public class EngineChecks {
    static int passed = 0, failed = 0;

    static void check(String name, boolean ok) {
        if (ok) passed++; else failed++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name);
    }

    static final long MIN = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;
    static final long T0 = 1_788_253_200_000L; // 2026-09-01 09:00 UTC, same start as the py-fsrs reference

    /**
     * Reference values generated with py-fsrs (open-spaced-repetition, FSRS-6 defaults, no fuzz):
     * scenario, grade, when, resulting state, stability, difficulty, next interval (minutes).
     */
    static final String[][] PY_FSRS = {
        {"A", "good", "0", "Learning", "2.3065000000", "2.1181039705", "10.0000"},
        {"A", "good", "+10m", "Review", "2.3065000000", "2.1112142358", "2880.0000"},
        {"A", "good", "due", "Review", "10.9710482631", "2.1043313908", "15840.0000"},
        {"A", "good", "due", "Review", "46.3168584401", "2.0974554288", "66240.0000"},
        {"A", "again", "due", "Relearning", "2.9338452902", "7.3877157060", "10.0000"},
        {"A", "good", "+10m", "Review", "2.9338452902", "7.3755563596", "4320.0000"},
        {"A", "good", "due", "Review", "7.7991022284", "7.3634091726", "11520.0000"},
        {"A", "easy", "due", "Review", "28.4961713778", "6.4676117525", "40320.0000"},
        {"B", "easy", "0", "Review", "8.2956000000", "1.0000000000", "11520.0000"},
        {"B", "hard", "due", "Review", "26.7041833594", "4.0106089693", "38880.0000"},
        {"B", "good", "due+3d", "Review", "89.8555329521", "4.0018267296", "129600.0000"},
        {"B", "again", "due", "Relearning", "3.6511922730", "8.0136696029", "10.0000"},
        {"B", "hard", "+10m", "Relearning", "3.6511922730", "8.6666096887", "15.0000"},
        {"B", "good", "+15m", "Review", "3.6511922730", "8.6531714483", "5760.0000"},
        {"B", "good", "due", "Review", "7.6197568930", "8.6397466462", "11520.0000"},
        {"C", "again", "0", "Learning", "0.2120000000", "6.4133000000", "1.0000"},
        {"C", "again", "+1m", "Learning", "0.0833567171", "8.8063044689", "1.0000"},
        {"C", "hard", "+1m", "Learning", "0.0833567171", "9.1927976495", "5.5000"},
        {"C", "good", "+6m", "Learning", "0.1031406501", "9.1788332212", "10.0000"},
        {"C", "good", "+10m", "Review", "0.1258442373", "9.1648827572", "1440.0000"},
        {"C", "easy", "due", "Review", "1.1366150739", "8.8710538605", "1440.0000"},
        {"C", "hard", "due+10d", "Review", "3.6726124286", "9.2357813130", "5760.0000"},
        {"D", "easy", "0", "Review", "8.2956000000", "1.0000000000", "11520.0000"},
        {"D", "good", "+2h", "Review", "8.2956000000", "1.0000000000", "11520.0000"},
        {"D", "again", "+3h", "Relearning", "2.5625081682", "7.0269895693", "10.0000"},
        {"D", "good", "+10m", "Review", "2.5625081682", "7.0151909490", "4320.0000"},
        {"D", "good", "due", "Review", "7.8155847569", "7.0034041274", "11520.0000"},
    };

    public static void main(String[] args) throws java.io.UnsupportedEncodingException {
        // UTF-8 whatever the console's encoding, so the saved results keep their quotes and arrows
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"));
        fsrsMatchesReference();
        gradingFromGestures();
        rhythm();
        skipping();
        whichCard();
        calibration();
        formats();
        copy();
        longAnswers();
        ankiFiles();
        realAnkiExports();
        ankiDroidNotes();
        notes();
        forecast();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }

    // ------------------------------------------------------------------ FSRS-6

    static void fsrsMatchesReference() {
        System.out.println("FSRS-6 matches py-fsrs (the reference implementation)");
        Fsrs f = new Fsrs();
        f.fuzz = false;
        f.maxIntervalDays = 36500;
        Card c = null;
        long now = T0;
        String scenario = "";
        int ok = 0;
        double worst = 0;
        for (String[] row : PY_FSRS) {
            if (!row[0].equals(scenario)) { scenario = row[0]; c = new Card("x", "d", "q", "a", ""); now = T0; }
            String when = row[2];
            if (when.equals("0")) now = T0;
            else if (when.equals("due")) now = c.due;
            else if (when.startsWith("due+")) now = c.due + Long.parseLong(when.substring(4, when.length() - 1)) * DAY;
            else if (when.endsWith("m")) now += Long.parseLong(when.substring(1, when.length() - 1)) * MIN;
            else if (when.endsWith("h")) now += Long.parseLong(when.substring(1, when.length() - 1)) * HOUR;
            f.review(c, Grade.fromLabel(row[1]), now, 0);
            String state = c.state == Card.LEARNING ? "Learning" : c.state == Card.RELEARNING ? "Relearning" : "Review";
            double ivlMin = (c.due - now) / (double) MIN;
            double ds = Math.abs(c.stability - Double.parseDouble(row[4]));
            double dd = Math.abs(c.difficulty - Double.parseDouble(row[5]));
            double di = Math.abs(ivlMin - Double.parseDouble(row[6]));
            worst = Math.max(worst, Math.max(ds, dd));
            boolean match = state.equals(row[3]) && ds < 1e-6 && dd < 1e-6 && di < 0.01;
            if (match) ok++;
            else System.out.println("        mismatch " + Arrays.toString(row) + " got " + state + " S=" + c.stability + " D=" + c.difficulty + " ivl=" + ivlMin);
        }
        check(ok + "/" + PY_FSRS.length + " review steps identical (state, stability, difficulty, interval; worst diff " + String.format("%.1e", worst) + ")", ok == PY_FSRS.length);
        check("R(S, S) = 90%", Math.abs(f.retrievability(5, 5) - 0.9) < 1e-9);
        check("interval(S, 90%) = S", Math.abs(f.intervalDays(7, 0.9) - 7) < 1e-9);
        {
            Fsrs g = new Fsrs(); g.fuzz = false;
            Card k = new Card("y", "d", "q", "a", "");
            long exam = T0 + 4 * DAY;
            g.review(k, Grade.EASY, T0, exam);
            check("no interval jumps past the day before the exam (" + (k.due - T0) / DAY + " d with exam in 4 d)", k.due - T0 <= 3 * DAY);
            check("target recall 90% -> 92% within 3 weeks -> 95% in the final week",
                    Fsrs.desiredRetention(T0, 0) == 0.90 && Fsrs.desiredRetention(T0, T0 + 20 * DAY) == 0.92 && Fsrs.desiredRetention(T0, T0 + 6 * DAY) == 0.95);
        }
        {
            Fsrs g = new Fsrs(); g.rnd = new Random(3);
            boolean spread = false; long first = -1;
            for (int i = 0; i < 20; i++) {
                Card k = new Card("z" + i, "d", "q", "a", "");
                g.review(k, Grade.EASY, T0, 0);
                if (first < 0) first = k.due; else if (k.due != first) spread = true;
            }
            check("fuzz spreads cards learned together over different days", spread);
        }
    }

    // ------------------------------------------------------------------ grading

    static void gradingFromGestures() {
        System.out.println("Grading from reel gestures (response-time aware)");
        String q = "Which organ actually makes bile?"; // ~3 s to read
        Outcome o = new Outcome(); o.liked = true; o.likedBeforeReveal = true; o.likeAtMs = 3000;
        check("quick like before the answer = Easy", Grade.fromOutcome(o, false, -1, q, "") == Grade.EASY);
        o.likeAtMs = 11000;
        check("slow like before the answer = Good (slow recall is weaker)", Grade.fromOutcome(o, false, -1, q, "") == Grade.GOOD);
        o = new Outcome(); o.revealed = true; o.liked = true;
        check("like after the reveal = Good", Grade.fromOutcome(o, false, -1, q, "") == Grade.GOOD);
        o = new Outcome(); o.revealed = true;
        check("reveal, no like = Again", Grade.fromOutcome(o, false, -1, q, "") == Grade.AGAIN);
        o = new Outcome(); o.dwellMs = 400;
        check("swipe past = not graded, and a fast skip", Grade.fromOutcome(o, false, -1, q, "") == null && o.fastSkip());
        o = new Outcome(); o.quizPick = 1; o.revealed = true; o.pickAtMs = 4000;
        check("quiz right & quick = Good, wrong = Again", Grade.fromOutcome(o, true, 1, q, "The liver The pancreas") == Grade.GOOD && Grade.fromOutcome(o, true, 0, q, "") == Grade.AGAIN);
        o.pickAtMs = 20000;
        check("quiz right but slow = Hard", Grade.fromOutcome(o, true, 1, q, "The liver The pancreas") == Grade.HARD);
        o = new Outcome(); o.liked = true; o.likedBeforeReveal = true; o.quizPick = 0;
        check("quiz: early like then wrong pick = Again", Grade.fromOutcome(o, true, 1, q, "") == Grade.AGAIN);
    }

    // ------------------------------------------------------------------ rhythm

    static void rhythm() {
        System.out.println("When: rhythm, tolerance, natural breaks");
        {
            Engine e = fresh(1);
            int firstCardAt = -1; long t = T0;
            for (int i = 1; i <= 20; i++) { t += 12_000; if (e.onReelSwipe(t, "tiktok") != null) { firstCardAt = i; break; } }
            check("no card during warm-up; first card at reel 5 (got " + firstCardAt + ")", firstCardAt == 5);
        }
        {
            Engine e = fresh(2);
            e.settings.intensity = Settings.DEMO;
            long t = T0; int shown = 0;
            for (int i = 0; i < 20; i++) {
                t += 10_000;
                Show s = e.onReelSwipe(t, "practice");
                if (s != null) { shown++; t += 4000; e.onCardFinished(s, liked(4000), t); }
            }
            check("demo mode: a card every 2 reels (" + shown + " in 20 swipes)", shown >= 8);
        }
        {
            // a patient student only answers if cards are at least 9 reels apart; a keen one answers everything
            int[] learned = new int[2];
            for (int who = 0; who < 2; who++) {
                Engine e = fresh(3 + who);
                for (int i = 0; i < 400; i++) e.addCards(java.util.Collections.singletonList(new Card("x" + i, "Extra", "Question " + i, "Answer " + i, "")));
                e.settings.newPerDay();
                long t = T0; int since = 0;
                for (int i = 0; i < 6000; i++) {
                    t += 10_000; since++;
                    if (i % 300 == 0) t += Engine.DAY; // new day, new cards
                    Show s = e.onReelSwipe(t, "tiktok");
                    if (s == null) continue;
                    boolean answers = who == 1 || since >= 9;
                    since = 0;
                    t += answers ? 4000 : 400;
                    e.onCardFinished(s, answers ? liked(4000) : skip(400), t);
                }
                learned[who] = (int) Math.round(e.settings.baseGap() * Rhythm.MULT[e.rhythm.bestArm(e.settings.baseGap())]);
            }
            check("learns each student's rhythm: patient student -> 1 card every " + learned[0] + " reels, keen student -> every " + learned[1], learned[0] >= 8 && learned[1] <= 4);
        }
        {
            Engine e = fresh(4);
            long t = T0;
            for (int i = 0; i < 4; i++) { t += 12_000; e.onReelSwipe(t, "tiktok"); }
            t += 40_000; // they watched this one for 40 s
            Show s = e.onReelSwipe(t, "tiktok");
            boolean waited = s == null && e.lastDecision.reasons.toString().contains("natural break");
            t += 8_000;
            Show s2 = e.onReelSwipe(t, "tiktok");
            check("waits for a natural break after an absorbing reel, then shows the card", waited && s2 != null);
        }
        {
            Engine e = fresh(5);
            long t = T0;
            Show s = null; int at = 0;
            for (int i = 1; i <= 10 && s == null; i++) { t += 1_500; s = e.onReelSwipe(t, "tiktok"); at = i; }
            check("shows a card a reel early when the student is skimming (reel " + at + ", normal would be 5)", s != null && at == 4);
        }
    }

    // ------------------------------------------------------------------ skipping

    static void skipping() {
        System.out.println("Skipping");
        {
            Engine e = fresh(6);
            long t = T0;
            Show s = next(e, t);
            t = e.lastSwipeAt;
            String id = s.card.id, layout = s.format.layout;
            t += 500;
            Engine.Result r = e.onCardFinished(s, skip(500), t);
            check("skip is not graded and nothing is scheduled", r.grade == null && e.find(id).reps == 0);
            Show back = null; int reels = 0;
            while (back == null && reels < 12) { t += 12_000; reels++; back = e.onReelSwipe(t, "tiktok"); }
            check("skipped card comes back 3+ reels later (after " + reels + ")", back != null && back.card.id.equals(id) && reels >= 3);
            check("...in a different layout (" + layout + " -> " + (back == null ? "-" : back.format.layout) + ")", back != null && !back.format.layout.equals(layout));
            t += 500; e.onCardFinished(back, skip(500), t);
            check("skipped twice in a session -> saved for the next session", e.find(id).buriedUntilSession == e.sessionId);
        }
        {
            Engine e = fresh(7);
            long t = T0; int fast = 0;
            while (fast < 5) { t += 12_000; Show s = e.onReelSwipe(t, "tiktok"); if (s != null) { t += 300; e.onCardFinished(s, skip(300), t); fast++; } }
            check("5 fast skips in a row -> quiet mode", e.snoozeUntil > t);
            int during = 0;
            for (int i = 0; i < 30; i++) { t += 12_000; if (e.onReelSwipe(t, "tiktok") != null) during++; }
            check("no cards during quiet mode", during == 0);
        }
    }

    // ------------------------------------------------------------------ which card

    static void whichCard() {
        System.out.println("Which card: priority, mixing subjects, exam pacing");
        {
            Engine e = fresh(8);
            long now = T0 + 30 * DAY;
            // two due Biology cards and one due Chemistry card with a slightly higher recall
            Card b1 = e.find("bio-1"), b2 = e.find("bio-2"), c1 = e.find("chem-1");
            for (Card c : new Card[]{b1, b2, c1}) { c.state = Card.REVIEW; c.stability = 5; c.difficulty = 5; c.due = now - DAY; }
            b1.lastReview = now - 20 * DAY; b2.lastReview = now - 19 * DAY; c1.lastReview = now - 18 * DAY;
            e.lastDeckShown = null;
            Show s1 = e.forceShow(now, "tiktok");
            e.lastDeckShown = "GCSE Biology";
            e.cards.get(e.cards.indexOf(b1)).due = now + 10 * DAY; // pretend b1 was just answered
            Show s2 = e.forceShow(now, "tiktok");
            check("lowest predicted recall first (" + s1.card.id + ")", s1.card == b1);
            check("mixes subjects: after Biology picks Chemistry when recall is close (" + s2.card.id + ")", s2.card == c1);
        }
        {
            Engine e = fresh(9);
            int base = e.newLimit(T0);
            e.settings.examDate = T0 + 2 * DAY;
            int withExam = e.newLimit(T0);
            check("exam pacing raises new cards per day to cover the deck (" + base + " -> " + withExam + " with an exam in 2 days)", withExam > base);
        }
    }

    // ------------------------------------------------------------------ calibration

    static void calibration() {
        System.out.println("Personal memory calibration");
        Fsrs f = new Fsrs();
        double[] fitted = new double[2];
        double[] truth = {1.6, 0.7};
        Calibration cal = null;
        for (int t = 0; t < 2; t++) {
            cal = new Calibration();
            Random rnd = new Random(11);
            for (int i = 0; i < 400; i++) {
                double s = 1 + rnd.nextDouble() * 20, days = 1 + rnd.nextDouble() * 30;
                cal.add(days, s, rnd.nextDouble() < f.retrievability(days, s * truth[t]));
            }
            cal.maybeRefit(f);
            fitted[t] = cal.scale;
        }
        check(String.format("fits how fast a student forgets (slow forgetter: true x%.1f -> fitted x%.2f; fast: true x%.1f -> x%.2f)", truth[0], fitted[0], truth[1], fitted[1]),
                fitted[0] > 1.2 && fitted[0] < 1.9 && fitted[1] < 0.9 && fitted[1] > 0.5);
        check("...and applies it to scheduling", f.personalScale == cal.scale);
        Calibration small = new Calibration();
        for (int i = 0; i < 60; i++) small.add(5, 5, true);
        check("doesn't fit on too little data", !small.maybeRefit(new Fsrs()) && small.scale == 1.0);
    }

    // ------------------------------------------------------------------ formats

    static void formats() {
        System.out.println("Format engine (recovering, contextual bandit)");
        {
            FormatBandit b = new FormatBandit();
            Random rnd = new Random(7);
            String prev = null; boolean repeat = false;
            for (int i = 0; i < 400; i++) {
                Format f = b.pick(rnd, "tiktok", null, true, true).format;
                if (f.key().equals(prev)) repeat = true;
                prev = f.key();
                b.update(f, "tiktok", "quiz".equals(f.layout) ? 1 : 0);
            }
            check("never the same combination twice in a row", !repeat);
            check("learns a taste for quiz layouts", "quiz".equals(b.favourite("layout")));
            int quiz = 0, streak = 0, maxStreak = 0;
            for (int i = 0; i < 200; i++) {
                boolean q = "quiz".equals(b.pick(rnd, "tiktok", null, true, true).format.layout);
                if (q) { quiz++; streak++; maxStreak = Math.max(maxStreak, streak); } else streak = 0;
            }
            check("favourite shows most often (" + quiz + "/200) but still rotates (longest run " + maxStreak + ")", quiz > 80 && quiz < 190 && maxStreak <= 6);
        }
        {
            FormatBandit b = new FormatBandit();
            Random rnd = new Random(8);
            for (int i = 0; i < 300; i++) {
                String ctx = i % 2 == 0 ? "tiktok" : "youtube";
                Format f = b.pick(rnd, ctx, null, true, true).format;
                boolean good = ctx.equals("tiktok") ? "chat".equals(f.layout) : "split".equals(f.layout);
                b.update(f, ctx, good ? 1 : 0.1);
            }
            int chatT = 0, splitY = 0;
            for (int i = 0; i < 100; i++) {
                if ("chat".equals(b.pick(rnd, "tiktok", null, true, true).format.layout)) chatT++;
                if ("split".equals(b.pick(rnd, "youtube", null, true, true).format.layout)) splitY++;
            }
            check("learns per app: chat in TikTok (" + chatT + "/100), split-screen in YouTube (" + splitY + "/100)", chatT > 30 && splitY > 30);
        }
        {
            Engine e = fresh(9);
            e.setVibes(Arrays.asList("gaming"));
            int gameplay = 0;
            for (int i = 0; i < 60; i++) if ("gameplay".equals(e.bandit.pick(e.rnd, "tiktok", null, true, true).format.bg)) gameplay++;
            check("a gamer starts with gameplay backgrounds more often (" + gameplay + "/60 vs ~10 by chance)", gameplay > 18);
        }
    }

    // ------------------------------------------------------------------ copy

    static void copy() {
        System.out.println("Reel-native copy");
        Engine e = fresh(10);
        Card c = e.find("bio-4");
        CardScript cap = new CopyWriter().write(c, new Format("pov", "captions", "caption", "plain"), e.cards, new Random(1), "due today", null);
        check("word-by-word captions split into short beats " + cap.beats, cap.beats.size() >= 2 && cap.beats.get(0).split(" ").length <= 3);
        CardScript com = new CopyWriter().write(c, new Format("pov", "comment", "caption", "plain"), e.cards, new Random(2), "due today", null);
        check("reply-to-comment format: \"" + com.hookLine + "\" / \"" + com.commentText + "\"", com.hookLine.startsWith("Replying to @") && com.commentText.startsWith("which organ"));
        CardScript v = null;
        for (int i = 0; i < 20 && (v == null || v.vibe == null); i++) v = new CopyWriter().write(c, new Format("pov", "bigtext", "caption", "pitch"), e.cards, new Random(i), "due", Arrays.asList("football"));
        check("themed hook for a football fan: \"" + v.hookLine + "\"", v.vibe != null && (v.hookLine.contains("VAR") || v.hookLine.contains("extra time")));
        check("voiceover reads the hook and question", com.voiceover.contains(c.front));
        check("hint gives the shape, not the answer: \"" + CopyWriter.hint("Amylase") + "\" / \"" + CopyWriter.hint("The liver") + "\"",
                CopyWriter.hint("Amylase").equals("Starts with “A” · 7 letters") && CopyWriter.hint("The liver").equals("Starts with “The…” · 2 words"));

        System.out.println("Rail buttons: hint, save, share");
        Outcome h = new Outcome(); h.hintAtMs = 2000; h.likeAtMs = 3000; h.liked = true; h.likedBeforeReveal = true;
        check("used a hint, then knew it = Hard", Grade.fromOutcome(h, false, -1, c.front, "") == Grade.HARD);
        Outcome h2 = new Outcome(); h2.hintAtMs = 2000; h2.revealed = true; h2.revealAtMs = 4000;
        check("used a hint and still had to look = Again", Grade.fromOutcome(h2, false, -1, c.front, "") == Grade.AGAIN);
        Engine se = fresh(11);
        long t = T0;
        Show sh = null;
        while (sh == null) { t += 12_000; sh = se.onReelSwipe(t, "tiktok"); }
        Outcome ok = new Outcome(); ok.revealed = true; ok.liked = true; ok.dwellMs = 4000; ok.revealAtMs = 2000; ok.likeAtMs = 3500;
        se.onCardFinished(sh, ok, t + 4000);
        Card saved = sh.card;
        // later, once it has graduated with a long interval, the student saves it
        saved.state = Card.REVIEW; saved.stability = 20; saved.lastReview = t; saved.due = t + 20 * Engine.DAY;
        Show again = se.forceShow(t + 5000, "tiktok");
        Outcome sv = new Outcome(); sv.saved = true; sv.revealed = true; sv.liked = true; sv.likedBeforeReveal = false; sv.dwellMs = 5000; sv.revealAtMs = 2000; sv.likeAtMs = 3000;
        again.card = saved;
        Engine.Result rs = se.onCardFinished(again, sv, t + 10_000);
        check("saving a card flags it and brings it back within a day (" + rs.summary.replaceAll(".*saved", "saved").replaceAll(" · look.*", "") + ")",
                saved.saved && se.savedCards().contains(saved) && saved.due <= t + 10_000 + Engine.DAY);
        Outcome shr = new Outcome(); shr.shared = true; shr.dwellMs = 900;
        check("sharing counts as full engagement for the look, even with a quick exit", shr.reward() == 1.0 && shr.engaged() && !shr.fastSkip());
        Engine sk = fresh(13);
        long ts = T0;
        Show shown = null;
        while (shown == null) { ts += 12_000; shown = sk.onReelSwipe(ts, "tiktok"); }
        Outcome sharedOnly = new Outcome(); sharedOnly.shared = true; sharedOnly.dwellMs = 2500; sharedOnly.exit = "shared";
        Engine.Result rsh = sk.onCardFinished(shown, sharedOnly, ts + 2500);
        check("sharing without answering isn't a skip: not graded, not counted, not re-queued (\"" + rsh.summary.replaceAll(" · look.*", "") + "\")",
                rsh.grade == null && shown.card.skips == 0 && sk.stats.total("skips") == 0 && sk.requeue.isEmpty() && shown.card.reps == 0);
    }

    // ------------------------------------------------------------------ long answers

    static AnswerShape sci(int i) { Card c = SampleDecks.get(SampleDecks.SCIENCE_LONG).get(i); return AnswerShape.of(c); }

    static void longAnswers() {
        System.out.println("Long answers and long questions");
        AnswerShape enz = sci(0), reflex = sci(2), potato = sci(3), blood = sci(5), mass = sci(7), copper = sci(8), states = sci(11), mg = sci(12);
        check("semicolons -> 3 points: " + enz.points, "list".equals(enz.kind) && enz.points.size() == 3 && enz.points.get(2).equals("So the reaction stops"));
        check("arrows -> 5 steps: " + reflex.points, "steps".equals(reflex.kind) && reflex.points.size() == 5 && reflex.points.get(0).equals("Receptor"));
        check("sentences -> 3 points (prose)", "prose".equals(potato.kind) && potato.points.size() == 3);
        check("\"A, B, C and D\" -> 4 points: " + blood.points, "list".equals(blood.kind) && blood.points.size() == 4 && blood.points.get(3).equals("Plasma"));
        check("one long sentence splits at \", so\": " + mass.points, "prose".equals(mass.kind) && mass.points.size() == 2 && mass.points.get(1).startsWith("So the mass"));
        check("inline \"1. … 2. …\" -> 4 steps", "steps".equals(copper.kind) && copper.points.size() == 4 && copper.points.get(1).equals("Filter off the leftover copper oxide"));
        check("one point per line -> 3 points", "list".equals(states.kind) && states.points.size() == 3 && states.points.get(0).startsWith("Solid:"));
        check("long question split into set-up and question: \"" + potato.questionMain + "\"",
                potato.context.startsWith("A potato cylinder") && potato.questionMain.equals("Explain why its mass goes down."));
        check("the question keeps every command sentence: \"" + mg.questionMain + "\"", mg.questionMain.equals("Name the gas, and describe a test for it.") && mg.points.size() == 2);
        AnswerShape html = AnswerShape.of("Name two blood cells", "<ul><li>Red blood cells</li><li>White&nbsp;blood cells</li></ul>", "");
        AnswerShape br = AnswerShape.of("Two facts", "Fact one<br>Fact two<br/>", "");
        check("Anki HTML lists and line breaks become points: " + html.points, html.points.size() == 2 && html.points.get(1).equals("White blood cells") && br.points.size() == 2);
        check("a word equation stays one short answer", "short".equals(AnswerShape.splitPoints("Carbon dioxide + water → glucose + oxygen").kind));
        boolean allShort = true; String odd = "";
        for (String deck : new String[]{SampleDecks.BIOLOGY, SampleDecks.CHEMISTRY, SampleDecks.SPANISH})
            for (Card c : SampleDecks.get(deck)) { AnswerShape a = AnswerShape.of(c); if (!"short".equals(a.kind) || !a.context.isEmpty()) { allShort = false; odd = c.back; } }
        check("every short-answer card in the other decks stays short " + odd, allShort);
        check("\"e.g.\" and \"i.e.\" don't end a sentence", AnswerShape.splitPoints("e.g. Sodium reacts with water. It fizzes and moves around on the surface").points.size() == 2);
        check("numbered steps split only where the numbers run 1, 2, 3 (not at \"100. \")",
                AnswerShape.splitPoints("1. Heat to 100. Then stir 2. Cool it down slowly").points.equals(list("Heat to 100. Then stir", "Cool it down slowly")));
        AnswerShape seven = AnswerShape.splitPoints("A; B; C; D; E; F; G");
        check("more than 6 points: the rest go behind \"more\"", seven.points.size() == 6 && seven.over.size() == 1);

        System.out.println("The shape picks the layouts");
        Card enzCard = SampleDecks.get(SampleDecks.SCIENCE_LONG).get(0), liver = SampleDecks.get(SampleDecks.BIOLOGY).get(3);
        java.util.Set<String> multi = AnswerShape.layoutsFor(enz, enzCard.back, true), shortL = AnswerShape.layoutsFor(AnswerShape.of(liver), liver.back, true);
        check("a 3-point answer: no quiz buttons or split-screen, numbered list allowed " + multi, !multi.contains("quiz") && !multi.contains("split") && multi.contains("list"));
        check("a short answer: quiz and split allowed, no numbered list", shortL.contains("quiz") && shortL.contains("split") && !shortL.contains("list"));
        check("a long question: no word-by-word captions", !AnswerShape.layoutsFor(potato, "x", true).contains("captions"));
        Engine e = fresh(30);
        e.addCards(SampleDecks.get(SampleDecks.SCIENCE_LONG));
        boolean distractorsShort = true;
        for (int i = 0; i < 200; i++) { String w = CopyWriter.distractor(liver, e.cards, new Random(i)); if (w != null && !"short".equals(AnswerShape.splitPoints(w).kind)) distractorsShort = false; }
        check("quiz distractors are never long answers", distractorsShort);
        int multiShown = 0; boolean badLayout = false;
        long t = T0;
        e.settings.intensity = Settings.DEMO;
        for (int i = 0; i < 400; i++) {
            t += 9000;
            Show s = e.onReelSwipe(t, "tiktok");
            if (s == null) continue;
            if (s.script.multi()) { multiShown++; if ("quiz".equals(s.format.layout) || "split".equals(s.format.layout)) badLayout = true; }
            else if ("list".equals(s.format.layout)) badLayout = true;
            Outcome o = new Outcome(); o.revealed = true; o.liked = true; o.dwellMs = 4000;
            e.onCardFinished(s, o, t + 4000);
        }
        check("in a real session, long cards (" + multiShown + ") never get quiz or split, short ones never get a numbered list", multiShown > 10 && !badLayout);

        System.out.println("Words for long answers");
        CardScript chat = new CopyWriter().write(enzCard, new Format("pov", "chat", "caption", "plain"), e.cards, new Random(3), "due", null);
        check("chat: one message per point, question kept for after the reveal", chat.chatMe.size() == 3 && chat.chatMe.get(0).startsWith("the active site") && chat.chatThemQuestion.size() == 1);
        CardScript list = new CopyWriter().write(enzCard, new Format("rate", "list", "caption", "plain"), e.cards, new Random(4), "due", null);
        check("numbered-list hook counts the points: \"" + list.hookLine + "\"", list.hookLine.contains("3") && list.marks == 3 && list.pointStarts.size() == 3);
        check("hint for a long answer: \"" + CopyWriter.hint(enzCard.back, enz.points) + "\"", CopyWriter.hint(enzCard.back, enz.points).equals("3 points: “Active…” “Substrate…” “Reaction…”"));
        List<String> st = AnswerShape.starts(sci(6).points);
        check("points that start alike get longer hints " + st, st.get(0).equals("Aerobic") && st.get(1).equals("Aerobic makes") && st.get(2).equals("Aerobic releases"));
        check("a long explanation goes behind \"more\"", !enz.more.isEmpty() && enz.inlineExplanation.isEmpty());

        System.out.println("Part marks for long answers");
        String q = enzCard.front;
        check("all 3 points ticked = Good", Grade.fromOutcome(pts(3), false, -1, q, "", 3) == Grade.GOOD);
        check("2 of 3 = Hard", Grade.fromOutcome(pts(2), false, -1, q, "", 3) == Grade.HARD);
        check("1 of 3 = Again", Grade.fromOutcome(pts(1), false, -1, q, "", 3) == Grade.AGAIN);
        check("2 of 4 = Hard (half counts)", Grade.fromOutcome(pts(2), false, -1, q, "", 4) == Grade.HARD);
        Outcome hinted = pts(3); hinted.hintAtMs = 1500;
        check("all points but with a hint = Hard", Grade.fromOutcome(hinted, false, -1, q, "", 3) == Grade.HARD);
        Outcome early = new Outcome(); early.liked = true; early.likedBeforeReveal = true; early.likeAtMs = Grade.readingMs(q) + 4500;
        check("knowing 3 points takes longer: a like at reading time + 4.5 s is still Easy", Grade.fromOutcome(early, false, -1, q, "", 3) == Grade.EASY
                && Grade.fromOutcome(early, false, -1, q, "", 1) == Grade.GOOD);
        Engine pe = fresh(31);
        pe.addCards(SampleDecks.get(SampleDecks.SCIENCE_LONG));
        Show ms = null;
        pe.settings.intensity = Settings.DEMO;
        long tt = T0;
        for (int i = 0; i < 300 && (ms == null || !ms.script.multi()); i++) {
            tt += 9000; Show s = pe.onReelSwipe(tt, "tiktok");
            if (s == null) continue;
            if (s.script.multi()) { ms = s; break; }
            Outcome o = new Outcome(); o.revealed = true; o.liked = true; o.dwellMs = 4000; pe.onCardFinished(s, o, tt + 4000);
        }
        Outcome part = pts(1); part.dwellMs = 6000;
        int n = ms == null ? 0 : ms.script.points.size();
        part.pointsGot = Math.max(1, n / 2);
        Engine.Result r = ms == null ? null : pe.onCardFinished(ms, part, tt + 6000);
        check("the engine grades by points ticked: \"" + (r == null ? "-" : r.summary.replaceAll(" · look.*", "")) + "\"",
                r != null && r.summary.contains("of " + n + " points") && part.engaged() && part.reward() == 0.9);
    }

    static Outcome pts(int got) { Outcome o = new Outcome(); o.revealed = true; o.revealAtMs = 3000; o.pointsGot = got; o.dwellMs = 7000; return o; }

    // ------------------------------------------------------------------ Anki files (.apkg)

    static java.io.File deckFile(String name) {
        for (String dir : new String[]{"src/test/resources/anki", "engine/src/test/resources/anki"}) {
            java.io.File f = new java.io.File(dir, name);
            if (f.exists()) return f;
        }
        return new java.io.File(name);
    }

    static com.reelcards.engine.anki.AnkiPackage.Result readDeck(String name) throws java.io.IOException {
        java.util.zip.ZipFile z = new java.util.zip.ZipFile(deckFile(name));
        try { return com.reelcards.engine.anki.AnkiPackage.read(z, name, T0, null); } finally { z.close(); }
    }

    static Card byId(List<Card> cards, String id) { for (Card c : cards) if (c.id.equals(id)) return c; return null; }

    static String dump(List<Card> cards) {
        StringBuilder b = new StringBuilder();
        for (Card c : cards) b.append(c.id).append('|').append(c.front).append('|').append(c.back).append('|').append(c.extra).append('|').append(c.image).append('|').append(c.occlusion)
                .append('|').append(c.occlusionTarget).append('|').append(c.cloze).append('|').append(c.state).append('|').append(c.stability).append('|').append(c.due).append('\n');
        return b.toString();
    }

    static void ankiFiles() {
        System.out.println("Anki files (.apkg)");
        try {
            com.reelcards.engine.anki.AnkiPackage.Result latest = readDeck("latest.apkg"), l1 = readDeck("legacy1.apkg"), l2 = readDeck("legacy2.apkg");
            List<Card> cs = latest.cards;
            check("reads all three Anki file formats (2.0, 2.1, and 2.1.50+ with zstd) to the same " + cs.size() + " cards",
                    cs.size() == 18 && dump(cs).equals(dump(l1.cards)) && dump(cs).equals(dump(l2.cards)));
            check("leaves out suspended and audio-only cards: \"" + latest.stats.summary() + "\"", latest.stats.suspended == 1 && latest.stats.audio == 1 && latest.stats.imported == 18);
            Card basic = byId(cs, "apkg:3001"), list = byId(cs, "apkg:3002"), rev = byId(cs, "apkg:3005"), typed = byId(cs, "apkg:3020"), custom = byId(cs, "apkg:3022");
            check("basic note: front and back", basic != null && basic.front.equals("What does the mitochondria do?") && basic.back.equals("Aerobic respiration") && basic.deck.equals("GCSE Biology · Cells"));
            check("an HTML list on the back becomes a 3-point answer", list != null && AnswerShape.of(list).points.size() == 3);
            check("reversed note: the second card asks the other way round", rev != null && rev.back.equals("Osmosis") && rev.front.startsWith("The diffusion of water"));
            check("type-in-the-answer note: the typed answer is the answer", typed != null && typed.back.equals("Na") && typed.front.equals("Chemical symbol for sodium?"));
            check("custom note type: answer field, then the rest as the explanation", custom != null && custom.back.equals("−1") && custom.extra.startsWith("Protons are +1"));
            Card c1 = byId(cs, "apkg:3006"), c2 = byId(cs, "apkg:3007"), c3 = byId(cs, "apkg:3008");
            check("cloze: the gap is [" + "…], the deletion is the answer: \"" + (c1 == null ? "" : c1.front) + "\"", c1 != null && c1.cloze && c1.front.equals("[…] are the site of aerobic respiration.") && c1.back.equals("Mitochondria"));
            check("cloze hint shows in the gap", c2 != null && c2.front.equals("Mitochondria are the site of [type of respiration].") && c2.back.equals("aerobic respiration"));
            check("a gap is read aloud as \"blank\" (voiceover and screen readers)",
                    CardScript.spokenGaps(c1 == null ? "" : c1.front).equals("blank are the site of aerobic respiration.")
                    && CardScript.spokenGaps("Mitochondria are the site of [type of respiration].").equals("Mitochondria are the site of blank (type of respiration)."));
            check("one cloze number used three times = one card with 3 points", c3 != null && c3.back.equals("solid; liquid; gas") && AnswerShape.of(c3).points.size() == 3);
            check("cloze extra: the full sentence and Back Extra", c1 != null && c1.extra.equals("Mitochondria are the site of aerobic respiration. They have a folded inner membrane."));
            List<Object> nested = com.reelcards.engine.anki.AnkiPackage.clozeParse("A {{c1::folded {{c2::inner}} membrane}}");
            List<String> got = new java.util.ArrayList<>();
            String q2 = com.reelcards.engine.anki.AnkiPackage.clozeText(nested, 2, "q", got);
            check("nested cloze deletions", q2.equals("A folded […] membrane") && got.equals(list("inner")));
            int io = 0; boolean ioOk = true;
            for (Card c : cs) if (c.occlusion != null) {
                io++;
                List<Occlusion.Shape> sh = Occlusion.parse(c.occlusion);
                boolean has = false;
                for (Occlusion.Shape x : sh) { if (x.ord == c.occlusionTarget) has = true; if (x.x < 0 || x.x > 1 || (x.pts == null && (x.w <= 0 || x.w > 1))) ioOk = false; }
                if (!has || c.image == null || c.imageW != 900 || c.imageH != 640) ioOk = false;
            }
            check("image occlusion: " + io + " cards (Anki's built-in kind and the old add-on), each with its picture and masks in 0..1", io == 8 && ioOk);
            Card poly = byId(cs, "apkg:3012"), ell = byId(cs, "apkg:3013");
            check("occlusion shapes: rectangles, polygons and ellipses", poly != null && poly.occlusion.contains("p|3|1|") && ell != null && ell.occlusion.contains("e|4|1|"));
            Card old = byId(cs, "apkg:3101");
            check("old add-on: the mask to answer comes from the SVG's qshape", old != null && Occlusion.parse(old.occlusion).get(1).ord == 1 && old.occlusionTarget == 1);
            Card pic = byId(cs, "apkg:3003");
            check("pictures come along, with their size (JPEG 420×240)", pic != null && "organelle.jpg".equals(pic.image) && pic.imageW == 420 && pic.imageH == 240 && pic.front.equals("Which organelle is this?"));
            long crt = 1735704000L;
            check("review cards keep their Anki progress: interval → stability, due date kept", basic.state == Card.REVIEW && basic.stability == 12 && basic.due == (crt + 640 * 86400L) * 1000L && basic.reps == 4);
            Card fsrs = byId(cs, "apkg:3004");
            check("…and Anki's FSRS memory state when the deck has it", fsrs != null && fsrs.stability == 41.25 && fsrs.difficulty == 6.5 && fsrs.lapses == 1);
            check("new cards stay new", list.state == Card.NEW && list.reps == 0);
            java.util.Set<String> ioL = AnswerShape.layoutsFor(AnswerShape.of(byId(cs, "apkg:3010")), "", true), clozeL = AnswerShape.layoutsFor(AnswerShape.of(c1), c1.back, true);
            check("pictures and occlusion only use big text; a cloze never becomes a comment " + ioL, ioL.size() == 1 && ioL.contains("bigtext") && !clozeL.contains("comment") && clozeL.contains("quiz"));
            Engine e = new Engine();
            e.setRandom(new Random(40));
            e.tz = java.util.TimeZone.getTimeZone("UTC");
            e.settings.intensity = Settings.DEMO;
            e.addCards(cs);
            boolean sawIo = false, badLayout = false; long t = T0;
            for (int i = 0; i < 400; i++) {
                t += 9000;
                Show s = e.onReelSwipe(t, "tiktok");
                if (s == null) continue;
                if (s.script.isOcclusion()) { sawIo = true; if (!"bigtext".equals(s.format.layout) || s.script.occlusionTarget < 1) badLayout = true; }
                Outcome o = new Outcome(); o.revealed = true; o.liked = true; o.dwellMs = 4000;
                e.onCardFinished(s, o, t + 4000);
            }
            check("imported cards play in the feed (occlusion in big text with its masks)", sawIo && !badLayout);
            boolean refused = false;
            try { java.util.zip.ZipFile z = new java.util.zip.ZipFile(deckFile("sample.apkg")); z.close();
                java.io.File tmp = java.io.File.createTempFile("notanki", ".zip");
                java.util.zip.ZipOutputStream zo = new java.util.zip.ZipOutputStream(new java.io.FileOutputStream(tmp)); zo.putNextEntry(new java.util.zip.ZipEntry("hello.txt")); zo.write(1); zo.close();
                java.util.zip.ZipFile bad = new java.util.zip.ZipFile(tmp);
                try { com.reelcards.engine.anki.AnkiPackage.read(bad, "x.apkg", T0, null); } catch (com.reelcards.engine.anki.AnkiPackage.NotAnki ex) { refused = true; } finally { bad.close(); tmp.delete(); }
            } catch (java.io.IOException ex) { refused = false; }
            check("a zip with no Anki deck inside is refused with a clear message", refused);
        } catch (java.io.IOException ex) {
            check("Anki test decks readable (" + ex + ")", false);
        }
    }

    /**
     * Decks exported by Anki's own code (tools/real-anki-decks.py), checked against anki-says.tsv:
     * how Anki itself shows each card (question, answer, schedule), written by the same script.
     */
    static void realAnkiExports() {
        System.out.println("Anki's own exports (tools/real-anki-decks.py)");
        try {
            com.reelcards.engine.anki.AnkiPackage.Result latest = readDeck("real/anki-latest.apkg"), legacy = readDeck("real/anki-legacy.apkg"), colpkg = readDeck("real/anki-collection.colpkg");
            List<Card> cs = latest.cards;
            check("reads Anki's exports (the default, \"support older Anki versions\" and a collection package) to the same " + cs.size() + " cards",
                    cs.size() == 26 && dump(cs).equals(dump(legacy.cards)) && dump(cs).equals(dump(colpkg.cards)));
            List<String[]> says = new java.util.ArrayList<>();
            long crt = 0;
            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(deckFile("real/anki-says.tsv")), "UTF-8"));
            try {
                for (String line; (line = in.readLine()) != null; ) {
                    String[] f = line.split("\t", -1);
                    if (line.startsWith("# crt")) crt = Long.parseLong(f[1]);
                    else if (!line.startsWith("#")) says.add(f);
                }
            } finally { in.close(); }
            List<String> wrong = new java.util.ArrayList<>(), wrongSched = new java.util.ArrayList<>();
            int shown = 0, leftOut = 0, io = 0, reviewed = 0;
            for (String[] r : says) {
                Card c = byId(cs, "apkg:" + r[0]);
                String type = r[1], q = r[4].replace("\\n", "\n"), a = r[5].replace("\\n", " ");
                if (r[3].equals("1") || q.contains("[anki:play")) { leftOut++; if (c != null) wrong.add(r[0] + " should be left out"); continue; }
                if (c == null) { wrong.add(r[0] + " missing"); continue; }
                shown++;
                boolean ok;
                if (type.equals("Image Occlusion")) {
                    io++;
                    ok = c.occlusionTarget == Integer.parseInt(r[2]) + 1 && q.startsWith(c.front) && c.extra.length() > 0 && a.contains(c.extra);
                } else {
                    StringBuilder want = new StringBuilder();
                    for (String l : q.split("\n")) if (!l.contains("[[type:")) want.append(want.length() > 0 ? "\n" : "").append(l);
                    ok = c.front.equals(want.toString().replace("[...]", "[…]"));
                    if (!a.contains("[[type:")) { // a typed answer is filled in by Anki's reviewer, not the template
                        for (String part : c.back.split(c.cloze ? "; " : "\n")) if (!a.contains(part)) ok = false;
                        if (!c.extra.isEmpty() && !a.replace(" ", "").contains(c.extra.replace(" ", ""))) ok = false;
                    }
                }
                if (r[6].equals("2")) {
                    reviewed++;
                    long due = (crt + Long.parseLong(r[7]) * 86400L) * 1000L;
                    boolean mem = r[9].isEmpty() ? c.stability == Math.max(1, Long.parseLong(r[8]))
                            : Math.abs(c.stability - Double.parseDouble(r[9])) < 1e-3 && Math.abs(c.difficulty - Double.parseDouble(r[10])) < 1e-3;
                    if (c.state != Card.REVIEW || c.due != due || !mem) wrongSched.add(r[0] + " s=" + c.stability + " d=" + c.difficulty + " due=" + c.due);
                } else if (c.state != Card.NEW) wrongSched.add(r[0] + " should be new");
                if (!ok) wrong.add(r[0] + " " + type + ": \"" + c.front + "\" / \"" + c.back + "\"");
            }
            check("each card reads as Anki shows it: " + shown + " questions and answers (lists, line breaks, entities, subscript, cloze hints, nested cloze, reversed, optional reversed, typed, a custom note type), "
                    + leftOut + " left out (suspended, audio only)" + (wrong.isEmpty() ? "" : " " + wrong), wrong.isEmpty() && shown == 26 && leftOut == 2);
            check("the " + reviewed + " reviewed cards keep Anki's FSRS memory state, interval and due date; the rest stay new" + (wrongSched.isEmpty() ? "" : " " + wrongSched), reviewed == 4 && wrongSched.isEmpty());
            boolean labels = true;
            for (Card c : cs) if (c.occlusion != null) {
                boolean label = false;
                for (Occlusion.Shape sh : Occlusion.parse(c.occlusion)) if ("text".equals(sh.kind)) label = sh.ord == 0 && "Aorta".equals(sh.text);
                if (!label || c.imageW != 400 || c.imageH != 300) labels = false;
            }
            check("image occlusion: " + io + " cards, each covering the shape Anki's card does; the text label is drawn but isn't a card", io == 4 && labels);
        } catch (java.io.IOException ex) {
            check("Anki's own exports readable (" + ex + ")", false);
        }
    }

    /** AnkiDroid hands over a note's fields and note type; they go through the same rules as an imported deck. */
    static void ankiDroidNotes() {
        System.out.println("AnkiDroid notes");
        List<String[]> basicT = new java.util.ArrayList<>(), clozeT = new java.util.ArrayList<>(), ioT = new java.util.ArrayList<>();
        basicT.add(new String[]{"Card 1", "{{Front}}", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Back}}"});
        clozeT.add(new String[]{"Cloze", "{{cloze:Text}}", "{{cloze:Text}}<br>\n{{Back Extra}}"});
        ioT.add(new String[]{"Image Occlusion", "{{#Header}}<div>{{Header}}</div>{{/Header}}<div style=\"display: none\">{{cloze:Occlusion}}</div>{{Image}}", "{{Image}}"});
        com.reelcards.engine.anki.AnkiPackage.NoteCard cz = com.reelcards.engine.anki.AnkiPackage.noteCard("Cloze", true, list("Text", "Back Extra"), clozeT,
                "{{c1::Mitochondria}} are the site of {{c2::aerobic respiration::type of respiration}}.\u001fThey have a folded inner membrane.", "", "GCSE Biology::Cells", "Biology", 1);
        com.reelcards.engine.anki.AnkiPackage.NoteCard br = com.reelcards.engine.anki.AnkiPackage.noteCard("Basic", false, list("Front", "Back"), basicT,
                "Stages of mitosis?\u001fProphase<br>Metaphase<br>Anaphase<br>Telophase", "", "Biology", "Biology", 0);
        check("cloze from AnkiDroid: the gap is a blank and the answer is just the deletion (\"" + (cz.card == null ? "" : cz.card.front) + "\")",
                cz.card != null && cz.card.cloze && cz.card.front.equals("Mitochondria are the site of [type of respiration].") && cz.card.back.equals("aerobic respiration")
                && cz.card.extra.equals("Mitochondria are the site of aerobic respiration. They have a folded inner membrane."));
        check("line breaks from AnkiDroid stay separate points (4 stages, not one run-on line)", br.card != null && AnswerShape.of(br.card).points.size() == 4);
        com.reelcards.engine.anki.AnkiPackage.NoteCard pic = com.reelcards.engine.anki.AnkiPackage.noteCard("Basic", false, list("Front", "Back"), basicT,
                "Which organelle is this?<br><img src=\"organelle.png\">\u001fA mitochondrion", "", "Biology", "Biology", 0);
        com.reelcards.engine.anki.AnkiPackage.NoteCard io = com.reelcards.engine.anki.AnkiPackage.noteCard("Image Occlusion", true, list("Occlusion", "Image", "Header", "Back Extra", "Comments"), ioT,
                "{{c1::image-occlusion:rect:left=.2:top=.3:width=.2:height=.4:oi=1}}\u001f<img src=\"heart.png\">\u001fLabel the heart\u001f\u001f", "", "Biology", "Biology", 0);
        com.reelcards.engine.anki.AnkiPackage.NoteCard ansPic = com.reelcards.engine.anki.AnkiPackage.noteCard("Basic", false, list("Front", "Back"), basicT,
                "What does a mitochondrion look like?\u001fBean-shaped, with folds <img src=\"organelle.png\">", "", "Biology", "Biology", 0);
        check("a question that needs a picture, and image occlusion, are flagged for the \"export as .apkg\" tip; a picture only on the answer side still shows",
                pic.card == null && pic.needsPicture && io.card == null && io.needsPicture && ansPic.card != null && !ansPic.needsPicture && ansPic.card.back.equals("Bean-shaped, with folds"));
        check("numeric HTML entities are decoded (&#x27; &#8217; from decks made by scripts), escaped ones aren't",
                com.reelcards.engine.anki.AnkiPackage.clean("What&#x27;s it&#8217;s &amp;#39; &#0;").equals("What's it’s &#39; &#0;"));
    }

    // ------------------------------------------------------------------ notes

    static void notes() {
        System.out.println("Notes to cards");
        String notes = "Osmosis - diffusion of water across a partially permeable membrane\n"
            + "• Mitochondria: site of aerobic respiration\n"
            + "Q: Which organ makes bile?\nA: The liver\n"
            + "Enzymes are biological catalysts.\n"
            + "photosynthesis\tCO2 + water -> glucose + oxygen\n"
            + "The powerhouse bit I never remember lol\n";
        NotesParser.Result r = NotesParser.parse(notes, "My notes");
        for (Card c : r.cards) System.out.println("        " + c.front + "  |  " + c.back);
        check("5 cards from 6 facts, 1 non-fact line skipped", r.cards.size() == 5 && r.skippedLines == 1);
        check("\"X is Y\" becomes a question", r.cards.size() > 3 && r.cards.get(3).front.equals("What are enzymes?"));
        String reply = "Here are your flashcards:\n\n**Q:** What does the mitochondria do?\n**A:** Aerobic respiration\n\n"
            + "Q1: Name the three main parts of a neurone.\nA1: cell body; axon; dendrites\n\nQuestion 2: What is the charge on an electron?\nAnswer 2: −1\n";
        NotesParser.Result ai = NotesParser.parse(reply, "Biology");
        check("a chatbot's reply to the \"Make cards with AI\" prompt becomes cards (bold labels, Q1:/A1:, its intro line left out)",
                ai.cards.size() == 3 && ai.skippedLines == 1 && ai.cards.get(0).front.equals("What does the mitochondria do?") && ai.cards.get(0).back.equals("Aerobic respiration")
                && ai.cards.get(2).back.equals("−1"));
        check("a several-part answer on one A: line (as the prompt asks) is a 3-mark card", ai.cards.size() > 1 && AnswerShape.of(ai.cards.get(1)).points.size() == 3);
        check("the prompt asks for exactly the Q:/A: lines the parser reads", NotesParser.AI_PROMPT.contains("Q: the question\nA: the answer\n")
                && NotesParser.parse("Q: the question\nA: the answer\n", "x").cards.size() == 1 && NotesParser.AI_PROMPT.endsWith("My notes:\n\n"));
    }

    // ------------------------------------------------------------------ forecast

    static void forecast() {
        System.out.println("Forecast");
        Engine e = fresh(12);
        e.settings.minutesPerDay = 210;
        Stats.Forecast f = e.stats.forecast(e, T0);
        System.out.println("        210 min/day -> " + f.cardsPerDay + " cards/day, " + f.newPerDay + " new/day, deck in " + f.daysToCover + " days");
        check("3.5 h of reels ~ 150 cards a day at the default rhythm (the Normal cap)", f.cardsPerDay == 150 && f.capped);
    }

    // ------------------------------------------------------------------ helpers

    static Engine fresh(long seed) {
        Engine e = new Engine();
        e.setRandom(new Random(seed));
        e.tz = java.util.TimeZone.getTimeZone("UTC");
        e.addCards(SampleDecks.get(SampleDecks.BIOLOGY));
        e.addCards(SampleDecks.get(SampleDecks.CHEMISTRY));
        return e;
    }

    /** Swipe until a card appears. */
    static Show next(Engine e, long t) {
        Show s = null;
        for (int i = 0; i < 60 && s == null; i++) { t += 12_000; s = e.onReelSwipe(t, "tiktok"); }
        return s;
    }

    static Outcome liked(long dwell) { Outcome o = new Outcome(); o.revealed = true; o.liked = true; o.revealAtMs = 2500; o.likeAtMs = 3500; o.dwellMs = dwell; return o; }

    static Outcome skip(long dwell) { Outcome o = new Outcome(); o.dwellMs = dwell; return o; }

    static List<String> list(String... s) { return Arrays.asList(s); }
}
