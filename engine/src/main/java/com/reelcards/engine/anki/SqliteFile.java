package com.reelcards.engine.anki;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A read-only reader for SQLite database files, held in memory. It reads whole tables, which is
 * all an Anki import needs, with no SQLite library (the engine is plain Java; on Android this also
 * avoids writing the deck to disk to open it). UTF-8 text only, as Anki uses.
 * Values come back as Long, Double, String, byte[] or null. Mirrors Sqlite in web-demo/src/apkg.js.
 */
public final class SqliteFile {
    private final byte[] b;
    private final int ps, usable;
    private final Map<String, Table> tables = new HashMap<>();

    static final class Table {
        int root; List<String> order = new ArrayList<>(); String rowidCol;
    }

    public static final class Damaged extends RuntimeException { Damaged(String m) { super(m); } }

    public SqliteFile(byte[] bytes) {
        b = bytes;
        if (b.length < 100 || !new String(b, 0, 15, StandardCharsets.US_ASCII).equals("SQLite format 3")) throw new Damaged("The deck's database is damaged.");
        int p = be16(16);
        ps = p == 1 ? 65536 : p;
        usable = ps - (b[20] & 0xFF);
        if (be32(56) > 1) throw new Damaged("This deck's database uses UTF-16 text, which isn't supported.");
        walk(1, new Visitor() {
            @Override public void row(long rowid, Object[] v) {
                if ("table".equals(v[0]) && v.length > 4 && v[4] != null) tables.put(String.valueOf(v[1]).toLowerCase(Locale.ROOT), parseCreate(String.valueOf(v[4]), ((Long) v[3]).intValue()));
            }
        }, 0);
    }

    public boolean has(String table) { return tables.containsKey(table.toLowerCase(Locale.ROOT)); }

    /** Every row of a table, as column name -> value; null if there's no such table. */
    public List<Map<String, Object>> rows(String name) {
        final Table t = tables.get(name.toLowerCase(Locale.ROOT));
        if (t == null) return null;
        final List<Map<String, Object>> out = new ArrayList<>();
        walk(t.root, new Visitor() {
            @Override public void row(long rowid, Object[] v) {
                Map<String, Object> o = new HashMap<>();
                for (int i = 0; i < t.order.size(); i++) o.put(t.order.get(i), i < v.length ? v[i] : null);
                if (t.rowidCol != null && o.get(t.rowidCol) == null) o.put(t.rowidCol, rowid);
                out.add(o);
            }
        }, 0);
        return out;
    }

    interface Visitor { void row(long rowid, Object[] values); }

    private int be16(int p) { return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); }
    private int be32(int p) { return ((b[p] & 0xFF) << 24) | ((b[p + 1] & 0xFF) << 16) | ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF); }

    /** SQLite varint at p: {value, length}. */
    private static long[] varint(byte[] buf, int p) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            int c = buf[p + i] & 0xFF;
            v = (v << 7) | (c & 0x7F);
            if ((c & 0x80) == 0) return new long[]{v, i + 1};
        }
        return new long[]{(v << 8) | (buf[p + 8] & 0xFF), 9};
    }

    private byte[] payload(int pos, long P, boolean tableLeaf) {
        int U = usable;
        int X = tableLeaf ? U - 35 : ((U - 12) * 64 / 255) - 23;
        if (P <= X) { byte[] r = new byte[(int) P]; System.arraycopy(b, pos, r, 0, (int) P); return r; }
        int M = ((U - 12) * 32 / 255) - 23;
        int K = (int) (M + ((P - M) % (U - 4))), local = K <= X ? K : M;
        byte[] out = new byte[(int) P];
        System.arraycopy(b, pos, out, 0, local);
        int got = local, ov = be32(pos + local), guard = 0;
        while (got < P) {
            if (ov == 0 || ++guard > 10_000_000) throw new Damaged("The deck's database is damaged (overflow).");
            int ob = (ov - 1) * ps, n = (int) Math.min(P - got, U - 4);
            System.arraycopy(b, ob + 4, out, got, n);
            got += n;
            ov = be32(ob);
        }
        return out;
    }

    private static Object[] record(byte[] buf) {
        long[] h = varint(buf, 0);
        int hs = (int) h[0], p = (int) h[1];
        List<Long> types = new ArrayList<>();
        while (p < hs) { long[] t = varint(buf, p); types.add(t[0]); p += (int) t[1]; }
        Object[] vals = new Object[types.size()];
        int q = hs;
        for (int i = 0; i < vals.length; i++) {
            long t = types.get(i);
            if (t == 0) vals[i] = null;
            else if (t >= 1 && t <= 6) {
                int len = new int[]{0, 1, 2, 3, 4, 6, 8}[(int) t];
                long v = buf[q]; // sign-extended first byte
                for (int k = 1; k < len; k++) v = (v << 8) | (buf[q + k] & 0xFF);
                vals[i] = v; q += len;
            } else if (t == 7) {
                long v = 0;
                for (int k = 0; k < 8; k++) v = (v << 8) | (buf[q + k] & 0xFF);
                vals[i] = Double.longBitsToDouble(v); q += 8;
            } else if (t == 8) vals[i] = 0L;
            else if (t == 9) vals[i] = 1L;
            else if (t >= 12 && t % 2 == 0) { int len = (int) ((t - 12) / 2); byte[] r = new byte[len]; System.arraycopy(buf, q, r, 0, len); vals[i] = r; q += len; }
            else if (t >= 13) { int len = (int) ((t - 13) / 2); vals[i] = new String(buf, q, len, StandardCharsets.UTF_8); q += len; }
            else vals[i] = null;
        }
        return vals;
    }

    private void walk(int page, Visitor cb, int depth) {
        if (depth > 64) throw new Damaged("The deck's database is damaged (loop).");
        int base = (page - 1) * ps, off = base + (page == 1 ? 100 : 0), type = b[off] & 0xFF, n = be16(off + 3);
        boolean interior = type == 2 || type == 5;
        int hdr = interior ? 12 : 8;
        for (int i = 0; i < n; i++) {
            int c = base + be16(off + hdr + 2 * i);
            if (type == 0x0D) {
                long[] P = varint(b, c), r = varint(b, c + (int) P[1]);
                cb.row(r[0], record(payload(c + (int) P[1] + (int) r[1], P[0], true)));
            } else if (type == 0x05) walk(be32(c), cb, depth + 1);
            else if (type == 0x0A) { long[] P = varint(b, c); cb.row(0, record(payload(c + (int) P[1], P[0], false))); }
            else if (type == 0x02) {
                walk(be32(c), cb, depth + 1);
                long[] P = varint(b, c + 4);
                cb.row(0, record(payload(c + 4 + (int) P[1], P[0], false)));
            } else throw new Damaged("The deck's database is damaged (page type " + type + ").");
        }
        if (interior) walk(be32(off + 8), cb, depth + 1);
    }

    static final Pattern CONSTRAINT = Pattern.compile("^(primary\\s+key|unique|check|foreign\\s+key|constraint)\\b", Pattern.CASE_INSENSITIVE);

    /** Column names from CREATE TABLE; a WITHOUT ROWID table stores its primary key columns first. */
    static Table parseCreate(String sql, int root) {
        String inner = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'));
        String tail = sql.substring(sql.lastIndexOf(')') + 1);
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (char ch : inner.toCharArray()) {
            if (quote != 0) { cur.append(ch); if (ch == quote) quote = 0; continue; }
            if (ch == '\'' || ch == '"' || ch == '`') { quote = ch; cur.append(ch); continue; }
            if (ch == '(') depth++; else if (ch == ')') depth--;
            if (ch == ',' && depth == 0) { parts.add(cur.toString().trim()); cur.setLength(0); } else cur.append(ch);
        }
        if (cur.toString().trim().length() > 0) parts.add(cur.toString().trim());
        List<String> cols = new ArrayList<>(), pk = new ArrayList<>();
        String rowidCol = null;
        for (String d : parts) {
            Matcher m = CONSTRAINT.matcher(d);
            if (m.find()) {
                if (d.toLowerCase(Locale.ROOT).matches("^primary\\s+key[\\s\\S]*")) {
                    pk.clear();
                    for (String x : d.substring(d.indexOf('(') + 1, d.lastIndexOf(')')).split(",")) pk.add(unquote(x.trim().split("\\s+")[0]));
                }
                continue;
            }
            String name = unquote(d.split("\\s+")[0]);
            cols.add(name);
            if (Pattern.compile("primary\\s+key", Pattern.CASE_INSENSITIVE).matcher(d).find()) {
                pk.clear(); pk.add(name);
                if (Pattern.compile("^\\S+\\s+integer\\b", Pattern.CASE_INSENSITIVE).matcher(d).find()) rowidCol = name;
            }
        }
        boolean withoutRowid = Pattern.compile("without\\s+rowid", Pattern.CASE_INSENSITIVE).matcher(tail).find();
        Table t = new Table();
        t.root = root;
        if (withoutRowid) { t.order.addAll(pk); for (String c : cols) if (!pk.contains(c)) t.order.add(c); }
        else t.order.addAll(cols);
        t.rowidCol = withoutRowid ? null : rowidCol;
        return t;
    }

    static String unquote(String s) { return s.replaceAll("^[\"`\\[]|[\"`\\]]$", ""); }
}
