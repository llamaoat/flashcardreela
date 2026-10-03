#!/usr/bin/env python3
"""Builds Anki test decks (.apkg) in all three of Anki's file formats, for checking the importer.

    python3 tools/make-test-decks.py [OUT_DIR]      (default: engine/src/test/resources/anki)

Writes:
  legacy1.apkg   collection.anki2 only, media list as JSON              (Anki 2.0)
  legacy2.apkg   collection.anki21 + a stub collection.anki2             (Anki 2.1 up to 2.1.49,
                                                                         or "support older versions")
  latest.apkg    collection.anki21b compressed with zstd, protobuf note  (Anki 2.1.50 and later,
                 types and media list, zstd-compressed media files       the default export)
  sample.apkg    same content as latest.apkg: the "try a sample Anki deck" file

The decks are built to Anki's file format (schema 11 and schema 18 SQLite databases) by this
script, not exported by Anki itself, so real exports should still be tried when changing the
importer. Every note type the importer handles is here: Basic, Basic (and reversed card),
Basic (type in the answer), a custom note type with conditional sections and an image, Cloze
(hints, repeated and nested deletions), Image Occlusion (Anki's built-in kind) and the older
Image Occlusion Enhanced add-on, plus a suspended card, an audio-only card and review history.
Needs Python 3 with Pillow, and libzstd (for the latest format).
"""
import ctypes, ctypes.util, io, json, os, sqlite3, sys, tempfile, zipfile
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "engine", "src", "test", "resources", "anki")
FONT = os.path.join(ROOT, "app", "src", "main", "assets", "fonts")

CRT = 1735704000            # collection created 2025-01-01 04:00 UTC
DECK_CELLS, DECK_CHEM = 1700000000001, 1700000000002
SEP = "\x1f"

# ------------------------------------------------------------------ pictures

def font(size, bold=True):
    for name in ("TikTokSans-Black.ttf", "TikTokSans-Bold.ttf", "TikTokSans.ttf"):
        p = os.path.join(FONT, name)
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    for p in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",):
        if os.path.exists(p):
            return ImageFont.truetype(p, size)
    return ImageFont.load_default()

W, H = 900, 640
LABELS = [  # text, label box (x, y), point on the drawing
    ("Cell membrane", (40, 40), (224, 172)),
    ("Nucleus", (660, 60), (470, 290)),
    ("Mitochondrion", (40, 540), (300, 420)),
    ("Cytoplasm", (650, 545), (600, 430)),
    ("Ribosome", (720, 300), (640, 300)),
]

def cell_png():
    im = Image.new("RGB", (W, H), "#fbf7f0")
    d = ImageDraw.Draw(im)
    d.ellipse((130, 110, 770, 530), fill="#ffd9e1", outline="#d9467a", width=8)
    d.ellipse((380, 220, 560, 370), fill="#b79ce0", outline="#6b4bb0", width=6)
    d.ellipse((445, 270, 495, 315), fill="#6b4bb0")
    for x, y in ((270, 390), (560, 190), (300, 230)):
        d.rounded_rectangle((x, y, x + 90, y + 44), radius=22, fill="#ffb15c", outline="#c46a12", width=4)
        for k in range(4):
            d.arc((x + 10 + k * 18, y + 10, x + 30 + k * 18, y + 34), 200, 340, fill="#c46a12", width=3)
    for x, y in ((640, 300), (610, 330), (660, 350), (230, 300), (250, 330), (420, 450), (520, 470)):
        d.ellipse((x - 6, y - 6, x + 6, y + 6), fill="#3a3a60")
    f = font(30)
    boxes = []
    for text, (lx, ly), (px, py) in LABELS:
        tw = d.textlength(text, font=f)
        box = (lx - 10, ly - 8, lx + tw + 10, ly + 40)
        cx = box[0] if px < box[0] else box[2] if px > box[2] else (box[0] + box[2]) / 2
        cy = box[1] if py < box[1] else box[3] if py > box[3] else (box[1] + box[3]) / 2
        d.line((cx, cy, px, py), fill="#222222", width=3)
        d.ellipse((px - 6, py - 6, px + 6, py + 6), fill="#222222")
        d.rounded_rectangle(box, radius=8, fill="#ffffff", outline="#222222", width=2)
        d.text((lx, ly), text, font=f, fill="#141414")
        boxes.append(box)
    out = io.BytesIO()
    im.save(out, "PNG", optimize=True)
    return out.getvalue(), boxes

def organelle_png():
    im = Image.new("RGB", (420, 240), "#fbf7f0")
    d = ImageDraw.Draw(im)
    d.rounded_rectangle((40, 50, 380, 190), radius=70, fill="#ffb15c", outline="#c46a12", width=8)
    for k in range(7):
        d.arc((70 + k * 40, 80, 110 + k * 40, 160), 200, 340, fill="#c46a12", width=6)
    out = io.BytesIO()
    im.save(out, "JPEG", quality=85)
    return out.getvalue()

def norm(box):
    x0, y0, x1, y1 = box
    return x0 / W, y0 / H, (x1 - x0) / W, (y1 - y0) / H

# ------------------------------------------------------------------ note types

AFMT = "{{FrontSide}}\n\n<hr id=answer>\n\n"
IO_Q = ('{{#Header}}<div>{{Header}}</div>{{/Header}}\n<div style="display: none">{{cloze:Occlusion}}</div>\n'
        '<div id="err"></div>\n<div id="image-occlusion-container">\n    {{Image}}\n    <canvas id="image-occlusion-canvas"></canvas>\n</div>\n'
        '<script>\ntry {\n    anki.imageOcclusion.setup();\n} catch (exc) {\n    document.getElementById("err").innerHTML = `Error loading image occlusion.<br><br>${exc}`;\n}\n</script>\n')
IO_A = IO_Q.replace("<script>", "<div><button id=\"toggle\">Toggle Masks</button></div>\n{{#Back Extra}}<div>{{Back Extra}}</div>{{/Back Extra}}\n<script>")
IOE_FIELDS = ["ID (hidden)", "Header", "Image", "Question Mask", "Footer", "Remarks", "Sources", "Extra 1", "Extra 2", "Answer Mask", "Original Mask"]
NOTETYPES = [
    # id, name, kind (0 normal, 1 cloze), stock kind, fields, templates [(name, qfmt, afmt)]
    (1001, "Basic", 0, 1, ["Front", "Back"], [("Card 1", "{{Front}}", AFMT + "{{Back}}")]),
    (1002, "Basic (and reversed card)", 0, 2, ["Front", "Back"], [("Card 1", "{{Front}}", AFMT + "{{Back}}"), ("Card 2", "{{Back}}", AFMT + "{{Front}}")]),
    (1003, "Cloze", 1, 5, ["Text", "Back Extra"], [("Cloze", "{{cloze:Text}}", "{{cloze:Text}}<br>\n{{Back Extra}}")]),
    (1004, "Image Occlusion", 1, 6, ["Occlusion", "Image", "Header", "Back Extra", "Comments"], [("Image Occlusion", IO_Q, IO_A)]),
    (1005, "Basic (type in the answer)", 0, 4, ["Front", "Back"], [("Card 1", "{{Front}}\n\n{{type:Back}}", "{{Front}}\n\n<hr id=answer>\n\n{{type:Back}}")]),
    (1006, "Image Occlusion Enhanced", 0, 0, IOE_FIELDS, [("IO Card", '{{#Image}}<div id="io-header">{{Header}}</div><div id="io-wrapper"><div id="io-overlay">{{Question Mask}}</div><div id="io-original">{{Image}}</div></div>{{/Image}}',
        '{{#Image}}<div id="io-header">{{Header}}</div><div id="io-wrapper"><div id="io-overlay">{{Answer Mask}}</div><div id="io-original">{{Image}}</div></div>{{#Footer}}<div id="io-footer">{{Footer}}</div>{{/Footer}}{{/Image}}')]),
    (1007, "GCSE question", 0, 0, ["Question", "Answer", "Notes", "Picture"],
        [("Card 1", "{{Question}}{{#Picture}}<br>{{Picture}}{{/Picture}}", "{{FrontSide}}<hr id=answer>{{Answer}}{{#Notes}}<br><i>{{Notes}}</i>{{/Notes}}")]),
]

def build_notes(cell_boxes):
    """(note id, note type id, deck id, fields, tags, cards [(card id, ord, type, queue, due, ivl, factor, reps, lapses, data)])"""
    rects = [norm(b) for b in cell_boxes]
    def io_rect(n, r, oi=True):
        return "{{c%d::image-occlusion:rect:left=%.4f:top=%.4f:width=%.4f:height=%.4f%s}}" % (n, r[0], r[1], r[2], r[3], ":oi=1" if oi else "")
    occ = io_rect(1, rects[0]) + io_rect(2, rects[1])
    x, y, w, h = rects[2]
    occ += "{{c3::image-occlusion:polygon:points=%.4f,%.4f %.4f,%.4f %.4f,%.4f %.4f,%.4f:oi=1}}" % (x, y, x + w, y, x + w, y + h, x, y + h)
    x, y, w, h = rects[3]
    occ += "{{c4::image-occlusion:ellipse:left=%.4f:top=%.4f:rx=%.4f:ry=%.4f:oi=1}}" % (x - 0.01, y - 0.01, w / 2 + 0.01, h / 2 + 0.01)
    occ += io_rect(5, rects[4])
    new = lambda cid, ord_=0: (cid, ord_, 0, 0, cid % 1000, 0, 0, 0, 0, "")
    notes = [
        (2001, 1001, DECK_CELLS, ["What does the mitochondria do?", "Aerobic respiration"], "biology",
            [(3001, 0, 2, 2, 640, 12, 2500, 4, 0, "")]),
        (2002, 1001, DECK_CELLS, ["Name the three main parts of a neurone.", "<ul><li>Cell body</li><li>Axon</li><li>Dendrites</li></ul>"], "", [new(3002)]),
        (2003, 1001, DECK_CELLS, ['Which organelle is this?<br><img src="organelle.jpg">', "A mitochondrion"], "", [new(3003)]),
        (2004, 1002, DECK_CELLS, ["Osmosis", "The diffusion of water across a partially permeable membrane"], "",
            [(3004, 0, 2, 2, 652, 30, 2300, 7, 1, json.dumps({"s": 41.25, "d": 6.5, "dr": 0.9})), new(3005, 1)]),
        (2005, 1003, DECK_CELLS, ["{{c1::Mitochondria}} are the site of {{c2::aerobic respiration::type of respiration}}.", "They have a folded inner membrane."], "",
            [new(3006, 0), new(3007, 1)]),
        (2006, 1003, DECK_CHEM, ["The three states of matter are {{c1::solid}}, {{c1::liquid}} and {{c1::gas}}.", ""], "", [new(3008, 0)]),
        (2007, 1003, DECK_CHEM, ["{{c1::Hydrogen}} makes a squeaky pop with a lit splint.", ""], "", [(3009, 0, 0, -1, 9, 0, 0, 0, 0, "")]),  # suspended
        (2008, 1004, DECK_CELLS, [occ, '<img src="cell.png">', "Label the animal cell", "Plant cells also have a cell wall and chloroplasts.", ""], "",
            [new(3010 + k, k) for k in range(5)]),
        (2009, 1005, DECK_CHEM, ["Chemical symbol for sodium?", "Na"], "", [new(3020)]),
        (2010, 1001, DECK_CHEM, ["[sound:bubbles.mp3]", "[sound:pop.mp3]"], "", [new(3021)]),  # audio only: skipped
        (2011, 1007, DECK_CHEM, ["What's the charge on an electron?", "−1", "Protons are +1, neutrons have no charge.", ""], "", [new(3022)]),
    ]
    for k in range(3):  # the old add-on: one note per covered label
        mask = "cell-ioe-%d-Q.svg" % (k + 1)
        fields = ["ioe%d" % k, "Cell (old add-on)", '<img src="cell.png">', '<img src="%s">' % mask, "", "", "", "", "", '<img src="cell-ioe-%d-A.svg">' % (k + 1), '<img src="cell-ioe-O.svg">']
        notes.append((2100 + k, 1006, DECK_CELLS, fields, "", [new(3100 + k)]))
    return notes

def ioe_svg(boxes, target):
    shapes = []
    for i, (x0, y0, x1, y1) in enumerate(boxes[:3]):
        q = i == target
        shapes.append('<rect id="shape-%d" class="%s" fill="%s" stroke="#2D2D2D" x="%d" y="%d" width="%d" height="%d"/>'
                      % (i, "qshape" if q else "shape", "#FF7E7E" if q else "#FFEBA2", x0, y0, x1 - x0, y1 - y0))
    return ('<svg width="%d" height="%d" xmlns="http://www.w3.org/2000/svg"><g><title>Masks</title>%s</g></svg>' % (W, H, "".join(shapes))).encode()

# ------------------------------------------------------------------ databases

SCHEMA11 = """
CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, scm integer not null, ver integer not null, dty integer not null,
  usn integer not null, ls integer not null, conf text not null, models text not null, decks text not null, dconf text not null, tags text not null);
CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, mod integer not null, usn integer not null, tags text not null,
  flds text not null, sfld integer not null, csum integer not null, flags integer not null, data text not null);
CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, ord integer not null, mod integer not null, usn integer not null,
  type integer not null, queue integer not null, due integer not null, ivl integer not null, factor integer not null, reps integer not null,
  lapses integer not null, left integer not null, odue integer not null, odid integer not null, flags integer not null, data text not null);
CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, ease integer not null, ivl integer not null,
  lastIvl integer not null, factor integer not null, time integer not null, type integer not null);
CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null);
CREATE INDEX ix_notes_usn on notes (usn);
CREATE INDEX ix_cards_usn on cards (usn);
CREATE INDEX ix_revlog_usn on revlog (usn);
CREATE INDEX ix_cards_nid on cards (nid);
CREATE INDEX ix_cards_sched on cards (did, queue, due);
CREATE INDEX ix_revlog_cid on revlog (cid);
CREATE INDEX ix_notes_csum on notes (csum);
"""
SCHEMA18 = """
CREATE TABLE notetypes (id integer NOT NULL PRIMARY KEY, name text NOT NULL COLLATE unicase, mtime_secs integer NOT NULL, usn integer NOT NULL, config blob NOT NULL);
CREATE TABLE fields (ntid integer NOT NULL, ord integer NOT NULL, name text NOT NULL COLLATE unicase, config blob NOT NULL, PRIMARY KEY (ntid, ord)) without rowid;
CREATE TABLE templates (ntid integer NOT NULL, ord integer NOT NULL, name text NOT NULL COLLATE unicase, mtime_secs integer NOT NULL, usn integer NOT NULL,
  config blob NOT NULL, PRIMARY KEY (ntid, ord)) without rowid;
CREATE TABLE decks (id integer PRIMARY KEY NOT NULL, name text NOT NULL COLLATE unicase, mtime_secs integer NOT NULL, usn integer NOT NULL, common blob NOT NULL, kind blob NOT NULL);
CREATE TABLE config (KEY text NOT NULL PRIMARY KEY, usn integer NOT NULL, mtime_secs integer NOT NULL, val blob NOT NULL) without rowid;
CREATE TABLE tags (tag text NOT NULL PRIMARY KEY COLLATE unicase, usn integer NOT NULL, collapsed boolean NOT NULL, config blob NULL) without rowid;
"""

def varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        out.append(b | (0x80 if n else 0))
        if not n:
            return bytes(out)

def pb_field(no, value):
    if isinstance(value, int):
        return varint(no << 3) + varint(value)
    data = value.encode() if isinstance(value, str) else value
    return varint((no << 3) | 2) + varint(len(data)) + data

def collection(schema, notes, stub=False):
    fd, path = tempfile.mkstemp(suffix=".db")
    os.close(fd)
    c = sqlite3.connect(path)
    c.create_collation("unicase", lambda a, b: (a.lower() > b.lower()) - (a.lower() < b.lower()))
    c.executescript(SCHEMA11)
    decks = {str(DECK_CELLS): {"id": DECK_CELLS, "name": "GCSE Biology::Cells"}, str(DECK_CHEM): {"id": DECK_CHEM, "name": "GCSE Chemistry"}, "1": {"id": 1, "name": "Default"}}
    models = {}
    for mid, name, kind, stock, flds, tmpls in NOTETYPES:
        models[str(mid)] = {"id": mid, "name": name, "type": kind, "originalStockKind": stock, "sortf": 0, "css": ".card {}",
                            "flds": [{"name": f, "ord": i} for i, f in enumerate(flds)],
                            "tmpls": [{"name": t[0], "ord": i, "qfmt": t[1], "afmt": t[2]} for i, t in enumerate(tmpls)]}
    if stub:
        models = {"1001": models["1001"]}
        notes = [(1, 1001, 1, ["Please update to the latest Anki version, then import the .colpkg/.apkg file again.", ""], "", [(1, 0, 0, 0, 1, 0, 0, 0, 0, "")])]
    if schema == 18:
        c.executescript(SCHEMA18)
        for mid, name, kind, stock, flds, tmpls in NOTETYPES:
            c.execute("insert into notetypes values (?,?,?,?,?)", (mid, name, 0, 0, pb_field(1, kind) + pb_field(3, ".card {}") + pb_field(9, stock)))
            for i, f in enumerate(flds):
                c.execute("insert into fields values (?,?,?,?)", (mid, i, f, pb_field(1, 0)))
            for i, t in enumerate(tmpls):
                c.execute("insert into templates values (?,?,?,?,?,?)", (mid, i, t[0], 0, 0, pb_field(1, t[1]) + pb_field(2, t[2])))
        for did, d in decks.items():
            c.execute("insert into decks values (?,?,?,?,?,?)", (int(did), d["name"].replace("::", SEP), 0, 0, b"", b""))
        models_json, decks_json = "", ""
    else:
        models_json, decks_json = json.dumps(models), json.dumps(decks)
    c.execute("insert into col values (1,?,0,0,?,0,0,0,'{}',?,?,'{}','{}')", (CRT, schema, models_json, decks_json))
    for nid, mid, did, flds, tags, cards in notes:
        c.execute("insert into notes values (?,?,?,?,?,?,?,?,?,?,?)", (nid, "g%d" % nid, mid, 0, 0, tags, SEP.join(flds), flds[0], 0, 0, ""))
        for cid, ord_, typ, queue, due, ivl, factor, reps, lapses, data in cards:
            c.execute("insert into cards values (?,?,?,?,0,0,?,?,?,?,?,?,?,0,0,0,0,?)", (cid, nid, did, ord_, typ, queue, due, ivl, factor, reps, lapses, data))
    c.commit()
    c.execute("vacuum")
    c.close()
    data = open(path, "rb").read()
    os.remove(path)
    return data

# ------------------------------------------------------------------ packages

def zstd_compress(data, level=3):
    lib = ctypes.CDLL(ctypes.util.find_library("zstd") or "libzstd.so.1")
    lib.ZSTD_compressBound.restype = ctypes.c_size_t
    lib.ZSTD_compress.restype = ctypes.c_size_t
    cap = lib.ZSTD_compressBound(len(data))
    buf = ctypes.create_string_buffer(cap)
    n = lib.ZSTD_compress(buf, cap, data, len(data), level)
    return buf.raw[:n]

def write(name, entries):
    path = os.path.join(OUT, name)
    with zipfile.ZipFile(path, "w") as z:
        for n, data, method in entries:
            z.writestr(zipfile.ZipInfo(n, (2025, 1, 1, 0, 0, 0)), data, compress_type=method)
    print("wrote", path, os.path.getsize(path), "bytes")

def main():
    os.makedirs(OUT, exist_ok=True)
    cell, boxes = cell_png()
    files = [("cell.png", cell), ("organelle.jpg", organelle_png())]
    for k in range(3):
        files.append(("cell-ioe-%d-Q.svg" % (k + 1), ioe_svg(boxes, k)))
        files.append(("cell-ioe-%d-A.svg" % (k + 1), ioe_svg(boxes, -1)))
    files.append(("cell-ioe-O.svg", ioe_svg(boxes, -1)))
    notes = build_notes(boxes)
    D = zipfile.ZIP_DEFLATED
    media_json = json.dumps({str(i): n for i, (n, _) in enumerate(files)}).encode()
    write("legacy1.apkg", [("collection.anki2", collection(11, notes), D), ("media", media_json, D)] + [(str(i), d, D) for i, (_, d) in enumerate(files)])
    write("legacy2.apkg", [("collection.anki2", collection(11, notes, stub=True), D), ("collection.anki21", collection(11, notes), D), ("media", media_json, D)]
          + [(str(i), d, D) for i, (_, d) in enumerate(files)])
    entries = b"".join(pb_field(1, pb_field(1, n) + pb_field(2, len(d))) for n, d in files)
    S = zipfile.ZIP_STORED
    latest = [("collection.anki2", collection(11, notes, stub=True), D), ("collection.anki21b", zstd_compress(collection(18, notes)), S),
              ("meta", pb_field(1, 3), S), ("media", zstd_compress(entries), S)] + [(str(i), zstd_compress(d), S) for i, (_, d) in enumerate(files)]
    write("latest.apkg", latest)
    write("sample.apkg", latest)

if __name__ == "__main__":
    main()
