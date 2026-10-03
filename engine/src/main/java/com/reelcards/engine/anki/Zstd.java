package com.reelcards.engine.anki;

/**
 * A zstd decoder (RFC 8878), enough for Anki's .apkg files: newer Anki versions compress the deck's
 * database and media with zstd, and Android has no zstd built in. No dictionaries. Checked against
 * the reference zstd on text, binary and random data at every compression level.
 * Mirrors Zstd in web-demo/src/apkg.js.
 */
public final class Zstd {
    private Zstd() {}

    public static boolean isZstd(byte[] b) {
        return b != null && b.length >= 4 && (b[0] & 0xFF) == 0x28 && (b[1] & 0xFF) == 0xB5 && (b[2] & 0xFF) == 0x2F && (b[3] & 0xFF) == 0xFD;
    }

    static final int[] LL_BASE = new int[36], LL_BITS = new int[36], ML_BASE = new int[53], ML_BITS = new int[53];
    static {
        for (int i = 0; i < 16; i++) { LL_BASE[i] = i; LL_BITS[i] = 0; }
        int[][] ll = {{16,1},{18,1},{20,1},{22,1},{24,2},{28,2},{32,3},{40,3},{48,4},{64,6},{128,7},{256,8},{512,9},{1024,10},{2048,11},{4096,12},{8192,13},{16384,14},{32768,15},{65536,16}};
        for (int i = 0; i < ll.length; i++) { LL_BASE[16 + i] = ll[i][0]; LL_BITS[16 + i] = ll[i][1]; }
        for (int i = 0; i < 32; i++) { ML_BASE[i] = i + 3; ML_BITS[i] = 0; }
        int[][] ml = {{35,1},{37,1},{39,1},{41,1},{43,2},{47,2},{51,3},{59,3},{67,4},{83,4},{99,5},{131,7},{259,8},{515,9},{1027,10},{2051,11},{4099,12},{8195,13},{16387,14},{32771,15},{65539,16}};
        for (int i = 0; i < ml.length; i++) { ML_BASE[32 + i] = ml[i][0]; ML_BITS[32 + i] = ml[i][1]; }
    }
    static final int[] LL_DEF = {4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1};
    static final int[] ML_DEF = {1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1};
    static final int[] OF_DEF = {1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1};

    static final class Bad extends RuntimeException { Bad(String m) { super("zstd: " + m); } }

    static int hb(int x) { return 31 - Integer.numberOfLeadingZeros(x); }

    /** Bits read from the end of a stream backwards (FSE and Huffman streams). Reading past the start gives zeros. */
    static final class Back {
        final byte[] b; final int s; long pos;
        Back(byte[] b, int start, int end) {
            if (end <= start) throw new Bad("empty stream");
            int last = b[end - 1] & 0xFF;
            if (last == 0) throw new Bad("no end marker");
            this.b = b; this.s = start; this.pos = (long) (end - start - 1) * 8 + hb(last);
        }
        long bits(long p, int n) {
            if (n == 0) return 0;
            if (p < 0) { long k = n + p; return k <= 0 ? 0 : bits(0, (int) k) << (-p); }
            int i = s + (int) (p >> 3), sh = (int) (p & 7);
            long acc = 0;
            for (int k = 0; k * 8 < n + sh; k++) acc |= (long) (b[i + k] & 0xFF) << (8 * k);
            return (acc >>> sh) & ((1L << n) - 1);
        }
        long read(int n) { pos -= n; return bits(pos, n); }
        long peek(int n) { return bits(pos - n, n); }
    }

    static final class Fse {
        final int[] sym, nb, base; final int al;
        Fse(int[] sym, int[] nb, int[] base, int al) { this.sym = sym; this.nb = nb; this.base = base; this.al = al; }
    }

    static Fse fseTable(int[] norm, int n, int al) {
        int size = 1 << al;
        int[] sym = new int[size], nb = new int[size], base = new int[size], next = new int[n];
        int high = size - 1;
        for (int s = 0; s < n; s++) { if (norm[s] == -1) { sym[high--] = s; next[s] = 1; } else next[s] = Math.max(0, norm[s]); }
        int step = (size >> 1) + (size >> 3) + 3, mask = size - 1, pos = 0;
        for (int s = 0; s < n; s++) for (int i = 0; i < norm[s]; i++) { sym[pos] = s; do pos = (pos + step) & mask; while (pos > high); }
        if (pos != 0) throw new Bad("bad table");
        for (int u = 0; u < size; u++) { int s = sym[u], ns = next[s]++, k = al - hb(ns); nb[u] = k; base[u] = (ns << k) - size; }
        return new Fse(sym, nb, base, al);
    }

    static Fse rle(int s) { return new Fse(new int[]{s}, new int[]{0}, new int[]{0}, 0); }

    static final Fse PRE_LL = fseTable(LL_DEF, LL_DEF.length, 6), PRE_ML = fseTable(ML_DEF, ML_DEF.length, 6), PRE_OF = fseTable(OF_DEF, OF_DEF.length, 5);

    /** An FSE table description (forward bits). out[0] = table, returns next byte position. */
    static int fseDesc(byte[] b, int p, int maxSym, int maxAL, Fse[] out) {
        long bit = 0;
        int al = (int) peekF(b, p, bit, 4) + 5;
        bit += 4;
        if (al > maxAL) throw new Bad("accuracy too high");
        int remaining = (1 << al) + 1, threshold = 1 << al, nbBits = al + 1, s = 0;
        boolean prev0 = false;
        int[] norm = new int[maxSym + 2];
        while (remaining > 1 && s <= maxSym) {
            if (prev0) {
                int n0 = s;
                for (;;) { int r = (int) peekF(b, p, bit, 2); bit += 2; n0 += r; if (r != 3) break; }
                if (n0 > maxSym + 1) throw new Bad("too many zeros");
                while (s < n0) norm[s++] = 0;
                if (s > maxSym) break;
            }
            int max = (2 * threshold - 1) - remaining, count;
            int low = (int) peekF(b, p, bit, nbBits - 1);
            if (low < max) { count = low; bit += nbBits - 1; }
            else { count = (int) peekF(b, p, bit, nbBits); if (count >= threshold) count -= max; bit += nbBits; }
            count--;
            remaining -= count < 0 ? -count : count;
            norm[s++] = count;
            prev0 = count == 0;
            while (remaining < threshold) { nbBits--; threshold >>= 1; }
        }
        if (remaining != 1) throw new Bad("bad counts");
        out[0] = fseTable(norm, s, al);
        return p + (int) ((bit + 7) >> 3);
    }

    static long peekF(byte[] b, int p, long bit, int n) {
        int i = p + (int) (bit >> 3), sh = (int) (bit & 7);
        long acc = 0;
        for (int k = 0; k * 8 < n + sh; k++) acc |= (long) (i + k < b.length ? b[i + k] & 0xFF : 0) << (8 * k);
        return (acc >>> sh) & ((1L << n) - 1);
    }

    static final class Huf { final int[] sym, nb; final int log; Huf(int[] sym, int[] nb, int log) { this.sym = sym; this.nb = nb; this.log = log; } }

    /** Huffman tree for literals. out[0] = table, returns next byte position. */
    static int hufTable(byte[] b, int p, Huf[] out) {
        int h = b[p++] & 0xFF;
        int[] w = new int[256];
        int n = 0;
        if (h < 128) {
            int end = p + h;
            Fse[] t1 = new Fse[1];
            int q = fseDesc(b, p, 255, 6, t1);
            Fse t = t1[0];
            Back br = new Back(b, q, end);
            int s1 = (int) br.read(t.al), s2 = (int) br.read(t.al);
            for (;;) {
                if (n >= 255) throw new Bad("too many weights");
                w[n++] = t.sym[s1]; s1 = t.base[s1] + (int) br.read(t.nb[s1]);
                if (br.pos < 0) { w[n++] = t.sym[s2]; break; }
                w[n++] = t.sym[s2]; s2 = t.base[s2] + (int) br.read(t.nb[s2]);
                if (br.pos < 0) { w[n++] = t.sym[s1]; break; }
            }
            p = end;
        } else {
            n = h - 127;
            for (int i = 0; i < n; i++) { int x = b[p + (i >> 1)] & 0xFF; w[i] = i % 2 == 0 ? x >> 4 : x & 15; }
            p += (n + 1) >> 1;
        }
        int sum = 0;
        for (int i = 0; i < n; i++) if (w[i] > 0) sum += 1 << (w[i] - 1);
        if (sum == 0) throw new Bad("empty tree");
        int log = hb(sum) + 1, rest = (1 << log) - sum;
        if ((rest & (rest - 1)) != 0) throw new Bad("bad weights");
        w[n++] = hb(rest) + 1;
        if (log > 11) throw new Bad("tree too deep");
        int size = 1 << log;
        int[] sym = new int[size], nb = new int[size], start = new int[log + 2], count = new int[log + 2];
        for (int i = 0; i < n; i++) if (w[i] > 0) count[w[i]]++;
        int next = 0;
        for (int x = 1; x <= log; x++) { start[x] = next; next += count[x] << (x - 1); }
        for (int s = 0; s < n; s++) {
            int x = w[s];
            if (x == 0) continue;
            int len = 1 << (x - 1), at = start[x];
            for (int i = 0; i < len; i++) { sym[at + i] = s; nb[at + i] = log + 1 - x; }
            start[x] += len;
        }
        out[0] = new Huf(sym, nb, log);
        return p;
    }

    static void hufStream(byte[] b, int start, int end, Huf t, byte[] out, int o, int count) {
        Back br = new Back(b, start, end);
        for (int i = 0; i < count; i++) { int v = (int) br.peek(t.log); out[o + i] = (byte) t.sym[v]; br.pos -= t.nb[v]; }
    }

    static final class Out {
        byte[] a; int n;
        Out(int cap) { a = new byte[Math.max(cap, 65536)]; }
        void room(long k) {
            if (n + k > a.length) {
                long want = Math.max((long) a.length * 2, n + k);
                if (want > Integer.MAX_VALUE - 16) throw new Bad("too large");
                byte[] z = new byte[(int) want];
                System.arraycopy(a, 0, z, 0, n);
                a = z;
            }
        }
        void put(byte[] src, int s, int e) { room(e - s); System.arraycopy(src, s, a, n, e - s); n += e - s; }
    }

    static final class State { long[] rep = {1, 4, 8}; Huf huf; Fse ll, of, ml; }

    static void block(byte[] b, int p, int end, State st, Out out, int frameStart) {
        int b0 = b[p] & 0xFF, type = b0 & 3, sf = (b0 >> 2) & 3;
        byte[] lit;
        if (type < 2) {
            int regen;
            if (sf == 0 || sf == 2) { regen = b0 >> 3; p += 1; }
            else if (sf == 1) { regen = (b0 >> 4) + ((b[p + 1] & 0xFF) << 4); p += 2; }
            else { regen = (b0 >> 4) + ((b[p + 1] & 0xFF) << 4) + ((b[p + 2] & 0xFF) << 12); p += 3; }
            lit = new byte[regen];
            if (type == 0) { System.arraycopy(b, p, lit, 0, regen); p += regen; }
            else { java.util.Arrays.fill(lit, b[p]); p += 1; }
        } else {
            int regen, comp, streams = 4;
            if (sf < 2) { int v = b0 | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16); regen = (v >> 4) & 0x3FF; comp = (v >> 14) & 0x3FF; if (sf == 0) streams = 1; p += 3; }
            else if (sf == 2) { long v = b0 | ((long) (b[p + 1] & 0xFF) << 8) | ((long) (b[p + 2] & 0xFF) << 16) | ((long) (b[p + 3] & 0xFF) << 24); regen = (int) ((v >>> 4) & 0x3FFF); comp = (int) ((v >>> 18) & 0x3FFF); p += 4; }
            else { long v = b0 | ((long) (b[p + 1] & 0xFF) << 8) | ((long) (b[p + 2] & 0xFF) << 16) | ((long) (b[p + 3] & 0xFF) << 24) | ((long) (b[p + 4] & 0xFF) << 32); regen = (int) ((v >>> 4) & 0x3FFFF); comp = (int) ((v >>> 22) & 0x3FFFF); p += 5; }
            int lend = p + comp;
            if (type == 2) { Huf[] h = new Huf[1]; p = hufTable(b, p, h); st.huf = h[0]; } else if (st.huf == null) throw new Bad("no Huffman table to repeat");
            lit = new byte[regen];
            if (streams == 1) hufStream(b, p, lend, st.huf, lit, 0, regen);
            else {
                int[] sz = {(b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8), (b[p + 2] & 0xFF) | ((b[p + 3] & 0xFF) << 8), (b[p + 4] & 0xFF) | ((b[p + 5] & 0xFF) << 8)};
                p += 6;
                int seg = (regen + 3) / 4, a = p, o = 0;
                for (int k = 0; k < 4; k++) {
                    int len = k < 3 ? sz[k] : lend - a, cnt = k < 3 ? seg : regen - 3 * seg;
                    hufStream(b, a, a + len, st.huf, lit, o, cnt);
                    a += len; o += cnt;
                }
            }
            p = lend;
        }
        int nseq = b[p++] & 0xFF;
        if (nseq == 0) { out.put(lit, 0, lit.length); return; }
        if (nseq >= 128) { if (nseq < 255) nseq = ((nseq - 128) << 8) + (b[p++] & 0xFF); else { nseq = (b[p] & 0xFF) + ((b[p + 1] & 0xFF) << 8) + 0x7F00; p += 2; } }
        int modes = b[p++] & 0xFF;
        int[] pp = {p};
        st.ll = pick(b, pp, modes >> 6, PRE_LL, 35, 9, st.ll);
        st.of = pick(b, pp, (modes >> 4) & 3, PRE_OF, 31, 8, st.of);
        st.ml = pick(b, pp, (modes >> 2) & 3, PRE_ML, 52, 9, st.ml);
        p = pp[0];
        Back br = new Back(b, p, end);
        Fse LL = st.ll, OF = st.of, ML = st.ml;
        long[] rep = st.rep;
        int ls = (int) br.read(LL.al), os = (int) br.read(OF.al), ms = (int) br.read(ML.al), lp = 0;
        for (int i = 0; i < nseq; i++) {
            int oc = OF.sym[os], mc = ML.sym[ms], lc = LL.sym[ls];
            long ofv = (1L << oc) + br.read(oc);
            long ml = ML_BASE[mc] + br.read(ML_BITS[mc]);
            long ll = LL_BASE[lc] + br.read(LL_BITS[lc]);
            if (i < nseq - 1) {
                ls = LL.base[ls] + (int) br.read(LL.nb[ls]);
                ms = ML.base[ms] + (int) br.read(ML.nb[ms]);
                os = OF.base[os] + (int) br.read(OF.nb[os]);
            }
            long off;
            if (ofv > 3) { off = ofv - 3; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = off; }
            else {
                int idx = (int) ofv - 1 + (ll == 0 ? 1 : 0);
                if (idx == 0) off = rep[0];
                else { off = idx == 3 ? rep[0] - 1 : rep[idx]; if (idx != 1) rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = off; }
            }
            if (lp + ll > lit.length) throw new Bad("literals overrun");
            out.put(lit, lp, lp + (int) ll);
            lp += (int) ll;
            if (off <= 0 || off > out.n - frameStart) throw new Bad("bad offset");
            out.room(ml);
            byte[] a = out.a;
            int src = out.n - (int) off, dst = out.n, m = (int) ml;
            if (off >= ml) System.arraycopy(a, src, a, dst, m);
            else for (int k = 0; k < m; k++) a[dst + k] = a[src + k];
            out.n += m;
        }
        out.put(lit, lp, lit.length);
    }

    static Fse pick(byte[] b, int[] pp, int mode, Fse def, int max, int maxAL, Fse prev) {
        if (mode == 0) return def;
        if (mode == 1) return rle(b[pp[0]++] & 0xFF);
        if (mode == 2) { Fse[] t = new Fse[1]; pp[0] = fseDesc(b, pp[0], max, maxAL, t); return t[0]; }
        if (prev == null) throw new Bad("no table to repeat");
        return prev;
    }

    static int le32(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8) | ((b[p + 2] & 0xFF) << 16) | ((b[p + 3] & 0xFF) << 24); }

    public static byte[] decompress(byte[] src) {
        Out out = new Out(src.length * 4);
        int p = 0;
        try {
            while (p < src.length) {
                int magic = le32(src, p);
                if ((magic & 0xFFFFFFF0) == 0x184D2A50) { p += 8 + le32(src, p + 4); continue; }
                if (magic != 0xFD2FB528) throw new Bad("not a zstd frame");
                p += 4;
                int fhd = src[p++] & 0xFF, fcsFlag = fhd >> 6, single = (fhd >> 5) & 1, checksum = (fhd >> 2) & 1, dict = fhd & 3;
                if (single == 0) p++;
                p += new int[]{0, 1, 2, 4}[dict];
                int fcsSize = fcsFlag == 0 ? (single == 1 ? 1 : 0) : new int[]{0, 2, 4, 8}[fcsFlag];
                if (fcsSize > 0) {
                    long v = 0;
                    for (int i = 0; i < fcsSize; i++) v |= (long) (src[p + i] & 0xFF) << (8 * i);
                    if (fcsSize == 2) v += 256;
                    if (v > 0 && v < Integer.MAX_VALUE - 16) out.room(v);
                }
                p += fcsSize;
                State st = new State();
                int frameStart = out.n;
                for (;;) {
                    int bh = (src[p] & 0xFF) | ((src[p + 1] & 0xFF) << 8) | ((src[p + 2] & 0xFF) << 16);
                    p += 3;
                    int last = bh & 1, type = (bh >> 1) & 3, size = bh >>> 3;
                    if (type == 0) { out.put(src, p, p + size); p += size; }
                    else if (type == 1) { out.room(size); java.util.Arrays.fill(out.a, out.n, out.n + size, src[p]); out.n += size; p += 1; }
                    else if (type == 2) { block(src, p, p + size, st, out, frameStart); p += size; }
                    else throw new Bad("reserved block");
                    if (last == 1) break;
                }
                if (checksum == 1) p += 4;
            }
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new Bad("damaged data");
        }
        byte[] r = new byte[out.n];
        System.arraycopy(out.a, 0, r, 0, out.n);
        return r;
    }
}
