package com.reelcards.engine;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/** Counters per day plus the forecast ("how long until the whole deck is covered?"). */
public final class Stats {

    public static final class Day {
        public int reels, cards, reviews, again, hard, good, easy, skips, fastSkips, newSeen;
        public double reelSec, cardSec;

        public double minutes() { return (reelSec + cardSec) / 60.0; }
    }

    public final TreeMap<Long, Day> days = new TreeMap<>();
    public double avgReelSec = 12, avgCardSec = 6; // starting guesses until we have measurements
    public int reelSamples, cardSamples;
    public final Map<String, int[]> layoutCounts = new HashMap<>(); // layout -> {shown, skipped}

    public Day day(long key) {
        Day d = days.get(key);
        if (d == null) { d = new Day(); days.put(key, d); }
        return d;
    }

    /** Called with the time between two reel swipes. Gaps over 2 minutes mean they put the phone down. */
    public void onReel(long dayKey, double seconds) {
        Day d = day(dayKey);
        d.reels++;
        if (seconds <= 0 || seconds > 120) return;
        d.reelSec += seconds;
        reelSamples++;
        double a = reelSamples < 20 ? 1.0 / reelSamples : 0.05;
        avgReelSec = avgReelSec + a * (seconds - avgReelSec);
    }

    public void onCard(long dayKey, double seconds) {
        Day d = day(dayKey);
        if (seconds <= 0 || seconds > 120) return;
        d.cardSec += seconds;
        cardSamples++;
        double a = cardSamples < 20 ? 1.0 / cardSamples : 0.05;
        avgCardSec = avgCardSec + a * (seconds - avgCardSec);
    }

    public int[] layout(String layout) {
        int[] v = layoutCounts.get(layout);
        if (v == null) { v = new int[2]; layoutCounts.put(layout, v); }
        return v;
    }

    public int total(String what) {
        int t = 0;
        for (Day d : days.values()) {
            switch (what) {
                case "reviews": t += d.reviews; break;
                case "skips": t += d.skips; break;
                case "fastSkips": t += d.fastSkips; break;
                case "cards": t += d.cards; break;
                case "reels": t += d.reels; break;
                case "again": t += d.again; break;
                case "hard": t += d.hard; break;
                case "good": t += d.good; break;
                case "easy": t += d.easy; break;
                default: break;
            }
        }
        return t;
    }

    /** Share of graded cards remembered (Hard, Good or Easy) over the last `n` days, or -1 if no data. */
    public double recallRate(long todayKey, int n) {
        int ok = 0, all = 0;
        for (Map.Entry<Long, Day> e : days.entrySet()) {
            if (e.getKey() <= todayKey - n) continue;
            ok += e.getValue().hard + e.getValue().good + e.getValue().easy;
            all += e.getValue().reviews;
        }
        return all == 0 ? -1 : ok / (double) all;
    }

    /** Average minutes per day on reels over recent days with real use (20+ reels), or -1. */
    public double measuredMinutesPerDay(long todayKey) {
        double sum = 0; int n = 0;
        for (Map.Entry<Long, Day> e : days.entrySet()) {
            if (e.getKey() <= todayKey - 8 || e.getKey() == todayKey) continue;
            if (e.getValue().reels < 20) continue;
            sum += e.getValue().minutes(); n++;
        }
        return n == 0 ? -1 : sum / n;
    }

    public int streak(long todayKey) {
        int s = 0;
        long k = todayKey;
        Day today = days.get(k);
        if (today == null || today.reviews == 0) k--; // today not started yet doesn't break it
        while (true) {
            Day d = days.get(k);
            if (d == null || d.reviews == 0) break;
            s++; k--;
        }
        return s;
    }

    public static final class Forecast {
        public int minutesPerDay;
        public boolean measured;
        public int gap;
        public int cardsPerDay;
        public boolean capped;
        public int minutesOnCards;
        public int newLeft;
        public int newPerDay;
        public int daysToCover;
        public int examDaysLeft = -1;
        public boolean onTrack = true;
        public int minutesNeeded;
    }

    public Forecast forecast(Engine e, long now) {
        Forecast f = new Forecast();
        long today = e.dayKey(now);
        double measured = measuredMinutesPerDay(today);
        if (e.settings.minutesPerDay > 0) f.minutesPerDay = e.settings.minutesPerDay;
        else if (measured > 0) { f.minutesPerDay = (int) Math.round(measured); f.measured = true; }
        else f.minutesPerDay = 60;

        int ex = e.examDaysLeft(now);
        int gap = e.currentGap(now);
        f.gap = gap;
        double cycle = gap * avgReelSec + avgCardSec; // seconds for "gap reels + 1 card"
        int raw = (int) Math.floor(f.minutesPerDay * 60.0 / cycle);
        f.cardsPerDay = Math.min(e.settings.dailyCap(), raw);
        f.capped = raw > e.settings.dailyCap();
        f.minutesOnCards = (int) Math.round(f.cardsPerDay * avgCardSec / 60.0);

        f.newLeft = e.newRemaining();
        // Most daily cards become reviews of older ones; about 1 in 5 can be new material.
        f.newPerDay = Math.max(1, Math.min(e.newLimit(now), f.cardsPerDay / 5));
        f.daysToCover = f.newLeft == 0 ? 0 : (int) Math.ceil(f.newLeft / (double) f.newPerDay);
        f.examDaysLeft = ex;
        if (ex > 0) {
            f.onTrack = f.daysToCover <= ex;
            int needNew = (int) Math.ceil(f.newLeft / (double) ex);
            int needCards = needNew * 5;
            f.minutesNeeded = (int) Math.ceil(needCards * cycle / 60.0);
        }
        return f;
    }
}
