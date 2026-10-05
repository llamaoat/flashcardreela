// Prints how the JavaScript engine (web-demo/src/engine.js) reads the shape of each card in
// corpus.json, one line per card, for comparing with Splitter.java.
// Usage: node tools/crosscheck/splitter.js tools/crosscheck/corpus.json
const fs = require("fs"), path = require("path");
const root = path.join(__dirname, "..", "..");
const m = new module.constructor();
m._compile(fs.readFileSync(path.join(root, "web-demo/src/engine.js"), "utf8") + "\nmodule.exports=RC;", "engine.js");
const RC = m.exports;

let out = "";
for (const r of JSON.parse(fs.readFileSync(process.argv[2], "utf8"))) {
  const a = RC.analyse({f: r[0], b: r[1], x: r[2], cloze: r[3] === "cloze"}); // optional 4th field: a cloze card
  const sp = RC.splitPoints(r[1]);
  out += a.kind + " | " + a.points.join(" ¦ ") + " | over=" + sp.over.join(" ¦ ") + " | ctx=" + a.ctx + " | main=" + a.qMain +
    " | a2=" + a.a2 + " | more=" + a.more + " | starts=" + (a.multi ? RC.starts(a.points).join(",") : "") +
    " | layouts=" + RC.layoutsFor(a, true).slice().sort().join(",") + " | like=" + RC.likeness(RC.plainText(r[0]), RC.plainText(r[1])) +
    " | case=" + RC.caseMatters(RC.plainText(r[0])) + " | deck=" + RC.deckLabel(r[2]) + "\n";
}
process.stdout.write(out);
