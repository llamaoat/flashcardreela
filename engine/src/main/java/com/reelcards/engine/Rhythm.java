package com.reelcards.engine;

import java.util.Random;

/**
 * Learns how often this student wants cards.
 *
 * Too often and they swipe cards away; too rarely and they barely revise. The goal is the most
 * *answered* cards per reel scrolled. Each rhythm (a multiplier on the student's setting) is an arm:
 * the engine keeps a Beta belief about how likely a card is to be engaged with at that rhythm,
 * and picks by Thompson sampling on "engaged cards per reel" = p / (gap + 1).
 * Priors start flat on that value, so no rhythm is favoured until the student's behaviour says so.
 */
public final class Rhythm {
    public static final double[] MULT = {0.6, 0.8, 1.0, 1.3, 1.7, 2.2};
    static final double DISCOUNT = 0.98;

    public final double[] a = new double[MULT.length], b = new double[MULT.length];
    public int arm = 2;
    public boolean seeded;

    /** Flat prior on engaged-per-reel: longer gaps start with proportionally higher engagement. */
    public void seed(int base) {
        for (int i = 0; i < MULT.length; i++) {
            double g = base * MULT[i];
            double m = Math.min(0.9, 0.5 * (g + 1) / (base + 1.0));
            a[i] = 4 * m;
            b[i] = 4 * (1 - m);
        }
        seeded = true;
    }

    public double multiplier() { return MULT[arm]; }

    public int choose(Random rnd, int base) {
        if (!seeded) seed(base);
        int best = arm; double bv = -1;
        for (int i = 0; i < MULT.length; i++) {
            double p = FormatBandit.sampleBeta(rnd, a[i], b[i]);
            double v = p / (base * MULT[i] + 1);
            if (v > bv) { bv = v; best = i; }
        }
        arm = best;
        return best;
    }

    /** reward 0..1 for the card shown at the current rhythm */
    public void update(int usedArm, double reward) {
        if (!seeded) return;
        reward = Math.max(0, Math.min(1, reward));
        a[usedArm] = a[usedArm] * DISCOUNT + reward;
        b[usedArm] = b[usedArm] * DISCOUNT + (1 - reward);
    }

    /** The rhythm with the best expected engaged-cards-per-reel so far. */
    public int bestArm(int base) {
        int best = 2; double bv = -1;
        for (int i = 0; i < MULT.length; i++) {
            double v = a[i] / (a[i] + b[i]) / (base * MULT[i] + 1);
            if (v > bv) { bv = v; best = i; }
        }
        return best;
    }

    public int evidence() {
        double n = 0;
        for (int i = 0; i < MULT.length; i++) n += a[i] + b[i] - 4;
        return (int) Math.round(n);
    }
}
