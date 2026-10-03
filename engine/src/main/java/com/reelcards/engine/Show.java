package com.reelcards.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A card reel the engine has decided to show: which card, how it looks, its words, and why. */
public final class Show {
    public Card card;
    public Format format;
    public CardScript script;
    public String host;              // app it appears in: tiktok, instagram, youtube, … (for layout + learning)
    public long shownAt;
    public boolean test;
    public String reasonShort;       // "due today", "new", "back again"
    public String exploredDim;       // dimension explored at random, or null
    public final List<String> why = new ArrayList<>();
    public final Map<String, String> formatDetail = new HashMap<>();

    public boolean isQuiz() { return "quiz".equals(format.layout) && script.correct >= 0; }
}
