#!/usr/bin/env python3
"""Exports test decks with Anki's own code, so the importer is tried on files Anki itself wrote.

    python3 -m venv /tmp/anki && /tmp/anki/bin/pip install anki pillow
    /tmp/anki/bin/python tools/real-anki-decks.py [OUT_DIR]

tools/make-test-decks.py writes Anki's file format by hand; this script instead builds a collection
through Anki's library (the same Rust code as Anki desktop) and exports it the ways a student can:

  anki-latest.apkg        File > Export > Anki Deck Package (the default, 2.1.50+ format)
  anki-legacy.apkg        the same with "Support older Anki versions" ticked
  anki-collection.colpkg  File > Export > Anki Collection Package
  anki-says.tsv           how Anki itself shows each card: card id, question and answer as text,
                          and its schedule; EngineChecks compares the importer with it

Default OUT_DIR: engine/src/test/resources/anki/real. Not covered: a shared deck from AnkiWeb and
the old Image Occlusion Enhanced add-on (its note type is made by the add-on, not Anki), which still
need trying by hand (docs/ROADMAP.md).
"""
import html, io, os, re, sys, tempfile

from anki.collection import Collection, ExportAnkiPackageOptions
from anki.scheduler.v3 import CardAnswer
from anki.stdmodels import StockNotetypeKind
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "engine", "src", "test", "resources", "anki", "real")

def png(w, h, draw):
    im = Image.new("RGB", (w, h), "#fbf7f0")
    draw(ImageDraw.Draw(im))
    out = io.BytesIO()
    im.save(out, "PNG", optimize=True)
    return out.getvalue()

def heart(d):
    d.ellipse((60, 40, 340, 260), fill="#ffd9e1", outline="#d9467a", width=6)
    d.rectangle((90, 90, 190, 210), fill="#b79ce0")
    d.rectangle((210, 90, 310, 210), fill="#ffb15c")
    d.polygon([(200, 230), (240, 270), (160, 270)], fill="#6b4bb0")

def organelle(d):
    d.rounded_rectangle((20, 30, 220, 110), radius=40, fill="#ffb15c", outline="#c46a12", width=5)

def notetype(col, kind):
    return col.models.by_name({
        StockNotetypeKind.KIND_BASIC: "Basic",
        StockNotetypeKind.KIND_BASIC_AND_REVERSED: "Basic (and reversed card)",
        StockNotetypeKind.KIND_BASIC_OPTIONAL_REVERSED: "Basic (optional reversed card)",
        StockNotetypeKind.KIND_BASIC_TYPING: "Basic (type in the answer)",
        StockNotetypeKind.KIND_CLOZE: "Cloze",
    }[kind])

def vocab_notetype(col):
    """A custom note type: three fields, two cards, a conditional section and a filter."""
    mm = col.models
    m = mm.new("Spanish vocab")
    for f in ("Spanish", "English", "Example"):
        mm.add_field(m, mm.new_field(f))
    t = mm.new_template("Recognise")
    t["qfmt"] = "<div class=big>{{Spanish}}</div>"
    t["afmt"] = "{{FrontSide}}<hr id=answer>{{English}}{{#Example}}<br><i>{{text:Example}}</i>{{/Example}}"
    mm.add_template(m, t)
    t = mm.new_template("Produce")
    t["qfmt"] = "How do you say <b>{{English}}</b>?"
    t["afmt"] = "{{FrontSide}}<hr id=answer>{{Spanish}}"
    mm.add_template(m, t)
    mm.add(m)
    return mm.by_name("Spanish vocab")

def add(col, nt, deck, fields, tags=()):
    n = col.new_note(nt)
    for k, v in fields.items():
        n[k] = v
    n.tags = list(tags)
    col.add_note(n, deck)
    return n

def build(path):
    col = Collection(path)
    col.set_config("fsrs", True)
    cells = col.decks.id("GCSE Biology::Cells")
    chem = col.decks.id("GCSE Chemistry")
    spanish = col.decks.id("Spanish")
    col.media.write_data("organelle.png", png(240, 140, organelle))
    col.media.write_data("pop.mp3", b"ID3\x03\x00\x00\x00\x00\x00\x00")

    basic = notetype(col, StockNotetypeKind.KIND_BASIC)
    n_mito = add(col, basic, cells, {"Front": "What does the mitochondria do?", "Back": "Aerobic respiration"}, ["biology"])
    add(col, basic, cells, {"Front": "Name the three main parts of a <b>neurone</b>.", "Back": "<ul><li>Cell body</li><li>Axon</li><li>Dendrites</li></ul>"})
    add(col, basic, cells, {"Front": "Put the stages of mitosis in order.", "Back": "Prophase<br>Metaphase<br>Anaphase<br>Telophase"})
    add(col, basic, cells, {"Front": "Word equation for aerobic respiration?", "Back": "<div>glucose + oxygen</div><div>→ carbon dioxide + water</div>"})
    add(col, basic, cells, {"Front": 'Which organelle is this?<br><img src="organelle.png">', "Back": "A mitochondrion"})
    add(col, basic, chem, {"Front": "Is 5&nbsp;g &lt; 7&nbsp;g?", "Back": "Yes &amp; by 2&nbsp;g"})
    add(col, basic, chem, {"Front": "What&#x27;s the formula for water?", "Back": "H<sub>2</sub>O"})  # as genanki writes it
    add(col, basic, chem, {"Front": "[sound:pop.mp3]", "Back": "[sound:pop.mp3]"})  # audio only: left out
    rev = notetype(col, StockNotetypeKind.KIND_BASIC_AND_REVERSED)
    n_osm = add(col, rev, cells, {"Front": "Osmosis", "Back": "The diffusion of water across a partially permeable membrane"})
    opt = notetype(col, StockNotetypeKind.KIND_BASIC_OPTIONAL_REVERSED)
    add(col, opt, chem, {"Front": "Ion", "Back": "An atom that has gained or lost electrons", "Add Reverse": "y"})
    add(col, opt, chem, {"Front": "Isotope", "Back": "Same element, different number of neutrons"})
    typing = notetype(col, StockNotetypeKind.KIND_BASIC_TYPING)
    add(col, typing, chem, {"Front": "Chemical symbol for sodium?", "Back": "Na"})
    cloze = notetype(col, StockNotetypeKind.KIND_CLOZE)
    add(col, cloze, cells, {"Text": "{{c1::Mitochondria}} are the site of {{c2::aerobic respiration::type of respiration}}.", "Back Extra": "They have a folded inner membrane."})
    add(col, cloze, chem, {"Text": "The three states of matter are {{c1::solid}}, {{c1::liquid}} and {{c1::gas}}."})
    add(col, cloze, chem, {"Text": "{{c1::Rutherford's {{c2::gold foil}} experiment}} showed the nucleus."})
    n_susp = add(col, cloze, chem, {"Text": "{{c1::Hydrogen}} makes a squeaky pop with a lit splint."})
    vocab = vocab_notetype(col)
    add(col, vocab, spanish, {"Spanish": "la manzana", "English": "the apple", "Example": "<b>Como</b> una manzana."})
    add(col, vocab, spanish, {"Spanish": "el perro", "English": "the dog"})

    # Anki's own image occlusion: two rectangles, an ellipse, a polygon and a text label
    col.add_image_occlusion_notetype()
    io_nt = col.models.by_name("Image Occlusion")
    fd, pic = tempfile.mkstemp(suffix=".png")
    os.write(fd, png(400, 300, heart))
    os.close(fd)
    occ = ("{{c1::image-occlusion:rect:left=.225:top=.3:width=.25:height=.4:oi=1}}"
           "{{c2::image-occlusion:rect:left=.525:top=.3:width=.25:height=.4:oi=1}}"
           "{{c3::image-occlusion:ellipse:left=.15:top=.13:rx=.35:ry=.37:oi=1}}"
           "{{c4::image-occlusion:polygon:points=.5,.767 .6,.9 .4,.9:oi=1}}"
           "{{c0::image-occlusion:text:left=.05:top=.9:text=Aorta:scale=1:fs=.05}}")  # Anki gives labels 0: no card
    col.add_image_occlusion_note(notetype_id=io_nt["id"], image_path=pic, occlusions=occ, header="Label the heart",
                                 back_extra="Left and right are swapped on a diagram.", tags=[])
    os.remove(pic)
    col.set_deck(col.find_cards('"note:Image Occlusion"'), cells)  # added to the current deck, like the editor; moved

    # history: some cards reviewed (FSRS memory state), one rescheduled by hand (interval, no memory state), one suspended
    for cid in col.find_cards("nid:%d or nid:%d" % (n_mito.id, n_osm.id)):
        c = col.get_card(cid)
        c.start_timer()
        states = col._backend.get_scheduling_states(c.id)
        col.sched.answer_card(col.sched.build_answer(card=c, states=states, rating=CardAnswer.EASY))
    col.sched.set_due_date(col.find_cards('"deck:GCSE Chemistry" Ion card:1'), "12!")
    col.sched.suspend_cards(col.find_cards("nid:%d" % n_susp.id))
    return col

def text(rendered):
    """Anki's rendered HTML as plain lines (roughly what a student reads)."""
    t = re.sub(r"(?is)<(script|style).*?</\1>", " ", rendered)
    t = re.sub(r"(?i)<br\s*/?>|</div>|</li>|<hr[^>]*>", "\n", t)
    t = html.unescape(re.sub(r"<[^>]+>", "", t)).replace("\xa0", " ")
    return "\\n".join(l.strip() for l in t.split("\n") if l.strip())

def main():
    os.makedirs(OUT, exist_ok=True)
    work = tempfile.mkdtemp()
    col = build(os.path.join(work, "collection.anki2"))
    everything = None  # the whole collection, all decks
    for name, legacy in (("anki-latest.apkg", False), ("anki-legacy.apkg", True)):
        n = col.export_anki_package(out_path=os.path.join(OUT, name), limit=everything,
                                    options=ExportAnkiPackageOptions(with_scheduling=True, with_deck_configs=True, with_media=True, legacy=legacy))
        print("wrote", name, n, "notes")
    with open(os.path.join(OUT, "anki-says.tsv"), "w", encoding="utf-8") as f:
        f.write("# card id\tnote type\tord\tsuspended\tquestion\tanswer\ttype\tdue\tinterval\tstability\tdifficulty\n")
        for cid in sorted(col.find_cards("")):
            c = col.get_card(cid)
            q, a = c.question(), c.answer()
            a = a.split("<hr id=answer>", 1)[-1]
            ms = c.memory_state
            f.write("\t".join(str(x) for x in (cid, c.note_type()["name"], c.ord, int(c.queue == -1), text(q), text(a), c.type, c.due, c.ivl,
                                               "%.4f" % ms.stability if ms else "", "%.4f" % ms.difficulty if ms else "")) + "\n")
        f.write("# crt\t%d\n" % col.crt)
    col.close()
    col = Collection(os.path.join(work, "collection.anki2"))  # colpkg export needs the collection closed and reopened
    col.export_collection_package(os.path.join(OUT, "anki-collection.colpkg"), include_media=True, legacy=False)
    col.close()
    print("wrote anki-collection.colpkg and anki-says.tsv to", OUT)

if __name__ == "__main__":
    main()
