import com.reelcards.engine.*;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Simulated students using the real engine for 30 days. This is a model, not user data: it shows
 * the engine behaves sensibly and compares engine versions under the same assumptions.
 *
 * Each simulated student:
 *  - scrolls a set number of minutes a day in 3 sessions; most reels ~11 s, 1 in 10 absorbing (30-60 s)
 *  - has a taste: some card looks they engage with, others they swipe past in under a second
 *  - has a tolerance: if cards come more often than every T reels (T = 3..12), they engage less
 *  - has a hidden memory of each card (FSRS-6 with a personal forgetting rate of x0.7..x1.4);
 *    they "know" a card with that hidden recall probability, and answer faster when they know it well
 *
 * Three engine versions, same students:
 *   baseline  = random looks + fixed rhythm (roughly the first prototype)
 *   +looks    = adaptive looks (recovering contextual bandit)
 *   full      = adaptive looks + learned rhythm with natural-break timing
 *
 *   ./gradlew :engine:simulate     (or)  java -cp out Simulate
 */
public class Simulate {
    static final long DAY = 86_400_000L, MIN = 60_000L;
    static final long T0 = 1_788_253_200_000L;

    static final String[] PERSONAS = {"quiz fan", "chat fan", "gamer", "no preference"};

    static double taste(String persona, Format f) {
        Map<String, Double> p = new HashMap<>();
        switch (persona) {
            case "quiz fan": p.put("layout:quiz", .9); p.put("hook:mostwrong", .85); p.put("hook:rate", .6); p.put("layout:comment", .5); break;
            case "chat fan": p.put("layout:chat", .9); p.put("hook:text2am", .9); p.put("layout:comment", .8); p.put("hook:pov", .6); break;
            case "gamer": p.put("bg:gameplay", .95); p.put("layout:split", .75); p.put("hook:waitforit", .65); p.put("layout:captions", .6); break;
            default: return .55;
        }
        double sum = 0; int n = 0;
        for (String d : Format.DIMS) { Double v = p.get(d + ":" + f.get(d)); if (v != null) { sum += v; n++; } }
        return n == 0 ? .18 : sum / n * .9 + .05;
    }

    static final class Run {
        int reels, cards, reviews, skips, engaged, learned, deck;
        double examRecall, trueScale, fittedScale;
        double lossCal, lossBase; int lossN;
    }

    static Run simulate(String persona, int minutesPerDay, String version, long seed, int days) {
        Random rnd = new Random(seed);
        Engine e = new Engine();
        e.setRandom(new Random(seed * 31 + 7));
        e.tz = java.util.TimeZone.getTimeZone("UTC");
        e.bandit.learn = !version.equals("baseline");
        e.adaptiveRhythm = version.equals("full");
        e.settings.examDate = T0 + days * DAY;
        e.addCards(SampleDecks.get(SampleDecks.BIOLOGY));
        e.addCards(SampleDecks.get(SampleDecks.CHEMISTRY));
        e.addCards(SampleDecks.get(SampleDecks.SPANISH));
        java.util.List<Card> extra = new java.util.ArrayList<>();
        for (int i = 1; i <= 530; i++) extra.add(new Card("syn-" + i, "Synthetic " + (i % 3), "Synthetic question number " + i, "Answer " + i + (i % 2 == 0 ? " (longer form)" : ""), ""));
        e.addCards(extra);

        double ability = 0.7 + rnd.nextDouble() * 0.7;
        double tolerance = 3 + rnd.nextDouble() * 9;
        Map<String, Card> hidden = new HashMap<>();
        Fsrs truth = new Fsrs();
        truth.rnd = new Random(seed);
        Run run = new Run();
        run.deck = e.cards.size();
        run.trueScale = ability;
        int reelsSinceCard = 0;

        for (int d = 0; d < days; d++) {
            long dayStart = T0 + d * DAY;
            for (long start : new long[]{dayStart, dayStart + 5 * 60 * MIN, dayStart + 11 * 60 * MIN}) {
                long t = start, end = start + minutesPerDay / 3 * MIN;
                while (t < end) {
                    boolean absorbing = rnd.nextDouble() < 0.1;
                    double sec = absorbing ? 30 + rnd.nextDouble() * 30 : Math.max(1.5, 11 * Math.exp(rnd.nextGaussian() * 0.6));
                    t += (long) (sec * 1000);
                    run.reels++;
                    reelsSinceCard++;
                    Show s = e.onReelSwipe(t, "tiktok");
                    if (s == null) continue;
                    run.cards++;
                    double annoyance = reelsSinceCard >= tolerance ? 1 : Math.pow(reelsSinceCard / tolerance, 1.2);
                    reelsSinceCard = 0;
                    Outcome o = new Outcome();
                    if (rnd.nextDouble() > taste(persona, s.format) * annoyance) {
                        o.dwellMs = 300 + rnd.nextInt(700);
                    } else {
                        run.engaged++;
                        Card h = hidden.get(s.card.id);
                        double recall = h == null ? 0.2 : truth.retrievability((t - h.lastReview) / (double) DAY, h.stability * ability);
                        boolean knows = rnd.nextDouble() < recall;
                        boolean fast = knows && rnd.nextDouble() < recall;
                        long read = Grade.readingMs(s.card.front);
                        o.dwellMs = 3000 + rnd.nextInt(4000);
                        if (s.isQuiz()) {
                            o.revealed = true;
                            o.pickAtMs = read + (fast ? 1500 : 9000);
                            if (knows && fast && rnd.nextDouble() < .3) { o.liked = true; o.likedBeforeReveal = true; o.likeAtMs = read + 800; }
                            o.quizPick = knows || rnd.nextBoolean() ? s.script.correct : 1 - s.script.correct;
                        } else if (knows) {
                            if (rnd.nextDouble() < .45) { o.liked = true; o.likedBeforeReveal = true; o.likeAtMs = read + (fast ? 1000 : 5000); }
                            else { o.revealed = true; o.revealAtMs = read + 2000; o.liked = true; o.likeAtMs = read + 3500; }
                        } else {
                            o.revealed = true; o.revealAtMs = read + 3000;
                        }
                        Card ec = s.card;
                        if (e.calibration.fittedOn > 0 && ec.state == Card.REVIEW && ec.lastReview > 0 && t - ec.lastReview >= DAY) {
                            double pc = clamp(e.fsrs.recallNow(ec, t)), pb = clamp(e.fsrs.retrievability((t - ec.lastReview) / (double) DAY, ec.stability));
                            run.lossCal -= knows ? Math.log(pc) : Math.log(1 - pc);
                            run.lossBase -= knows ? Math.log(pb) : Math.log(1 - pb);
                            run.lossN++;
                        }
                        if (h == null) { h = new Card(s.card.id, "", "", "", ""); hidden.put(s.card.id, h); }
                        truth.review(h, knows ? Grade.GOOD : Grade.AGAIN, t, 0);
                    }
                    t += o.dwellMs;
                    Engine.Result r = e.onCardFinished(s, o, t);
                    if (r.grade == null) run.skips++; else run.reviews++;
                }
            }
        }
        long exam = T0 + days * DAY;
        double sum = 0;
        for (Card c : e.cards) {
            Card h = hidden.get(c.id);
            sum += h == null ? 0 : truth.retrievability((exam - h.lastReview) / (double) DAY, h.stability * ability);
            if (c.state == Card.REVIEW) run.learned++;
        }
        run.examRecall = sum / e.cards.size();
        run.fittedScale = e.calibration.scale;
        return run;
    }

    static double clamp(double p) { return Math.max(1e-4, Math.min(1 - 1e-4, p)); }

    public static void main(String[] args) {
        int days = 30, students = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        System.out.println("Reelcards engine: " + students + " simulated students per row, " + days + " days,");
        System.out.println("600-card deck (70 real GCSE cards + 530 synthetic), exam on day " + days + ". A model, not user data.");
        System.out.println();
        System.out.println(String.format(Locale.UK, "%-14s %7s %-9s %9s %9s %11s %9s %11s",
                "Persona", "Min/day", "Engine", "Cards/day", "Skipped", "Engaged/day", "Learned", "Exam recall"));
        String[] versions = {"baseline", "+looks", "full"};
        Map<String, double[]> totals = new HashMap<>();
        double lossCal = 0, lossBase = 0; int lossN = 0;
        for (String persona : PERSONAS) {
            for (int mins : new int[]{60, 210}) {
                for (String v : versions) {
                    double cards = 0, skip = 0, engaged = 0, learned = 0, recall = 0;
                    for (int s = 0; s < students; s++) {
                        Run r = simulate(persona, mins, v, 1000 + s, days);
                        cards += r.cards / (double) days;
                        skip += r.cards == 0 ? 0 : r.skips / (double) r.cards;
                        engaged += r.engaged / (double) days;
                        learned += r.learned / (double) r.deck;
                        recall += r.examRecall;
                        if (v.equals("full")) { lossCal += r.lossCal; lossBase += r.lossBase; lossN += r.lossN; }
                    }
                    System.out.println(String.format(Locale.UK, "%-14s %7d %-9s %9.0f %8.0f%% %11.0f %8.0f%% %10.0f%%",
                            persona, mins, v, cards / students, 100 * skip / students, engaged / students, 100 * learned / students, 100 * recall / students));
                    double[] t = totals.get(v);
                    if (t == null) { t = new double[4]; totals.put(v, t); }
                    t[0] += skip / students; t[1] += engaged / students; t[2] += recall / students; t[3]++;
                }
            }
        }
        System.out.println();
        for (String v : versions) {
            double[] t = totals.get(v);
            System.out.println(String.format(Locale.UK, "%-9s average: %4.0f%% skipped, %4.0f engaged cards a day, %3.0f%% modelled exam-day recall",
                    v, 100 * t[0] / t[3], t[1] / t[3], 100 * t[2] / t[3]));
        }
        if (lossN > 0) System.out.println(String.format(Locale.UK, "Memory calibration: recall predictions on %d later reviews, log-loss %.3f with calibration vs %.3f without (lower is better).", lossN, lossCal / lossN, lossBase / lossN));
        System.out.println("\"Engaged\" = cards answered, liked or read (not swiped away). \"Learned\" = graduated to review. \"Exam recall\" = modelled chance of recalling each deck card on exam day.");
    }
}
