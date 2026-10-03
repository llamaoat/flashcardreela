package com.reelcards.engine;

import java.util.ArrayList;
import java.util.List;

/**
 * Learns how this student forgets, compared with FSRS's average learner.
 *
 * Full FSRS optimisation fits 21 parameters and needs hundreds of reviews. A student in their
 * first weeks has far fewer, so this fits one number: a scale on every stability
 * (1.3 = "remembers about 30% longer than average"). It minimises log-loss of the predicted recall
 * against what actually happened on real (1+ day) reviews, with a gentle pull towards 1.0.
 * FSRS-6's forgetting curve is flat, so one review says little about the scale: it waits for
 * 100 long-term reviews and tends to under- rather than over-correct.
 */
public final class Calibration {
    public static final int MIN_REVIEWS = 100;
    static final int MAX_LOG = 2000;
    static final double PRIOR = 1.0; // strength of the pull towards 1.0 (in nats)

    /** One long-term review: days since the previous review, stability at that time, recalled or not. */
    public static final class Event {
        public double days, stability;
        public boolean recalled;
        public Event() {}
        public Event(double days, double stability, boolean recalled) { this.days = days; this.stability = stability; this.recalled = recalled; }
    }

    public final List<Event> log = new ArrayList<>();
    public double scale = 1.0;
    public int fittedOn;

    public void add(double days, double stability, boolean recalled) {
        if (days < 1 || stability <= 0) return;
        log.add(new Event(days, stability, recalled));
        while (log.size() > MAX_LOG) log.remove(0);
    }

    /** Refit when there's enough new evidence. Returns true if the scale changed. */
    public boolean maybeRefit(Fsrs f) {
        if (log.size() < MIN_REVIEWS || log.size() - fittedOn < 10) return false;
        double best = 1.0, bestLoss = Double.MAX_VALUE;
        for (double k = 0.4; k <= 2.51; k += 0.05) {
            double loss = PRIOR * Math.pow(Math.log(k), 2);
            for (Event ev : log) {
                double r = Fsrs.clamp(f.retrievability(ev.days, ev.stability * k), 1e-4, 1 - 1e-4);
                loss -= ev.recalled ? Math.log(r) : Math.log(1 - r);
            }
            if (loss < bestLoss) { bestLoss = loss; best = k; }
        }
        fittedOn = log.size();
        boolean changed = Math.abs(best - scale) > 1e-9;
        scale = Math.round(best * 100) / 100.0;
        f.personalScale = scale;
        return changed;
    }

    /** Plain-English summary for the dashboard, or null if there isn't enough data yet. */
    public String describe() {
        if (fittedOn == 0) return null;
        int pct = (int) Math.round((scale - 1) * 100);
        if (Math.abs(pct) < 8) return "You forget at about the rate FSRS expects, so intervals are standard.";
        if (pct > 0) return "You remember about " + pct + "% longer than FSRS's average learner, so cards come back less often.";
        return "You forget about " + (-pct) + "% faster than FSRS's average learner, so cards come back sooner.";
    }
}
