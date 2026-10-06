/* Reading Anki .apkg / .colpkg files in the browser, with no libraries.
   Mirrors engine/src/main/java/com/reelcards/engine/anki/ (same rules, same output).

   An .apkg is a zip holding an SQLite database (collection.anki2 / .anki21, or .anki21b compressed
   with zstd in newer Anki versions) and the deck's media. This file has the four small readers that
   needs (zstd, zip, SQLite, protobuf) and the part that turns Anki notes into Reelcards cards. */
"use strict";
const Apkg = (function(){

/* ================================================================ zstd (RFC 8878), decoder only */
const Zstd = (function(){
  const hb = x => 31 - Math.clz32(x); // highest set bit
  const LL_BASE = [], LL_BITS = [], ML_BASE = [], ML_BITS = [];
  for (let i = 0; i < 16; i++) { LL_BASE.push(i); LL_BITS.push(0); }
  [[16,1],[18,1],[20,1],[22,1],[24,2],[28,2],[32,3],[40,3],[48,4],[64,6],[128,7],[256,8],[512,9],[1024,10],[2048,11],[4096,12],[8192,13],[16384,14],[32768,15],[65536,16]]
    .forEach(([b, n]) => { LL_BASE.push(b); LL_BITS.push(n); });
  for (let i = 0; i < 32; i++) { ML_BASE.push(i + 3); ML_BITS.push(0); }
  [[35,1],[37,1],[39,1],[41,1],[43,2],[47,2],[51,3],[59,3],[67,4],[83,4],[99,5],[131,7],[259,8],[515,9],[1027,10],[2051,11],[4099,12],[8195,13],[16387,14],[32771,15],[65539,16]]
    .forEach(([b, n]) => { ML_BASE.push(b); ML_BITS.push(n); });
  const LL_DEF = [4,3,2,2,2,2,2,2,2,2,2,2,2,1,1,1,2,2,2,2,2,2,2,2,2,3,2,1,1,1,1,1,-1,-1,-1,-1];
  const ML_DEF = [1,4,3,2,2,2,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1,-1,-1];
  const OF_DEF = [1,1,1,1,1,1,2,2,2,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,-1,-1,-1,-1,-1];
  const bad = m => { throw new Error("zstd: " + m); };

  /* bits counted from the end of a stream backwards (FSE and Huffman streams) */
  function Back(b, start, end){
    if (end <= start) bad("empty stream");
    const last = b[end - 1]; if (!last) bad("no end marker");
    this.b = b; this.s = start; this.pos = (end - start - 1) * 8 + hb(last);
  }
  Back.prototype.bits = function(p, n){ // n bits starting at bit p (little-endian bit order); zeros below 0
    if (n === 0) return 0;
    if (p < 0) { const k = n + p; return k <= 0 ? 0 : this.bits(0, k) * Math.pow(2, -p); }
    const b = this.b, i = this.s + (p >> 3), sh = p & 7;
    let acc = 0, mul = 1;
    for (let k = 0; k * 8 < n + sh; k++) { acc += b[i + k] * mul; mul *= 256; }
    return Math.floor(acc / (1 << sh)) % Math.pow(2, n);
  };
  Back.prototype.read = function(n){ this.pos -= n; return this.bits(this.pos, n); };
  Back.prototype.peek = function(n){ return this.bits(this.pos - n, n); };

  function fseTable(norm, al){
    const size = 1 << al, sym = new Uint16Array(size), nb = new Uint8Array(size), base = new Int32Array(size), next = new Int32Array(norm.length);
    let high = size - 1;
    for (let s = 0; s < norm.length; s++) { if (norm[s] === -1) { sym[high--] = s; next[s] = 1; } else next[s] = Math.max(0, norm[s]); }
    const step = (size >> 1) + (size >> 3) + 3, mask = size - 1;
    let pos = 0;
    for (let s = 0; s < norm.length; s++) for (let i = 0; i < norm[s]; i++) { sym[pos] = s; do pos = (pos + step) & mask; while (pos > high); }
    if (pos !== 0) bad("bad table");
    for (let u = 0; u < size; u++) { const s = sym[u], ns = next[s]++, n = al - hb(ns); nb[u] = n; base[u] = (ns << n) - size; }
    return {sym, nb, base, al};
  }
  const rleTable = s => ({sym: Uint16Array.of(s), nb: Uint8Array.of(0), base: Int32Array.of(0), al: 0});
  const PRE = {ll: fseTable(LL_DEF, 6), ml: fseTable(ML_DEF, 6), of: fseTable(OF_DEF, 5)};

  /* an FSE table description (forward bits); returns [normalised counts, accuracy log, next byte] */
  function fseDesc(b, p, maxSym, maxAL){
    let bit = 0;
    const peek = n => { const i = p + (bit >> 3), sh = bit & 7; let acc = 0, mul = 1; for (let k = 0; k * 8 < n + sh; k++) { acc += (b[i + k] || 0) * mul; mul *= 256; } return Math.floor(acc / (1 << sh)) % (1 << n); };
    const al = peek(4) + 5; bit += 4;
    if (al > maxAL) bad("accuracy too high");
    let remaining = (1 << al) + 1, threshold = 1 << al, nbBits = al + 1, s = 0, prev0 = false;
    const norm = [];
    while (remaining > 1 && s <= maxSym) {
      if (prev0) {
        let n0 = s;
        for (;;) { const r = peek(2); bit += 2; n0 += r; if (r !== 3) break; }
        if (n0 > maxSym + 1) bad("too many zeros");
        while (s < n0) norm[s++] = 0;
        if (s > maxSym) break;
      }
      const max = (2 * threshold - 1) - remaining;
      let count;
      const low = peek(nbBits - 1);
      if (low < max) { count = low; bit += nbBits - 1; }
      else { count = peek(nbBits); if (count >= threshold) count -= max; bit += nbBits; }
      count--;
      remaining -= count < 0 ? -count : count;
      norm[s++] = count;
      prev0 = count === 0;
      while (remaining < threshold) { nbBits--; threshold >>= 1; }
    }
    if (remaining !== 1) bad("bad counts");
    return [norm, al, p + ((bit + 7) >> 3)];
  }

  /* Huffman tree for literals; returns [table, next byte] */
  function hufTable(b, p){
    const h = b[p++];
    const w = [];
    if (h < 128) {
      const end = p + h;
      const [norm, al, q] = fseDesc(b, p, 255, 6);
      const t = fseTable(norm, al), br = new Back(b, q, end);
      let s1 = br.read(al), s2 = br.read(al);
      for (;;) {
        w.push(t.sym[s1]); s1 = t.base[s1] + br.read(t.nb[s1]);
        if (br.pos < 0) { w.push(t.sym[s2]); break; }
        w.push(t.sym[s2]); s2 = t.base[s2] + br.read(t.nb[s2]);
        if (br.pos < 0) { w.push(t.sym[s1]); break; }
        if (w.length > 255) bad("too many weights");
      }
      p = end;
    } else {
      const n = h - 127;
      for (let i = 0; i < n; i++) { const x = b[p + (i >> 1)]; w.push(i % 2 === 0 ? x >> 4 : x & 15); }
      p += (n + 1) >> 1;
    }
    let sum = 0;
    for (const x of w) if (x > 0) sum += 1 << (x - 1);
    if (!sum) bad("empty tree");
    const log = hb(sum) + 1, rest = (1 << log) - sum;
    if (rest & (rest - 1)) bad("bad weights");
    w.push(hb(rest) + 1);
    if (log > 11) bad("tree too deep");
    const size = 1 << log, sym = new Uint8Array(size), nb = new Uint8Array(size), start = new Int32Array(log + 2);
    const count = new Int32Array(log + 2);
    for (const x of w) if (x > 0) count[x]++;
    let next = 0;
    for (let x = 1; x <= log; x++) { start[x] = next; next += count[x] << (x - 1); }
    for (let s = 0; s < w.length; s++) {
      const x = w[s]; if (!x) continue;
      const len = 1 << (x - 1), at = start[x];
      for (let i = 0; i < len; i++) { sym[at + i] = s; nb[at + i] = log + 1 - x; }
      start[x] += len;
    }
    return [{sym, nb, log}, p];
  }

  function hufStream(b, start, end, t, out, o, count){
    const br = new Back(b, start, end);
    for (let i = 0; i < count; i++) { const v = br.peek(t.log); out[o + i] = t.sym[v]; br.pos -= t.nb[v]; }
  }

  function Out(n){ this.a = new Uint8Array(Math.max(n, 65536)); this.n = 0; }
  Out.prototype.room = function(k){ if (this.n + k > this.a.length) { const z = new Uint8Array(Math.max(this.a.length * 2, this.n + k)); z.set(this.a.subarray(0, this.n)); this.a = z; } };
  Out.prototype.put = function(src, s, e){ this.room(e - s); this.a.set(src.subarray(s, e), this.n); this.n += e - s; };

  function block(b, p, end, st, out, frameStart){
    // literals
    const b0 = b[p], type = b0 & 3, sf = (b0 >> 2) & 3;
    let lit;
    if (type < 2) {
      let regen;
      if (sf === 0 || sf === 2) { regen = b0 >> 3; p += 1; }
      else if (sf === 1) { regen = (b0 >> 4) + (b[p + 1] << 4); p += 2; }
      else { regen = (b0 >> 4) + (b[p + 1] << 4) + (b[p + 2] << 12); p += 3; }
      if (type === 0) { lit = b.subarray(p, p + regen); p += regen; }
      else { lit = new Uint8Array(regen).fill(b[p]); p += 1; }
    } else {
      let regen, comp, streams = 4;
      if (sf < 2) { const v = b0 | (b[p + 1] << 8) | (b[p + 2] << 16); regen = (v >> 4) & 0x3FF; comp = (v >> 14) & 0x3FF; if (sf === 0) streams = 1; p += 3; }
      else if (sf === 2) { const v = (b0 | (b[p + 1] << 8) | (b[p + 2] << 16) | (b[p + 3] << 24)) >>> 0; regen = (v >>> 4) & 0x3FFF; comp = (v >>> 18) & 0x3FFF; p += 4; }
      else { const v = b0 + b[p + 1] * 256 + b[p + 2] * 65536 + b[p + 3] * 16777216 + b[p + 4] * 4294967296; regen = Math.floor(v / 16) % 262144; comp = Math.floor(v / 4194304) % 262144; p += 5; }
      const lend = p + comp;
      if (type === 2) { const r = hufTable(b, p); st.huf = r[0]; p = r[1]; } else if (!st.huf) bad("no Huffman table to repeat");
      lit = new Uint8Array(regen);
      if (streams === 1) hufStream(b, p, lend, st.huf, lit, 0, regen);
      else {
        const sz = [b[p] | (b[p + 1] << 8), b[p + 2] | (b[p + 3] << 8), b[p + 4] | (b[p + 5] << 8)];
        p += 6;
        const seg = Math.floor((regen + 3) / 4);
        let a = p, o = 0;
        for (let k = 0; k < 4; k++) {
          const len = k < 3 ? sz[k] : lend - a, cnt = k < 3 ? seg : regen - 3 * seg;
          hufStream(b, a, a + len, st.huf, lit, o, cnt);
          a += len; o += cnt;
        }
      }
      p = lend;
    }
    // sequences
    let nseq = b[p++];
    if (nseq === 0) { out.put(lit, 0, lit.length); return; }
    if (nseq >= 128) { if (nseq < 255) nseq = ((nseq - 128) << 8) + b[p++]; else { nseq = b[p] + (b[p + 1] << 8) + 0x7F00; p += 2; } }
    const modes = b[p++];
    const pick = (mode, def, max, maxAL, prev) => {
      if (mode === 0) return def;
      if (mode === 1) return rleTable(b[p++]);
      if (mode === 2) { const r = fseDesc(b, p, max, maxAL); p = r[2]; return fseTable(r[0], r[1]); }
      if (!prev) bad("no table to repeat");
      return prev;
    };
    st.ll = pick(modes >> 6, PRE.ll, 35, 9, st.ll);
    st.of = pick((modes >> 4) & 3, PRE.of, 31, 8, st.of);
    st.ml = pick((modes >> 2) & 3, PRE.ml, 52, 9, st.ml);
    const br = new Back(b, p, end), LL = st.ll, OF = st.of, ML = st.ml, rep = st.rep;
    let ls = br.read(LL.al), os = br.read(OF.al), ms = br.read(ML.al), lp = 0;
    for (let i = 0; i < nseq; i++) {
      const oc = OF.sym[os], mc = ML.sym[ms], lc = LL.sym[ls];
      const ofv = Math.pow(2, oc) + br.read(oc);
      const ml = ML_BASE[mc] + br.read(ML_BITS[mc]);
      const ll = LL_BASE[lc] + br.read(LL_BITS[lc]);
      if (i < nseq - 1) { ls = LL.base[ls] + br.read(LL.nb[ls]); ms = ML.base[ms] + br.read(ML.nb[ms]); os = OF.base[os] + br.read(OF.nb[os]); }
      let off;
      if (ofv > 3) { off = ofv - 3; rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = off; }
      else {
        const idx = ofv - 1 + (ll === 0 ? 1 : 0);
        if (idx === 0) off = rep[0];
        else { off = idx === 3 ? rep[0] - 1 : rep[idx]; if (idx !== 1) rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = off; }
      }
      if (lp + ll > lit.length) bad("literals overrun");
      out.put(lit, lp, lp + ll); lp += ll;
      if (off <= 0 || off > out.n - frameStart) bad("bad offset");
      out.room(ml);
      const a = out.a;
      let src = out.n - off, dst = out.n;
      if (off >= ml) a.copyWithin(dst, src, src + ml);
      else for (let k = 0; k < ml; k++) a[dst + k] = a[src + k];
      out.n += ml;
    }
    out.put(lit, lp, lit.length);
  }

  function decompress(src){
    const out = new Out(src.length * 4);
    let p = 0;
    while (p < src.length) {
      const magic = (src[p] | (src[p + 1] << 8) | (src[p + 2] << 16) | (src[p + 3] << 24)) >>> 0;
      if ((magic & 0xFFFFFFF0) >>> 0 === 0x184D2A50) { const n = (src[p + 4] | (src[p + 5] << 8) | (src[p + 6] << 16) | (src[p + 7] << 24)) >>> 0; p += 8 + n; continue; }
      if (magic !== 0xFD2FB528) bad("not a zstd frame");
      p += 4;
      const fhd = src[p++], fcsFlag = fhd >> 6, single = (fhd >> 5) & 1, checksum = (fhd >> 2) & 1, dict = fhd & 3;
      if (!single) p++;
      p += [0, 1, 2, 4][dict];
      const fcsSize = fcsFlag === 0 ? (single ? 1 : 0) : [0, 2, 4, 8][fcsFlag];
      if (fcsSize) { let v = 0, mul = 1; for (let i = 0; i < fcsSize; i++) { v += src[p + i] * mul; mul *= 256; } if (fcsSize === 2) v += 256; out.room(v); }
      p += fcsSize;
      const st = {rep: [1, 4, 8], huf: null, ll: null, of: null, ml: null}, frameStart = out.n;
      for (;;) {
        const bh = src[p] | (src[p + 1] << 8) | (src[p + 2] << 16);
        p += 3;
        const last = bh & 1, type = (bh >> 1) & 3, size = bh >>> 3;
        if (type === 0) { out.put(src, p, p + size); p += size; }
        else if (type === 1) { out.room(size); out.a.fill(src[p], out.n, out.n + size); out.n += size; p += 1; }
        else if (type === 2) { block(src, p, p + size, st, out, frameStart); p += size; }
        else bad("reserved block");
        if (last) break;
      }
      if (checksum) p += 4;
    }
    return out.a.slice(0, out.n);
  }
  const isZstd = b => b.length >= 4 && b[0] === 0x28 && b[1] === 0xB5 && b[2] === 0x2F && b[3] === 0xFD;
  return {decompress, isZstd};
})();

/* ================================================================ zip (stored and deflate entries) */
const Zip = (function(){
  const u16 = (b, p) => b[p] | (b[p + 1] << 8), u32 = (b, p) => (b[p] | (b[p + 1] << 8) | (b[p + 2] << 16) | (b[p + 3] << 24)) >>> 0;
  function open(buf){
    const b = buf instanceof Uint8Array ? buf : new Uint8Array(buf);
    let e = -1;
    for (let i = b.length - 22; i >= Math.max(0, b.length - 22 - 65535); i--) if (u32(b, i) === 0x06054b50) { e = i; break; }
    if (e < 0) throw new Error("This isn't an Anki file (it isn't a zip).");
    const n = u16(b, e + 10), entries = {};
    let p = u32(b, e + 16);
    for (let i = 0; i < n; i++) {
      if (u32(b, p) !== 0x02014b50) throw new Error("Damaged zip directory.");
      const method = u16(b, p + 10), csize = u32(b, p + 20), nlen = u16(b, p + 28), xlen = u16(b, p + 30), clen = u16(b, p + 32), local = u32(b, p + 42);
      const name = new TextDecoder().decode(b.subarray(p + 46, p + 46 + nlen));
      entries[name] = {method, csize, local};
      p += 46 + nlen + xlen + clen;
    }
    async function read(name){
      const en = entries[name]; if (!en) return null;
      const lp = en.local, start = lp + 30 + u16(b, lp + 26) + u16(b, lp + 28), raw = b.subarray(start, start + en.csize);
      if (en.method === 0) return raw;
      if (en.method !== 8) throw new Error("Unsupported zip compression in " + name + ".");
      const stream = new Blob([raw]).stream().pipeThrough(new DecompressionStream("deflate-raw"));
      return new Uint8Array(await new Response(stream).arrayBuffer());
    }
    return {names: Object.keys(entries), has: n => !!entries[n], read};
  }
  return {open};
})();

/* ================================================================ SQLite file reader (read-only, UTF-8) */
const Sqlite = (function(){
  function open(b){
    const head = new TextDecoder().decode(b.subarray(0, 15));
    if (head !== "SQLite format 3") throw new Error("The deck's database is damaged.");
    let ps = (b[16] << 8) | b[17]; if (ps === 1) ps = 65536;
    const U = ps - b[20];
    const be16 = p => (b[p] << 8) | b[p + 1], be32 = p => ((b[p] << 24) | (b[p + 1] << 16) | (b[p + 2] << 8) | b[p + 3]) >>> 0;
    const enc = be32(56);
    if (enc > 1) throw new Error("This deck's database uses UTF-16 text, which isn't supported.");
    const td = new TextDecoder("utf-8");
    const varint = (buf, p) => { let v = 0; for (let i = 0; i < 8; i++) { const c = buf[p + i]; v = v * 128 + (c & 0x7f); if (!(c & 0x80)) return [v, i + 1]; } return [v * 256 + buf[p + 8], 9]; };
    function payload(pos, P, tableLeaf){
      const X = tableLeaf ? U - 35 : Math.floor((U - 12) * 64 / 255) - 23;
      if (P <= X) return b.subarray(pos, pos + P);
      const M = Math.floor((U - 12) * 32 / 255) - 23, K = M + ((P - M) % (U - 4)), local = K <= X ? K : M;
      const out = new Uint8Array(P);
      out.set(b.subarray(pos, pos + local));
      let got = local, ov = be32(pos + local), guard = 0;
      while (got < P) {
        if (!ov || ++guard > 1e7) throw new Error("The deck's database is damaged (overflow).");
        const ob = (ov - 1) * ps, n = Math.min(P - got, U - 4);
        out.set(b.subarray(ob + 4, ob + 4 + n), got); got += n; ov = be32(ob);
      }
      return out;
    }
    function record(buf){
      const [hs, n0] = varint(buf, 0); let p = n0; const types = [];
      while (p < hs) { const [t, k] = varint(buf, p); types.push(t); p += k; }
      let q = hs; const vals = [];
      for (const t of types) {
        if (t === 0) vals.push(null);
        else if (t >= 1 && t <= 6) {
          const len = [0, 1, 2, 3, 4, 6, 8][t];
          let v = 0; for (let i = 0; i < len; i++) v = v * 256 + buf[q + i];
          if (buf[q] & 0x80) v -= Math.pow(2, 8 * len);
          vals.push(v); q += len;
        } else if (t === 7) { vals.push(new DataView(buf.buffer, buf.byteOffset + q, 8).getFloat64(0)); q += 8; }
        else if (t === 8) vals.push(0);
        else if (t === 9) vals.push(1);
        else if (t >= 12 && t % 2 === 0) { const len = (t - 12) / 2; vals.push(buf.slice(q, q + len)); q += len; }
        else if (t >= 13) { const len = (t - 13) / 2; vals.push(td.decode(buf.subarray(q, q + len))); q += len; }
        else vals.push(null);
      }
      return vals;
    }
    function walk(page, cb, depth){
      if (depth > 64) throw new Error("The deck's database is damaged (loop).");
      const base = (page - 1) * ps, off = base + (page === 1 ? 100 : 0), type = b[off], n = be16(off + 3);
      const interior = type === 2 || type === 5, hdr = interior ? 12 : 8;
      for (let i = 0; i < n; i++) {
        const c = base + be16(off + hdr + 2 * i);
        if (type === 0x0D) { const [P, a] = varint(b, c); const [rowid, r] = varint(b, c + a); cb(rowid, record(payload(c + a + r, P, true))); }
        else if (type === 0x05) walk(be32(c), cb, depth + 1);
        else if (type === 0x0A) { const [P, a] = varint(b, c); cb(null, record(payload(c + a, P, false))); }
        else if (type === 0x02) { walk(be32(c), cb, depth + 1); const [P, a] = varint(b, c + 4); cb(null, record(payload(c + 4 + a, P, false))); }
        else throw new Error("The deck's database is damaged (page type " + type + ").");
      }
      if (interior) walk(be32(off + 8), cb, depth + 1);
    }
    // the schema: every table's root page and columns, from its CREATE TABLE statement
    const tables = {};
    walk(1, (rowid, v) => { if (v[0] === "table" && v[4]) tables[String(v[1]).toLowerCase()] = parseCreate(String(v[4]), v[3]); }, 0);
    function rows(name){
      const t = tables[name.toLowerCase()]; if (!t) return null;
      const out = [];
      walk(t.root, (rowid, v) => {
        const o = {};
        t.order.forEach((col, i) => { o[col] = i < v.length ? v[i] : null; });
        if (t.rowidCol && o[t.rowidCol] == null) o[t.rowidCol] = rowid;
        out.push(o);
      }, 0);
      return out;
    }
    return {rows, tables: () => Object.keys(tables)};
  }
  /* column names from CREATE TABLE; a WITHOUT ROWID table stores its primary key columns first */
  function parseCreate(sql, root){
    const inner = sql.slice(sql.indexOf("(") + 1, sql.lastIndexOf(")"));
    const tail = sql.slice(sql.lastIndexOf(")") + 1);
    const parts = []; let depth = 0, cur = "", quote = null;
    for (const ch of inner) {
      if (quote) { cur += ch; if (ch === quote) quote = null; continue; }
      if (ch === "'" || ch === '"' || ch === "`") { quote = ch; cur += ch; continue; }
      if (ch === "(") depth++; else if (ch === ")") depth--;
      if (ch === "," && depth === 0) { parts.push(cur.trim()); cur = ""; } else cur += ch;
    }
    if (cur.trim()) parts.push(cur.trim());
    const cols = []; let pk = [], rowidCol = null;
    const unq = s => s.replace(/^["`\[]|["`\]]$/g, "");
    for (const d of parts) {
      const m = d.match(/^(primary\s+key|unique|check|foreign\s+key|constraint)\b/i);
      if (m) { if (/^primary\s+key/i.test(d)) pk = d.slice(d.indexOf("(") + 1, d.lastIndexOf(")")).split(",").map(x => unq(x.trim().split(/\s+/)[0])); continue; }
      const name = unq(d.split(/\s+/)[0]);
      cols.push(name);
      if (/primary\s+key/i.test(d)) { pk = [name]; if (/^\S+\s+integer\b/i.test(d)) rowidCol = name; }
    }
    const withoutRowid = /without\s+rowid/i.test(tail);
    const order = withoutRowid ? pk.concat(cols.filter(c => pk.indexOf(c) < 0)) : cols;
    return {root, cols, order, rowidCol: withoutRowid ? null : rowidCol};
  }
  return {open};
})();

/* ================================================================ protobuf (just enough to read Anki's configs) */
function pb(buf){
  const f = {}; let p = 0;
  const vint = () => { let v = 0, mul = 1, c; do { c = buf[p++]; v += (c & 0x7f) * mul; mul *= 128; } while (c & 0x80); return v; };
  while (p < buf.length) {
    const key = vint(), no = Math.floor(key / 8), wt = key % 8;
    let v;
    if (wt === 0) v = vint();
    else if (wt === 1) { v = buf.subarray(p, p + 8); p += 8; }
    else if (wt === 2) { const n = vint(); v = buf.subarray(p, p + n); p += n; }
    else if (wt === 5) { v = buf.subarray(p, p + 4); p += 4; }
    else break;
    (f[no] = f[no] || []).push(v);
  }
  return f;
}
const pbText = v => v instanceof Uint8Array ? new TextDecoder().decode(v) : "";

/* ================================================================ image size from the file header */
function imageSize(b){
  if (!b || b.length < 24) return null;
  if (b[0] === 0x89 && b[1] === 0x50) return {w: ((b[16] << 24) | (b[17] << 16) | (b[18] << 8) | b[19]) >>> 0, h: ((b[20] << 24) | (b[21] << 16) | (b[22] << 8) | b[23]) >>> 0, type: "png"};
  if (b[0] === 0x47 && b[1] === 0x49) return {w: b[6] | (b[7] << 8), h: b[8] | (b[9] << 8), type: "gif"};
  if (b[0] === 0x52 && b[1] === 0x49 && b[8] === 0x57 && b[9] === 0x45) { // WebP
    const k = String.fromCharCode(b[12], b[13], b[14], b[15]);
    if (k === "VP8X") return {w: 1 + (b[24] | (b[25] << 8) | (b[26] << 16)), h: 1 + (b[27] | (b[28] << 8) | (b[29] << 16)), type: "webp"};
    if (k === "VP8 ") return {w: (b[26] | (b[27] << 8)) & 0x3fff, h: (b[28] | (b[29] << 8)) & 0x3fff, type: "webp"};
    if (k === "VP8L") { const v = b[21] | (b[22] << 8) | (b[23] << 16) | (b[24] << 24); return {w: (v & 0x3fff) + 1, h: ((v >>> 14) & 0x3fff) + 1, type: "webp"}; }
    return null;
  }
  if (b[0] === 0xFF && b[1] === 0xD8) { // JPEG: find a start-of-frame marker
    let p = 2;
    while (p + 9 < b.length) {
      if (b[p] !== 0xFF) { p++; continue; }
      const m = b[p + 1], len = (b[p + 2] << 8) | b[p + 3];
      if (m >= 0xC0 && m <= 0xCF && m !== 0xC4 && m !== 0xC8 && m !== 0xCC) return {w: (b[p + 7] << 8) | b[p + 8], h: (b[p + 5] << 8) | b[p + 6], type: "jpeg"};
      p += 2 + len;
    }
    return null;
  }
  if (b[0] === 0x42 && b[1] === 0x4D) return {w: b[18] | (b[19] << 8), h: Math.abs((b[22] | (b[23] << 8) | (b[24] << 16) | (b[25] << 24))), type: "bmp"};
  return null;
}

/* ================================================================ Anki notes -> Reelcards cards */
const DAY = 86400000;
const GAP = "…";
const IMG_RE = /<img[^>]*?src\s*=\s*["']?([^"'>]+)["']?[^>]*>/gi;
const SOUND_RE = /\[sound:([^\]]+)\]/g, LISTEN = "Listen: what's this?", MAX_SOUND = 4 * 1024 * 1024;
function sounds(html){ const out = []; let m; SOUND_RE.lastIndex = 0; while ((m = SOUND_RE.exec(html || ""))) out.push(m[1].trim()); return out; }
/* the sound file types cards play, from the file's first bytes (mirrors AnkiPackage.audioType) */
function audioType(b){
  if (!b || b.length < 12) return null;
  const s = (i, t) => [...t].every((ch, k) => b[i + k] === ch.charCodeAt(0));
  if (s(0, "ID3") || (b[0] === 0xFF && (b[1] & 0xE0) === 0xE0)) return "mp3";
  if (s(0, "OggS")) return "ogg";
  if (s(4, "ftyp")) return "m4a";
  if (s(0, "RIFF") && s(8, "WAVE")) return "wav";
  if (s(0, "fLaC")) return "flac";
  return null;
}
function entities(t){ // the few Anki's editor writes, and numeric ones (&#x27; from decks made by scripts)
  return t.replace(/&#(?:[xX]([0-9a-fA-F]{1,6})|([0-9]{1,7}));/g, (m, h, d) => { const cp = h ? parseInt(h, 16) : +d; return cp > 0 && cp <= 0x10FFFF && !(cp >= 0xD800 && cp <= 0xDFFF) ? String.fromCodePoint(cp) : m; })
    .replace(/&nbsp;/g, " ").replace(/&lt;/g, "<").replace(/&gt;/g, ">").replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&amp;/g, "&"); }
function images(html){ const out = []; let m; IMG_RE.lastIndex = 0; while ((m = IMG_RE.exec(html || ""))) { let n = entities(m[1]).trim(); try { n = decodeURIComponent(n); } catch (e) {} out.push(n); } return out; }
/* A table becomes one line per row, values labelled with their column's header (the same as tableLines in
   engine.js and AnswerShape.tableLines in Java; the cross-check compares them) */
const CELLSPACE=/[ \t\r\n  -   　]+/g;
function tableLines(t){return t.replace(/<table[^>]*>([\s\S]*?)<\/table>/gi,(m,body)=>{
  const rows=body.split(/<tr[^>]*>/i).slice(1).map(row=>{const cells=[],re=/<t([dh])(?:\s[^>]*)?>([\s\S]*?)<\/t[dh]>/gi;let c;
    while((c=re.exec(row)))cells.push({h:c[1].toLowerCase()==="h",v:c[2].replace(/<\s*br\s*\/?>/gi," ").replace(/<[^>]+>/g,"").replace(/&nbsp;/g," ").replace(CELLSPACE," ").trim()});return cells;}).filter(r=>r.some(c=>c.v));
  if(!rows.length)return"";
  const head=rows.length>1&&rows[0].length>1&&(rows[0].every(c=>c.h)||!rows[0][0].v)?rows.shift().map(c=>c.v):null;
  return"\n"+rows.map(r=>{const vals=r.slice(1).map((c,j)=>!c.v?"":head&&head.length>2&&head[j+1]?head[j+1]+" "+c.v:c.v).filter(v=>v);
    return r[0].v&&vals.length?r[0].v+": "+vals.join(", "):r[0].v||vals.join(", ");}).join("\n")+"\n";});}
/* card text: HTML to plain lines, without images, sounds or scripts */
function clean(html){
  let t = tableLines(String(html || "").replace(/<(script|style)[\s\S]*?<\/\1>/gi, " ").replace(/\[sound:[^\]]*\]/g, " ").replace(/<img[^>]*>/gi, " "))
    .replace(/<\s*br\s*\/?>/gi, "\n").replace(/<hr[^>]*>/gi, "\n").replace(/<\/(?:li|p|div|h\d|tr)>/gi, "\n").replace(/<li[^>]*>/gi, "\n").replace(/<[^>]+>/g, "");
  t = entities(t).replace(/[\u00a0\u2000-\u200a\u202f\u205f\u3000]/g, " ").replace(/[ \t]+/g, " ");
  return t.split("\n").map(x => x.trim()).filter(x => x).join("\n");
}
const hasText = html => clean(html).length > 0;

/* cloze text -> nodes: strings and {nums:[1], kids:[...], hint} */
function clozeParse(t){
  const root = {kids: []}, stack = [root];
  let i = 0, buf = "";
  const flush = () => { if (buf) { stack[stack.length - 1].kids.push(buf); buf = ""; } };
  while (i < t.length) {
    const m = /^\{\{c(\d+(?:,\d+)*)::/.exec(t.slice(i, i + 24));
    if (m) { flush(); const node = {nums: m[1].split(",").map(Number), kids: [], hint: null}; stack[stack.length - 1].kids.push(node); stack.push(node); i += m[0].length; continue; }
    if (stack.length > 1 && t.startsWith("::", i)) { // a hint: the rest of this deletion
      const node = stack[stack.length - 1], end = t.indexOf("}}", i + 2);
      if (end >= 0 && t.slice(i + 2, end).indexOf("{{c") < 0) { flush(); node.hint = t.slice(i + 2, end); i = end; continue; }
    }
    if (stack.length > 1 && t.startsWith("}}", i)) { flush(); stack.pop(); i += 2; continue; }
    buf += t[i++];
  }
  flush();
  return root.kids;
}
/* mode "q": card n's deletions become [hint] or […]; "a" and "all": everything shown; collect = card n's answers */
function clozeText(nodes, n, mode, collect){
  let out = "";
  for (const x of nodes) {
    if (typeof x === "string") { out += x; continue; }
    const active = n != null && x.nums.indexOf(n) >= 0;
    const inner = clozeText(x.kids, n, mode, active ? null : collect);
    if (active && collect) collect.push(inner);
    out += active && mode === "q" ? "[" + (x.hint ? x.hint : GAP) + "]" : inner;
  }
  return out;
}
const clozeNums = nodes => { const s = new Set(); const walk = ns => ns.forEach(x => { if (typeof x !== "string") { x.nums.forEach(k => s.add(k)); walk(x.kids); } }); walk(nodes); return s; };

/* Anki template rendering: fields, filters, conditional sections */
function fieldRefs(tpl){
  const out = []; let m; const re = /\{\{([^}]+)\}\}/g;
  while ((m = re.exec(tpl))) {
    const inner = m[1].trim();
    if (/^[#^\/!]/.test(inner)) continue;
    const parts = inner.split(":"), field = parts.pop().trim(), filters = parts.map(x => x.trim());
    out.push({field, type: filters.indexOf("type") >= 0, cloze: filters.indexOf("cloze") >= 0});
  }
  return out;
}
function renderTpl(tpl, F, ctx){
  const value = name => {
    if (name === "FrontSide") return ctx.front || "";
    if (name === "Tags") return ctx.tags || "";
    if (name === "Type") return ctx.type || "";
    if (name === "Deck") return ctx.deck || "";
    if (name === "Subdeck") return String(ctx.deck || "").split("::").pop();
    if (name === "Card") return ctx.card || "";
    if (name === "CardFlag") return "";
    return F[name];
  };
  let t = String(tpl || ""), guard = 0, m;
  const sec = /\{\{([#^])\s*([^}]+?)\s*\}\}([\s\S]*?)\{\{\/\s*\2\s*\}\}/;
  while ((m = sec.exec(t)) && guard++ < 200) {
    const name = m[2].split(":").pop().trim(), full = hasText(value(name) || "") || /<img/i.test(value(name) || "");
    t = t.slice(0, m.index) + ((m[1] === "#") === full ? m[3] : "") + t.slice(m.index + m[0].length);
  }
  return t.replace(/\{\{([^}]+)\}\}/g, (all, inner) => {
    inner = inner.trim();
    if (/^[#^\/!]/.test(inner)) return "";
    const parts = inner.split(":"), name = parts.pop().trim();
    let v = value(name);
    if (v == null) return "";
    for (let i = parts.length - 1; i >= 0; i--) {
      const f = parts[i].trim();
      if (f === "cloze") v = ctx.cloze != null ? clozeText(clozeParse(v), ctx.cloze, ctx.side, null) : v;
      else if (f === "cloze-only") { const got = []; clozeText(clozeParse(v), ctx.cloze, "a", got); v = got.join(", "); }
      else if (f === "text") v = clean(v);
      else if (f === "type") v = ctx.side === "q" ? "" : v;
      else if (f === "tts" || f === "tts-voices") v = "";
      else if (f === "furigana" || f === "kanji") v = v.replace(/\[[^\]]*\]/g, "");
      else if (f === "kana") v = v.replace(/[^\s\[\]]+\[([^\]]*)\]/g, "$1");
    }
    return v;
  });
}

/* image occlusion, Anki's built-in kind: {{c1::image-occlusion:rect:left=.1:top=.2:width=.3:height=.1:oi=1}} */
function ioShapes(text, size){
  const out = []; let m; const re = /\{\{c(\d+)::image-occlusion:([a-z]+):([^}]*)\}\}/gi;
  while ((m = re.exec(text || ""))) {
    const p = {}; m[3].split(":").forEach(kv => { const i = kv.indexOf("="); if (i > 0) p[kv.slice(0, i).trim()] = kv.slice(i + 1).trim(); });
    const sh = {ord: +m[1], kind: m[2].toLowerCase(), oi: p.oi === "1"};
    const num = k => parseFloat(p[k]);
    if (sh.kind === "rect") Object.assign(sh, {x: num("left"), y: num("top"), w: num("width"), h: num("height")});
    else if (sh.kind === "ellipse") { const rx = num("rx"), ry = num("ry"); Object.assign(sh, {x: num("left"), y: num("top"), w: 2 * rx, h: 2 * ry}); }
    else if (sh.kind === "polygon") sh.pts = String(p.points || "").trim().split(/\s+/).map(xy => xy.split(",").map(Number)).filter(a => a.length === 2 && !isNaN(a[0]) && !isNaN(a[1]));
    else if (sh.kind === "text") Object.assign(sh, {x: num("left"), y: num("top"), text: p.text || ""});
    else continue;
    out.push(sh);
  }
  return normalise(out, size);
}
/* older Anki versions stored pixels; newer ones fractions of the image. Store fractions. */
function normalise(shapes, size){
  const big = shapes.some(s => [s.x, s.y, s.w, s.h].some(v => v > 1.5) || (s.pts || []).some(a => a[0] > 1.5 || a[1] > 1.5));
  if (!big) return shapes;
  if (!size || !size.w || !size.h) return [];
  return shapes.map(s => Object.assign({}, s, {x: s.x / size.w, y: s.y / size.h, w: s.w != null ? s.w / size.w : s.w, h: s.h != null ? s.h / size.h : s.h,
    pts: s.pts ? s.pts.map(a => [a[0] / size.w, a[1] / size.h]) : s.pts}));
}
/* the old "Image Occlusion Enhanced" add-on: an SVG mask per card; the shape to answer is class "qshape" */
function ioeShapes(svg){
  const root = /<svg\b[^>]*>/i.exec(svg); if (!root) return [];
  const attr = (tag, k) => { const m = new RegExp("\\s" + k + "\\s*=\\s*[\"']([^\"']*)[\"']", "i").exec(tag); return m ? m[1] : null; };
  let W = parseFloat(attr(root[0], "width")), H = parseFloat(attr(root[0], "height"));
  const vb = attr(root[0], "viewBox");
  if ((!W || !H) && vb) { const v = vb.split(/[\s,]+/).map(Number); W = v[2]; H = v[3]; }
  if (!W || !H) return [];
  const out = []; let m; const re = /<(rect|ellipse|polygon)\b[^>]*>/gi;
  while ((m = re.exec(svg))) {
    const tag = m[0], kind = m[1].toLowerCase(), cls = attr(tag, "class") || "", fill = (attr(tag, "fill") || "").toUpperCase();
    const target = /qshape/.test(cls) || fill === "#FF7E7E";
    const sh = {ord: target ? 1 : 0, kind, oi: true};
    const n = k => parseFloat(attr(tag, k) || "0");
    if (kind === "rect") Object.assign(sh, {x: n("x") / W, y: n("y") / H, w: n("width") / W, h: n("height") / H});
    else if (kind === "ellipse") Object.assign(sh, {x: (n("cx") - n("rx")) / W, y: (n("cy") - n("ry")) / H, w: 2 * n("rx") / W, h: 2 * n("ry") / H});
    else sh.pts = String(attr(tag, "points") || "").trim().split(/[\s]+/).map(xy => xy.split(",").map(Number)).filter(a => a.length === 2).map(a => [a[0] / W, a[1] / H]);
    out.push(sh);
  }
  return out;
}

const RASTER = {png: 1, gif: 1, jpeg: 1, webp: 1, bmp: 1};

/* Reads an .apkg or .colpkg. opts: {now, name}. Returns {cards, images: {name: bytes}, stats}. */
async function read(buf, opts){
  opts = opts || {};
  const now = opts.now || Date.now();
  const zip = Zip.open(buf);
  const dbName = ["collection.anki21b", "collection.anki21", "collection.anki2"].find(n => zip.has(n));
  if (!dbName) throw new Error("There's no Anki deck inside this file.");
  let dbBytes = await zip.read(dbName);
  if (Zstd.isZstd(dbBytes)) dbBytes = Zstd.decompress(dbBytes);
  const db = Sqlite.open(dbBytes);
  // media: file name -> entry in the zip
  const mediaMap = {};
  if (zip.has("media")) {
    let m = await zip.read("media");
    if (Zstd.isZstd(m)) m = Zstd.decompress(m);
    if (m.length && m[0] === 0x7B) { const j = JSON.parse(new TextDecoder().decode(m)); for (const k in j) mediaMap[j[k]] = k; }
    else (pb(m)[1] || []).forEach((e, i) => { const g = pb(e), name = pbText(g[1] && g[1][0]); if (name) mediaMap[name] = g[255] ? String(g[255][0]) : String(i); });
  }
  const mediaCache = {};
  const media = async name => {
    if (name in mediaCache) return mediaCache[name];
    const z = mediaMap[name];
    let d = z == null ? null : await zip.read(z);
    if (d && Zstd.isZstd(d)) d = Zstd.decompress(d);
    return (mediaCache[name] = d);
  };
  const col = (db.rows("col") || [])[0] || {};
  const crt = +col.crt || 0;
  // note types
  const nts = {};
  const ntRows = db.rows("notetypes");
  if (ntRows && ntRows.length) {
    for (const r of ntRows) { const c = pb(r.config || new Uint8Array(0)); nts[r.id] = {name: String(r.name), cloze: !!(c[1] && c[1][0] === 1), fields: [], tmpls: []}; }
    for (const r of db.rows("fields") || []) if (nts[r.ntid]) nts[r.ntid].fields[r.ord] = String(r.name);
    for (const r of db.rows("templates") || []) if (nts[r.ntid]) {
      const c = pb(r.config || new Uint8Array(0));
      let q = pbText(c[1] && c[1][0]), a = pbText(c[2] && c[2][0]);
      if (q.indexOf("{{") < 0) { const strs = []; Object.keys(c).sort((x, y) => x - y).forEach(k => c[k].forEach(v => { if (v instanceof Uint8Array) { const t = pbText(v); if (t.indexOf("{{") >= 0) strs.push(t); } })); q = strs[0] || q; a = strs[1] || a; }
      nts[r.ntid].tmpls[r.ord] = {name: String(r.name), q, a};
    }
  } else if (col.models) {
    const m = JSON.parse(col.models);
    for (const id in m) { const x = m[id]; nts[id] = {name: x.name, cloze: x.type === 1, fields: (x.flds || []).slice().sort((a, b) => a.ord - b.ord).map(f => f.name),
      tmpls: (x.tmpls || []).slice().sort((a, b) => a.ord - b.ord).map(t => ({name: t.name, q: t.qfmt || "", a: t.afmt || ""}))}; }
  }
  for (const id in nts) { const t = nts[id]; if (!t.cloze && t.tmpls.some(x => x && /\{\{[^}]*cloze:/.test(x.q))) t.cloze = true; }
  // decks
  const decks = {};
  const dRows = db.rows("decks");
  if (dRows && dRows.length) for (const r of dRows) decks[r.id] = String(r.name).split("\x1f").join("::");
  else if (col.decks) { const d = JSON.parse(col.decks); for (const id in d) decks[id] = d[id].name; }
  const fallbackName = String(opts.name || "Anki deck").replace(/\.(apkg|colpkg)$/i, "");
  const deckLabel = did => { const n = decks[did]; return !n || n === "Default" ? fallbackName : n.split("::").join(" · "); };
  const notes = {};
  for (const r of db.rows("notes") || []) notes[r.id] = r;

  const stats = {cards: 0, imported: 0, suspended: 0, audio: 0, empty: 0, unknown: 0, images: 0, cloze: 0, occlusion: 0, kept: 0, sounds: 0};
  const cards = [], imgs = {}, snds = {}, soundRefs = {};
  async function sound(name){ // saved once, shared by every card that plays it
    if (!name) return null;
    if (name in soundRefs) return soundRefs[name];
    const d = await media(name), t = audioType(d);
    let ref = null;
    if (t && d.length <= MAX_SOUND) { snds[name] = d; ref = name; }
    return (soundRefs[name] = ref);
  }
  async function picture(name){
    if (!name) return null;
    const d = await media(name), size = imageSize(d);
    if (!d || !size || !RASTER[size.type] || d.length > 8 * 1024 * 1024) return null;
    imgs[name] = d;
    return {name, w: size.w, h: size.h};
  }
  const cardRows = (db.rows("cards") || []).sort((a, b) => a.id - b.id);
  for (const c of cardRows) {
    stats.cards++;
    if (c.queue === -1) { stats.suspended++; continue; }
    const n = notes[c.nid], nt = n && nts[n.mid];
    if (!nt) { stats.unknown++; continue; }
    const vals = String(n.flds).split("\x1f"), F = {};
    nt.fields.forEach((name, i) => { F[name] = vals[i] || ""; });
    const deck = deckLabel(c.did), ctx = {tags: String(n.tags || "").trim(), type: nt.name, deck: decks[c.did] || "", card: ""};
    let card = null;
    const occField = Object.keys(F).find(k => /image-occlusion:/.test(F[k]));
    const ioeMask = F["Question Mask"];
    if (nt.cloze && occField) {
      const imgName = images(Object.keys(F).filter(k => k !== occField).map(k => F[k]).join(" "))[0];
      const pic = await picture(imgName), target = c.ord + 1;
      const shapes = pic ? ioShapes(F[occField], pic) : [];
      if (pic && shapes.some(s => s.ord === target && s.kind !== "text")) {
        const header = clean(F.Header || ""), extra = clean(F["Back Extra"] || "");
        card = {f: header || "Name the highlighted part", b: "", x: extra, img: pic.name, imgW: pic.w, imgH: pic.h, io: {target, shapes}};
        stats.occlusion++;
      }
    } else if (ioeMask != null && images(ioeMask).length) {
      const pic = await picture(images(F.Image || "")[0]), svg = await media(images(ioeMask)[0]);
      const shapes = svg ? ioeShapes(new TextDecoder().decode(svg)) : [];
      if (pic && shapes.some(s => s.ord === 1 && s.kind !== "text")) {
        const extra = ["Footer", "Remarks", "Extra 1", "Extra 2"].map(k => clean(F[k] || "")).filter(x => x).join("\n");
        card = {f: clean(F.Header || "") || "Name the highlighted part", b: "", x: extra, img: pic.name, imgW: pic.w, imgH: pic.h, io: {target: 1, shapes}};
        stats.occlusion++;
      }
    } else if (nt.cloze) {
      const t = nt.tmpls[0] || {q: "{{cloze:Text}}", a: "{{cloze:Text}}"}, num = c.ord + 1;
      const qRefs = fieldRefs(t.q), clozeField = (qRefs.find(r => r.cloze) || {}).field;
      const qHtml = renderTpl(t.q, F, Object.assign({side: "q", cloze: num}, ctx));
      const got = [];
      if (clozeField) clozeText(clozeParse(F[clozeField] || ""), num, "a", got);
      const full = clozeField ? clean(clozeText(clozeParse(F[clozeField] || ""), null, "all", null)) : "";
      const extra = fieldRefs(t.a).filter(r => r.field !== "FrontSide" && !qRefs.some(q => q.field === r.field)).map(r => clean(F[r.field] || "")).filter(x => x);
      const answers = got.map(clean).filter(x => x);
      if (answers.length && hasText(qHtml)) {
        const pic = await picture(images(qHtml)[0]);
        card = {f: clean(qHtml), b: answers.filter((v, i) => answers.indexOf(v) === i).join("; "), x: [full].concat(extra).filter(x => x).join("\n"), img: pic ? pic.name : null, imgW: pic ? pic.w : 0, imgH: pic ? pic.h : 0, cloze: true,
          snd: await sound(sounds(qHtml)[0])};
        stats.cloze++;
      }
    } else {
      const t = nt.tmpls[c.ord];
      if (t) {
        ctx.card = t.name;
        const qHtml = renderTpl(t.q, F, Object.assign({side: "q"}, ctx));
        const qRefs = fieldRefs(t.q).filter(r => !r.type).map(r => r.field);
        const aFields = [];
        fieldRefs(t.a).forEach(r => { if (r.field !== "FrontSide" && qRefs.indexOf(r.field) < 0 && aFields.indexOf(r.field) < 0 && F[r.field] != null && (hasText(F[r.field]) || /<img/i.test(F[r.field]))) aFields.push(r.field); });
        let bHtml, xText = "";
        if (aFields.length) { bHtml = F[aFields[0]]; xText = aFields.slice(1).map(f => clean(F[f])).filter(x => x).join("\n"); }
        else {
          let a = renderTpl(t.a, F, Object.assign({side: "a", front: ""}, ctx));
          const i = a.search(/<hr[^>]*id\s*=\s*["']?answer/i);
          if (i >= 0) a = a.slice(i).replace(/^<hr[^>]*>/i, "");
          bHtml = a;
        }
        const qPic = await picture(images(qHtml)[0]), aPic = await picture(images(bHtml)[0]);
        const qSnd = await sound(sounds(qHtml)[0]), aSnd = await sound(sounds(bHtml)[0]);
        const f = clean(qHtml), b = clean(bHtml);
        // something to ask (words, a picture or a sound) and an answer (words or a picture, or a sound with a written or pictured question)
        if ((f || qPic || qSnd) && (b || aPic || (aSnd && (f || qPic)))) card = {f: f || (qPic ? "What's this?" : LISTEN), b: b || "", x: xText, img: qPic ? qPic.name : null, imgW: qPic ? qPic.w : 0, imgH: qPic ? qPic.h : 0,
          aimg: aPic ? aPic.name : null, aimgW: aPic ? aPic.w : 0, aimgH: aPic ? aPic.h : 0, snd: qSnd, asnd: aSnd};
        else if (/\[sound:/.test(qHtml + bHtml)) { stats.audio++; continue; }
      }
    }
    if (!card) { stats.empty++; continue; }
    card.id = "apkg:" + c.id;
    card.deck = deck;
    card.reps = c.reps || 0; card.lapses = c.lapses || 0; card.state = "new";
    if (c.type === 2 || c.type === 3) {
      let s = 0, d = 0;
      try { const j = JSON.parse(c.data || "{}"); s = +j.s || 0; d = +j.d || 0; } catch (e) {}
      const ivl = Math.max(1, c.ivl || 1);
      card.state = "review";
      card.s = s || ivl;
      card.d = d || Math.max(1, Math.min(10, 5 + (2500 - (c.factor || 2500)) / 250));
      card.due = c.type === 2 && crt && c.queue === 2 ? (crt + c.due * 86400) * 1000 : now;
      card.last = card.due - ivl * DAY;
      stats.kept++;
    }
    if (card.img || card.aimg) stats.images++;
    if (card.snd || card.asnd) stats.sounds++;
    cards.push(card);
    stats.imported++;
  }
  return {cards, images: imgs, sounds: snds, stats};
}

return {Zstd, Zip, Sqlite, pb, pbText, imageSize, audioType, read, clozeParse, clozeText, renderTpl, ioShapes, ioeShapes, clean};
})();
if (typeof module !== "undefined") module.exports = Apkg;
