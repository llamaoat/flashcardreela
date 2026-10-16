package com.reelcards.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;

/**
 * The Reelcards engine. Every time the student swipes to a new reel it answers three questions:
 *
 *  1. WHEN  is it time for a card?
 *           A learned rhythm (Rhythm): starts at the student's setting and settles on the spacing
 *           that gets the most cards answered per reel scrolled. Waits for natural breaks: not
 *           straight after a reel they were absorbed in, a little early when they're skimming.
 *           Warm-up, daily cap, quiet mode after 5 fast skips in a row, more often near the exam.
 *  2. WHICH card?
 *           Skipped cards first, then learning steps, then due reviews with the lowest predicted
 *           recall (recall *at the exam* when one is close), mixing subjects, then new cards paced
 *           so the deck is covered before the exam.
 *  3. LOOK  how should it look?  FormatBandit.
 *
 * Then it grades what the student did (response-time aware), schedules with FSRS-6, learns the
 * student's forgetting rate (Calibration), and rewards the look they saw.
 *
 * Pure Java: the Android app calls it from the accessibility service; the tests and the
 * simulator run it on a normal JVM.
 */
public final class Engine {
    public static final long MIN = 60_000L;
    public static final long DAY = 86_400_000L;
    public static final long SESSION_GAP = 10 * MIN;      // 10 minutes without a swipe = new session
    static final double ABSORBED_SEC = 25, SKIMMING_SEC = 3;

    public final List<Card> cards = new ArrayList<>();
    public final Settings settings = new Settings();
    public final FormatBandit bandit = new FormatBandit();
    public final Stats stats = new Stats();
    public final Fsrs fsrs = new Fsrs();
    public final Calibration calibration = new Calibration();
    public final Rhythm rhythm = new Rhythm();
    /** Cards held back for now, never shown (not saved): the app puts a card here when its picture or sound is missing. */
    public final Set<String> hidden = new HashSet<>();
    public CopyWriter writer = new CopyWriter();
    /** false = fixed rhythm with no natural-break timing (the simulator's baseline). */
    public boolean adaptiveRhythm = true;
    public GradeSink ankiSink;
    public Random rnd = new Random();
    public TimeZone tz = TimeZone.getDefault();

    // session / day state
    public int sessionId, sessionReels, reelsSinceCard, cardsToday, newToday, postponed;
    public long lastSwipeAt, sessionStartAt, snoozeUntil, currentDayKey = Long.MIN_VALUE;
    public int rhythmArmForNext = -1; // rhythm arm that set the gap before the next card
    public String lastHost, lastDeckShown;
    public final List<Boolean> recentFast = new ArrayList<>();
    public final List<Double> recentReelSec = new ArrayList<>();
    public final List<Requeue> requeue = new ArrayList<>();
    public final Set<String> newShownToday = new HashSet<>();
    public Decision lastDecision;
    public final LinkedList<String> log = new LinkedList<>();

    /** Receives grades for AnkiDroid cards so they can be sent back to Anki. */
    public interface GradeSink { void onGrade(Card c, Grade g, long timeTakenMs); }

    public static final class Requeue {
        public String cardId;
        public int atReel;
        public Requeue() {}
        public Requeue(String id, int at) { cardId = id; atReel = at; }
    }

    public static final class Decision {
        public boolean show;
        public int gap;
        public String blocked;
        public final List<String> reasons = new ArrayList<>();
    }

    public static final class Result {
        public Grade grade;
        public String summary;
        public double intervalDays = -1;
        public double reward;
    }

    /** Same seed for the engine, the scheduler's fuzz and the format picker. */
    public void setRandom(Random r) { rnd = r; fsrs.rnd = r; }

    // ------------------------------------------------------------------ cards

    public int addCards(Collection<Card> add) {
        Set<String> have = new HashSet<>();
        for (Card c : cards) have.add(c.id);
        int n = 0;
        for (Card c : add) {
            if (c.id == null || have.contains(c.id)) continue;
            cards.add(c); have.add(c.id); n++;
        }
        return n;
    }

    public Card find(String id) {
        for (Card c : cards) if (c.id.equals(id)) return c;
        return null;
    }

    public void removeDeck(String deck) {
        for (int i = cards.size() - 1; i >= 0; i--) if (deck.equals(cards.get(i).deck)) cards.remove(i);
    }

    public List<String> decks() {
        List<String> out = new ArrayList<>();
        for (Card c : cards) if (!out.contains(c.deck)) out.add(c.deck);
        return out;
    }

    public List<Card> savedCards() {
        List<Card> out = new ArrayList<>();
        for (Card c : cards) if (c.saved) out.add(c);
        return out;
    }

    public List<Card> leeches() {
        List<Card> out = new ArrayList<>();
        for (Card c : cards) if (c.isLeech()) out.add(c);
        return out;
    }

    /** Sets the student's vibes and seeds the format picker with matching starting tastes (once per vibe). */
    public void setVibes(Collection<String> vibes) {
        List<String> added = new ArrayList<>();
        for (String v : vibes) if (!settings.vibes.contains(v)) added.add(v);
        settings.vibes.clear();
        settings.vibes.addAll(vibes);
        bandit.applyVibes(added);
    }

    // ------------------------------------------------------------------ time

    public long dayKey(long now) { return Math.floorDiv(now + tz.getOffset(now), DAY); }

    public int examDaysLeft(long now) {
        if (settings.examDate <= 0) return -1;
        if (settings.examDate <= now) return 0;
        return (int) Math.ceil((settings.examDate - now) / (double) DAY);
    }

    void rollDay(long now) {
        long k = dayKey(now);
        if (k != currentDayKey) {
            currentDayKey = k;
            cardsToday = 0;
            newToday = 0;
            newShownToday.clear();
        }
    }

    /** @return true if this swipe started a new session */
    boolean rollSession(long now) {
        if (lastSwipeAt == 0 || now - lastSwipeAt > SESSION_GAP) {
            sessionId++;
            sessionReels = 0;
            reelsSinceCard = 0;
            postponed = 0;
            sessionStartAt = now;
            requeue.clear();
            recentReelSec.clear();
            for (Card c : cards) c.sessionBounces = 0;
            return true;
        }
        return false;
    }

    boolean buried(Card c) { return c.buriedUntilSession >= sessionId || hidden.contains(c.id); }

    /** The student went back to a card they had swiped past and answered it: it needn't come round again in a few reels. */
    public void dropRequeue(Card c) {
        for (int i = requeue.size() - 1; i >= 0; i--) if (requeue.get(i).cardId.equals(c.id)) requeue.remove(i);
    }

    boolean inRequeue(Card c) {
        for (Requeue r : requeue) if (r.cardId.equals(c.id)) return true;
        return false;
    }

    public int dueCount(long now) {
        int n = 0;
        for (Card c : cards) if (c.state != Card.NEW && c.due <= now && !buried(c)) n++;
        return n;
    }

    public int newRemaining() {
        int n = 0;
        for (Card c : cards) if (c.state == Card.NEW) n++;
        return n;
    }

    public int learnedCount() {
        int n = 0;
        for (Card c : cards) if (c.state == Card.REVIEW) n++;
        return n;
    }

    // ------------------------------------------------------------------ pacing

    /**
     * New cards allowed today. With an exam date, raised so every card is first seen by ~80% of the
     * way to the exam, leaving the rest for reviews (up to 3x the setting).
     */
    public int newLimit(long now) {
        int base = settings.newPerDay();
        int ex = examDaysLeft(now);
        if (ex <= 0 || settings.isDemo()) return base;
        int need = (int) Math.ceil(newRemaining() / Math.max(1.0, ex * 0.8));
        return Math.max(base, Math.min(need, base * 3));
    }

    /** The current rhythm: the starting setting, times the learned tolerance, times exam/backlog pressure. */
    public int currentGap(long now) {
        double g = settings.baseGap() * (settings.isDemo() || !adaptiveRhythm || !rhythm.seeded ? 1 : rhythm.multiplier());
        int ex = examDaysLeft(now);
        if (dueCount(now) >= 20) g *= 0.85;
        if (ex >= 0 && ex <= 2) g *= 0.6;
        else if (ex >= 0 && ex <= 7) g *= 0.75;
        int lo = settings.isDemo() ? 1 : 2;
        return (int) Math.max(lo, Math.min(15, Math.round(g)));
    }

    /** Plain-English description of the learned rhythm for the dashboard. */
    public String rhythmDescription() {
        int base = settings.baseGap();
        if (settings.isDemo() || !rhythm.seeded || rhythm.evidence() < 15) return "Rhythm: 1 card every " + base + " reels (your setting). It adapts once you've seen a few cards.";
        long best = Math.round(base * Rhythm.MULT[rhythm.bestArm(base)]);
        if (best == base) return "Rhythm: 1 card every " + base + " reels suits you best so far.";
        return "Rhythm learned from you: 1 card every " + best + " reels gets you the most cards answered (you started at " + base + ").";
    }

    // ------------------------------------------------------------------ 1. WHEN

    public Decision decide(long now) {
        Decision d = new Decision();
        int base = settings.baseGap();
        d.gap = base;
        if (settings.pausedUntil > now) { d.blocked = "Paused by you"; return d; }
        if (snoozeUntil > now) {
            d.blocked = "Quiet mode after lots of fast skips (back in " + Math.max(1, (snoozeUntil - now) / MIN) + " min)";
            return d;
        }
        int warm = settings.isDemo() ? 1 : 3;
        if (sessionReels < warm) { d.blocked = "Warm-up: no cards in the first few reels of a session"; return d; }
        if (cardsToday >= settings.dailyCap()) { d.blocked = "Daily limit of " + settings.dailyCap() + " cards reached"; return d; }

        int gap = currentGap(now);
        d.gap = gap;
        int ex = examDaysLeft(now);
        int due = dueCount(now);
        boolean avail = due > 0 || readyRequeue() != null || newAvailable(now) || (ex >= 0 && ex <= 3 && learnedCount() > 0);
        if (!avail) { d.blocked = "Nothing due, and today's new cards are done"; return d; }

        d.reasons.add("1 card every " + gap + " reels" + (rhythm.seeded && adaptiveRhythm && !settings.isDemo() && rhythm.multiplier() != 1.0
                ? " (rhythm being learned; you started at " + base + ")" : " (your setting)"));
        if (due >= 20) d.reasons.add(due + " cards due, so a little more often");
        if (ex >= 0 && ex <= 7) d.reasons.add("Exam in " + ex + " days: cram mode");

        double last = recentReelSec.isEmpty() ? 0 : recentReelSec.get(recentReelSec.size() - 1);
        boolean skimming = recentReelSec.size() >= 3;
        for (int i = Math.max(0, recentReelSec.size() - 3); i < recentReelSec.size(); i++) if (recentReelSec.get(i) >= SKIMMING_SEC) skimming = false;

        if (reelsSinceCard >= gap) {
            if (adaptiveRhythm && last >= ABSORBED_SEC && postponed < 2 && !settings.isDemo()) {
                postponed++;
                d.reasons.add(String.format(Locale.UK, "You watched that reel for %.0f s, so waiting for a natural break", last));
                return d;
            }
            d.show = true;
        } else if (adaptiveRhythm && reelsSinceCard == gap - 1 && gap >= 3 && skimming) {
            d.show = true;
            d.reasons.add("You're skimming past reels: a good moment for a card");
        } else {
            d.reasons.add("Next card in " + (gap - reelsSinceCard) + " reels");
        }
        return d;
    }

    boolean newAvailable(long now) {
        if (newToday >= newLimit(now)) return false;
        for (Card c : cards) if (c.state == Card.NEW && !buried(c) && !inRequeue(c)) return true;
        return false;
    }

    Requeue readyRequeue() {
        for (Requeue r : requeue) if (sessionReels >= r.atReel) return r;
        return null;
    }

    // ------------------------------------------------------------------ 2. WHICH

    static final class Pick { Card card; String why; String shortWhy; }

    Pick pickCard(long now, boolean force) {
        Pick p = new Pick();
        Requeue r = readyRequeue();
        if (r != null) {
            requeue.remove(r);
            Card c = find(r.cardId);
            if (c != null && !buried(c)) {
                p.card = c; p.why = "You swiped past it earlier without looking, so it's back in a different look."; p.shortWhy = "back again";
                return p;
            }
        }
        int ex = examDaysLeft(now);
        boolean examFocus = ex > 0 && ex <= 14;

        // learning steps first (short, same-session), then reviews with the lowest predicted recall
        List<Card> learning = new ArrayList<>(), reviews = new ArrayList<>();
        for (Card c : cards) {
            if (c.state == Card.NEW || c.due > now || buried(c) || inRequeue(c)) continue;
            if (c.state == Card.LEARNING || c.state == Card.RELEARNING) learning.add(c); else reviews.add(c);
        }
        if (!learning.isEmpty()) {
            Card best = null;
            for (Card c : learning) if (best == null || c.due < best.due) best = c;
            p.card = best;
            p.why = best.state == Card.RELEARNING ? "You forgot this one recently, so it's back after a short step." : "Still learning this one: a short step after you last saw it.";
            p.shortWhy = "learning";
            return p;
        }
        if (!reviews.isEmpty()) {
            Card best = null; double bestR = 2;
            for (Card c : reviews) {
                double rr = c.isAnki() ? 0 : (examFocus ? fsrs.recallAt(c, settings.examDate) : fsrs.recallNow(c, now));
                if (rr < bestR) { bestR = rr; best = c; }
            }
            boolean switched = false;
            if (best != null && best.deck != null && best.deck.equals(lastDeckShown)) {
                // interleave: mixing subjects beats blocking one topic, so switch if a close alternative exists
                Card alt = null; double altR = 2;
                for (Card c : reviews) {
                    if (c.deck != null && c.deck.equals(lastDeckShown)) continue;
                    double rr = c.isAnki() ? 0 : (examFocus ? fsrs.recallAt(c, settings.examDate) : fsrs.recallNow(c, now));
                    if (rr < altR) { altR = rr; alt = c; }
                }
                if (alt != null && altR <= bestR + 0.10) { best = alt; bestR = altR; switched = true; }
            }
            p.card = best;
            if (best.isAnki()) p.why = "Due in your Anki deck.";
            else p.why = String.format(Locale.UK, "Due. Predicted recall %s %d%%, the lowest of your due cards%s.",
                    examFocus ? "on exam day" : "now", Math.round(bestR * 100), switched ? " from a different subject (mixing subjects helps)" : "");
            if (best.isLeech()) p.why += " It's a tricky one: forgotten " + best.lapses + " times.";
            p.shortWhy = best.isLeech() ? "tricky one" : "due today";
            return p;
        }
        int limit = newLimit(now);
        if (newToday < limit || force) {
            Map<String, Integer> perDeck = new HashMap<>();
            for (String id : newShownToday) { Card c = find(id); if (c != null) perDeck.put(c.deck, (perDeck.containsKey(c.deck) ? perDeck.get(c.deck) : 0) + 1); }
            Card pickNew = null; int fewest = Integer.MAX_VALUE;
            for (Card c : cards) {
                if (c.state != Card.NEW || buried(c) || inRequeue(c)) continue;
                int k = (perDeck.containsKey(c.deck) ? perDeck.get(c.deck) : 0) * 2 + (c.deck != null && c.deck.equals(lastDeckShown) ? 1 : 0);
                if (k < fewest) { fewest = k; pickNew = c; }
            }
            if (pickNew != null) {
                p.card = pickNew;
                p.why = "New card (" + (newToday + 1) + " of " + limit + " today" + (limit > settings.newPerDay() ? ", raised so you cover the deck before your exam" : "") + "). No reviews were due.";
                p.shortWhy = "new";
                return p;
            }
        }
        if ((ex >= 0 && ex <= 3) || force) {
            // final sweep before the exam (or a test card): weakest card at exam time, even if not due
            Card weak = null; double wr = 2;
            long at = ex >= 0 ? settings.examDate : now;
            for (Card c : cards) {
                if (c.state == Card.NEW || buried(c) || inRequeue(c)) continue;
                double rr = fsrs.recallAt(c, at);
                if (rr < wr) { wr = rr; weak = c; }
            }
            if (weak == null && force && !cards.isEmpty()) weak = cards.get(rnd.nextInt(cards.size()));
            if (weak != null && (force || wr < 0.97)) {
                p.card = weak;
                p.why = force ? "Test card." : String.format(Locale.UK, "Exam in %d days: final sweep of your weakest card (%d%% predicted on the day).", ex, Math.round(wr * 100));
                p.shortWhy = force ? "test" : "exam soon";
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ main entry points

    /**
     * Call on every detected reel swipe.
     * @param host the app the swipe happened in ("tiktok", "instagram", "youtube", "practice"…)
     * @return a card to show, or null to let the reel play
     */
    public Show onReelSwipe(long now, String host) {
        rollDay(now);
        boolean fresh = rollSession(now);
        if (!fresh) {
            double sec = (now - lastSwipeAt) / 1000.0;
            stats.onReel(currentDayKey, sec);
            if (sec > 0 && sec <= 120) { recentReelSec.add(sec); while (recentReelSec.size() > 6) recentReelSec.remove(0); }
        } else stats.day(currentDayKey).reels++;
        lastSwipeAt = now;
        lastHost = host;
        sessionReels++;
        reelsSinceCard++;
        Decision d = decide(now);
        lastDecision = d;
        if (!d.show) return null;
        return makeShow(now, d, false);
    }

    /** A card right now, whatever the rhythm says (setup wizard "show me a test card", demo button). */
    public Show forceShow(long now, String host) {
        rollDay(now);
        if (lastSwipeAt == 0) { lastSwipeAt = now; sessionId++; }
        if (host != null) lastHost = host;
        Decision d = new Decision();
        d.show = true; d.gap = currentGap(now);
        d.reasons.add("Requested now (test card)");
        return makeShow(now, d, true);
    }

    public Show forceShow(long now) { return forceShow(now, lastHost); }

    /**
     * The card for the home-screen widget: the one a reel would show now (a due review first, then a new
     * card), or null. Looking doesn't count as a reel or a card shown, so the rhythm and looks don't learn
     * from it. Mirrors pickForWidget() in engine.js.
     */
    public Card pickForWidget(long now) {
        rollDay(now);
        Pick p = pickCard(now, true);
        return p == null ? null : p.card;
    }

    /** The widget's answer ("Knew it" is Good, "Again" is Again): scheduled like a reel card, without touching looks or rhythm. */
    public Result gradeOutside(Card c, Grade g, long now) {
        rollDay(now);
        Result res = new Result();
        res.grade = g;
        Stats.Day day = stats.day(currentDayKey);
        day.reviews++;
        if (g == Grade.AGAIN) day.again++; else if (g == Grade.HARD) day.hard++; else if (g == Grade.EASY) day.easy++; else day.good++;
        if (c.state == Card.NEW && newShownToday.add(c.id)) { newToday++; day.newSeen++; }
        c.sessionBounces = 0;
        if (c.isAnki()) {
            if (ankiSink != null) ankiSink.onGrade(c, g, 0);
            c.reps++; c.lastGrade = g.label(); c.lastReview = now;
            c.due = now + 365 * DAY;
            if (c.state == Card.NEW) c.state = Card.REVIEW;
            res.summary = "Graded " + cap(g.label()) + " on the widget → sent to AnkiDroid";
        } else {
            if (c.state == Card.REVIEW && c.lastReview > 0) calibration.add((now - c.lastReview) / (double) DAY, c.stability, g != Grade.AGAIN);
            res.intervalDays = fsrs.review(c, g, now, settings.examDate);
            res.summary = "Graded " + cap(g.label()) + " on the widget → next in " + human(res.intervalDays);
            calibration.maybeRefit(fsrs);
        }
        addLog(res.summary);
        return res;
    }

    Show makeShow(long now, Decision d, boolean force) {
        Pick p = pickCard(now, force);
        if (p == null) return null;
        Card c = p.card;
        // the answer's shape decides which layouts can draw it (no quiz buttons for a 3-mark answer)
        java.util.Set<String> allowed = AnswerShape.layoutsFor(AnswerShape.of(c), c.back, CopyWriter.hasDistractor(c, cards));
        String avoid = (c.sessionBounces > 0 || (c.skips > 0 && c.lastGrade == null)) ? c.lastLayout : null;
        FormatBandit.Pick fp = bandit.pick(rnd, lastHost, avoid, allowed);
        CardScript sc = writer.write(c, fp.format, cards, rnd, p.shortWhy, settings.vibes);
        if ("quiz".equals(fp.format.layout) && sc.correct < 0) {
            fp.format.layout = "bigtext";
            sc = writer.write(c, fp.format, cards, rnd, p.shortWhy, settings.vibes);
        }
        Show s = new Show();
        s.card = c; s.format = fp.format; s.script = sc; s.shownAt = now; s.test = force; s.host = lastHost;
        s.reasonShort = p.shortWhy; s.exploredDim = fp.exploredDim; s.formatDetail.putAll(fp.detail);
        s.why.add("WHEN: " + join(d.reasons));
        s.why.add("WHICH: " + p.why);
        s.why.add("LOOK: " + Format.label(fp.format.hook) + " · " + Format.label(fp.format.layout) + " · "
                + Format.label(fp.format.look) + " · " + Format.label(fp.format.bg)
                + (fp.exploredDim != null ? " (exploring a random " + Format.dimLabel(fp.exploredDim).toLowerCase(Locale.UK) + ")" : "")
                + (sc.vibe != null ? " · themed for " + Format.label(sc.vibe) : ""));
        reelsSinceCard = 0;
        postponed = 0;
        cardsToday++;
        stats.day(currentDayKey).cards++;
        stats.layout(fp.format.layout)[0]++;
        if (c.state == Card.NEW && newShownToday.add(c.id)) { newToday++; stats.day(currentDayKey).newSeen++; }
        c.lastLayout = fp.format.layout;
        lastDeckShown = c.deck;
        addLog("Showed \"" + shorten(c.front, 48) + "\" as " + Format.label(fp.format.layout) + " (" + p.shortWhy + ")");
        return s;
    }

    /** Call when the card reel leaves the screen. Grades it, updates the schedule, rhythm, formats and stats. */
    public Result onCardFinished(Show s, Outcome o, long now) {
        rollDay(now);
        Card c = s.card;
        Result res = new Result();
        int points = s.script.points.size();
        Grade g = Grade.fromOutcome(o, s.isQuiz(), s.script.correct, s.script.question, s.script.optionsText(), points);
        res.grade = g;
        Stats.Day day = stats.day(currentDayKey);
        stats.onCard(currentDayKey, o.dwellMs / 1000.0);
        if (g != null) {
            day.reviews++;
            if (g == Grade.AGAIN) day.again++; else if (g == Grade.HARD) day.hard++; else if (g == Grade.EASY) day.easy++; else day.good++;
            c.sessionBounces = 0;
            if (c.isAnki()) {
                if (ankiSink != null) ankiSink.onGrade(c, g, o.dwellMs);
                c.reps++; c.lastGrade = g.label(); c.lastReview = now;
                c.due = now + 365 * DAY; // Anki owns the schedule; it hands the card back when it's due
                if (c.state == Card.NEW) c.state = Card.REVIEW;
                res.summary = "Graded " + cap(g.label()) + " → sent to AnkiDroid (ease " + g.ease + ")";
            } else {
                if (c.state == Card.REVIEW && c.lastReview > 0) {
                    calibration.add((now - c.lastReview) / (double) DAY, c.stability, g != Grade.AGAIN);
                }
                res.intervalDays = fsrs.review(c, g, now, settings.examDate);
                res.summary = "Graded " + cap(g.label()) + timingNote(o, g, points) + " → next in " + human(res.intervalDays);
                if (calibration.maybeRefit(fsrs)) res.summary += " · memory calibration updated (×" + calibration.scale + ")";
            }
            if (c.isLeech() && g == Grade.AGAIN) res.summary += " · flagged as a tricky card";
        } else if (o.shared) {
            // shared without answering: they engaged with it, so it isn't a skip and isn't re-queued,
            // but there's no grade (no way to tell if they knew it); its schedule is left as it was
            res.summary = "Shared (not graded)";
        } else {
            day.skips++;
            c.skips++;
            c.sessionBounces++;
            stats.layout(s.format.layout)[1]++;
            if (o.fastSkip()) day.fastSkips++;
            if (c.sessionBounces >= 2) {
                c.buriedUntilSession = sessionId;
                res.summary = "Skipped twice this session → saved for next session, in a different look";
            } else {
                requeue.add(new Requeue(c.id, sessionReels + 3));
                res.summary = "Not graded (swiped past) → back in 3 reels";
            }
        }

        if (o.saved && !c.saved) {
            c.saved = true;
            // bookmarked: bring it back within a day (FSRS state is untouched, only the date moves)
            if (!c.isAnki() && c.state == Card.REVIEW && c.due > now + DAY) c.due = now + DAY;
            res.summary += " · saved: back within a day";
        }
        if (o.shared && g != null) res.summary += " · shared";

        // reward the look they saw (0..1), per app
        res.reward = o.reward();
        bandit.update(s.format, s.host, res.reward);
        res.summary += String.format(Locale.UK, " · look scored %.2f", res.reward);

        // learn their rhythm: reward the spacing that led up to this card, then pick the next one
        if (adaptiveRhythm && !settings.isDemo() && !s.test) {
            int before = currentGap(now);
            if (rhythmArmForNext >= 0) rhythm.update(rhythmArmForNext, o.engaged() ? 1 : 0);
            rhythmArmForNext = rhythm.choose(rnd, settings.baseGap());
            int after = currentGap(now);
            if (after != before) res.summary += " · next card in ~" + after + " reels";
        }

        recentFast.add(o.fastSkip());
        while (recentFast.size() > 10) recentFast.remove(0);
        int tail = 0;
        for (int i = recentFast.size() - 1; i >= 0 && recentFast.get(i); i--) tail++;
        if (tail >= 5) {
            snoozeUntil = now + 15 * MIN;
            recentFast.clear();
            res.summary += " · 5 fast skips in a row → quiet mode for 15 min";
        }
        lastSwipeAt = now;
        addLog(res.summary);
        return res;
    }

    static String timingNote(Outcome o, Grade g, int points) {
        String pts = points >= 2 && o.pointsGot > 0 && !o.liked ? " (" + o.pointsGot + " of " + points + " points)" : "";
        if (!pts.isEmpty() && o.hintAtMs == 0) return pts;
        if (g == Grade.GOOD && o.likedBeforeReveal) return " (knew it, but slowly)";
        if (g == Grade.HARD) return o.hintAtMs > 0 ? " (with a hint)" : " (right, but slowly)";
        return pts;
    }

    // ------------------------------------------------------------------ helpers

    void addLog(String s) {
        log.addFirst(s);
        while (log.size() > 40) log.removeLast();
    }

    static String join(List<String> xs) {
        StringBuilder b = new StringBuilder();
        for (String x : xs) { if (b.length() > 0) b.append("; "); b.append(x); }
        return b.toString();
    }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    public static String shorten(String s, int n) { return s.length() <= n ? s : s.substring(0, n - 1) + "…"; }

    public static String human(double days) {
        if (days < 1.0 / 24) return Math.max(1, Math.round(days * 24 * 60)) + " min";
        if (days < 1) return Math.round(days * 24) + " h";
        long d = Math.round(days);
        return d + (d == 1 ? " day" : " days");
    }
}
