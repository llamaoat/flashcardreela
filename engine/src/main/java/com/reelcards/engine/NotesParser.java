package com.reelcards.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns pasted revision notes into flashcards, no AI or internet needed.
 *
 * Understands, line by line:
 *   Q: question / A: answer          (on consecutive lines)
 *   term - definition   term – definition   term = definition   term :: definition
 *   term: definition                  (left side short)
 *   front<TAB>back[<TAB>extra]        (Anki "Notes in plain text" export, Quizlet export)
 *   "X is/are Y." "X is called Y." "X means Y."   (turned into a question)
 * Bullets, numbering and simple HTML are stripped. Lines it can't use are counted, not guessed.
 * An on-device model can later handle messy prose; this keeps the offline path predictable.
 */
public final class NotesParser {

    public static final class Result {
        public final List<Card> cards = new ArrayList<>();
        public int skippedLines;
    }

    static final Pattern BULLET = Pattern.compile("^\\s*(?:[-*•·▪◦]|\\d+[.)]|[a-z][.)])\\s+");
    static final Pattern TAGS = Pattern.compile("<[^>]+>");
    static final Pattern IS_CALLED = Pattern.compile("^(.{2,60}?)\\s+(is|are)\\s+called\\s+(.{1,120}?)[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern MEANS = Pattern.compile("^(.{2,60}?)\\s+means\\s+(.{2,160}?)[.!]?$", Pattern.CASE_INSENSITIVE);
    static final Pattern IS_ARE = Pattern.compile("^(.{2,60}?)\\s+(is|are)\\s+(.{2,160}?)[.!]?$", Pattern.CASE_INSENSITIVE);

    public static Result parse(String text, String deck) {
        Result r = new Result();
        if (text == null) return r;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        String pendingQ = null;
        for (String raw : lines) {
            String line = clean(raw);
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue; // Anki export headers / markdown headings

            String lower = line.toLowerCase(Locale.UK);
            if (lower.startsWith("q:") || lower.startsWith("q.") || lower.startsWith("question:")) {
                if (pendingQ != null) r.skippedLines++;
                pendingQ = line.substring(line.indexOf(lower.startsWith("question:") ? ':' : line.charAt(1)) + 1).trim();
                continue;
            }
            if (lower.startsWith("a:") || lower.startsWith("a.") || lower.startsWith("answer:")) {
                String a = line.substring(line.indexOf(lower.startsWith("answer:") ? ':' : line.charAt(1)) + 1).trim();
                if (pendingQ != null && !pendingQ.isEmpty() && !a.isEmpty()) add(r, deck, pendingQ, a, "");
                else r.skippedLines++;
                pendingQ = null;
                continue;
            }
            if (pendingQ != null) { r.skippedLines++; pendingQ = null; }

            if (line.indexOf('\t') >= 0) {
                String[] cols = line.split("\t");
                if (cols.length >= 2 && !cols[0].trim().isEmpty() && !cols[1].trim().isEmpty()) {
                    add(r, deck, asQuestion(cols[0].trim()), cols[1].trim(), cols.length > 2 ? cols[2].trim() : "");
                } else r.skippedLines++;
                continue;
            }

            String[] pair = splitPair(line);
            if (pair != null) { add(r, deck, asQuestion(pair[0]), cap(pair[1]), ""); continue; }

            Matcher m = IS_CALLED.matcher(line);
            if (m.matches() && words(m.group(1)) <= 8) {
                add(r, deck, "What " + m.group(2).toLowerCase(Locale.UK) + " " + lowerFirst(m.group(1)) + " called?", cap(m.group(3)), line);
                continue;
            }
            m = MEANS.matcher(line);
            if (m.matches() && words(m.group(1)) <= 8) {
                add(r, deck, "What does " + lowerFirst(m.group(1)) + " mean?", cap(m.group(2)), "");
                continue;
            }
            m = IS_ARE.matcher(line);
            if (m.matches() && words(m.group(1)) <= 6 && words(m.group(3)) <= 22) {
                add(r, deck, "What " + m.group(2).toLowerCase(Locale.UK) + " " + lowerFirst(m.group(1)) + "?", cap(m.group(3)), "");
                continue;
            }
            r.skippedLines++;
        }
        return r;
    }

    static String[] splitPair(String line) {
        String[] seps = {" :: ", " - ", " – ", " — ", " = ", " -> ", " → "};
        for (String s : seps) {
            int i = line.indexOf(s);
            if (i > 0 && i + s.length() < line.length()) {
                String a = line.substring(0, i).trim(), b = line.substring(i + s.length()).trim();
                if (!a.isEmpty() && !b.isEmpty() && a.length() <= 120) return new String[]{a, b};
            }
        }
        int c = line.indexOf(": ");
        if (c > 0 && c <= 60) {
            String a = line.substring(0, c).trim(), b = line.substring(c + 2).trim();
            if (!a.isEmpty() && !b.isEmpty() && words(a) <= 8) return new String[]{a, b};
        }
        return null;
    }

    /** "Osmosis" -> "What is osmosis?" ; a line that is already a question is kept. */
    static String asQuestion(String front) {
        String f = front.trim();
        if (f.endsWith("?")) return f;
        if (words(f) <= 5 && !f.contains("“") && !f.contains("\"")) {
            boolean acronym = f.length() > 1 && f.equals(f.toUpperCase(Locale.UK));
            return "What is " + (acronym ? f : lowerFirstIfCommon(f)) + "?";
        }
        return f;
    }

    static void add(Result r, String deck, String front, String back, String extra) {
        front = front.trim(); back = back.trim();
        if (front.length() > 200 || back.length() > 220 || front.isEmpty() || back.isEmpty()) { r.skippedLines++; return; }
        String id = "notes-" + Integer.toHexString((deck + "|" + front + "|" + back).hashCode());
        r.cards.add(new Card(id, deck, front, back, extra == null ? "" : extra));
    }

    static String clean(String raw) {
        String s = TAGS.matcher(raw).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        s = BULLET.matcher(s).replaceFirst("");
        // keep tabs (column separators), collapse other whitespace
        StringBuilder b = new StringBuilder();
        for (String part : s.split("\t", -1)) {
            if (b.length() > 0) b.append('\t');
            b.append(part.replaceAll("\\s+", " ").trim());
        }
        return b.toString().trim();
    }

    static int words(String s) { return s.trim().isEmpty() ? 0 : s.trim().split("\\s+").length; }

    static String cap(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }

    static String lowerFirst(String s) {
        if (s.isEmpty()) return s;
        // keep proper nouns / acronyms ("DNA", "Newton") as written
        if (s.length() > 1 && Character.isUpperCase(s.charAt(1))) return s;
        String first = s.split("\\s+")[0];
        if (first.length() > 1 && first.substring(1).matches(".*[A-Z].*")) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String lowerFirstIfCommon(String s) { return lowerFirst(s); }
}
