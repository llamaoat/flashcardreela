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
        s.question = c.front;
        s.answer = c.back;
        s.explanation = c.extra == null ? "" : c.extra;
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
        s.hint = hint(c.back);
        s.sound = "original sound · " + s.account.substring(1);

        if ("chat".equals(f.layout)) {
            s.chatName = pick(rnd, new String[]{"Jess", "Sam", "Priya", "Tom", "Aisha", "Leo"});
            s.chatThem.add(pick(rnd, new String[]{"ok random q", "wait quick one", "don't laugh but"}));
            s.chatThem.add(c.front);
            s.chatThem.add(pick(rnd, new String[]{"and don't google it", "fast", "i've forgotten already"}));
            s.chatMe.add(c.back);
            if (!s.explanation.isEmpty()) s.chatMe.add(lowerFirst(s.explanation));
            s.chatAfter = pick(rnd, new String[]{"ok genius go to sleep", "how do you just know that", "saving this"});
        }

        if ("comment".equals(f.layout)) {
            s.commentUser = pick(rnd, new String[]{"@revision.panic", "@mocks_in_2_weeks", "@year11.survivor", "@lost.in.chem", "@notes.pls"});
            s.commentText = casual(c.front) + pick(rnd, new String[]{" pls", " someone explain", " i'm begging", " help"});
            s.hookLine = "Replying to " + s.commentUser;
        }

        if ("captions".equals(f.layout)) beats(c.front, s);

        if ("quiz".equals(f.layout)) {
            String wrong = distractor(c, pool, rnd);
            if (wrong != null) {
                if (rnd.nextBoolean()) { s.options.add(c.back); s.options.add(wrong); s.correct = 0; }
                else { s.options.add(wrong); s.options.add(c.back); s.correct = 1; }
            }
        }

        // what the voice reads: the hook then the question, like a creator talking to camera
        String hookSpoken = "comment".equals(f.layout) ? "Someone asked: " + s.commentText + "." : s.hookLine.replace("…", ".").replace("1–10", "one to ten");
        s.voiceover = hookSpoken + " " + c.front;
        return s;
    }

    /**
     * A nudge that doesn't give the answer away: first letter and length for short answers,
     * the first word for longer ones.
     */
    public static String hint(String answer) {
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
        String[] words = q.trim().split("\\s+");
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

    /** A believable wrong answer: another card's answer from the same deck, similar length. */
    public static String distractor(Card c, List<Card> pool, Random rnd) {
        List<String> same = new ArrayList<>(), any = new ArrayList<>();
        String mine = norm(c.back);
        for (Card o : pool) {
            if (o == c || o.back == null || o.back.isEmpty()) continue;
            String ob = norm(o.back);
            if (ob.equals(mine)) continue;
            double ratio = (o.back.length() + 4.0) / (c.back.length() + 4.0);
            if (ratio < 0.4 || ratio > 2.5) continue;
            if (c.deck != null && c.deck.equals(o.deck)) same.add(o.back); else any.add(o.back);
        }
        List<String> src = same.isEmpty() ? any : same;
        if (src.isEmpty()) return null;
        return src.get(rnd.nextInt(src.size()));
    }

    public static boolean hasDistractor(Card c, List<Card> pool) {
        String mine = norm(c.back);
        for (Card o : pool) {
            if (o == c || o.back == null) continue;
            String ob = norm(o.back);
            if (ob.isEmpty() || ob.equals(mine)) continue;
            double ratio = (o.back.length() + 4.0) / (c.back.length() + 4.0);
            if (ratio >= 0.4 && ratio <= 2.5) return true;
        }
        return false;
    }

    static String norm(String s) { return s == null ? "" : s.trim().toLowerCase(Locale.UK); }

    static String slug(String deck) {
        if (deck == null || deck.isEmpty()) return "study";
        String s = deck.toLowerCase(Locale.UK).replaceAll("^(gcse|a-level|a level|ks3)\\s+", "");
        s = s.replaceAll("[^a-z0-9]+", "");
        return s.isEmpty() ? "study" : (s.length() > 14 ? s.substring(0, 14) : s);
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
