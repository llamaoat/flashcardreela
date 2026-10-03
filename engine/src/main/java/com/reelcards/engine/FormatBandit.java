package com.reelcards.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Picks how the next card reel looks, and learns from how the student reacts.
 *
 * Built on three ideas:
 *  1. Thompson sampling: every piece (e.g. layout "quiz") keeps a Beta(a, b) belief about how
 *     well it holds attention. Rewards are fractional (a like = 1, a sub-second swipe-away = 0).
 *  2. Recovering novelty (Duolingo's "sleeping, recovering bandit", Yancey & Settles, KDD 2020):
 *     a piece used recently gets a penalty that fades with a half-life, so looks rotate naturally
 *     and a favourite still comes back often. Hard limits on top: a hook never twice in a row, a
 *     layout at most twice, and the exact same combination never twice in a row.
 *  3. Context: beliefs are kept both overall and per app (TikTok vs Instagram vs YouTube…), pooled
 *     so a new context starts from the overall belief and drifts towards its own evidence.
 * Old evidence slowly fades (x0.98 per update), so tastes can change. ~1 in 10 cards also
 * explores a random piece in one dimension.
 */
public final class FormatBandit {
    public static final double EXPLORE = 0.10;
    public static final double DISCOUNT = 0.98;
    static final double HALF_LIFE = 1.5; // cards

    /** "dim:piece" (overall) or "context|dim:piece" -> {a, b} */
    public final Map<String, double[]> scores = new HashMap<>();
    /** "dim:piece" -> value of `shown` when it was last used */
    public final Map<String, Integer> lastUsed = new HashMap<>();
    public int shown;
    public String lastKey;
    /** the last few pieces used in each dimension, for the run-length limits */
    public final Map<String, List<String>> recent = new HashMap<>();
    /** false = never learn (every look equally likely). Used by the simulator as the baseline. */
    public boolean learn = true;

    public FormatBandit() {
        for (String d : Format.DIMS) for (String p : Format.pieces(d)) scores.put(d + ":" + p, new double[]{1, 1});
    }

    /** Longest run of the same piece allowed: a hook never twice in a row, a layout at most twice. */
    static int maxRun(String dim) {
        switch (dim) {
            case "hook": return 1;
            case "layout": return 2;
            default: return 3;
        }
    }

    List<String> recent(String dim) {
        List<String> r = recent.get(dim);
        if (r == null) { r = new ArrayList<>(); recent.put(dim, r); }
        return r;
    }

    boolean runFull(String dim, String piece) {
        List<String> r = recent(dim);
        int n = maxRun(dim);
        if (r.size() < n) return false;
        for (int i = r.size() - n; i < r.size(); i++) if (!r.get(i).equals(piece)) return false;
        return true;
    }

    static double penalty(String dim) {
        switch (dim) {
            case "hook": return 0.30;
            case "layout": return 0.25;
            case "look": return 0.18;
            default: return 0.08;
        }
    }

    public double[] score(String key) {
        double[] s = scores.get(key);
        if (s == null) { s = new double[]{1, 1}; scores.put(key, s); }
        return s;
    }

    public double[] score(String dim, String piece) { return score(dim + ":" + piece); }

    public double mean(String dim, String piece) {
        double[] s = score(dim, piece);
        return s[0] / (s[0] + s[1]);
    }

    /** Starting taste from the vibes the student picked. Only raises priors; evidence takes over. */
    public void applyVibes(Collection<String> vibes) {
        for (String v : vibes) {
            for (String[] p : Format.vibePriors(v)) {
                double[] s = score(p[0], p[1]);
                s[0] += Double.parseDouble(p[2]);
            }
        }
    }

    /** Pooled belief for a piece in a context: context evidence plus overall evidence, down-weighted as context data grows. */
    double[] pooled(String context, String dim, String piece) {
        double[] g = score(dim, piece);
        if (context == null) return g;
        double[] c = scores.get(context + "|" + dim + ":" + piece);
        if (c == null) return g;
        double nc = c[0] + c[1] - 2;
        double wg = 1.0 / (1.0 + nc / 8.0);
        return new double[]{1 + wg * (g[0] - 1) + (c[0] - 1), 1 + wg * (g[1] - 1) + (c[1] - 1)};
    }

    public static final class Pick {
        public Format format;
        public String exploredDim;
        public final Map<String, String> detail = new HashMap<>();
    }

    /**
     * @param context     e.g. "tiktok", "instagram", "youtube", "practice"; may be null
     * @param avoidLayout layout to avoid (the one this card was skipped in last time), may be null
     * @param allowQuiz   false when no believable wrong answer exists
     * @param allowCaptions false for long questions (word-by-word would drag)
     */
    public Pick pick(Random rnd, String context, String avoidLayout, boolean allowQuiz, boolean allowCaptions) {
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(Format.LAYOUTS));
        if (!allowQuiz) allowed.remove("quiz");
        if (!allowCaptions) allowed.remove("captions");
        return pick(rnd, context, avoidLayout, allowed);
    }

    /**
     * @param allowed the layouts this card's shape can use (AnswerShape.layoutsFor); null = all
     */
    public Pick pick(Random rnd, String context, String avoidLayout, java.util.Set<String> allowed) {
        Pick out = new Pick();
        Format f = new Format();
        String exploreDim = rnd.nextDouble() < EXPLORE ? Format.DIMS[rnd.nextInt(Format.DIMS.length)] : null;
        out.exploredDim = exploreDim;
        for (String dim : Format.DIMS) {
            List<String> cands = new ArrayList<>();
            for (String p : Format.pieces(dim)) {
                if ("layout".equals(dim)) {
                    if (allowed != null && !allowed.contains(p)) continue;
                    if (p.equals(avoidLayout)) continue;
                }
                if (runFull(dim, p)) continue;
                cands.add(p);
            }
            if (cands.isEmpty()) cands.add(Format.pieces(dim)[0]);
            if (dim.equals(exploreDim)) {
                f.set(dim, cands.get(rnd.nextInt(cands.size())));
                out.detail.put(dim, "explored");
                continue;
            }
            String best = cands.get(0);
            double bestV = -9, bestDraw = 0, bestPen = 0;
            for (String p : cands) {
                double[] s = pooled(context, dim, p);
                double draw = sampleBeta(rnd, s[0], s[1]);
                Integer at = lastUsed.get(dim + ":" + p);
                double pen = at == null ? 0 : penalty(dim) * Math.pow(0.5, (shown - at) / HALF_LIFE);
                if (draw - pen > bestV) { bestV = draw - pen; best = p; bestDraw = draw; bestPen = pen; }
            }
            f.set(dim, best);
            out.detail.put(dim, bestPen > 0.005
                    ? String.format(Locale.UK, "score %.2f − recent-use %.2f", bestDraw, bestPen)
                    : String.format(Locale.UK, "score %.2f", bestDraw));
        }
        if (f.key().equals(lastKey)) { // never the same combination twice in a row
            String alt = f.look;
            while (alt.equals(f.look)) alt = Format.LOOKS[rnd.nextInt(Format.LOOKS.length)];
            f.look = alt;
            out.detail.put("look", "swapped: no repeats");
        }
        shown++;
        for (String dim : Format.DIMS) {
            lastUsed.put(dim + ":" + f.get(dim), shown);
            List<String> r = recent(dim);
            r.add(f.get(dim));
            while (r.size() > 4) r.remove(0);
        }
        lastKey = f.key();
        out.format = f;
        return out;
    }

    /** reward in 0..1 (see Outcome.reward). Updates the overall and the per-context belief. */
    public void update(Format f, String context, double reward) {
        if (f == null || !learn) return;
        reward = Math.max(0, Math.min(1, reward));
        for (String dim : Format.DIMS) {
            bump(score(dim, f.get(dim)), reward);
            if (context != null) bump(score(context + "|" + dim + ":" + f.get(dim)), reward);
        }
    }

    private static void bump(double[] s, double r) {
        s[0] = 1 + (s[0] - 1) * DISCOUNT + r;
        s[1] = 1 + (s[1] - 1) * DISCOUNT + (1 - r);
    }

    /** Top piece per dimension (overall), once there's a little evidence. */
    public String favourite(String dim) {
        String best = null; double bm = -1;
        for (String p : Format.pieces(dim)) {
            double[] s = score(dim, p);
            if (s[0] + s[1] < 5) continue;
            double m = s[0] / (s[0] + s[1]);
            if (m > bm) { bm = m; best = p; }
        }
        return best;
    }

    // ---- Beta sampling (Marsaglia & Tsang gamma) ----
    static double sampleBeta(Random rnd, double a, double b) {
        double x = sampleGamma(rnd, a), y = sampleGamma(rnd, b);
        return x / (x + y);
    }

    static double sampleGamma(Random rnd, double k) {
        if (k < 1) return sampleGamma(rnd, k + 1) * Math.pow(rnd.nextDouble(), 1 / k);
        double d = k - 1.0 / 3, c = 1 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do { x = rnd.nextGaussian(); v = 1 + c * x; } while (v <= 0);
            v = v * v * v;
            double u = rnd.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }
}
