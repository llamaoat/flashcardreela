// Prints every card the JavaScript importer (web-demo/src/apkg.js) reads from an .apkg, one
// tab-separated line per card, then the stats, for comparing with AnkiFiles.java.
// Usage: node tools/crosscheck/anki-files.js deck.apkg
const fs = require("fs"), path = require("path");
const Apkg = require(path.join(__dirname, "..", "..", "web-demo/src/apkg.js"));
const NOW = 1759400000000; // fixed, so due dates of new cards match
const f = v => v.toFixed(5);

(async () => {
  const file = process.argv[2];
  const r = await Apkg.read(new Uint8Array(fs.readFileSync(file)), {now: NOW, name: path.basename(file)});
  let b = "";
  for (const c of r.cards) {
    let io = "";
    if (c.io) {
      io = c.io.target + "|";
      for (const sh of c.io.shapes) {
        io += sh.kind[0] + ":" + sh.ord + ":" + (sh.oi ? 1 : 0) + ":";
        if (sh.pts) for (const p of sh.pts) io += f(p[0]) + "," + f(p[1]) + " ";
        else io += f(sh.x) + "," + f(sh.y) + "," + f(sh.w || 0) + "," + f(sh.h || 0);
        io += ";";
      }
    }
    const rev = c.state === "review";
    b += [c.id, c.deck, c.f, c.b, c.x, c.img || "", c.imgW || 0, c.imgH || 0, c.aimg || "", c.aimgW || 0, c.aimgH || 0,
      c.cloze ? "1" : "0", io, rev ? "review" : "new", rev ? f(c.s) : "", rev ? f(c.d) : "", rev ? "" + c.due : "", rev ? "" + c.last : "",
      "" + c.reps, "" + c.lapses, c.snd || "", c.asnd || ""].join("\t").replace(/\n/g, "\\n") + "\n";
  }
  const s = r.stats;
  b += "stats " + [s.cards, s.imported, s.suspended, s.audio, s.empty, s.unknown, s.images, s.cloze, s.occlusion, s.kept, s.sounds].join(" ") + "\n";
  process.stdout.write(b);
})().catch(e => { console.error(e); process.exit(1); });
