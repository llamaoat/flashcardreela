package com.reelcards.engine;

/** Anki's answer buttons. The number is the "ease" AnkiDroid's API expects. */
public enum Grade {
    AGAIN(1), HARD(2), GOOD(3), EASY(4);

    public final int ease;

    Grade(int ease) { this.ease = ease; }

    public String label() {
        switch (this) {
            case AGAIN: return "again";
            case HARD: return "hard";
            case GOOD: return "good";
            default: return "easy";
        }
    }

    public static Grade fromLabel(String s) {
        if ("again".equals(s)) return AGAIN;
        if ("hard".equals(s)) return HARD;
        if ("easy".equals(s)) return EASY;
        return GOOD;
    }

    /** Rough time to read a question of this length (~22 characters a second, plus a moment to look). */
    public static long readingMs(String text) {
        return 1500 + 45L * (text == null ? 0 : text.length());
    }

    /**
     * Turns what the student did on a reel into a grade, using how fast they did it.
     *
     * Speed matters because a fast correct answer means a stronger memory than a slow one
     * (the idea behind response-time-based scheduling such as ARTS, Mettler et al. 2016):
     *  - liked before the answer showed, quickly           -> EASY
     *  - liked before the answer showed, but slowly        -> GOOD
     *  - quiz: right option, quickly -> GOOD; slowly -> HARD; wrong option -> AGAIN
     *  - revealed the answer, then liked it                -> GOOD
     *  - revealed the answer, no like                      -> AGAIN
     *  - long answers (2+ points), ticked point by point:
     *    all points -> GOOD, at least half -> HARD, fewer -> AGAIN
     *  - used the hint: capped at HARD (remembered with help at best)
     *  - never revealed (swiped past)                      -> null: not graded, comes back later
     * A missing timing (0) is treated as fast, so older clients still work. Recalling several points
     * takes longer, so each extra point adds 1.2 s to what counts as quick.
     */
    public static Grade fromOutcome(Outcome o, boolean isQuiz, int correctOption, String question, String optionsText, int points) {
        int n = Math.max(1, points);
        long read = readingMs(question) + 1200L * (n - 1);
        if (isQuiz && o.quizPick >= 0 && o.quizPick != correctOption) return AGAIN; // a wrong pick beats an early like
        Grade g;
        if (o.likedBeforeReveal) g = (o.likeAtMs == 0 || o.likeAtMs <= read + 2500) ? EASY : GOOD;
        else if (isQuiz && o.quizPick >= 0) g = (o.pickAtMs == 0 || o.pickAtMs <= read + readingMs(optionsText) + 4000) ? GOOD : HARD;
        else if (o.liked) g = GOOD;
        else if (n >= 2 && o.pointsGot > 0) g = o.pointsGot >= n ? GOOD : o.pointsGot * 2 >= n ? HARD : AGAIN;
        else if (o.revealed) g = AGAIN;
        else g = null;
        if (g != null && o.hintAtMs > 0 && (g == GOOD || g == EASY)) g = HARD;
        return g;
    }

    public static Grade fromOutcome(Outcome o, boolean isQuiz, int correctOption, String question, String optionsText) {
        return fromOutcome(o, isQuiz, correctOption, question, optionsText, 1);
    }

    /** Timing-free version, kept for callers that don't track time. */
    public static Grade fromOutcome(Outcome o, boolean isQuiz, int correctOption) {
        return fromOutcome(o, isQuiz, correctOption, "", "");
    }
}
