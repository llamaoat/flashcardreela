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

    // chat layout
    public String chatName;
    public final List<String> chatThem = new ArrayList<>();
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
