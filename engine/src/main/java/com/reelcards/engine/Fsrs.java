package com.reelcards.engine;

import java.util.Random;

/**
 * FSRS-6 spaced-repetition scheduler: the algorithm Anki ships, ported from the reference
 * implementation (open-spaced-repetition/py-fsrs) with its 21 default parameters.
 * EngineChecks compares this port with py-fsrs output step by step.
 *
 * Same behaviour as py-fsrs: learning steps 1 min / 10 min, relearning step 10 min, same-day
 * reviews use FSRS's short-term stability formula, whole-day intervals with fuzz.
 *
 * Two additions for students:
 *  - exam-aware: target recall rises from 90% to 92% within 3 weeks of the exam and 95% in the
 *    final week, and no interval is allowed to jump past the day before the exam
 *  - personal calibration: `personalScale` stretches or shrinks every stability to fit how
 *    this student actually forgets (see Calibration)
 */
public final class Fsrs {
    public static final long MIN = 60_000L;
    public static final long DAY = 86_400_000L;

    public static final double[] DEFAULT_W = {
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542
    };
    static final double S_MIN = 0.001;
    static final double[][] FUZZ = {{2.5, 7.0, 0.15}, {7.0, 20.0, 0.1}, {20.0, Double.MAX_VALUE, 0.05}};

    public final double[] w = DEFAULT_W.clone();
    public long[] learningSteps = {1 * MIN, 10 * MIN};
    public long[] relearningSteps = {10 * MIN};
    public int maxIntervalDays = 365;
    public boolean fuzz = true;
    public double personalScale = 1.0;
    public Random rnd = new Random();

    // ------------------------------------------------------------------ core formulas

    double decay() { return -w[20]; }

    double factor() { return Math.pow(0.9, 1 / decay()) - 1; }

    /** Probability of recall after `elapsedDays` for stability `s` (days). R(s, s) = 90%. */
    public double retrievability(double elapsedDays, double s) {
        if (s <= 0) return 0;
        return Math.pow(1 + factor() * Math.max(0, elapsedDays) / s, decay());
    }

    /** Days until recall probability falls to `r`. */
    public double intervalDays(double s, double r) {
        return s / factor() * (Math.pow(r, 1 / decay()) - 1);
    }

    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    double initStability(Grade g) { return Math.max(S_MIN, w[g.ease - 1]); }

    double initDifficulty(Grade g, boolean clamp) {
        double d = w[4] - Math.exp(w[5] * (g.ease - 1)) + 1;
        return clamp ? clamp(d, 1, 10) : d;
    }

    double nextDifficulty(double d, Grade g) {
        double delta = -(w[6] * (g.ease - 3));
        double damped = d + (10.0 - d) * delta / 9.0;               // linear damping
        double next = w[7] * initDifficulty(Grade.EASY, false) + (1 - w[7]) * damped; // mean reversion
        return clamp(next, 1, 10);
    }

    double shortTermStability(double s, Grade g) {
        double inc = Math.exp(w[17] * (g.ease - 3 + w[18])) * Math.pow(s, -w[19]);
        if (g != Grade.AGAIN) inc = Math.max(inc, 1.0);
        return Math.max(S_MIN, s * inc);
    }

    double nextRecallStability(double d, double s, double r, Grade g) {
        double hard = g == Grade.HARD ? w[15] : 1, easy = g == Grade.EASY ? w[16] : 1;
        return s * (1 + Math.exp(w[8]) * (11 - d) * Math.pow(s, -w[9]) * (Math.exp((1 - r) * w[10]) - 1) * hard * easy);
    }

    double nextForgetStability(double d, double s, double r) {
        double longTerm = w[11] * Math.pow(d, -w[12]) * (Math.pow(s + 1, w[13]) - 1) * Math.exp((1 - r) * w[14]);
        double shortTerm = s / Math.exp(w[17] * w[18]);
        return Math.min(longTerm, shortTerm);
    }

    double nextStability(double d, double s, double r, Grade g) {
        double n = g == Grade.AGAIN ? nextForgetStability(d, s, r) : nextRecallStability(d, s, r, g);
        return Math.max(S_MIN, n);
    }

    // ------------------------------------------------------------------ helpers used by the engine

    /** Whole days since the last review, as FSRS counts them. */
    public static long elapsedDays(Card c, long now) {
        return c.lastReview == 0 ? -1 : Math.max(0, (now - c.lastReview) / DAY);
    }

    /** Predicted recall right now (fractional days, personal calibration applied). 0 for new cards. */
    public double recallNow(Card c, long now) {
        if (c.state == Card.NEW || c.lastReview == 0) return 0;
        return retrievability((now - c.lastReview) / (double) DAY, c.stability * personalScale);
    }

    /** Predicted recall at time `t` if the card isn't reviewed before then. */
    public double recallAt(Card c, long t) {
        if (c.state == Card.NEW || c.lastReview == 0) return 0;
        return retrievability((t - c.lastReview) / (double) DAY, c.stability * personalScale);
    }

    /** Target recall: 90% normally, 92% within 3 weeks of the exam, 95% in the final week. */
    public static double desiredRetention(long now, long examDate) {
        if (examDate > now) {
            long left = examDate - now;
            if (left <= 7 * DAY) return 0.95;
            if (left <= 21 * DAY) return 0.92;
        }
        return 0.90;
    }

    // ------------------------------------------------------------------ a review

    /**
     * Applies a grade and sets the card's next due time.
     * @param examDate epoch ms of the exam, or 0 for none
     * @return the new interval in days (fractions for minute-level steps)
     */
    public double review(Card c, Grade g, long now, long examDate) {
        long elapsed = elapsedDays(c, now);
        boolean sameDay = elapsed >= 0 && elapsed < 1;
        long next;

        switch (c.state) {
            case Card.NEW:
            case Card.LEARNING:
            case Card.RELEARNING: {
                boolean relearning = c.state == Card.RELEARNING;
                if (c.state == Card.NEW || c.stability <= 0) {
                    c.stability = initStability(g);
                    c.difficulty = initDifficulty(g, true);
                    c.state = Card.LEARNING;
                    c.step = 0;
                } else if (sameDay) {
                    c.stability = shortTermStability(c.stability, g);
                    c.difficulty = nextDifficulty(c.difficulty, g);
                } else {
                    double r = retrievability(elapsed, c.stability);
                    c.stability = nextStability(c.difficulty, c.stability, r, g);
                    c.difficulty = nextDifficulty(c.difficulty, g);
                }
                long[] steps = relearning ? relearningSteps : learningSteps;
                if (steps.length == 0 || c.step >= steps.length) {
                    next = graduate(c, now, examDate);
                } else if (g == Grade.AGAIN) {
                    c.step = 0;
                    next = steps[0];
                } else if (g == Grade.HARD) {
                    if (c.step == 0 && steps.length == 1) next = Math.round(steps[0] * 1.5);
                    else if (c.step == 0) next = (steps[0] + steps[1]) / 2;
                    else next = steps[c.step];
                } else if (g == Grade.GOOD) {
                    if (c.step + 1 == steps.length) next = graduate(c, now, examDate);
                    else { c.step++; next = steps[c.step]; }
                } else { // EASY
                    next = graduate(c, now, examDate);
                }
                break;
            }
            default: { // REVIEW
                if (sameDay) {
                    c.stability = shortTermStability(c.stability, g);
                    c.difficulty = nextDifficulty(c.difficulty, g);
                } else {
                    double r = retrievability(elapsed, c.stability);
                    c.stability = nextStability(c.difficulty, c.stability, r, g);
                    c.difficulty = nextDifficulty(c.difficulty, g);
                }
                if (g == Grade.AGAIN) {
                    c.lapses++;
                    if (relearningSteps.length == 0) next = reviewInterval(c.stability, now, examDate);
                    else { c.state = Card.RELEARNING; c.step = 0; next = relearningSteps[0]; }
                } else {
                    next = reviewInterval(c.stability, now, examDate);
                }
            }
        }
        c.reps++;
        c.lastReview = now;
        c.lastGrade = g.label();
        c.due = now + next;
        return next / (double) DAY;
    }

    private long graduate(Card c, long now, long examDate) {
        c.state = Card.REVIEW;
        c.step = 0;
        return reviewInterval(c.stability, now, examDate);
    }

    /** Whole-day interval for stability s: target recall, fuzz, 1..max days, and never past the exam. */
    long reviewInterval(double s, long now, long examDate) {
        double target = desiredRetention(now, examDate);
        long days = Math.round(intervalDays(s * personalScale, target));
        days = Math.max(1, Math.min(maxIntervalDays, days));
        if (fuzz) days = fuzzed(days);
        if (examDate > now) {
            long left = (examDate - now) / DAY; // whole days until the exam
            long cap = Math.max(1, left - 1);   // review again before exam day
            if (days > cap) days = cap;
        }
        return days * DAY;
    }

    /** py-fsrs fuzz: spreads reviews so cards learned together don't all come due together. */
    long fuzzed(long days) {
        if (days < 2.5) return days;
        double delta = 1.0;
        for (double[] f : FUZZ) delta += f[2] * Math.max(Math.min(days, f[1]) - f[0], 0.0);
        long lo = Math.round(days - delta), hi = Math.round(days + delta);
        lo = Math.max(2, lo);
        hi = Math.min(hi, maxIntervalDays);
        lo = Math.min(lo, hi);
        long v = Math.round(rnd.nextDouble() * (hi - lo + 1) + lo);
        return Math.min(v, maxIntervalDays);
    }
}
