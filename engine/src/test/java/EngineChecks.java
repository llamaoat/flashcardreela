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

    public static void main(String[] args) {
        fsrsMatchesReference();
        gradingFromGestures();
        rhythm();
        skipping();
        whichCard();
        calibration();
        formats();
        copy();
        longAnswers();
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
