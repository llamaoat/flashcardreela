package com.reelcards.engine.anki;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small JSON reader for the bits of Anki files stored as JSON (note types, decks, media lists). */
final class Json {
    private final String s;
    private int p;

    private Json(String s) { this.s = s; }

    /** Map, List, String, Double, Boolean or null. */
    static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        return v;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<String, Object>(); }

    @SuppressWarnings("unchecked")
    static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : new ArrayList<Object>(); }

    static String str(Object o) { return o == null ? null : o instanceof String ? (String) o : o instanceof Double && ((Double) o) == Math.rint((Double) o) ? String.valueOf(((Double) o).longValue()) : String.valueOf(o); }

    static double num(Object o, double dflt) {
        if (o instanceof Double) return (Double) o;
        if (o instanceof String) { try { return Double.parseDouble((String) o); } catch (NumberFormatException e) { return dflt; } }
        return dflt;
    }

    private void ws() { while (p < s.length() && Character.isWhitespace(s.charAt(p))) p++; }

    private Object value() {
        if (p >= s.length()) throw new IllegalArgumentException("bad JSON");
        char c = s.charAt(p);
        if (c == '{') {
            p++;
            Map<String, Object> m = new LinkedHashMap<>();
            ws();
            if (s.charAt(p) == '}') { p++; return m; }
            for (;;) {
                ws();
                String k = string();
                ws(); p++; // ':'
                ws();
                m.put(k, value());
                ws();
                char d = s.charAt(p++);
                if (d == '}') return m;
            }
        }
        if (c == '[') {
            p++;
            List<Object> l = new ArrayList<>();
            ws();
            if (s.charAt(p) == ']') { p++; return l; }
            for (;;) {
                ws();
                l.add(value());
                ws();
                char d = s.charAt(p++);
                if (d == ']') return l;
            }
        }
        if (c == '"') return string();
        if (s.startsWith("true", p)) { p += 4; return Boolean.TRUE; }
        if (s.startsWith("false", p)) { p += 5; return Boolean.FALSE; }
        if (s.startsWith("null", p)) { p += 4; return null; }
        int start = p;
        while (p < s.length() && "+-0123456789.eE".indexOf(s.charAt(p)) >= 0) p++;
        if (start == p) throw new IllegalArgumentException("bad JSON");
        return Double.parseDouble(s.substring(start, p));
    }

    private String string() {
        StringBuilder b = new StringBuilder();
        p++; // opening quote
        for (;;) {
            char c = s.charAt(p++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(p++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(p, p + 4), 16)); p += 4; break;
                default: b.append(e);
            }
        }
    }
}
