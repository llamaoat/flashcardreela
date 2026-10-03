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
 * Bullets, numbering, simple HTML and markdown bold are stripped, "Q1:"/"A1:" labels work, and lines
 * ending in a colon are headings, as chatbots write them. Lines it can't use are counted, not guessed.
 * An on-device model can later handle messy prose; this keeps the offline path predictable.
 * For AI-made cards with no AI in the app, AI_PROMPT asks any chatbot for Q:/A: lines.
 * Mirrors parseNotes() and AI_PROMPT in web-demo/src/notes.js.
 */
public final class NotesParser {

    public static final class Result {
        public final List<Card> cards = new ArrayList<>();
        public int skippedLines;
    }

    /**
     * "Make cards with AI": the student copies this into a chatbot they already use, adds their notes
     * underneath, and pastes the reply back. The app itself stays offline.
     */
    public static final String AI_PROMPT = "Make flashcards from my revision notes below. They're for GCSE revision.\n"
            + "\n"
            + "Write each card as two lines, with a blank line after it:\n"
            + "Q: the question\n"
            + "A: the answer\n"
            + "\n"
            + "Rules:\n"
            + "- Only use facts that are in my notes. Don't add any others.\n"
            + "- One fact per card. Keep each answer short: a word, a phrase or one sentence.\n"
            + "- If an answer has several parts (like a 3-mark question), put them all on the A: line, separated by semicolons. For example: A: cell body; axon; dendrites\n"
            + "- Each question must make sense on its own, without my notes.\n"
            + "- Use UK spelling.\n"
            + "- Don't add headings, numbers, bold or anything else before or after the cards.\n"
            + "\n"
            + "My notes:\n"
            + "\n";

    static final Pattern Q_LABEL = Pattern.compile("^(?:q|question)\\s*\\d{0,3}\\s*[:.]\\s*", Pattern.CASE_INSENSITIVE);
    static final Pattern A_LABEL = Pattern.compile("^(?:a|answer)\\s*\\d{0,3}\\s*[:.]\\s*", Pattern.CASE_INSENSITIVE);
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

            Matcher q = Q_LABEL.matcher(line);
            if (q.find()) {
                if (pendingQ != null) r.skippedLines++;
                pendingQ = line.substring(q.end()).trim();
                continue;
            }
            Matcher al = A_LABEL.matcher(line);
            if (al.find()) {
                String a = line.substring(al.end()).trim();
                if (pendingQ != null && !pendingQ.isEmpty() && !a.isEmpty()) add(r, deck, pendingQ, a, "");
                else r.skippedLines++;
                pendingQ = null;
                continue;
            }
            if (pendingQ != null) { r.skippedLines++; pendingQ = null; }
            if (line.endsWith(":")) { r.skippedLines++; continue; } // a heading, or a chatbot's "Here are your flashcards:"

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
        String s = TAGS.matcher(raw).replaceAll(" ").replace("**", ""); // markdown bold, as chatbots write "**Q:**"
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
