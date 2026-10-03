package com.reelcards.engine;

/**
 * One flashcard plus its memory state.
 * Plain Java (no Android imports) so the whole engine runs and is tested on a normal JVM.
 */
public final class Card {
    public static final int NEW = 0, LEARNING = 1, REVIEW = 2, RELEARNING = 3;
    /** Cards forgotten this many times are "leeches": flagged so the student can rewrite them. */
    public static final int LEECH_LAPSES = 6;

    public String id;          // stable id: "bio-01", "notes-3f2a…", "anki:<noteId>:<ord>"
    public String deck;        // deck / subject name shown on the reel
    public String front;       // question
    public String back;        // short answer
    public String extra = "";  // optional explanation shown under the answer

    public int state = NEW;
    public int step;           // index into the (re)learning steps
    public double stability;   // FSRS stability in days (0 = never reviewed)
    public double difficulty;  // FSRS difficulty, 1..10
    public long due;           // epoch ms
    public long lastReview;    // epoch ms, 0 = never
    public int reps, lapses;
    public int skips;          // lifetime "swiped past without looking"
    public int sessionBounces; // skips in the current session
    public int buriedUntilSession = -1;
    public String lastGrade;   // "again" | "hard" | "good" | "easy"
    public String lastLayout;  // layout used the last time it was shown
    public boolean saved;      // bookmarked by the student: shows up in "Saved" and comes back sooner

    // Pictures and Anki card kinds (cards imported from .apkg files)
    public String image;            // picture on the question side: a stored file (app) or URL (web)
    public int imageW, imageH;      // its size in pixels, so it can be laid out before it loads
    public String answerImage;      // picture on the answer side
    public int answerImageW, answerImageH;
    public String occlusion;        // image occlusion masks (see Occlusion), or null
    public int occlusionTarget;     // which masks this card asks about (c1 = 1)
    public boolean cloze;           // a cloze card: the question has [gaps]

    // AnkiDroid-sourced cards: Anki owns the schedule, we only display and send grades back.
    public long ankiNoteId = -1;
    public int ankiOrd = -1;

    public Card() {}

    public Card(String id, String deck, String front, String back, String extra) {
        this.id = id;
        this.deck = deck;
        this.front = front;
        this.back = back;
        this.extra = extra == null ? "" : extra;
    }

    public boolean isAnki() { return ankiNoteId >= 0; }

    public boolean isNew() { return state == NEW; }

    public boolean isLeech() { return lapses >= LEECH_LAPSES; }

    public String stateName() {
        switch (state) {
            case NEW: return "new";
            case LEARNING: return "learning";
            case RELEARNING: return "relearning";
            default: return "review";
        }
    }
}
