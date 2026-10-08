/* Reelcards extension popup: pick decks, add your own cards, set the exam date, see today's numbers.
   Every change is saved straight away (state.js); the tabs pick it up from storage. */
"use strict";
(function(){
const $ = id => document.getElementById(id);
const {DAY, LABEL, VIBES, makeCard} = RC;
const PRE = {"GCSE Biology":"bio","GCSE Chemistry":"chem","GCSE Spanish":"es","GCSE Science: longer answers":"sci"}; // same ids as the web demo
const deckCards = name => DECKS[name].map((r,i) => makeCard(PRE[name]+"-"+(i+1), name, r[0], r[1], r[2]));
let E, meta;
if (location.hash === "#tab") document.body.classList.add("tab"), $("tabTip").hidden = true;

function save(){ meta.onboarded = E.cards.length > 0; return RCState.save(E, meta).then(() => { RCState.cleanMedia(E); draw(); }); }
const MIME = {png:"image/png", jpeg:"image/jpeg", gif:"image/gif", webp:"image/webp", bmp:"image/bmp", mp3:"audio/mpeg", ogg:"audio/ogg", m4a:"audio/mp4", wav:"audio/wav", flac:"audio/flac"};
function dataUrl(bytes, type){ let s = ""; for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000)); return "data:" + type + ";base64," + btoa(s); }
function draw(){
  const t = Date.now(), k = E.dk(t), d = E.day(k), due = E.cards.filter(c => c.state !== RC.NEW && c.due <= t).length;
  $("stats").innerHTML = [[d.reviews,"done today"],[due,"due now"],[E.cards.length,"cards"]].map(x => '<div><b>'+x[0]+'</b><small>'+x[1]+'</small></div>').join("");
  const p = E.settings.pausedUntil > t;
  $("pause").textContent = p ? "Resume cards" : "Pause for 1 hour";
  const have = new Set(E.cards.map(c => c.deck));
  $("decks").innerHTML = Object.keys(DECKS).map(n => '<label class="row"><input type="checkbox" data-deck="'+n+'"'+(have.has(n)?" checked":"")+'> '+n+' <span class="muted">('+DECKS[n].length+')</span></label>').join("");
  $("exam").value = String(meta.exam == null ? 14 : meta.exam);
  $("intensity").value = E.settings.intensity;
  $("vibes").innerHTML = VIBES.map(v => '<label><input type="checkbox" data-vibe="'+v+'"'+(E.settings.vibes.includes(v)?" checked":"")+'>'+LABEL[v]+'</label>').join("");
  const decks = [...have].map(n => { const cs = E.cards.filter(c => c.deck === n); return '<div>'+n+': '+cs.filter(c => c.state === RC.REVIEW).length+' learned, '+cs.filter(c => c.state === RC.NEW).length+' new</div>'; });
  $("mine").innerHTML = decks.length ? decks.join("") : "No cards yet. Pick a deck above, or add your own.";
}
function setExam(){ meta.exam = +$("exam").value; E.settings.examDate = meta.exam ? Date.now() + meta.exam*DAY : 0; }

RCState.load().then(r => { E = r.E; meta = r.meta; if (meta.firstDay == null) meta.firstDay = E.dk(Date.now()); draw(); });
// a tab finished a card: show the new numbers, and change the newest copy next time
RCState.onChange(() => RCState.load().then(r => { E = r.E; meta = r.meta; draw(); }));

$("decks").addEventListener("change", e => {
  const n = e.target.dataset.deck; if (!n) return;
  if (e.target.checked) { E.addCards(deckCards(n)); if (!E.settings.examDate && meta.exam) setExam(); }
  else E.cards = E.cards.filter(c => c.deck !== n);
  save();
});
$("vibes").addEventListener("change", e => {
  const v = e.target.dataset.vibe; if (!v) return;
  E.setVibes(e.target.checked ? E.settings.vibes.concat([v]) : E.settings.vibes.filter(x => x !== v)); save();
});
$("exam").onchange = () => { setExam(); save(); };
$("intensity").onchange = () => { E.settings.intensity = $("intensity").value; save(); };
$("pause").onclick = () => { E.settings.pausedUntil = E.settings.pausedUntil > Date.now() ? 0 : Date.now() + 60*60000; save(); };
$("makeCards").onclick = () => {
  const r = parseNotes($("notes").value, $("notesDeck").value.trim() || "My notes");
  const n = E.addCards(r.cards);
  $("cardsMsg").textContent = n ? "Made " + n + " cards." : "No cards found: write one fact a line, like “Term - meaning”.";
  if (n) { $("notes").value = ""; save(); }
};
$("copyPrompt").onclick = () => navigator.clipboard.writeText(AI_PROMPT).then(() => { $("cardsMsg").textContent = "Copied. Paste it into a chatbot with your notes, then paste the reply above."; });
$("openTab").onclick = e => { e.preventDefault(); chrome.tabs.create({url: chrome.runtime.getURL("popup.html#tab")}); };
$("apkg").onchange = e => {
  const f = e.target.files && e.target.files[0]; if (!f) return;
  $("cardsMsg").textContent = "Reading " + f.name + "…";
  f.arrayBuffer().then(b => Apkg.read(new Uint8Array(b), {name: f.name, now: Date.now()})).then(r => {
    // pictures and sounds are stored once each, under a key for this file; cards point at the key
    const store = {}, key = (n, bytes, type) => { if (!n || !bytes) return null; const k = "media:" + f.name + ":" + n; if (!store[k]) store[k] = dataUrl(bytes, type); return k; };
    const pic = n => { const by = r.images[n]; const t = by && Apkg.imageSize(by); return key(n, by, MIME[t && t.type] || "image/png"); };
    const snd = n => { const by = r.sounds[n]; return key(n, by, MIME[by && Apkg.audioType(by)] || "audio/mpeg"); };
    const cards = r.cards.map(c => { const m = makeCard(c.id, c.deck, c.f, c.b, c.x); m.cloze = !!c.cloze; m.io = c.io || null;
      Object.assign(m, {img: c.img ? pic(c.img) : null, aimg: c.aimg ? pic(c.aimg) : null, snd: c.snd ? snd(c.snd) : null, asnd: c.asnd ? snd(c.asnd) : null,
        imgW: c.imgW || 0, imgH: c.imgH || 0, aimgW: c.aimgW || 0, aimgH: c.aimgH || 0});
      if (c.state === "review") Object.assign(m, {state: RC.REVIEW, s: c.s, d: c.d, due: c.due, last: c.last, reps: c.reps, lapses: c.lapses}); return m; });
    const media = Object.keys(store).length;
    return RCState.putMedia(store).then(() => {
      const n = E.addCards(cards);
      $("cardsMsg").textContent = n + " cards from " + f.name + "." + (media ? " " + media + " pictures and sounds kept." : "");
      save();
    });
  }).catch(err => { $("cardsMsg").textContent = "Couldn't read that file: " + err.message; });
};
$("test").onclick = () => {
  chrome.tabs.query({active: true, currentWindow: true}, tabs => {
    const t = tabs[0]; if (!t) return;
    chrome.tabs.sendMessage(t.id, {type: "testCard"}, r => {
      $("testMsg").textContent = chrome.runtime.lastError ? "Open TikTok, Instagram Reels, YouTube Shorts or X in this tab first (reload it if it was already open)."
        : r && r.ok ? "" : (r && r.why) || "No card to show yet.";
      if (r && r.ok && location.hash !== "#tab") window.close();
    });
  });
};
let confirmReset = false;
$("reset").onclick = () => {
  if (!confirmReset) { confirmReset = true; $("reset").textContent = "Tap again to erase everything"; return; }
  RCState.clear().then(() => RCState.load()).then(r => { E = r.E; meta = r.meta; confirmReset = false; $("reset").textContent = "Reset everything"; draw(); });
};
})();
