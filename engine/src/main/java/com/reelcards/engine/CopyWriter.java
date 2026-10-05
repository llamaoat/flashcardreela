package com.reelcards.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Writes the words for a card reel in the voice of its hook, themed to what the student watches.
 *
 * Templates keep it offline, free and predictable. The plan is to plug an on-device model
 * (Gemini Nano via ML Kit's Prompt API) in behind this same write() method on phones that have
 * one, falling back to these templates everywhere else.
 */
public class CopyWriter {

    public CardScript write(Card c, Format f, List<Card> pool, Random rnd, String reason, Collection<String> vibes) {
        CardScript s = new CardScript();
        AnswerShape a = AnswerShape.of(c);
        String q = AnswerShape.plainText(c.front).replace('\n', ' ');
        s.question = q;
        s.answer = a.multi() ? AnswerShape.join(a.points, "; ") : (a.points.isEmpty() ? "" : a.points.get(0));
        s.explanation = a.inlineExplanation;
        s.shape = a.kind;
        s.points.addAll(a.points);
        s.marks = a.marks();
        s.context = a.context;
        s.questionMain = a.questionMain;
        s.more = a.more;
        if (a.multi()) s.pointStarts.addAll(AnswerShape.starts(a.points));
        s.image = c.image; s.imageW = c.imageW; s.imageH = c.imageH;
        s.answerImage = c.answerImage; s.answerImageW = c.answerImageW; s.answerImageH = c.answerImageH;
        if (c.occlusion != null) { s.occlusion.addAll(Occlusion.parse(c.occlusion)); s.occlusionTarget = c.occlusionTarget; }
        s.cloze = c.cloze;
        s.audio = c.audio; s.answerAudio = c.answerAudio;
        s.account = "@" + slug(c.deck) + pick(rnd, new String[]{".mocks", ".revision", ".explained", ".facts"});
        s.tag = c.deck + " · " + reason;
        String vibe = vibes == null || vibes.isEmpty() || rnd.nextDouble() < 0.4 ? null : pickFrom(rnd, vibes);
        s.vibe = vibe;

        String[] hooks, caps;
        switch (f.hook) {
            case "pov":
                hooks = themed(vibe, "pov", new String[]{"POV: it's 2 days before your mock", "POV: you actually revised this time", "POV: this comes up and you blank"});
                caps = new String[]{"if you don't know this we need to talk", "tag someone who'd get this wrong", "be honest"};
                break;
            case "waitforit":
                hooks = themed(vibe, "waitforit", new String[]{"Wait for it…", "Watch till the end", "Answer drops in 3…"});
                caps = new String[]{"watch till the end", "say it before the reveal", "no pausing"};
                break;
            case "mostwrong":
                hooks = themed(vibe, "mostwrong", new String[]{"Most people get this wrong", "Everyone forgets this one", "This one catches people out"});
                caps = new String[]{"be honest in the comments", "no googling", "first answer only"};
                break;
            case "text2am":
                hooks = new String[]{"She texted me this at 2am", "My mate asked this in the group chat", "Got this text in the middle of lunch"};
                caps = new String[]{"would you have got it?", "reply before the dots do", "the group chat went silent"};
                break;
            case "storytime":
                hooks = new String[]{"Storytime: this cost me marks", "The mock question I'll never forget", "I got this wrong so you don't have to"};
                caps = new String[]{"learn from my pain", "part 2?", "don't be me"};
                break;
            default: // rate
                hooks = themed(vibe, "rate", new String[]{"Rate my answer 1–10", "Can you beat my answer?", "Say it out loud first"});
                caps = new String[]{"no googling", "rate it honestly", "answer in your head first"};
        }
        s.hookLine = pick(rnd, hooks);
        s.caption = pick(rnd, caps);
        s.revealPrompt = pick(rnd, new String[]{"wait for it… (tap)", "tap for the answer", "think… then tap"});
        s.hint = a.io ? "Other labels uncovered: rule them out" : hint(c.back, a.points);
        s.sound = "original sound · " + s.account.substring(1);

        if ("chat".equals(f.layout)) {
            s.chatName = pick(rnd, new String[]{"Jess", "Sam", "Priya", "Tom", "Aisha", "Leo"});
            String open = pick(rnd, new String[]{"ok random q", "wait quick one", "don't laugh but"});
            String close = pick(rnd, new String[]{"and don't google it", "fast", "i've forgotten already"});
            s.chatThem.add(open);
            if (!a.context.isEmpty()) { s.chatThem.add(a.context); s.chatThem.add(a.questionMain); s.chatThemQuestion.add(a.context); s.chatThemQuestion.add(a.questionMain); }
            else { s.chatThem.add(q); s.chatThem.add(close); s.chatThemQuestion.add(q); }
            // a long answer is texted one point per message
            if (a.multi()) for (String p : a.points) s.chatMe.add(textish(p));
            else {
                s.chatMe.add(s.answer);
                if (!s.explanation.isEmpty()) s.chatMe.add(textish(s.explanation));
            }
            s.chatAfter = pick(rnd, a.multi() ? new String[]{"ok that's full marks", "saving this for the mock", "how do you remember all that"}
                                              : new String[]{"ok genius go to sleep", "how do you just know that", "saving this"});
        }

        if ("comment".equals(f.layout)) {
            s.commentUser = pick(rnd, new String[]{"@revision.panic", "@mocks_in_2_weeks", "@year11.survivor", "@lost.in.chem", "@notes.pls"});
            s.commentText = casual(q) + pick(rnd, new String[]{" pls", " someone explain", " i'm begging", " help"});
            s.hookLine = "Replying to " + s.commentUser;
        }

        if ("captions".equals(f.layout)) beats(q, s);

        if ("list".equals(f.layout) && a.multi()) {
            int n = a.marks();
            s.hookLine = pick(rnd, "steps".equals(a.kind)
                    ? new String[]{n + " steps. Say them in order", n + " steps, in order. Go", "Can you do all " + n + " steps?"}
                    : new String[]{n + " marks. Can you get all " + n + "?", "Name all " + n + " (most people miss one)", n + " things examiners look for here"});
        }

        if ("quiz".equals(f.layout)) {
            String wrong = distractor(c, pool, rnd);
            if (wrong != null) {
                if (rnd.nextBoolean()) { s.options.add(s.answer); s.options.add(wrong); s.correct = 0; }
                else { s.options.add(wrong); s.options.add(s.answer); s.correct = 1; }
            }
        }

        // what the voice reads: the hook then the question, like a creator talking to camera
        String hookSpoken = "comment".equals(f.layout) ? "Someone asked: " + s.commentText + "." : s.hookLine.replace("…", ".").replace("1–10", "one to ten");
        s.voiceover = hookSpoken + " " + (s.cloze ? CardScript.spokenGaps(q) : q);
        return s;
    }

    /**
     * A nudge that doesn't give the answer away: first letter and length for short answers,
     * the first word for longer ones.
     */
    public static String hint(String answer) { return hint(answer, null); }

    /** For a long answer: how many points, and how the first few start ("3 points: “Active…” “Substrate…”"). */
    public static String hint(String answer, List<String> points) {
        if (points != null && points.size() >= 2) {
            List<String> st = AnswerShape.starts(points);
            StringBuilder b = new StringBuilder().append(points.size()).append(" points:");
            for (int i = 0; i < Math.min(3, st.size()); i++) b.append(" “").append(st.get(i)).append("…”");
            if (points.size() > 3) b.append(" …");
            return b.toString();
        }
        String a = answer == null ? "" : answer.trim();
        if (a.isEmpty()) return "No hint for this one";
        String[] words = a.split("\\s+");
        String first = words[0].replaceAll("^[^\\p{L}\\p{N}]+", "");
        if (first.isEmpty()) first = words[0];
        if (words.length == 1) {
            int letters = first.replaceAll("[^\\p{L}\\p{N}]", "").length();
            return "Starts with “" + first.substring(0, 1).toUpperCase(Locale.UK) + "” · " + letters + (letters == 1 ? " letter" : " letters");
        }
        return "Starts with “" + first + "…” · " + words.length + " words";
    }

    /** Hook lines themed to a vibe, falling back to the general ones. */
    static String[] themed(String vibe, String hook, String[] general) {
        if (vibe == null) return general;
        String[] t = null;
        switch (vibe + "/" + hook) {
            case "football/pov": t = new String[]{"POV: VAR is checking your answer", "POV: last minute of the mock, extra time"}; break;
            case "football/mostwrong": t = new String[]{"Even the pundits get this wrong"}; break;
            case "football/waitforit": t = new String[]{"Wait for the replay…"}; break;
            case "gaming/waitforit": t = new String[]{"Speedrun this question", "Boss level question. Wait for it…"}; break;
            case "gaming/rate": t = new String[]{"Clutch or choke? Answer first"}; break;
            case "gaming/pov": t = new String[]{"POV: you respawn in the exam hall"}; break;
            case "beauty/pov": t = new String[]{"GRWM while I explain this", "POV: 5-minute revision routine"}; break;
            case "beauty/mostwrong": t = new String[]{"The revision tip nobody gatekeeps"}; break;
            case "comedy/pov": t = new String[]{"POV: the teacher picks you", "POV: you said the answer with confidence"}; break;
            case "comedy/rate": t = new String[]{"Rate my answer (be nice)"}; break;
            case "music/waitforit": t = new String[]{"Wait for the drop…", "Answer drops on the beat…"}; break;
            case "music/pov": t = new String[]{"POV: this is stuck in your head now"}; break;
            case "fitness/pov": t = new String[]{"POV: rest set, quick question", "POV: brain day, not leg day"}; break;
            case "fitness/rate": t = new String[]{"One rep: answer this"}; break;
            case "food/pov": t = new String[]{"POV: revising while dinner cooks"}; break;
            case "food/waitforit": t = new String[]{"Let it cook… answer in 3"}; break;
            default: break;
        }
        return t == null ? general : t;
    }

    /** Splits a question into 2–4 word caption beats and marks the longest word in each as the key term. */
    static void beats(String q, CardScript s) {
        String[] raw = q.trim().split("\\s+");
        List<String> merged = new ArrayList<>();
        for (int i = 0; i < raw.length; i++) { // a cloze [gap] stays in one beat
            String w = raw[i];
            if (w.contains("[") && !w.contains("]")) while (i + 1 < raw.length && !w.contains("]")) w += " " + raw[++i];
            merged.add(w);
        }
        String[] words = merged.toArray(new String[0]);
        List<String> cur = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            cur.add(words[i]);
            boolean end = cur.size() >= 3 || words[i].endsWith(",") || words[i].endsWith("?") || i == words.length - 1;
            if (end) {
                StringBuilder b = new StringBuilder();
                int hi = -1, len = 0;
                for (int k = 0; k < cur.size(); k++) {
                    if (k > 0) b.append(' ');
                    b.append(cur.get(k));
                    String bare = cur.get(k).replaceAll("[^\\p{L}\\p{N}]", "");
                    if (bare.length() > len && bare.length() >= 5) { len = bare.length(); hi = k; }
                }
                s.beats.add(b.toString());
                s.beatHighlight.add(hi);
                cur.clear();
            }
        }
    }

    /** "Which organ makes bile?" -> "which organ makes bile" : how people type in comments. */
    static String casual(String q) {
        String t = q.trim().replaceAll("[?.!]+$", "");
        if (t.length() > 1 && !Character.isUpperCase(t.charAt(1))) t = Character.toLowerCase(t.charAt(0)) + t.substring(1);
        return t;
    }

    /** A believable wrong answer: another card's answer from the same deck, similar length, and as alike in look as any (likeness). */
    public static String distractor(Card c, List<Card> pool, Random rnd) {
        List<String> same = new ArrayList<>(), any = new ArrayList<>();
        String mine = norm(c.back), q = AnswerShape.plainText(c.front).toLowerCase(Locale.UK);
        for (Card o : pool) {
            if (o == c || o.back == null || o.back.isEmpty()) continue;
            String ob = norm(o.back);
            if (ob.equals(mine) || q.contains(ob) || !AnswerShape.isShort(o.back)) continue; // not one the question already shows (a sibling cloze's answer)
            double ratio = (o.back.length() + 4.0) / (c.back.length() + 4.0);
            if (ratio < 0.4 || ratio > 2.5) continue;
            if (c.deck != null && c.deck.equals(o.deck)) same.add(o.back); else any.add(o.back);
        }
        List<String> src = same.isEmpty() ? any : same;
        if (src.isEmpty()) return null;
        String me = AnswerShape.plainText(c.back);
        int[] sc = new int[src.size()];
        int top = Integer.MIN_VALUE;
        for (int i = 0; i < sc.length; i++) {
            String b = src.get(i);
            sc[i] = likeness(me, b.indexOf('<') >= 0 || b.indexOf('&') >= 0 ? AnswerShape.plainText(b) : b);
            top = Math.max(top, sc[i]);
        }
        List<String> best = new ArrayList<>();
        for (int i = 0; i < sc.length; i++) if (sc[i] == top) best.add(src.get(i));
        return best.get(rnd.nextInt(best.size()));
    }

    /**
     * How alike two answers look, 0-5: the same pattern of capitals ("Streptococcus pneumoniae" and
     * "Staphylococcus aureus" are both Aa), word count, digits or none, and ending ("-ase", "-ine").
     */
    public static int likeness(String a, String b) {
        String wa = wordShape(a), wb = wordShape(b), ea = ending(a);
        return (wa.equals(wb) ? 2 : 0) + (wa.length() == wb.length() ? 1 : 0) + (hasDigit(a) == hasDigit(b) ? 1 : 0)
                + (!ea.isEmpty() && ea.equals(ending(b)) ? 1 : 0);
    }

    /** Each word's first letter or digit: A (capital), a, or 9. */
    static String wordShape(String t) {
        StringBuilder b = new StringBuilder();
        for (String w : t.trim().split("\\s+")) {
            for (int i = 0; i < w.length(); ) {
                int cp = w.codePointAt(i);
                int type = Character.getType(cp);
                if (Character.isLetter(cp) || type == Character.DECIMAL_DIGIT_NUMBER) {
                    b.append(type == Character.UPPERCASE_LETTER ? 'A' : type == Character.DECIMAL_DIGIT_NUMBER ? '9' : 'a');
                    break;
                }
                i += Character.charCount(cp);
            }
        }
        return b.toString();
    }

    static boolean hasDigit(String t) {
        for (int i = 0; i < t.length(); ) {
            int cp = t.codePointAt(i);
            if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /** The last three letters of the last word ("" if it's shorter). */
    static String ending(String t) {
        String last = "";
        for (String w : t.toLowerCase(Locale.UK).trim().split("\\s+")) {
            String l = w.replaceAll("[^\\p{L}]", "");
            if (!l.isEmpty()) last = l;
        }
        return last.length() >= 3 ? last.substring(last.length() - 3) : "";
    }

    public static boolean hasDistractor(Card c, List<Card> pool) {
        String mine = norm(c.back), q = AnswerShape.plainText(c.front).toLowerCase(Locale.UK);
        for (Card o : pool) {
            if (o == c || o.back == null) continue;
            String ob = norm(o.back);
            if (ob.isEmpty() || ob.equals(mine) || q.contains(ob) || !AnswerShape.isShort(o.back)) continue;
            double ratio = (o.back.length() + 4.0) / (c.back.length() + 4.0);
            if (ratio >= 0.4 && ratio <= 2.5) return true;
        }
        return false;
    }

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.UK); }

    /** A deck path's parts: "USMLE Step 1::Renal", or "USMLE Step 1 · Renal" as imported. */
    static final String DECKSEP = "::| · |:";

    /** The deck chip: its most specific part ("Renal"), without "GCSE". */
    public static String deckLabel(String deck) {
        if (deck == null || deck.isEmpty()) return "Study";
        String[] p = deck.split(DECKSEP, -1);
        String l = p[p.length - 1].trim().replaceFirst("(?i)^(gcse|a-level|a level|ks3)\\s+", "");
        return l.isEmpty() ? "Study" : l;
    }

    static String slug(String deck) {
        if (deck == null || deck.isEmpty()) return "study";
        String s = deck.split(DECKSEP)[0].toLowerCase(Locale.UK).replaceAll("^(gcse|a-level|a level|ks3)\\s+", "");
        s = s.replaceAll("[^a-z0-9]+", "");
        return s.isEmpty() ? "study" : (s.length() > 14 ? s.substring(0, 14) : s);
    }

    /** Texting style: lower-case first letter, unless it's an acronym ("LH…"). */
    static String textish(String p) {
        if (p == null || p.length() < 2) return p;
        String second = p.substring(1, 2);
        return second.equals(second.toLowerCase(Locale.UK)) ? p.substring(0, 1).toLowerCase(Locale.UK) + p.substring(1) : p;
    }

    static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static String pick(Random rnd, String[] a) { return a[rnd.nextInt(a.length)]; }

    static String pickFrom(Random rnd, Collection<String> c) {
        int i = rnd.nextInt(c.size());
        for (String s : c) if (i-- == 0) return s;
        return null;
    }
}
