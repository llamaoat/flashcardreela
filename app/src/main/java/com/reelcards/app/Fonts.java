package com.reelcards.app;

import android.content.Context;
import android.graphics.Typeface;

import java.util.HashMap;
import java.util.Map;

/**
 * The typefaces cards are set in, loaded once from assets/fonts (all SIL Open Font License; the
 * licences sit next to the files). Each host app gets the family its own captions use:
 *   TikTok     TikTok Sans (TikTok's own typeface, released under the OFL)
 *   Instagram  the phone's system sans (what Instagram uses on Android)
 *   YouTube    Roboto (the Android system sans on almost every phone)
 * plus the look-specific faces: Anton for neon, Fraunces for the clean notes page, Caveat for
 * handwritten notes. If a file can't be read, the system equivalent is used instead.
 */
final class Fonts {
    private static final Map<String, Typeface> CACHE = new HashMap<>();

    private Fonts() {}

    private static Typeface asset(Context c, String file, Typeface fallback) {
        Typeface t = CACHE.get(file);
        if (t != null) return t;
        try { t = Typeface.createFromAsset(c.getAssets(), "fonts/" + file); }
        catch (RuntimeException e) { t = fallback; }
        CACHE.put(file, t);
        return t;
    }

    /** Heaviest weight of the host's caption face: questions, captions, key words. */
    static Typeface hostBlack(Context c, String host) {
        if ("tiktok".equals(host)) return asset(c, "TikTokSans-Black.ttf", Typeface.create("sans-serif-black", Typeface.NORMAL));
        return Typeface.create("sans-serif-black", Typeface.NORMAL);
    }

    /** Bold weight of the host's face: hooks, account names, buttons. */
    static Typeface hostBold(Context c, String host) {
        if ("tiktok".equals(host)) return asset(c, "TikTokSans-Bold.ttf", Typeface.DEFAULT_BOLD);
        return Typeface.create("sans-serif", Typeface.BOLD);
    }

    /** Body weight of the host's face: captions, explanations, chat bubbles. */
    static Typeface hostRegular(Context c, String host) {
        if ("tiktok".equals(host)) return asset(c, "TikTokSans-Regular.ttf", Typeface.DEFAULT);
        return Typeface.create("sans-serif", Typeface.NORMAL);
    }

    static Typeface anton(Context c) { return asset(c, "Anton-Regular.ttf", Typeface.create("sans-serif-condensed", Typeface.BOLD)); }

    static Typeface fraunces(Context c) { return asset(c, "Fraunces-Bold.ttf", Typeface.create("serif", Typeface.BOLD)); }

    static Typeface frauncesItalic(Context c) { return asset(c, "Fraunces-BoldItalic.ttf", Typeface.create("serif", Typeface.BOLD_ITALIC)); }

    static Typeface caveat(Context c) { return asset(c, "Caveat-Bold.ttf", Typeface.create("casual", Typeface.BOLD)); }
}
