package com.reelcards.engine;

import java.util.LinkedHashSet;
import java.util.Set;

/** Student-facing settings. Intensity bundles the starting rhythm, the daily cap and new cards per day. */
public final class Settings {
    public static final String CHILL = "chill", NORMAL = "normal", CRAM = "cram", DEMO = "demo";

    public String intensity = NORMAL;
    public long examDate = 0;        // epoch ms, 0 = no exam set
    public long pausedUntil = 0;     // epoch ms
    public int minutesPerDay = 0;    // 0 = use measured screen time
    public boolean voiceover = true; // read the hook and question aloud, like a creator would
    public final Set<String> vibes = new LinkedHashSet<>(); // what they watch: football, gaming, …
    /** Opt-in: tint dark card styles with the colour of the reel just watched (Android 11+, on-device only). */
    public boolean matchColours = false;
    /** "auto" = dark versions of the light card styles between 22:00 and 06:00; "on" or "off" to force. */
    public String night = "auto";

    /** Whether light card styles should use their night (dark) versions at this local hour. */
    public boolean nightAt(int hour) {
        if ("on".equals(night)) return true;
        if ("off".equals(night)) return false;
        return hour >= 22 || hour < 6;
    }

    public boolean isDemo() { return DEMO.equals(intensity); }

    /** Starting rhythm: one card every N reels. The engine then adapts it to the student's tolerance. */
    public int baseGap() {
        switch (intensity) {
            case CHILL: return 8;
            case CRAM: return 3;
            case DEMO: return 2;
            default: return 5;
        }
    }

    public int dailyCap() {
        switch (intensity) {
            case CHILL: return 60;
            case CRAM: return 300;
            case DEMO: return 1000;
            default: return 150;
        }
    }

    public int newPerDay() {
        switch (intensity) {
            case CHILL: return 10;
            case CRAM: return 50;
            case DEMO: return 500;
            default: return 25;
        }
    }

    public static String intensityLabel(String i) {
        switch (i) {
            case CHILL: return "Chill · starts at 1 card every 8 reels";
            case CRAM: return "Cram · starts at 1 card every 3 reels";
            case DEMO: return "Demo · 1 card every 2 reels";
            default: return "Normal · starts at 1 card every 5 reels";
        }
    }
}
