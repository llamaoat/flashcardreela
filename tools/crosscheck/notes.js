// Prints the "Make cards with AI" prompt and the cards the JavaScript notes parser
// (web-demo/src/notes.js) makes from notes.txt, for comparing with Notes.java.
// Usage: node tools/crosscheck/notes.js tools/crosscheck/notes.txt
const fs = require("fs"), path = require("path");
const root = path.join(__dirname, "..", "..");
const m = new module.constructor();
m._compile("const makeCard=(id,deck,f,b,x)=>({id,deck,f,b,x:x||''});\n" + fs.readFileSync(path.join(root, "web-demo/src/notes.js"), "utf8") + "\nmodule.exports={AI_PROMPT,parseNotes};", "notes.js");
const N = m.exports;

let out = "prompt " + N.AI_PROMPT.replace(/\n/g, "\\n") + "\n";
const r = N.parseNotes(fs.readFileSync(process.argv[2], "utf8"), "My notes");
for (const c of r.cards) out += c.id + "\t" + c.f + "\t" + c.b + "\t" + c.x + "\n";
out += "skipped " + r.skipped + "\n";
process.stdout.write(out);
