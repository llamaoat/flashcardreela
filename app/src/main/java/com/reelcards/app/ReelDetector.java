package com.reelcards.app;

import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;

/**
 * Decides whether a scroll event means "swiped to the next reel".
 *
 * Signals, strongest first:
 *  1. the scrolled view is a known reel viewer (Instagram "clips_viewer…", YouTube "reel_…")
 *  2. a full-screen vertical pager/list whose first visible item changed (ViewPager / RecyclerView
 *     report the item index), which is exactly what a reel feed is
 *  3. apps that don't report indexes: the scroll distance adds up to most of a screen
 * Debounced so one swipe counts once. Every decision goes into a log shown in the app, so the
 * team can tune this on real phones (app updates rename views).
 */
final class ReelDetector {

    static final Map<String, String> APPS = new HashMap<>();
    static {
        APPS.put("com.zhiliaoapp.musically", "TikTok");
        APPS.put("com.ss.android.ugc.trill", "TikTok");
        APPS.put("com.instagram.android", "Instagram");
        APPS.put("com.google.android.youtube", "YouTube");
        APPS.put("com.snapchat.android", "Snapchat");
        APPS.put("com.facebook.katana", "Facebook");
    }

    /** Parts of view ids that mean "this is the reels / shorts viewer". */
    static final String[] REEL_IDS = {"clips_viewer", "clips_", "reel_recycler", "reel_player", "reels_", "shorts", "spotlight", "video_feed"};

    static boolean isReelApp(String pkg) { return APPS.containsKey(pkg); }

    /** Short key the engine uses as context (and CardView uses for safe zones). */
    static String hostKey(String pkg) {
        String l = APPS.get(pkg);
        return l == null ? "other" : l.toLowerCase(java.util.Locale.UK);
    }

    static String label(String pkg) {
        String l = APPS.get(pkg);
        return l == null ? pkg : l;
    }

    private final Map<String, Integer> lastIndex = new HashMap<>();
    private final Map<String, Integer> travelled = new HashMap<>();
    private long lastCounted;
    static final LinkedList<String> LOG = new LinkedList<>();
    static int countedTotal;

    boolean isSwipe(AccessibilityEvent e, int screenH) {
        String pkg = String.valueOf(e.getPackageName());
        String cls = e.getClassName() == null ? "" : e.getClassName().toString();
        int from = e.getFromIndex();
        int dy = Build.VERSION.SDK_INT >= 28 ? e.getScrollDeltaY() : 0;
        int dx = Build.VERSION.SDK_INT >= 28 ? e.getScrollDeltaX() : 0;
        if (Math.abs(dx) > Math.abs(dy)) return false; // sideways (tabs, carousels) is not a new reel

        String id = null;
        boolean big = false;
        AccessibilityNodeInfo src = e.getSource();
        if (src != null) {
            id = src.getViewIdResourceName();
            Rect r = new Rect();
            src.getBoundsInScreen(r);
            big = r.height() >= screenH * 0.6f;
            src.recycle();
        }
        String idLower = id == null ? "" : id.toLowerCase(Locale.UK);
        boolean knownReel = false;
        for (String k : REEL_IDS) if (idLower.contains(k)) { knownReel = true; break; }
        boolean pager = cls.contains("ViewPager") || cls.contains("RecyclerView") || cls.contains("Pager");
        // full-screen only: this skips comment sheets, search results and other small lists
        if (!(knownReel || (big && pager))) {
            log(pkg, cls, id, "ignored (not a full-screen feed)");
            return false;
        }

        String key = pkg + "|" + (id == null ? cls : id);
        Integer prev = lastIndex.get(key);
        if (from >= 0) lastIndex.put(key, from);
        boolean indexChanged = from >= 0 && prev != null && from != prev;

        int t = (travelled.containsKey(key) ? travelled.get(key) : 0) + Math.abs(dy);
        travelled.put(key, t);
        boolean farEnough = from < 0 && t >= screenH * 0.6f;

        if (!indexChanged && !farEnough) return false;
        long now = SystemClock.uptimeMillis();
        travelled.put(key, 0);
        if (now - lastCounted < 650) return false; // one swipe, one count
        lastCounted = now;
        countedTotal++;
        log(pkg, cls, id, "REEL SWIPE" + (knownReel ? " (reel viewer)" : "") + (indexChanged ? " item " + prev + "→" + from : " distance"));
        return true;
    }

    static void log(String pkg, String cls, String id, String what) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.UK).format(new Date());
        String shortCls = cls.substring(cls.lastIndexOf('.') + 1);
        String shortId = id == null ? "-" : id.substring(id.indexOf('/') + 1);
        synchronized (LOG) {
            // collapse repeats of the same "ignored" line
            String line = time + "  " + label(pkg) + "  " + what + "\n    " + shortCls + " · id " + shortId;
            if (!LOG.isEmpty() && what.startsWith("ignored") && LOG.getFirst().contains(what) && LOG.getFirst().contains(shortId)) return;
            LOG.addFirst(line);
            while (LOG.size() > 40) LOG.removeLast();
        }
    }
}
