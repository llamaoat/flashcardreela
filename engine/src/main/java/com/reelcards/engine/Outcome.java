package com.reelcards.engine;

/** What happened while a card reel was on screen. Filled in by the UI, read by the engine. */
public final class Outcome {
    public boolean revealed;
    public boolean liked;
    public boolean likedBeforeReveal;
    public int quizPick = -1;     // index of the option tapped, -1 = none

    // Timings, in ms since the card appeared (0 = didn't happen). Used for response-time grading.
    public long revealAtMs;
    public long likeAtMs;
    public long pickAtMs;
    public long hintAtMs;         // tapped the hint (speech-bubble) button
    public boolean saved;         // tapped save (bookmark): "bring this back soon"
    public boolean shared;        // tapped share: sent the question to a friend
    public int pointsGot;         // long answers: how many points the student ticked as "got it"

    public long dwellMs;          // total time the card was on screen
    public String exit = "swipe"; // "swipe" | "left_app" | "timeout" | "closed"

    public boolean fastSkip() {
        return !revealed && !liked && !saved && !shared && hintAtMs == 0 && quizPick < 0 && pointsGot == 0 && dwellMs < 1200;
    }

    public boolean engaged() {
        return liked || shared || saved || hintAtMs > 0 || quizPick >= 0 || pointsGot > 0 || (revealed && dwellMs >= 2000);
    }

    /**
     * How much this reel held attention, 0..1. Used as the reward for the format it was shown in.
     * A like is the strongest signal; a sub-second swipe-away the weakest.
     */
    public double reward() {
        if (liked || shared) return 1.0;
        if (saved) return 0.85;
        if (quizPick >= 0 || pointsGot > 0) return 0.9;
        if (revealed && dwellMs >= 2000) return 0.7;
        if (revealed) return 0.45;
        if (dwellMs >= 3000) return 0.35;
        if (dwellMs >= 1200) return 0.15;
        return 0.0;
    }
}
