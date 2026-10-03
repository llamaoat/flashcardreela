package com.reelcards.engine;

/**
 * How a card reel looks: one piece from each of four interchangeable dimensions.
 * 6 hooks x 7 layouts x 5 looks x 7 backgrounds = 1,470 combinations.
 */
public final class Format {
    public static final String[] DIMS = {"hook", "layout", "look", "bg"};

    public static final String[] HOOKS = {"pov", "waitforit", "mostwrong", "text2am", "rate", "storytime"};
    /** captions = word-by-word animated captions; comment = "replying to a comment" format;
     *  list = numbered list with one blank per point (only for answers with 2+ points). */
    public static final String[] LAYOUTS = {"bigtext", "captions", "chat", "comment", "split", "quiz", "list"};
    public static final String[] LOOKS = {"caption", "subtitle", "clean", "neon", "paper"};
    public static final String[] BGS = {"satisfying", "gameplay", "citywalk", "pitch", "glow", "equalizer", "plain"};

    /** What the student says they watch most. Used as a starting taste; the bandit takes over from there. */
    public static final String[] VIBES = {"football", "gaming", "beauty", "comedy", "music", "fitness", "food"};

    public String hook, layout, look, bg;

    public Format() {}

    public Format(String hook, String layout, String look, String bg) {
        this.hook = hook; this.layout = layout; this.look = look; this.bg = bg;
    }

    public static String[] pieces(String dim) {
        if ("hook".equals(dim)) return HOOKS;
        if ("layout".equals(dim)) return LAYOUTS;
        if ("look".equals(dim)) return LOOKS;
        return BGS;
    }

    public String get(String dim) {
        if ("hook".equals(dim)) return hook;
        if ("layout".equals(dim)) return layout;
        if ("look".equals(dim)) return look;
        return bg;
    }

    public void set(String dim, String v) {
        if ("hook".equals(dim)) hook = v;
        else if ("layout".equals(dim)) layout = v;
        else if ("look".equals(dim)) look = v;
        else bg = v;
    }

    public String key() { return hook + "/" + layout + "/" + look + "/" + bg; }

    /** Starting preferences for each vibe: {dimension, piece, prior strength}. */
    public static String[][] vibePriors(String vibe) {
        switch (vibe) {
            case "football": return new String[][]{{"bg", "pitch", "3"}, {"hook", "pov", "1"}};
            case "gaming": return new String[][]{{"bg", "gameplay", "3"}, {"layout", "split", "1"}, {"hook", "waitforit", "1"}};
            case "beauty": return new String[][]{{"bg", "glow", "3"}, {"look", "clean", "1"}, {"look", "paper", "1"}};
            case "comedy": return new String[][]{{"hook", "text2am", "2"}, {"layout", "comment", "2"}, {"layout", "chat", "1"}};
            case "music": return new String[][]{{"bg", "equalizer", "3"}, {"look", "neon", "2"}, {"layout", "captions", "1"}};
            case "fitness": return new String[][]{{"bg", "citywalk", "2"}, {"hook", "pov", "1"}, {"look", "caption", "1"}};
            case "food": return new String[][]{{"bg", "satisfying", "3"}, {"look", "paper", "1"}};
            default: return new String[0][];
        }
    }

    public static String label(String piece) {
        switch (piece) {
            case "pov": return "POV";
            case "waitforit": return "Wait for it";
            case "mostwrong": return "Most get it wrong";
            case "text2am": return "2am text";
            case "rate": return "Rate my answer";
            case "storytime": return "Storytime";
            case "bigtext": return "Big text";
            case "captions": return "Word-by-word captions";
            case "chat": return "Text chat";
            case "comment": return "Reply to a comment";
            case "split": return "Split-screen";
            case "quiz": return "Quiz buttons";
            case "list": return "Numbered list";
            case "caption": return "Bold captions";
            case "subtitle": return "Subtitle boxes";
            case "clean": return "Clean serif";
            case "neon": return "Neon";
            case "paper": return "Handwritten";
            case "satisfying": return "Satisfying clip";
            case "gameplay": return "Gameplay";
            case "citywalk": return "City walk";
            case "pitch": return "Football pitch";
            case "glow": return "Soft glow";
            case "equalizer": return "Music visualiser";
            case "plain": return "Plain";
            case "football": return "Football";
            case "gaming": return "Gaming";
            case "beauty": return "Beauty & fashion";
            case "comedy": return "Comedy";
            case "music": return "Music";
            case "fitness": return "Fitness";
            case "food": return "Food";
            default: return piece;
        }
    }

    public static String dimLabel(String dim) {
        switch (dim) {
            case "hook": return "Hook";
            case "layout": return "Layout";
            case "look": return "Look";
            default: return "Background";
        }
    }
}
