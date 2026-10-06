package com.reelcards.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The shape of a card's answer and question, which decides how a card reel can draw it.
 *
 * A reel is read in seconds, so a card isn't drawn by its words alone:
 *  - short:  a word or phrase ("The liver")            -> one big answer, any layout
 *  - medium: one long sentence                          -> a smaller answer, no quiz buttons
 *  - list / steps / prose: 2-6 points                   -> revealed one point at a time and
 *    self-marked point by point, like a mark scheme ("3 marks")
 * A long question is split into its set-up (context) and the actual question, so the question can
 * stay big while the set-up sits in a small quoted box.
 *
 * Splitting follows the minimum information principle (Wozniak, "Effective learning: Twenty rules
 * of formulating knowledge", 1999): recall is more reliable for small items, so a long answer is
 * reviewed as its parts. Mirrors analyse() in web-demo/src/engine.js.
 */
public final class AnswerShape {
    public static final int SHORT_MAX = 48, MAX_POINTS = 6, LONG_Q = 90, INLINE_X = 90, COMMENT_MAX = 180;

    public String kind = "short";                  // short | medium | list | steps | prose
    public List<String> points = new ArrayList<>(); // 1 for short/medium, 2-6 otherwise
    public List<String> over = new ArrayList<>();   // points beyond MAX_POINTS (shown under "more")
    public String context = "";                     // a long question's set-up, or ""
    public String questionMain = "";                // the question itself
    public String inlineExplanation = "";           // explanation short enough to show with the answer
    public String more = "";                        // what goes behind "more" in the caption

    public boolean pic;                             // the card has a picture (or is image occlusion), or a sound to listen to first
    public boolean io;                              // image occlusion: the picture is the answer
    public boolean cloze;                           // a cloze card: the question has [gaps]

    public boolean multi() { return points.size() >= 2; }
    public int marks() { return multi() ? points.size() : 0; }
    /** Quiz buttons only work when both options are short enough to read at a glance. */
    public boolean quizable() { return "short".equals(kind) && !pic; }
    /** Word-by-word captions drag for long questions. */
    public boolean captionable() { return context.isEmpty() && questionMain.length() <= 140 && !pic; }
    /** Split-screen leaves about a third of the screen for text. */
    public boolean splittable(String back) { return !multi() && !pic && plainText(back).length() <= 60 && questionMain.length() <= 110 && context.isEmpty(); }

    // ------------------------------------------------------------------ analysis

    public static AnswerShape of(Card c) {
        AnswerShape a = of(c.front, c.back, c.extra);
        a.pic = c.image != null || c.occlusion != null || c.audio != null; // big text, with room for the picture or the play button
        a.io = c.occlusion != null;
        a.cloze = c.cloze;
        if (c.cloze) { // the question has to include its gap
            String[] q = splitQuestion(c.front, true);
            a.context = q[0];
            a.questionMain = q[1];
        }
        if (a.io) { // image occlusion has no written answer: the picture is the answer, the extra goes behind "more"
            String x = inline(plainText(c.extra));
            String more = (a.over.isEmpty() ? "" : "Also: " + join(a.over, ". ") + ". ") + x;
            a.kind = "image";
            a.points.clear();
            a.over.clear();
            a.more = more.trim();
            a.inlineExplanation = "";
        }
        return a;
    }

    public static AnswerShape of(String front, String back, String extra) {
        AnswerShape a = splitPoints(back);
        String[] q = splitQuestion(front);
        a.context = q[0];
        a.questionMain = q[1];
        String x = inline(plainText(extra));
        boolean multi = a.multi();
        String more = (a.over.isEmpty() ? "" : "Also: " + join(a.over, ". ") + ". ") + (multi || x.length() > INLINE_X ? x : "");
        a.more = more.trim();
        a.inlineExplanation = multi || x.length() > INLINE_X ? "" : x;
        return a;
    }

    /** The layouts a card of this shape can be drawn in. */
    public static Set<String> layoutsFor(AnswerShape a, String back, boolean hasDistractor) {
        Set<String> out = new HashSet<>();
        if (a.pic) { out.add("bigtext"); return out; } // pictures stay in big text
        for (String l : Format.LAYOUTS) {
            if ("quiz".equals(l) && !(a.quizable() && hasDistractor)) continue;
            if ("captions".equals(l) && !a.captionable()) continue;
            if ("list".equals(l) && !a.multi()) continue;
            if ("split".equals(l) && !a.splittable(back)) continue;
            if ("comment".equals(l) && a.cloze) continue; // a cloze gap doesn't read as a comment
            if ("comment".equals(l) && a.context.length() + a.questionMain.length() > COMMENT_MAX) continue; // comments are short: not a clinical vignette
            out.add(l);
        }
        return out;
    }

    static final Pattern BULLET = Pattern.compile("^(?:[-•*·▪●–]|\\d{1,2}[.)]|\\(?[a-z]\\))\\s+");
    static final Pattern NUMBERED = Pattern.compile("^\\d{1,2}[.)]\\s");
    /** sentence ends, but not after e.g. / i.e. / etc. */
    static final String SENTENCE = "(?<!\\b(?:e\\.g|i\\.e|etc|vs|approx|cf|Dr|Mr|Mrs|Ms|St)\\.)(?<=[.!?])\\s+(?=[A-Z0-9“\"(])";
    static final String QSENTENCE = "(?<!\\b(?:e\\.g|i\\.e|etc|vs|approx|cf|Dr|Mr|Mrs|Ms|St)\\.)(?<=[.!?:])\\s+(?=[A-Z0-9“\"(])";
    static final Pattern STEP_NUMBER = Pattern.compile("(^|\\s)(\\d{1,2})[.)]\\s+");
    static final Pattern COMMAND = Pattern.compile("^(explain|describe|suggest|compare|contrast|name|state|give|calculate|evaluate|define|outline|identify|predict|use|write|complete)\\b", Pattern.CASE_INSENSITIVE);

    static final Pattern TABLE = Pattern.compile("<table[^>]*>(.*?)</table>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static final Pattern ROW = Pattern.compile("<tr[^>]*>", Pattern.CASE_INSENSITIVE);
    static final Pattern CELL = Pattern.compile("<t([dh])(?:\\s[^>]*)?>(.*?)</t[dh]>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    static final String CELLSPACE = "[ \\t\\r\\n\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]+";

    /**
     * A table becomes one line per row, each value labelled with its column's header when there is one
     * ("Ferritin: Iron deficiency ↓, Chronic disease ↑"; with one value column just "Warfarin: VKOR"), so a row can be a point to mark.
     */
    public static String tableLines(String t) {
        java.util.regex.Matcher m = TABLE.matcher(t);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            List<List<String[]>> rows = new ArrayList<>(); // cells as {"h" or "d", text}
            String[] parts = ROW.split(m.group(1), -1);
            for (int i = 1; i < parts.length; i++) {
                List<String[]> cells = new ArrayList<>();
                java.util.regex.Matcher c = CELL.matcher(parts[i]);
                boolean any = false;
                while (c.find()) {
                    String v = c.group(2).replaceAll("(?i)<\\s*br\\s*/?>", " ").replaceAll("<[^>]+>", "").replace("&nbsp;", " ").replaceAll(CELLSPACE, " ").trim();
                    cells.add(new String[]{c.group(1).toLowerCase(Locale.UK), v});
                    if (!v.isEmpty()) any = true;
                }
                if (any) rows.add(cells);
            }
            StringBuilder b = new StringBuilder();
            if (!rows.isEmpty()) {
                List<String> head = null;
                List<String[]> first = rows.get(0);
                boolean allTh = true;
                for (String[] c : first) if (!"h".equals(c[0])) allTh = false;
                if (rows.size() > 1 && first.size() > 1 && (allTh || first.get(0)[1].isEmpty())) {
                    head = new ArrayList<>();
                    for (String[] c : rows.remove(0)) head.add(c[1]);
                }
                b.append('\n');
                for (int i = 0; i < rows.size(); i++) {
                    List<String[]> row = rows.get(i);
                    List<String> vals = new ArrayList<>();
                    for (int j = 1; j < row.size(); j++) {
                        String v = row.get(j)[1];
                        if (v.isEmpty()) continue;
                        vals.add(head != null && head.size() > 2 && j < head.size() && !head.get(j).isEmpty() ? head.get(j) + " " + v : v);
                    }
                    String label = row.get(0)[1];
                    if (i > 0) b.append('\n');
                    b.append(!label.isEmpty() && !vals.isEmpty() ? label + ": " + join(vals, ", ") : !label.isEmpty() ? label : join(vals, ", "));
                }
                b.append('\n');
            }
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(b.toString()));
        }
        m.appendTail(out);
        return out.toString();
    }

    static final Pattern CASE_MATTERS = Pattern.compile("[a-z][A-Z]|\\b[A-Z][a-z]\\d*[+\\u2212-](?![a-z])|\\b[A-Z]\\d*[+\\u2212]");

    /** Text whose capitals carry meaning (Na+, Ca2+, Cl−, NaCl, pH, mEq/L, IgG) isn't put in capitals by the bold looks. */
    public static boolean caseMatters(String t) { return t != null && CASE_MATTERS.matcher(t).find(); }

    /** Lines on one line: "Glycols; Oxoproline; L-lactate", but sentences just follow each other. */
    public static String inline(String t) {
        StringBuilder b = new StringBuilder();
        for (String l : t.split("\n", -1)) {
            if (b.length() == 0) { b.append(l); continue; }
            char last = b.charAt(b.length() - 1);
            b.append(".!?;:,".indexOf(last) >= 0 ? " " : "; ").append(l);
        }
        return b.toString();
    }

    /** Anki/HTML card text -> plain lines. */
    public static String plainText(String t) {
        if (t == null) return "";
        String s = tableLines(t).replaceAll("(?i)<\\s*br\\s*/?>", "\n").replaceAll("(?i)</(?:li|p|div|h\\d)>", "\n").replaceAll("(?i)<li[^>]*>", "\n").replaceAll("<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
                .replaceAll("[\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]", " ").replaceAll("[ \\t]+", " ");
        StringBuilder b = new StringBuilder();
        for (String line : s.split("\n", -1)) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        return b.toString();
    }

    public static AnswerShape splitPoints(String back) {
        String t = plainText(back);
        String[] lines = t.split("\n", -1);
        if (lines.length >= 2) {
            boolean numbered = true;
            for (String l : lines) if (!NUMBERED.matcher(l).find()) numbered = false;
            return shaped(Arrays.asList(lines), numbered ? "steps" : "list");
        }
        String one = lines[0];
        if (one.matches("^1[.)]\\s.*")) {
            List<String> p = numberedSteps(one);
            int real = 0;
            if (p != null) for (String x : p) if (!x.trim().isEmpty()) real++;
            if (real >= 2) return shaped(p, "steps");
        }
        if (one.indexOf(';') >= 0) {
            List<String> p = nonEmpty(one.split("\\s*;\\s*", -1));
            if (p.size() >= 2) return shaped(p, "list");
        }
        String[] ar = one.split("\\s*(?:→|->)\\s*", -1);
        if (ar.length >= 3 && !one.matches("(?s).*[+=].*")) return shaped(Arrays.asList(ar), "steps");
        if (one.length() > SHORT_MAX) {
            String[] se = one.split(SENTENCE, -1);
            if (se.length >= 2) return shaped(Arrays.asList(se), "prose");
            List<String> items = new ArrayList<>(Arrays.asList(one.split(",\\s+", -1)));
            String last = items.remove(items.size() - 1);
            String[] lp = last.split("\\s+(?:and|or)\\s+", -1);
            if (!items.isEmpty() && lp.length == 2) {
                List<String> all = new ArrayList<>(items);
                all.addAll(Arrays.asList(lp));
                boolean small = all.size() >= 3;
                for (String x : all) if (x.isEmpty() || words(x) > 4) small = false;
                if (small) return shaped(all, "list");
            }
            String[] cl = one.split(",\\s+(?=(?:so|which|meaning|because)\\s)|\\s+(?=because\\s)", -1);
            boolean clauses = cl.length >= 2;
            for (String x : cl) if (words(x) < 3) clauses = false;
            if (clauses) return shaped(Arrays.asList(cl), "prose");
        }
        AnswerShape a = new AnswerShape();
        a.kind = one.length() > SHORT_MAX ? "medium" : "short";
        if (!one.isEmpty()) a.points.add(one);
        return a;
    }

    private static final java.util.Map<String, Boolean> SHORT_CACHE = new java.util.LinkedHashMap<String, Boolean>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) { return size() > 4000; }
    };

    /** Is this answer short (one point, up to 48 characters)? Cached: quiz distractors check every card. */
    public static synchronized boolean isShort(String back) {
        if (back == null) return false;
        Boolean v = SHORT_CACHE.get(back);
        if (v == null) { v = "short".equals(splitPoints(back).kind); SHORT_CACHE.put(back, v); }
        return v;
    }

    /** {context, question}: a long question split before its last question or command sentence(s). */
    public static String[] splitQuestion(String q) { return splitQuestion(q, false); }

    static final Pattern GAP = Pattern.compile("\\[[^\\]]*\\]");

    /** As above; a cloze card's question starts at or before the sentence with the gap, so the gap stays big. */
    public static String[] splitQuestion(String q, boolean cloze) {
        String t = plainText(q).replace('\n', ' ');
        if (t.length() <= LONG_Q) return new String[]{"", t};
        String[] s = t.split(QSENTENCE, -1);
        if (s.length < 2) return new String[]{"", t};
        int i = s.length - 1;
        for (int k = s.length - 1; k >= 1; k--) {
            if (s[k].endsWith("?") || COMMAND.matcher(s[k]).find()) i = k;
            else if (k < s.length - 1) break;
        }
        if (cloze) {
            for (int g = 0; g < i; g++) if (GAP.matcher(s[g]).find()) { i = g; break; }
        }
        String ctx = join(Arrays.asList(s).subList(0, i), " "), main = join(Arrays.asList(s).subList(i, s.length), " ");
        if (ctx.length() < 24 || main.length() < 10) return new String[]{"", t};
        return new String[]{ctx, main};
    }

    static final Set<String> STOP = new HashSet<>(Arrays.asList(
            "the a an it its they their them this these that those so and or but of to in on is are was were be by with for as at from because which then there".split(" ")));

    /** How each point starts ("Active…", "Aerobic makes…"): one word, or more when two points would start alike. */
    public static List<String> starts(List<String> points) {
        Set<String> used = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : points) {
            String[] ws = p.split("\\s+");
            int i = -1;
            for (int k = 0; k < ws.length; k++) {
                String bare = ws[k].replaceAll("[^\\p{L}\\p{N}]", "");
                if (!bare.isEmpty() && !STOP.contains(bare.toLowerCase(Locale.UK)) && !ws[k].matches("\\(?\\d{1,2}[:.)]")) { i = k; break; }
            }
            if (i < 0) i = 0;
            int k = i + 1;
            String st = join(Arrays.asList(ws).subList(i, k), " ");
            while (used.contains(st.toLowerCase(Locale.UK)) && k < ws.length) { k++; st = join(Arrays.asList(ws).subList(i, k), " "); }
            used.add(st.toLowerCase(Locale.UK));
            String clean = st.replaceAll("^[^\\p{L}\\p{N}]+", "").replaceAll("[^\\p{L}\\p{N}]+$", "");
            out.add(cap1(clean.isEmpty() ? ws[i] : clean));
        }
        return out;
    }

    /** "1. Warm… 2. Filter… 3. Heat…": split only where the numbers run 1, 2, 3 in order (not at "100. "). */
    static List<String> numberedSteps(String one) {
        java.util.regex.Matcher m = STEP_NUMBER.matcher(one);
        List<int[]> cuts = new ArrayList<>();
        int want = 1;
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) == want) { cuts.add(new int[]{m.start() + m.group(1).length(), m.end()}); want++; }
        }
        if (cuts.size() < 2) return null;
        List<String> out = new ArrayList<>();
        for (int i = 0; i < cuts.size(); i++) out.add(one.substring(cuts.get(i)[1], i + 1 < cuts.size() ? cuts.get(i + 1)[0] : one.length()));
        return out;
    }

    // ------------------------------------------------------------------ helpers

    static AnswerShape shaped(List<String> raw, String kind) {
        List<String> pts = new ArrayList<>();
        for (String p : raw) { String t = tidy(p); if (!t.isEmpty()) pts.add(t); }
        AnswerShape a = new AnswerShape();
        if (pts.size() < 2) {
            a.kind = join(pts, " ").length() > SHORT_MAX ? "medium" : "short";
            a.points.addAll(pts);
            return a;
        }
        a.kind = kind;
        a.points.addAll(pts.subList(0, Math.min(MAX_POINTS, pts.size())));
        if (pts.size() > MAX_POINTS) a.over.addAll(pts.subList(MAX_POINTS, pts.size()));
        return a;
    }

    static String tidy(String p) { return cap1(BULLET.matcher(p).replaceFirst("").replaceAll("[\\s;,.]+$", "").trim()); }

    static String cap1(String s) { return s == null || s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Locale.UK) + s.substring(1); }

    static int words(String s) { return s.trim().split("\\s+").length; }

    static List<String> nonEmpty(String[] a) {
        List<String> out = new ArrayList<>();
        for (String s : a) if (!s.isEmpty()) out.add(s);
        return out;
    }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }
}
