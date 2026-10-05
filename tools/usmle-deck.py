#!/usr/bin/env python3
"""A dense test deck: USMLE Step 1-style cards, exported with Anki's own code.

    python3 -m venv /tmp/anki && /tmp/anki/bin/pip install anki pillow
    /tmp/anki/bin/python tools/usmle-deck.py [OUT_DIR]

Medical students revise from huge Anki decks, so this is the far end of what Reelcards has to show
in a reel: long clinical vignettes with lab values, First Aid-style lists of 8 or more items, mechanism
chains with arrows, ions and units (Na<sup>+</sup>, HCO<sub>3</sub><sup>−</sup>, mEq/L), a table,
cloze notes in a community-deck style (Extra field, hint fields, a script in the template), and image
occlusion on a labelled diagram. The facts are standard Step 1 material; they're here to test layout,
not to teach.

Writes (default OUT_DIR engine/src/test/resources/anki/real):
  usmle.apkg        File > Export > Anki Deck Package
  usmle-says.tsv    how Anki itself shows each card (same columns as anki-says.tsv)
"""
import importlib.util, os, sys, tempfile

from anki.collection import Collection, ExportAnkiPackageOptions
from anki.scheduler.v3 import CardAnswer
from anki.stdmodels import StockNotetypeKind
from PIL import ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "engine", "src", "test", "resources", "anki", "real")
spec = importlib.util.spec_from_file_location("real_anki_decks", os.path.join(ROOT, "tools", "real-anki-decks.py"))
real = importlib.util.module_from_spec(spec)
spec.loader.exec_module(real)
png, add, notetype, text = real.png, real.add, real.notetype, real.text

# Community Step 1 decks use a cloze note type with an Extra field, resource fields shown as hint
# buttons, and some script; this is a small version of that.
CLOZE_Q = '<div class="text">{{cloze:Text}}</div>'
CLOZE_A = ('<div class="text">{{cloze:Text}}</div>\n{{#Extra}}<div id="extra">{{Extra}}</div>{{/Extra}}\n'
           '{{#First Aid}}<div class="hints">{{hint:First Aid}}</div>{{/First Aid}}\n'
           '{{#Pathology}}<div class="hints">{{hint:Pathology}}</div>{{/Pathology}}\n'
           '<script>\nvar el = document.getElementById("extra"); if (el) el.style.display = "block";\n</script>')

def step1_cloze(col):
    mm = col.models
    m = mm.copy(mm.by_name("Cloze"), add=False)
    m["name"] = "Step 1 cloze"
    mm.rename_field(m, m["flds"][1], "Extra")
    for f in ("First Aid", "Pathology"):
        mm.add_field(m, mm.new_field(f))
    m["tmpls"][0]["qfmt"], m["tmpls"][0]["afmt"] = CLOZE_Q, CLOZE_A
    mm.add(m)
    return mm.by_name("Step 1 cloze")

NEPHRON = [  # label, label box (x0, y0, x1, y1), point on the drawing
    ("Glomerulus", (30, 30, 230, 70), (150, 135)),
    ("Proximal convoluted tubule", (300, 20, 690, 60), (330, 120)),
    ("Thick ascending limb", (40, 400, 330, 440), (300, 330)),
    ("Distal convoluted tubule", (330, 170, 650, 210), (320, 190)),
    ("Collecting duct", (560, 440, 790, 480), (640, 380)),
]

def nephron(d):
    d.ellipse((110, 95, 190, 175), fill="#f6b3c2", outline="#c2345e", width=5)
    tube = dict(fill="#e8a33c", width=14)
    d.line([(190, 135), (260, 120), (330, 110), (380, 140), (300, 170), (260, 200), (280, 240)], **tube, joint="curve")  # PCT
    d.line([(280, 240), (280, 460)], **tube)                       # thin descending limb
    d.line([(280, 460), (300, 470), (320, 460)], **tube, joint="curve")
    d.line([(320, 460), (320, 230)], fill="#e8a33c", width=20)    # thick ascending limb
    d.line([(320, 230), (300, 190), (360, 180), (420, 220), (560, 220), (620, 200)], **tube, joint="curve")  # DCT
    d.line([(640, 120), (640, 480)], fill="#8a6fd1", width=22)    # collecting duct
    for label, (x0, y0, x1, y1), point in NEPHRON:
        d.rectangle((x0, y0, x1, y1), fill="#ffffff", outline="#333333", width=2)
        d.text((x0 + 10, y0 + 8), label, fill="#111111", font_size=24)
        d.line([((x0 + x1) // 2, y1 if point[1] > y1 else y0), point], fill="#333333", width=2)

def build(path):
    col = Collection(path)
    col.set_config("fsrs", True)
    renal = col.decks.id("USMLE Step 1::Renal")
    pharm = col.decks.id("USMLE Step 1::Pharmacology")
    cardio = col.decks.id("USMLE Step 1::Cardiology")
    heme = col.decks.id("USMLE Step 1::Heme-Onc")
    gen = col.decks.id("USMLE Step 1::Genetics and Biochem")
    vign = col.decks.id("USMLE Step 1::Vignettes")

    cz = step1_cloze(col)
    reviewed = []
    reviewed.append(add(col, cz, renal, {"Text": "{{c1::Nephrotic}} syndrome: proteinuria &gt; {{c2::3.5 g/day}}, hypoalbuminemia, edema and hyperlipidemia.",
        "Extra": "Fatty casts and &quot;Maltese cross&quot; lipid droplets under polarized light.", "First Aid": "Renal: nephrotic syndrome"}))
    add(col, cz, renal, {"Text": "Anion gap = {{c1::Na<sup>+</sup> − (Cl<sup>−</sup> + HCO<sub>3</sub><sup>−</sup>)}}; normal is about {{c2::8–12 mEq/L}}."})
    add(col, cz, renal, {"Text": "Causes of anion gap metabolic acidosis: {{c1::GOLDMARK}}",
        "Extra": "<b>G</b>lycols (ethylene, propylene)<br><b>O</b>xoproline (chronic acetaminophen)<br><b>L</b>-lactate<br><b>D</b>-lactate<br>"
                 "<b>M</b>ethanol<br><b>A</b>spirin<br><b>R</b>enal failure<br><b>K</b>etoacidosis"})
    add(col, cz, renal, {"Text": "{{c1::Kussmaul}} respirations (deep, labored breathing) are seen in {{c2::metabolic acidosis::acid-base disorder}}, e.g. DKA."})
    reviewed.append(add(col, cz, pharm, {"Text": "{{c1::Warfarin}} inhibits {{c2::vitamin K epoxide reductase}}, ↓ synthesis of factors {{c3::II, VII, IX and X}} and proteins C and S.",
        "Extra": "Monitor with PT/INR. Reverse with vitamin K, plus prothrombin complex concentrate if bleeding."}))
    add(col, cz, pharm, {"Text": "{{c1::Isoniazid}} causes {{c2::vitamin B<sub>6</sub> (pyridoxine)}} deficiency → {{c3::peripheral neuropathy}} and sideroblastic anemia.",
        "Pathology": "Sideroblastic anemia: ALA synthase needs B<sub>6</sub>"})
    add(col, cz, cardio, {"Text": "{{c1::Digoxin}} inhibits the Na<sup>+</sup>/K<sup>+</sup>-ATPase → ↑ intracellular Na<sup>+</sup> → ↓ Na<sup>+</sup>/Ca<sup>2+</sup> exchange → "
        "↑ intracellular Ca<sup>2+</sup> → {{c2::positive inotropy}}.", "Extra": "Toxicity: nausea, blurry yellow vision, arrhythmias, hyperkalemia."})
    add(col, cz, heme, {"Text": "The most common cause of hypercalcemia in hospitalized patients is {{c1::malignancy::cause}}; in outpatients it is {{c2::primary hyperparathyroidism}}."})
    add(col, cz, gen, {"Text": "Lesch–Nyhan syndrome: X-linked recessive deficiency of {{c1::HGPRT}} → defective purine salvage → excess {{c2::uric acid}} → gout, "
        "{{c3::self-mutilation}}, intellectual disability and dystonia. Treat hyperuricemia with {{c4::allopurinol or febuxostat}}.",
        "Extra": "HGPRT: <b>H</b>yperuricemia, <b>G</b>out, <b>P</b>issed off (aggression, self-mutilation), <b>R</b>etardation (intellectual disability), dys<b>T</b>onia."})

    basic = notetype(col, StockNotetypeKind.KIND_BASIC)
    add(col, basic, pharm, {"Front": "Antidote for acetaminophen overdose?", "Back": "<i>N</i>-acetylcysteine"})
    reviewed.append(add(col, basic, pharm, {"Front": "Antidote for heparin?", "Back": "Protamine sulfate"}))
    add(col, basic, pharm, {"Front": "Why must a pheochromocytoma get α-blockade before β-blockade?",
        "Back": "A β-blocker given first removes β<sub>2</sub> vasodilation and leaves α<sub>1</sub> vasoconstriction unopposed, which can cause a hypertensive crisis. "
                "Phenoxybenzamine, an irreversible α-blocker, is started first."})
    add(col, basic, cardio, {"Front": "Phases of the ventricular myocyte action potential?",
        "Back": "0: rapid depolarization (fast Na<sup>+</sup> channels open)<br>1: initial repolarization (Na<sup>+</sup> channels inactivate, K<sup>+</sup> channels open)<br>"
                "2: plateau (Ca<sup>2+</sup> influx balances K<sup>+</sup> efflux)<br>3: rapid repolarization (K<sup>+</sup> efflux, Ca<sup>2+</sup> channels close)<br>"
                "4: resting potential (high K<sup>+</sup> permeability)"})
    add(col, basic, gen, {"Front": "Features of Down syndrome (trisomy 21)?",
        "Back": "<ul><li>Intellectual disability</li><li>Flat facies</li><li>Prominent epicanthal folds</li><li>Single palmar crease</li>"
                "<li>Gap between 1st and 2nd toes</li><li>Duodenal atresia</li><li>Hirschsprung disease</li>"
                "<li>Congenital heart disease (most often AV septal defect)</li><li>↑ risk of ALL, AML and early-onset Alzheimer disease</li></ul>"})
    add(col, basic, heme, {"Front": "Causes of SIADH?",
        "Back": "<ul><li>Ectopic ADH (small cell lung cancer)</li><li>CNS disorders or head trauma</li><li>Pulmonary disease</li>"
                "<li>Drugs: SSRIs, carbamazepine, cyclophosphamide</li></ul>"})
    add(col, basic, heme, {"Front": "Iron studies: iron deficiency vs anemia of chronic disease?",
        "Back": "<table><tr><th></th><th>Iron deficiency</th><th>Chronic disease</th></tr>"
                "<tr><td>Serum iron</td><td>↓</td><td>↓</td></tr><tr><td>TIBC (transferrin)</td><td>↑</td><td>↓</td></tr>"
                "<tr><td>Ferritin</td><td>↓</td><td>↑</td></tr><tr><td>% saturation</td><td>↓↓</td><td>↓ or normal</td></tr></table>"})
    add(col, basic, cardio, {"Front": "Most common cause of community-acquired pneumonia in adults?", "Back": "<i>Streptococcus pneumoniae</i>"})

    add(col, basic, vign, {"Front": "A 23-year-old woman comes to the emergency department with fever and a new holosystolic murmur at the left lower sternal border "
        "that gets louder with inspiration. Chest CT shows multiple septic pulmonary emboli. She has track marks on both forearms. "
        "Which valve is most likely affected, and what is the most likely organism?",
        "Back": "Tricuspid valve; <i>Staphylococcus aureus</i>"})
    add(col, basic, vign, {"Front": "A 4-year-old boy has periorbital edema two weeks after an upper respiratory infection. Urinalysis shows 4+ protein and no blood. "
        "Serum albumin is 1.9 g/dL and cholesterol 320 mg/dL. What would electron microscopy of a kidney biopsy most likely show?",
        "Back": "Effacement of podocyte foot processes (minimal change disease). Light microscopy is normal, and it responds to corticosteroids."})
    add(col, basic, vign, {"Front": "A 58-year-old man with a 40-pack-year smoking history has had 3 weeks of confusion and fatigue. He takes no medications. "
        "Temperature 37.0 °C, pulse 82/min, blood pressure 128/78 mm Hg. He is euvolemic. "
        "Serum Na<sup>+</sup> 121 mEq/L, serum osmolality 255 mOsm/kg, urine osmolality 520 mOsm/kg, urine Na<sup>+</sup> 48 mEq/L. "
        "Chest x-ray shows a central hilar mass. What is the most likely cause of his hyponatremia?",
        "Back": "SIADH from ectopic ADH secretion by small cell lung cancer"})

    rev = notetype(col, StockNotetypeKind.KIND_BASIC_AND_REVERSED)
    add(col, rev, pharm, {"Front": "Mechanism of metformin", "Back": "↓ hepatic gluconeogenesis (and ↑ insulin sensitivity); risk of lactic acidosis"})

    # image occlusion on a labelled diagram, as anatomy and physiology decks use it
    col.add_image_occlusion_notetype()
    io_nt = col.models.by_name("Image Occlusion")
    W, H = 820, 500
    fd, pic = tempfile.mkstemp(suffix=".png")
    os.write(fd, png(W, H, nephron))
    os.close(fd)
    occ = "".join("{{c%d::image-occlusion:rect:left=%.4f:top=%.4f:width=%.4f:height=%.4f:oi=1}}"
                  % (i + 1, (b[0] - 4) / W, (b[1] - 4) / H, (b[2] - b[0] + 8) / W, (b[3] - b[1] + 8) / H) for i, (_, b, _) in enumerate(NEPHRON))
    col.add_image_occlusion_note(notetype_id=io_nt["id"], image_path=pic, occlusions=occ, header="Nephron: name each part",
                                 back_extra="Loop diuretics act on the thick ascending limb; thiazides on the DCT.", tags=[])
    os.remove(pic)
    col.set_deck(col.find_cards('"note:Image Occlusion"'), renal)

    # a few reviewed, as in a deck a student has been working through
    for n in reviewed:
        for cid in col.find_cards("nid:%d" % n.id):
            c = col.get_card(cid)
            c.start_timer()
            col.sched.answer_card(col.sched.build_answer(card=c, states=col._backend.get_scheduling_states(c.id), rating=CardAnswer.GOOD))
    return col

def main():
    os.makedirs(OUT, exist_ok=True)
    work = tempfile.mkdtemp()
    col = build(os.path.join(work, "collection.anki2"))
    n = col.export_anki_package(out_path=os.path.join(OUT, "usmle.apkg"), limit=None,
                                options=ExportAnkiPackageOptions(with_scheduling=True, with_deck_configs=True, with_media=True, legacy=False))
    print("wrote usmle.apkg", n, "notes")
    with open(os.path.join(OUT, "usmle-says.tsv"), "w", encoding="utf-8") as f:
        f.write("# card id\tnote type\tord\tsuspended\tquestion\tanswer\ttype\tdue\tinterval\tstability\tdifficulty\n")
        for cid in sorted(col.find_cards("")):
            c = col.get_card(cid)
            a = c.answer().split("<hr id=answer>", 1)[-1]
            ms = c.memory_state
            f.write("\t".join(str(x) for x in (cid, c.note_type()["name"], c.ord, int(c.queue == -1), text(c.question()), text(a), c.type, c.due, c.ivl,
                                               "%.4f" % ms.stability if ms else "", "%.4f" % ms.difficulty if ms else "")) + "\n")
        f.write("# crt\t%d\n" % col.crt)
    col.close()
    print("wrote usmle-says.tsv to", OUT)

if __name__ == "__main__":
    main()
