package com.reelcards.engine;

import java.util.ArrayList;
import java.util.List;

/** The words on a card reel, written for its hook and layout. The UI only draws this. */
public final class CardScript {
    public String hookLine;     // big hook ("Most people get this wrong")
    public String question;
    public String answer;
    public String explanation;
    public String caption;      // bottom caption under the account name
    public String account;      // "@biology.mocks"
    public String tag;          // "Biology · due today"
    public String revealPrompt; // "wait for it… (tap)"
    public String voiceover;    // what text-to-speech reads when the card appears
    public String vibe;         // the student vibe this card was themed for, or null
    public String hint;         // shown by the hint (speech-bubble) button: "Starts with A · 7 letters"
    public String sound;        // the audio line in the caption band: "original sound · biology.mocks"

    // answer shape (see AnswerShape): long answers are shown as points and self-marked point by point
    public String shape = "short";                              // short | medium | list | steps | prose
    public final List<String> points = new ArrayList<>();      // the answer's points (1 for short answers)
    public final List<String> pointStarts = new ArrayList<>(); // "Active…": hints in the numbered-list slots
    public int marks;                                           // points.size() when 2+, else 0
    public String context = "";                                 // a long question's set-up, shown in a quoted box
    public String questionMain;                                 // the question without its set-up
    public String more = "";                                    // explanation behind "more" in the caption

    // pictures and Anki card kinds
    public String image, answerImage;               // stored picture files, or null
    public int imageW, imageH, answerImageW, answerImageH;
    public final List<Occlusion.Shape> occlusion = new ArrayList<>(); // image occlusion masks
    public int occlusionTarget;                     // which masks this card asks about
    public boolean cloze;                           // the question has [gaps]
    public String audio, answerAudio;               // sounds to play: with the question, and with the answer (or null)

    public boolean multi() { return points.size() >= 2; }
    public boolean isOcclusion() { return !occlusion.isEmpty(); }

    private static final java.util.regex.Pattern GAP = java.util.regex.Pattern.compile("\\[([^\\]]{0,40})\\]");

    /** A cloze question as it's read aloud: "[…]" becomes "blank", "[hint]" becomes "blank (hint)". */
    public static String spokenGaps(String q) {
        java.util.regex.Matcher m = GAP.matcher(q);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            String h = m.group(1).trim();
            m.appendReplacement(b, java.util.regex.Matcher.quoteReplacement(h.isEmpty() || "…".equals(h) || "...".equals(h) ? "blank" : "blank (" + h + ")"));
        }
        m.appendTail(b);
        return b.toString();
    }

    // chat layout
    public String chatName;
    public final List<String> chatThem = new ArrayList<>();
    /** what's left of the friend's messages once a long answer arrives: just the question */
    public final List<String> chatThemQuestion = new ArrayList<>();
    public final List<String> chatMe = new ArrayList<>();
    public String chatAfter;    // friend's reaction after the reveal

    // comment layout ("replying to a comment")
    public String commentUser;
    public String commentText;

    // captions layout: the question split into short caption beats, shown one after another
    public final List<String> beats = new ArrayList<>();
    /** index of the word to highlight within each beat (the key term), or -1 */
    public final List<Integer> beatHighlight = new ArrayList<>();

    // quiz layout
    public final List<String> options = new ArrayList<>();
    public int correct = -1;

    public String optionsText() {
        StringBuilder b = new StringBuilder();
        for (String o : options) b.append(o).append(' ');
        return b.toString();
    }
}
