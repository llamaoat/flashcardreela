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
 *  - used the hint, then knew it (like or right option) -> HARD
     *  - never revealed (swiped past)                      -> null: not graded, comes back later
     * A missing timing (0) is treated as fast, so older clients still work.
     */
    public static Grade fromOutcome(Outcome o, boolean isQuiz, int correctOption, String question, String optionsText) {
        long read = readingMs(question);
        if (isQuiz && o.quizPick >= 0 && o.quizPick != correctOption) return AGAIN; // a wrong pick beats an early like
        if (o.hintAtMs > 0) {
            // needed a hint: remembered with help at best (FSRS "Hard"), forgot if they still had to look
            if (o.liked || (isQuiz && o.quizPick >= 0)) return HARD;
            if (o.revealed) return AGAIN;
            return null;
        }
        if (o.likedBeforeReveal) return (o.likeAtMs == 0 || o.likeAtMs <= read + 2500) ? EASY : GOOD;
        if (isQuiz && o.quizPick >= 0) {
            long slow = read + readingMs(optionsText) + 4000;
            return (o.pickAtMs == 0 || o.pickAtMs <= slow) ? GOOD : HARD;
        }
        if (o.liked) return GOOD;
        if (o.revealed) return AGAIN;
        return null;
    }

    /** Timing-free version, kept for callers that don't track time. */
    public static Grade fromOutcome(Outcome o, boolean isQuiz, int correctOption) {
        return fromOutcome(o, isQuiz, correctOption, "", "");
    }
}
