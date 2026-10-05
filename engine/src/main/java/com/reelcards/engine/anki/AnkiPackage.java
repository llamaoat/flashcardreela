package com.reelcards.engine.anki;

import com.reelcards.engine.AnswerShape;
import com.reelcards.engine.Card;
import com.reelcards.engine.Occlusion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Turns an Anki deck file (.apkg or .colpkg, any Anki version) into Reelcards cards, offline and with
 * no Anki account. One-way: answers stay in Reelcards.
 *
 *  - Basic and custom note types: the question is the front template; the answer is the first field
 *    the back adds, and any further fields become the explanation.
 *  - Cloze: one card per deletion number, with "[…]" (or "[hint]") where the gap is.
 *  - Image occlusion (Anki's built-in note type, and the older Image Occlusion Enhanced add-on): one
 *    card per covered label, with the picture and its masks.
 *  - Pictures on either side come along (PNG, JPEG, GIF, WebP, BMP up to 8 MB), and so do sounds
 *    (MP3, Ogg, M4A, WAV, FLAC up to 4 MB): the first on each side plays with the question or the
 *    answer. A question that's only a sound becomes "Listen: what's this?". Cards with nothing to
 *    read on either side (only sounds), and suspended cards, are left out.
 *  - Review cards keep their progress: FSRS memory state if the deck has it, otherwise the interval
 *    becomes the stability.
 *
 * Mirrors read() in web-demo/src/apkg.js (same rules, same output).
 */
public final class AnkiPackage {
    private AnkiPackage() {}

    /** Where pictures and sounds go. Returns what to store on the card (a file name on the phone). */
    public interface Media { String save(String name, byte[] data, String type); }

    public static final class Stats {
        public int cards, imported, suspended, audio, empty, unknown, images, cloze, occlusion, kept, sounds;

        /** "8 image occlusion, 3 cloze, 7 more. 2 keep their Anki progress. Left out: 1 suspended." */
        public String summary() {
            List<String> kinds = new ArrayList<>(), left = new ArrayList<>();
            if (occlusion > 0) kinds.add(occlusion + " image occlusion");
            if (cloze > 0) kinds.add(cloze + " cloze");
            int rest = imported - occlusion - cloze;
            if (rest > 0) kinds.add(rest + (kinds.isEmpty() ? " cards" : " more"));
            if (suspended > 0) left.add(suspended + " suspended");
            if (audio > 0) left.add(audio + " audio-only");
            if (empty > 0) left.add(empty + " blank or unsupported");
            if (unknown > 0) left.add(unknown + " with a missing note type");
            return (kinds.isEmpty() ? "No cards" : join(kinds, ", ")) + "." + (kept > 0 ? " " + kept + (kept == 1 ? " keeps its" : " keep their") + " Anki progress." : "")
                    + (sounds > 0 ? " " + sounds + (sounds == 1 ? " plays" : " play") + " a sound." : "")
                    + (left.isEmpty() ? "" : " Left out: " + join(left, ", ") + ".");
        }
    }

    public static final class Result {
        public final List<Card> cards = new ArrayList<>();
        public final Stats stats = new Stats();
    }

    public static final class NotAnki extends IOException { NotAnki(String m) { super(m); } }

    static final long DAY = 86_400_000L;
    static final String GAP = "…";
    static final String LISTEN = "Listen: what's this?";
    static final Pattern SOUND = Pattern.compile("\\[sound:([^\\]]+)\\]");
    static final Pattern IMG = Pattern.compile("<img[^>]*?src\\s*=\\s*[\"']?([^\"'>]+)[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);

    // ------------------------------------------------------------------ reading the file

    public static Result read(ZipFile zip, String fileName, long now, Media sink) throws IOException {
        String dbName = null;
        for (String n : new String[]{"collection.anki21b", "collection.anki21", "collection.anki2"}) if (zip.getEntry(n) != null) { dbName = n; break; }
        if (dbName == null) throw new NotAnki("There's no Anki deck inside this file.");
        byte[] dbBytes = entry(zip, dbName);
        if (Zstd.isZstd(dbBytes)) dbBytes = Zstd.decompress(dbBytes);
        SqliteFile db = new SqliteFile(dbBytes);
        dbBytes = null;

        // media: file name -> entry in the zip
        Map<String, String> mediaMap = new HashMap<>();
        if (zip.getEntry("media") != null) {
            byte[] m = entry(zip, "media");
            if (Zstd.isZstd(m)) m = Zstd.decompress(m);
            if (m.length > 0 && m[0] == '{') {
                for (Map.Entry<String, Object> e : Json.obj(Json.parse(new String(m, StandardCharsets.UTF_8))).entrySet()) mediaMap.put(Json.str(e.getValue()), e.getKey());
            } else {
                List<Object> entries = Pb.parse(m).get(1);
                if (entries != null) for (int i = 0; i < entries.size(); i++) {
                    Map<Integer, List<Object>> g = Pb.parse((byte[]) entries.get(i));
                    String name = Pb.text(g, 1);
                    if (!name.isEmpty()) mediaMap.put(name, g.containsKey(255) ? String.valueOf(g.get(255).get(0)) : String.valueOf(i));
                }
            }
        }
        Reader r = new Reader(zip, mediaMap, sink, now, fileName);
        return r.run(db);
    }

    /** One card of a note handed over by AnkiDroid (its content provider), rather than read from a file. */
    public static final class NoteCard {
        public Card card;               // null if there's nothing to show as text
        public boolean needsPicture;    // the question shows a picture (or it's image occlusion), which other apps can't read
        public boolean audioOnly;
    }

    /**
     * Turns a note from AnkiDroid's provider into a card, by the same rules as an imported deck: cloze
     * gaps become blanks and the answer is just the deletion. AnkiDroid doesn't let other apps read its
     * pictures, so a card whose question shows one comes back with needsPicture and no card.
     * @param templates each {name, question template, answer template}, by card number
     * @param fields    the note's fields, separated by 0x1f as Anki stores them
     */
    public static NoteCard noteCard(String typeName, boolean cloze, List<String> fieldNames, List<String[]> templates,
                                    String fields, String tags, String deckName, String deck, int ord) {
        NoteType nt = new NoteType();
        nt.name = typeName == null ? "" : typeName;
        nt.cloze = cloze;
        nt.fields.addAll(fieldNames);
        for (int i = 0; i < templates.size(); i++) setT(nt.tmpls, i, templates.get(i));
        for (String[] x : nt.tmpls) if (x != null && Pattern.compile("\\{\\{[^}]*cloze:").matcher(orEmpty(x[1])).find()) nt.cloze = true;
        Reader r = new Reader(null, new HashMap<String, String>(), null, 0, null);
        NoteCard out = new NoteCard();
        try {
            Card c = r.card(nt, fields == null ? "" : fields, tags, deckName, deck, ord);
            out.needsPicture = r.questionPicture;
            out.audioOnly = r.out.stats.audio > 0;
            out.card = out.needsPicture ? null : c;
        } catch (IOException | RuntimeException e) {
            out.card = null;
        }
        return out;
    }

    static byte[] entry(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) return null;
        InputStream in = zip.getInputStream(e);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(e.getSize() > 0 ? (int) Math.min(e.getSize(), 64 << 20) : 8192);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } finally { in.close(); }
    }

    static final class NoteType {
        String name; boolean cloze;
        final List<String> fields = new ArrayList<>();
        final List<String[]> tmpls = new ArrayList<>(); // {name, qfmt, afmt}
    }

    static final class Pic { String ref; int w, h; }

    static final int MAX_SOUND = 4 * 1024 * 1024;

    /** The sound file types cards play, from the file's first bytes: "mp3", "ogg", "m4a", "wav", "flac", or null. */
    public static String audioType(byte[] b) {
        if (b == null || b.length < 12) return null;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        if ((b0 == 'I' && b1 == 'D' && b[2] == '3') || (b0 == 0xFF && (b1 & 0xE0) == 0xE0)) return "mp3";
        if (b0 == 'O' && b1 == 'g' && b[2] == 'g' && b[3] == 'S') return "ogg";
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "m4a";
        if (b0 == 'R' && b1 == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "wav";
        if (b0 == 'f' && b1 == 'L' && b[2] == 'a' && b[3] == 'C') return "flac";
        return null;
    }

    static final class Reader {
        final ZipFile zip; final Map<String, String> mediaMap; final Media sink; final long now; final String fileName;
        final Map<String, byte[]> mediaCache = new HashMap<>();
        final Map<String, Pic> pics = new HashMap<>();
        final Result out = new Result();

        Reader(ZipFile zip, Map<String, String> mediaMap, Media sink, long now, String fileName) {
            this.zip = zip; this.mediaMap = mediaMap; this.sink = sink; this.now = now; this.fileName = fileName;
        }

        byte[] media(String name) throws IOException {
            if (mediaCache.containsKey(name)) return mediaCache.get(name);
            String z = mediaMap.get(name);
            byte[] d = z == null ? null : entry(zip, z);
            if (d != null && Zstd.isZstd(d)) d = Zstd.decompress(d);
            mediaCache.put(name, d);
            return d;
        }

        final Map<String, String> soundRefs = new HashMap<>();

        /** A sound file, saved once and shared by every card that plays it; null if it's missing, too big or not a type cards play. */
        String sound(String name) throws IOException {
            if (name == null) return null;
            if (soundRefs.containsKey(name)) return soundRefs.get(name);
            byte[] d = media(name);
            String type = audioType(d), ref = null;
            if (type != null && d.length <= MAX_SOUND) ref = sink == null ? name : sink.save(name, d, type);
            mediaCache.remove(name);
            soundRefs.put(name, ref);
            return ref;
        }

        Pic picture(String name) throws IOException {
            if (name == null) return null;
            if (pics.containsKey(name)) return pics.get(name);
            byte[] d = media(name);
            int[] size = imageSize(d);
            Pic p = null;
            if (d != null && size != null && d.length <= 8 * 1024 * 1024) {
                p = new Pic();
                p.w = size[0]; p.h = size[1];
                p.ref = sink == null ? name : sink.save(name, d, TYPES[size[2]]);
                if (p.ref == null) p = null;
            }
            mediaCache.remove(name); // pictures are written out once; don't keep them in memory
            pics.put(name, p);
            return p;
        }

        Result run(SqliteFile db) throws IOException {
            List<Map<String, Object>> colRows = db.rows("col");
            Map<String, Object> col = colRows == null || colRows.isEmpty() ? new HashMap<String, Object>() : colRows.get(0);
            long crt = col.get("crt") instanceof Long ? (Long) col.get("crt") : 0;

            Map<Long, NoteType> nts = new HashMap<>();
            List<Map<String, Object>> ntRows = db.rows("notetypes");
            if (ntRows != null && !ntRows.isEmpty()) {
                for (Map<String, Object> row : ntRows) {
                    Map<Integer, List<Object>> c = Pb.parse(bytes(row.get("config")));
                    NoteType t = new NoteType();
                    t.name = String.valueOf(row.get("name"));
                    t.cloze = c.containsKey(1) && Long.valueOf(1).equals(c.get(1).get(0));
                    nts.put((Long) row.get("id"), t);
                }
                List<Map<String, Object>> fields = db.rows("fields");
                if (fields != null) {
                    sortBy(fields, "ord");
                    for (Map<String, Object> row : fields) { NoteType t = nts.get((Long) row.get("ntid")); if (t != null) set(t.fields, ((Long) row.get("ord")).intValue(), String.valueOf(row.get("name"))); }
                }
                List<Map<String, Object>> tmpls = db.rows("templates");
                if (tmpls != null) {
                    sortBy(tmpls, "ord");
                    for (Map<String, Object> row : tmpls) {
                        NoteType t = nts.get((Long) row.get("ntid"));
                        if (t == null) continue;
                        Map<Integer, List<Object>> c = Pb.parse(bytes(row.get("config")));
                        String q = Pb.text(c, 1), a = Pb.text(c, 2);
                        if (!q.contains("{{")) { // unexpected layout: take the first two template-looking strings
                            List<String> strs = new ArrayList<>();
                            List<Integer> keys = new ArrayList<>(c.keySet());
                            Collections.sort(keys);
                            for (Integer k : keys) for (Object v : c.get(k)) if (v instanceof byte[]) { String s = new String((byte[]) v, StandardCharsets.UTF_8); if (s.contains("{{")) strs.add(s); }
                            if (!strs.isEmpty()) q = strs.get(0);
                            if (strs.size() > 1) a = strs.get(1);
                        }
                        setT(t.tmpls, ((Long) row.get("ord")).intValue(), new String[]{String.valueOf(row.get("name")), q, a});
                    }
                }
            } else if (col.get("models") instanceof String && !((String) col.get("models")).isEmpty()) {
                for (Map.Entry<String, Object> e : Json.obj(Json.parse((String) col.get("models"))).entrySet()) {
                    Map<String, Object> m = Json.obj(e.getValue());
                    NoteType t = new NoteType();
                    t.name = Json.str(m.get("name"));
                    t.cloze = Json.num(m.get("type"), 0) == 1;
                    List<Object> flds = new ArrayList<>(Json.arr(m.get("flds")));
                    sortByOrd(flds);
                    for (Object f : flds) t.fields.add(Json.str(Json.obj(f).get("name")));
                    List<Object> tm = new ArrayList<>(Json.arr(m.get("tmpls")));
                    sortByOrd(tm);
                    for (Object o : tm) { Map<String, Object> x = Json.obj(o); t.tmpls.add(new String[]{Json.str(x.get("name")), orEmpty(Json.str(x.get("qfmt"))), orEmpty(Json.str(x.get("afmt")))}); }
                    nts.put(Long.parseLong(e.getKey()), t);
                }
            }
            for (NoteType t : nts.values()) {
                if (t.cloze) continue;
                for (String[] x : t.tmpls) if (x != null && Pattern.compile("\\{\\{[^}]*cloze:").matcher(x[1]).find()) t.cloze = true;
            }

            Map<Long, String> decks = new HashMap<>();
            List<Map<String, Object>> dRows = db.rows("decks");
            if (dRows != null && !dRows.isEmpty()) for (Map<String, Object> row : dRows) decks.put((Long) row.get("id"), String.valueOf(row.get("name")).replace("\u001f", "::"));
            else if (col.get("decks") instanceof String && !((String) col.get("decks")).isEmpty())
                for (Map.Entry<String, Object> e : Json.obj(Json.parse((String) col.get("decks"))).entrySet()) decks.put(Long.parseLong(e.getKey()), Json.str(Json.obj(e.getValue()).get("name")));
            String fallback = (fileName == null ? "Anki deck" : fileName).replaceAll("(?i)\\.(apkg|colpkg)$", "");

            Map<Long, Map<String, Object>> notes = new HashMap<>();
            List<Map<String, Object>> noteRows = db.rows("notes");
            if (noteRows != null) for (Map<String, Object> n : noteRows) notes.put((Long) n.get("id"), n);

            List<Map<String, Object>> cards = db.rows("cards");
            if (cards == null) cards = new ArrayList<>();
            sortBy(cards, "id");
            Stats st = out.stats;
            for (Map<String, Object> c : cards) {
                st.cards++;
                if (lng(c, "queue") == -1) { st.suspended++; continue; }
                Map<String, Object> n = notes.get(lng(c, "nid"));
                NoteType nt = n == null ? null : nts.get(lng(n, "mid"));
                if (nt == null) { st.unknown++; continue; }
                String deckName = decks.get(lng(c, "did"));
                String deck = deckName == null || deckName.equals("Default") ? fallback : deckName.replace("::", " · ");
                int audio = st.audio;
                Card card = card(nt, String.valueOf(n.get("flds")), n.get("tags") == null ? "" : String.valueOf(n.get("tags")), deckName, deck, (int) lng(c, "ord"));
                if (card == null) { if (st.audio == audio) st.empty++; continue; }
                card.id = "apkg:" + lng(c, "id");
                card.reps = (int) lng(c, "reps");
                card.lapses = (int) lng(c, "lapses");
                long type = lng(c, "type");
                if (type == 2 || type == 3) {
                    double s = 0, d = 0;
                    Object data = c.get("data");
                    if (data instanceof String && ((String) data).startsWith("{")) {
                        try { Map<String, Object> j = Json.obj(Json.parse((String) data)); s = Json.num(j.get("s"), 0); d = Json.num(j.get("d"), 0); } catch (RuntimeException ignored) { }
                    }
                    long ivl = Math.max(1, lng(c, "ivl"));
                    long factor = lng(c, "factor");
                    card.state = Card.REVIEW;
                    card.stability = s > 0 ? s : ivl;
                    card.difficulty = d > 0 ? d : Math.max(1, Math.min(10, 5 + (2500 - (factor == 0 ? 2500 : factor)) / 250.0));
                    card.due = type == 2 && crt > 0 && lng(c, "queue") == 2 ? (crt + lng(c, "due") * 86400L) * 1000L : now;
                    card.lastReview = card.due - ivl * DAY;
                    st.kept++;
                }
                if (card.image != null || card.answerImage != null) st.images++;
                if (card.audio != null || card.answerAudio != null) st.sounds++;
                out.cards.add(card);
                st.imported++;
            }
            return out;
        }

        boolean questionPicture; // set by card(): the question side shows a picture (found or not)

        /**
         * One card of a note, from its note type, raw fields (separated by 0x1f), tags, deck and card
         * number. Null if there's nothing to show; st.audio counts the audio-only ones.
         */
        Card card(NoteType nt, String flds, String tags, String deckName, String deck, int ord) throws IOException {
            Stats st = out.stats;
            questionPicture = false;
            String[] vals = flds.split("\u001f", -1);
            Map<String, String> F = new LinkedHashMap<>();
            for (int i = 0; i < nt.fields.size(); i++) if (nt.fields.get(i) != null) F.put(nt.fields.get(i), i < vals.length ? vals[i] : "");
            Ctx ctx = new Ctx();
            ctx.tags = tags == null ? "" : tags.trim();
            ctx.type = nt.name;
            ctx.deck = deckName == null ? "" : deckName;
            Card card = null;
            String occField = null;
            for (Map.Entry<String, String> e : F.entrySet()) if (e.getValue().contains("image-occlusion:")) { occField = e.getKey(); break; }
            String ioeMask = F.get("Question Mask");
            if (nt.cloze && occField != null) {
                questionPicture = true;
                StringBuilder others = new StringBuilder();
                for (Map.Entry<String, String> e : F.entrySet()) if (!e.getKey().equals(occField)) others.append(e.getValue()).append(' ');
                List<String> imgs = images(others.toString());
                Pic pic = picture(imgs.isEmpty() ? null : imgs.get(0));
                int target = ord + 1;
                List<Occlusion.Shape> shapes = pic == null ? new ArrayList<Occlusion.Shape>() : ioShapes(F.get(occField), pic.w, pic.h);
                if (pic != null && hasOrd(shapes, target)) {
                    String header = clean(orEmpty(F.get("Header")));
                    card = new Card(null, deck, header.isEmpty() ? "Name the highlighted part" : header, "", clean(orEmpty(F.get("Back Extra"))));
                    setPic(card, pic);
                    card.occlusion = Occlusion.format(shapes);
                    card.occlusionTarget = target;
                    st.occlusion++;
                }
            } else if (ioeMask != null && !images(ioeMask).isEmpty()) {
                questionPicture = true;
                List<String> imgs = images(orEmpty(F.get("Image")));
                Pic pic = picture(imgs.isEmpty() ? null : imgs.get(0));
                byte[] svg = media(images(ioeMask).get(0));
                List<Occlusion.Shape> shapes = svg == null ? new ArrayList<Occlusion.Shape>() : ioeShapes(new String(svg, StandardCharsets.UTF_8));
                if (pic != null && hasOrd(shapes, 1)) {
                    List<String> extra = new ArrayList<>();
                    for (String k : new String[]{"Footer", "Remarks", "Extra 1", "Extra 2"}) { String x = clean(orEmpty(F.get(k))); if (!x.isEmpty()) extra.add(x); }
                    String header = clean(orEmpty(F.get("Header")));
                    card = new Card(null, deck, header.isEmpty() ? "Name the highlighted part" : header, "", join(extra, "\n"));
                    setPic(card, pic);
                    card.occlusion = Occlusion.format(shapes);
                    card.occlusionTarget = 1;
                    st.occlusion++;
                }
            } else if (nt.cloze) {
                String[] t = nt.tmpls.isEmpty() || nt.tmpls.get(0) == null ? new String[]{"Cloze", "{{cloze:Text}}", "{{cloze:Text}}"} : nt.tmpls.get(0);
                int num = ord + 1;
                List<Ref> qRefs = fieldRefs(t[1]);
                String clozeField = null;
                for (Ref rf : qRefs) if (rf.cloze) { clozeField = rf.field; break; }
                Ctx qc = ctx.copy(); qc.side = "q"; qc.cloze = num;
                String qHtml = renderTpl(t[1], F, qc);
                List<String> got = new ArrayList<>();
                String full = "";
                if (clozeField != null) {
                    List<Object> nodes = clozeParse(orEmpty(F.get(clozeField)));
                    clozeText(nodes, num, "a", got);
                    full = clean(clozeText(nodes, null, "all", null));
                }
                List<String> extra = new ArrayList<>();
                for (Ref rf : fieldRefs(t[2])) {
                    if (rf.field.equals("FrontSide")) continue;
                    boolean inQ = false;
                    for (Ref q : qRefs) if (q.field.equals(rf.field)) inQ = true;
                    if (!inQ) { String x = clean(orEmpty(F.get(rf.field))); if (!x.isEmpty()) extra.add(x); }
                }
                List<String> answers = new ArrayList<>();
                for (String a : got) { String x = clean(a); if (!x.isEmpty() && !answers.contains(x)) answers.add(x); }
                if (!answers.isEmpty() && hasText(qHtml)) {
                    List<String> qi = images(qHtml);
                    questionPicture = !qi.isEmpty();
                    Pic pic = picture(qi.isEmpty() ? null : qi.get(0));
                    List<String> xs = new ArrayList<>();
                    if (!full.isEmpty()) xs.add(full);
                    xs.addAll(extra);
                    card = new Card(null, deck, clean(qHtml), join(answers, "; "), join(xs, "\n"));
                    if (pic != null) setPic(card, pic);
                    List<String> qs = sounds(qHtml);
                    card.audio = sound(qs.isEmpty() ? null : qs.get(0));
                    card.cloze = true;
                    st.cloze++;
                }
            } else if (ord < nt.tmpls.size() && nt.tmpls.get(ord) != null) {
                String[] t = nt.tmpls.get(ord);
                ctx.card = t[0];
                Ctx qc = ctx.copy(); qc.side = "q";
                String qHtml = renderTpl(t[1], F, qc);
                List<String> qRefs = new ArrayList<>();
                for (Ref rf : fieldRefs(t[1])) if (!rf.type) qRefs.add(rf.field);
                List<String> aFields = new ArrayList<>();
                for (Ref rf : fieldRefs(t[2])) {
                    String v = F.get(rf.field);
                    if (!rf.field.equals("FrontSide") && !qRefs.contains(rf.field) && !aFields.contains(rf.field) && v != null && (hasText(v) || v.toLowerCase().contains("<img"))) aFields.add(rf.field);
                }
                String bHtml, xText = "";
                if (!aFields.isEmpty()) {
                    bHtml = F.get(aFields.get(0));
                    List<String> xs = new ArrayList<>();
                    for (int i = 1; i < aFields.size(); i++) { String x = clean(F.get(aFields.get(i))); if (!x.isEmpty()) xs.add(x); }
                    xText = join(xs, "\n");
                } else {
                    Ctx ac = ctx.copy(); ac.side = "a"; ac.front = "";
                    String a = renderTpl(t[2], F, ac);
                    Matcher m = Pattern.compile("<hr[^>]*id\\s*=\\s*[\"']?answer", Pattern.CASE_INSENSITIVE).matcher(a);
                    if (m.find()) a = a.substring(m.start()).replaceFirst("(?i)^<hr[^>]*>", "");
                    bHtml = a;
                }
                List<String> qi = images(qHtml), ai = images(bHtml);
                questionPicture = !qi.isEmpty();
                Pic qPic = picture(qi.isEmpty() ? null : qi.get(0)), aPic = picture(ai.isEmpty() ? null : ai.get(0));
                List<String> qs = sounds(qHtml), as = sounds(bHtml);
                String qSnd = sound(qs.isEmpty() ? null : qs.get(0)), aSnd = sound(as.isEmpty() ? null : as.get(0));
                String f = clean(qHtml), b = clean(bHtml);
                // something to ask (words, a picture or a sound) and an answer (words or a picture, or a
                // sound when there's a written or pictured question to go with it)
                boolean ask = !f.isEmpty() || qPic != null || qSnd != null;
                boolean answer = !b.isEmpty() || aPic != null || (aSnd != null && (!f.isEmpty() || qPic != null));
                if (ask && answer) {
                    card = new Card(null, deck, !f.isEmpty() ? f : qPic == null ? LISTEN : "What's this?", b, xText);
                    if (qPic != null) setPic(card, qPic);
                    if (aPic != null) { card.answerImage = aPic.ref; card.answerImageW = aPic.w; card.answerImageH = aPic.h; }
                    card.audio = qSnd;
                    card.answerAudio = aSnd;
                } else if ((qHtml + bHtml).contains("[sound:")) { st.audio++; return null; }
            }
            return card;
        }
    }

    static void setPic(Card c, Pic p) { c.image = p.ref; c.imageW = p.w; c.imageH = p.h; }

    /** A card needs a mask to name: a text label with the same number is drawn, but isn't something to cover. */
    static boolean hasOrd(List<Occlusion.Shape> shapes, int ord) { for (Occlusion.Shape s : shapes) if (s.ord == ord && !"text".equals(s.kind)) return true; return false; }

    // ------------------------------------------------------------------ text

    static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));");

    /** HTML entities: the few Anki's editor writes, and numeric ones (&#x27; from decks made by scripts). */
    static String entities(String t) {
        if (t.contains("&#")) {
            Matcher m = NUMERIC_ENTITY.matcher(t);
            StringBuffer b = new StringBuffer();
            while (m.find()) {
                int cp = m.group(1) != null ? Integer.parseInt(m.group(1), 16) : Integer.parseInt(m.group(2));
                boolean ok = cp > 0 && Character.isValidCodePoint(cp) && !(cp >= 0xD800 && cp <= 0xDFFF);
                m.appendReplacement(b, Matcher.quoteReplacement(ok ? new String(Character.toChars(cp)) : m.group()));
            }
            m.appendTail(b);
            t = b.toString();
        }
        return t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }

    static List<String> sounds(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = SOUND.matcher(html == null ? "" : html);
        while (m.find()) out.add(m.group(1).trim());
        return out;
    }

    static List<String> images(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = IMG.matcher(html == null ? "" : html);
        while (m.find()) out.add(percentDecode(entities(m.group(1)).trim()));
        return out;
    }

    /** Like decodeURIComponent: %XX sequences as UTF-8; the text is kept as it was if they're malformed. */
    static String percentDecode(String s) {
        if (s.indexOf('%') < 0) return s;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '%') { b.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; }
                else { byte[] u = String.valueOf(c).getBytes(StandardCharsets.UTF_8); b.write(u, 0, u.length); }
            }
            java.nio.charset.CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder();
            return dec.decode(java.nio.ByteBuffer.wrap(b.toByteArray())).toString();
        } catch (Exception e) { return s; }
    }

    /** Card text: HTML to plain lines, without images, sounds or scripts. */
    public static String clean(String html) {
        String t = AnswerShape.tableLines((html == null ? "" : html).replaceAll("(?is)<(script|style)[\\s\\S]*?</\\1>", " ").replaceAll("\\[sound:[^\\]]*\\]", " ").replaceAll("(?i)<img[^>]*>", " "))
                .replaceAll("(?i)<\\s*br\\s*/?>", "\n").replaceAll("(?i)<hr[^>]*>", "\n").replaceAll("(?i)</(?:li|p|div|h\\d|tr)>", "\n").replaceAll("(?i)<li[^>]*>", "\n").replaceAll("<[^>]+>", "");
        t = entities(t).replaceAll("[\\u00a0\\u2000-\\u200a\\u202f\\u205f\\u3000]", " ").replaceAll("[ \\t]+", " ");
        StringBuilder b = new StringBuilder();
        for (String line : t.split("\n", -1)) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            if (b.length() > 0) b.append('\n');
            b.append(l);
        }
        return b.toString();
    }

    static boolean hasText(String html) { return !clean(html).isEmpty(); }

    // ------------------------------------------------------------------ cloze

    static final class Node { List<Integer> nums = new ArrayList<>(); List<Object> kids = new ArrayList<>(); String hint; }

    static final Pattern CLOZE_OPEN = Pattern.compile("^\\{\\{c(\\d+(?:,\\d+)*)::");

    /** Cloze text -> nodes: Strings and Nodes (nested deletions allowed). */
    public static List<Object> clozeParse(String t) {
        Node root = new Node();
        List<Node> stack = new ArrayList<>();
        stack.add(root);
        StringBuilder buf = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            Matcher m = CLOZE_OPEN.matcher(t.substring(i, Math.min(t.length(), i + 24)));
            if (m.find()) {
                flush(stack, buf);
                Node node = new Node();
                for (String x : m.group(1).split(",")) node.nums.add(Integer.parseInt(x));
                top(stack).kids.add(node);
                stack.add(node);
                i += m.group(0).length();
                continue;
            }
            if (stack.size() > 1 && t.startsWith("::", i)) {
                int end = t.indexOf("}}", i + 2);
                if (end >= 0 && !t.substring(i + 2, end).contains("{{c")) { flush(stack, buf); top(stack).hint = t.substring(i + 2, end); i = end; continue; }
            }
            if (stack.size() > 1 && t.startsWith("}}", i)) { flush(stack, buf); stack.remove(stack.size() - 1); i += 2; continue; }
            buf.append(t.charAt(i++));
        }
        flush(stack, buf);
        return root.kids;
    }

    static Node top(List<Node> stack) { return stack.get(stack.size() - 1); }

    static void flush(List<Node> stack, StringBuilder buf) { if (buf.length() > 0) { top(stack).kids.add(buf.toString()); buf.setLength(0); } }

    /** mode "q": card n's deletions become [hint] or […]; "a"/"all": shown. collect gets card n's answers. */
    public static String clozeText(List<Object> nodes, Integer n, String mode, List<String> collect) {
        StringBuilder out = new StringBuilder();
        for (Object x : nodes) {
            if (x instanceof String) { out.append((String) x); continue; }
            Node node = (Node) x;
            boolean active = n != null && node.nums.contains(n);
            String inner = clozeText(node.kids, n, mode, active ? null : collect);
            if (active && collect != null) collect.add(inner);
            out.append(active && "q".equals(mode) ? "[" + (node.hint != null ? node.hint : GAP) + "]" : inner);
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ templates

    static final class Ctx {
        String side = "q", front = "", tags = "", type = "", deck = "", card = "";
        Integer cloze;
        Ctx copy() { Ctx c = new Ctx(); c.side = side; c.front = front; c.tags = tags; c.type = type; c.deck = deck; c.card = card; c.cloze = cloze; return c; }
    }

    static final class Ref { String field; boolean type, cloze; }

    static final Pattern TAG = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    static List<Ref> fieldRefs(String tpl) {
        List<Ref> out = new ArrayList<>();
        Matcher m = TAG.matcher(tpl == null ? "" : tpl);
        while (m.find()) {
            String inner = m.group(1).trim();
            if (inner.isEmpty() || "#^/!".indexOf(inner.charAt(0)) >= 0) continue;
            String[] parts = inner.split(":", -1);
            Ref r = new Ref();
            r.field = parts[parts.length - 1].trim();
            for (int i = 0; i < parts.length - 1; i++) { String f = parts[i].trim(); if (f.equals("type")) r.type = true; if (f.equals("cloze")) r.cloze = true; }
            out.add(r);
        }
        return out;
    }

    static String value(String name, Map<String, String> F, Ctx ctx) {
        switch (name) {
            case "FrontSide": return ctx.front;
            case "Tags": return ctx.tags;
            case "Type": return ctx.type;
            case "Deck": return ctx.deck;
            case "Subdeck": { String[] p = ctx.deck.split("::"); return p[p.length - 1]; }
            case "Card": return ctx.card;
            case "CardFlag": return "";
            default: return F.get(name);
        }
    }

    static final Pattern SECTION = Pattern.compile("\\{\\{([#^])\\s*([^}]+?)\\s*\\}\\}([\\s\\S]*?)\\{\\{/\\s*\\2\\s*\\}\\}");

    static String renderTpl(String tpl, Map<String, String> F, Ctx ctx) {
        String t = tpl == null ? "" : tpl;
        for (int guard = 0; guard < 200; guard++) {
            Matcher m = SECTION.matcher(t);
            if (!m.find()) break;
            String[] np = m.group(2).split(":");
            String v = value(np[np.length - 1].trim(), F, ctx);
            boolean full = v != null && (hasText(v) || v.toLowerCase().contains("<img"));
            t = t.substring(0, m.start()) + (("#".equals(m.group(1))) == full ? m.group(3) : "") + t.substring(m.end());
        }
        Matcher m = TAG.matcher(t);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String inner = m.group(1).trim(), rep;
            if (inner.isEmpty() || "#^/!".indexOf(inner.charAt(0)) >= 0) rep = "";
            else {
                String[] parts = inner.split(":", -1);
                String v = value(parts[parts.length - 1].trim(), F, ctx);
                if (v == null) rep = "";
                else {
                    for (int i = parts.length - 2; i >= 0; i--) {
                        String f = parts[i].trim();
                        if (f.equals("cloze")) { if (ctx.cloze != null) v = clozeText(clozeParse(v), ctx.cloze, ctx.side, null); }
                        else if (f.equals("cloze-only")) { List<String> got = new ArrayList<>(); clozeText(clozeParse(v), ctx.cloze, "a", got); v = join(got, ", "); }
                        else if (f.equals("text")) v = clean(v);
                        else if (f.equals("type")) v = "q".equals(ctx.side) ? "" : v;
                        else if (f.equals("tts") || f.equals("tts-voices")) v = "";
                        else if (f.equals("furigana") || f.equals("kanji")) v = v.replaceAll("\\[[^\\]]*\\]", "");
                        else if (f.equals("kana")) v = v.replaceAll("[^\\s\\[\\]]+\\[([^\\]]*)\\]", "$1");
                    }
                    rep = v;
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------ image occlusion

    static final Pattern IO = Pattern.compile("\\{\\{c(\\d+)::image-occlusion:([a-z]+):([^}]*)\\}\\}", Pattern.CASE_INSENSITIVE);

    /** Anki's built-in image occlusion: {{c1::image-occlusion:rect:left=.1:top=.2:width=.3:height=.1:oi=1}} */
    public static List<Occlusion.Shape> ioShapes(String text, int w, int h) {
        List<Occlusion.Shape> out = new ArrayList<>();
        Matcher m = IO.matcher(text == null ? "" : text);
        while (m.find()) {
            Map<String, String> p = new HashMap<>();
            for (String kv : m.group(3).split(":")) { int i = kv.indexOf('='); if (i > 0) p.put(kv.substring(0, i).trim(), kv.substring(i + 1).trim()); }
            Occlusion.Shape s = new Occlusion.Shape();
            s.ord = Integer.parseInt(m.group(1));
            s.kind = m.group(2).toLowerCase();
            s.oi = "1".equals(p.get("oi"));
            if (s.kind.equals("rect")) { s.x = num(p, "left"); s.y = num(p, "top"); s.w = num(p, "width"); s.h = num(p, "height"); }
            else if (s.kind.equals("ellipse")) { double rx = num(p, "rx"), ry = num(p, "ry"); s.x = num(p, "left"); s.y = num(p, "top"); s.w = 2 * rx; s.h = 2 * ry; }
            else if (s.kind.equals("polygon")) s.pts = points(p.get("points"), ",");
            else if (s.kind.equals("text")) { s.x = num(p, "left"); s.y = num(p, "top"); s.text = p.containsKey("text") ? p.get("text") : ""; }
            else continue;
            out.add(s);
        }
        return normalise(out, w, h);
    }

    static double num(Map<String, String> p, String k) { try { return Double.parseDouble(p.get(k)); } catch (RuntimeException e) { return Double.NaN; } }

    static double[] points(String text, String sep) {
        List<Double> v = new ArrayList<>();
        for (String xy : (text == null ? "" : text).trim().split("\\s+")) {
            String[] a = xy.split(sep);
            if (a.length != 2) continue;
            try { double x = Double.parseDouble(a[0]), y = Double.parseDouble(a[1]); v.add(x); v.add(y); } catch (NumberFormatException ignored) { }
        }
        double[] r = new double[v.size()];
        for (int i = 0; i < r.length; i++) r[i] = v.get(i);
        return r;
    }

    /** Older Anki versions stored pixels, newer ones fractions of the picture: keep fractions. */
    static List<Occlusion.Shape> normalise(List<Occlusion.Shape> shapes, int w, int h) {
        boolean big = false;
        for (Occlusion.Shape s : shapes) {
            if (s.x > 1.5 || s.y > 1.5 || s.w > 1.5 || s.h > 1.5) big = true;
            if (s.pts != null) for (double v : s.pts) if (v > 1.5) big = true;
        }
        if (!big) return shapes;
        if (w <= 0 || h <= 0) return new ArrayList<>();
        for (Occlusion.Shape s : shapes) {
            s.x /= w; s.y /= h; s.w /= w; s.h /= h;
            if (s.pts != null) for (int i = 0; i < s.pts.length; i++) s.pts[i] /= (i % 2 == 0 ? w : h);
        }
        return shapes;
    }

    /** The old Image Occlusion Enhanced add-on: an SVG mask per card; the shape to answer is class "qshape". */
    public static List<Occlusion.Shape> ioeShapes(String svg) {
        List<Occlusion.Shape> out = new ArrayList<>();
        Matcher root = Pattern.compile("<svg\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(svg);
        if (!root.find()) return out;
        String r = root.group();
        double W = parse(attr(r, "width")), H = parse(attr(r, "height"));
        String vb = attr(r, "viewBox");
        if ((!(W > 0) || !(H > 0)) && vb != null) { String[] v = vb.trim().split("[\\s,]+"); if (v.length == 4) { W = parse(v[2]); H = parse(v[3]); } }
        if (!(W > 0) || !(H > 0)) return out;
        Matcher m = Pattern.compile("<(rect|ellipse|polygon)\\b[^>]*>", Pattern.CASE_INSENSITIVE).matcher(svg);
        while (m.find()) {
            String tag = m.group(), kind = m.group(1).toLowerCase();
            String cls = orEmpty(attr(tag, "class")), fill = orEmpty(attr(tag, "fill")).toUpperCase();
            boolean target = cls.contains("qshape") || fill.equals("#FF7E7E");
            Occlusion.Shape s = new Occlusion.Shape();
            s.ord = target ? 1 : 0;
            s.kind = kind;
            s.oi = true;
            if (kind.equals("rect")) { s.x = n(tag, "x") / W; s.y = n(tag, "y") / H; s.w = n(tag, "width") / W; s.h = n(tag, "height") / H; }
            else if (kind.equals("ellipse")) { s.x = (n(tag, "cx") - n(tag, "rx")) / W; s.y = (n(tag, "cy") - n(tag, "ry")) / H; s.w = 2 * n(tag, "rx") / W; s.h = 2 * n(tag, "ry") / H; }
            else {
                s.pts = points(attr(tag, "points"), ",");
                for (int i = 0; i < s.pts.length; i++) s.pts[i] /= (i % 2 == 0 ? W : H);
            }
            out.add(s);
        }
        return out;
    }

    static String attr(String tag, String k) {
        Matcher m = Pattern.compile("\\s" + k + "\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE).matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    static double n(String tag, String k) { String v = attr(tag, k); return v == null ? 0 : parse(v); }

    static double parse(String v) {
        if (v == null) return Double.NaN;
        Matcher m = Pattern.compile("^\\s*([-+]?[0-9]*\\.?[0-9]+(?:[eE][-+]?[0-9]+)?)").matcher(v);
        return m.find() ? Double.parseDouble(m.group(1)) : Double.NaN;
    }

    // ------------------------------------------------------------------ pictures

    static final String[] TYPES = {"png", "gif", "webp", "jpeg", "bmp"};

    /** {width, height, type index into TYPES} from the file header, or null if it isn't a picture we show. */
    public static int[] imageSize(byte[] b) {
        if (b == null || b.length < 24) return null;
        int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
        if (b0 == 0x89 && b1 == 0x50) return new int[]{be32(b, 16), be32(b, 20), 0};
        if (b0 == 0x47 && b1 == 0x49) return new int[]{u16(b, 6), u16(b, 8), 1};
        if (b0 == 0x52 && b1 == 0x49 && (b[8] & 0xFF) == 0x57 && (b[9] & 0xFF) == 0x45) {
            String k = new String(b, 12, 4, StandardCharsets.US_ASCII);
            if (k.equals("VP8X") && b.length >= 30) return new int[]{1 + u24(b, 24), 1 + u24(b, 27), 2};
            if (k.equals("VP8 ") && b.length >= 30) return new int[]{u16(b, 26) & 0x3fff, u16(b, 28) & 0x3fff, 2};
            if (k.equals("VP8L") && b.length >= 25) { int v = (b[21] & 0xFF) | ((b[22] & 0xFF) << 8) | ((b[23] & 0xFF) << 16) | ((b[24] & 0xFF) << 24); return new int[]{(v & 0x3fff) + 1, ((v >>> 14) & 0x3fff) + 1, 2}; }
            return null;
        }
        if (b0 == 0xFF && b1 == 0xD8) {
            int p = 2;
            while (p + 9 < b.length) {
                if ((b[p] & 0xFF) != 0xFF) { p++; continue; }
                int m = b[p + 1] & 0xFF, len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) return new int[]{((b[p + 7] & 0xFF) << 8) | (b[p + 8] & 0xFF), ((b[p + 5] & 0xFF) << 8) | (b[p + 6] & 0xFF), 3};
                p += 2 + len;
            }
            return null;
        }
        if (b0 == 0x42 && b1 == 0x4D) return new int[]{u16(b, 18), Math.abs((b[22] & 0xFF) | ((b[23] & 0xFF) << 8) | ((b[24] & 0xFF) << 16) | ((b[25] & 0xFF) << 24)), 4};
        return null;
    }

    static int be32(byte[] b, int p) { return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF); }
    static int u16(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8); }
    static int u24(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16); }

    // ------------------------------------------------------------------ small helpers

    /** Protocol buffers: just enough to read Anki's note type and media configs. */
    static final class Pb {
        static Map<Integer, List<Object>> parse(byte[] buf) {
            Map<Integer, List<Object>> f = new HashMap<>();
            if (buf == null) return f;
            int[] p = {0};
            while (p[0] < buf.length) {
                long key = vint(buf, p);
                int no = (int) (key >>> 3), wt = (int) (key & 7);
                Object v;
                if (wt == 0) v = vint(buf, p);
                else if (wt == 1) { v = slice(buf, p[0], 8); p[0] += 8; }
                else if (wt == 2) { int n = (int) vint(buf, p); v = slice(buf, p[0], n); p[0] += n; }
                else if (wt == 5) { v = slice(buf, p[0], 4); p[0] += 4; }
                else break;
                List<Object> l = f.get(no);
                if (l == null) { l = new ArrayList<>(); f.put(no, l); }
                l.add(v);
            }
            return f;
        }
        static long vint(byte[] b, int[] p) { long v = 0; int sh = 0; int c; do { c = b[p[0]++] & 0xFF; v |= (long) (c & 0x7F) << sh; sh += 7; } while ((c & 0x80) != 0 && sh < 64); return v; }
        static byte[] slice(byte[] b, int s, int n) { byte[] r = new byte[Math.max(0, Math.min(n, b.length - s))]; System.arraycopy(b, s, r, 0, r.length); return r; }
        static String text(Map<Integer, List<Object>> f, int no) {
            List<Object> l = f.get(no);
            return l != null && !l.isEmpty() && l.get(0) instanceof byte[] ? new String((byte[]) l.get(0), StandardCharsets.UTF_8) : "";
        }
    }

    static byte[] bytes(Object o) { return o instanceof byte[] ? (byte[]) o : new byte[0]; }
    static long lng(Map<String, Object> m, String k) { Object v = m.get(k); return v instanceof Long ? (Long) v : v instanceof Double ? ((Double) v).longValue() : 0; }
    static String orEmpty(String s) { return s == null ? "" : s; }

    static String join(List<String> l, String sep) {
        StringBuilder b = new StringBuilder();
        for (String s : l) { if (b.length() > 0) b.append(sep); b.append(s); }
        return b.toString();
    }

    static void sortBy(List<Map<String, Object>> rows, final String k) {
        Collections.sort(rows, new Comparator<Map<String, Object>>() {
            @Override public int compare(Map<String, Object> a, Map<String, Object> b) { return Long.compare(lng(a, k), lng(b, k)); }
        });
    }

    static void sortByOrd(List<Object> l) {
        Collections.sort(l, new Comparator<Object>() {
            @Override public int compare(Object a, Object b) { return Double.compare(Json.num(Json.obj(a).get("ord"), 0), Json.num(Json.obj(b).get("ord"), 0)); }
        });
    }

    static void set(List<String> l, int i, String v) { while (l.size() <= i) l.add(null); l.set(i, v); }
    static void setT(List<String[]> l, int i, String[] v) { while (l.size() <= i) l.add(null); l.set(i, v); }
}
