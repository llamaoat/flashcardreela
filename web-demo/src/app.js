/* The demo app: setup screens, the feed, the dashboard, the judge panels, the design lab and a simulated student. */
(function(){
"use strict";
const $ = id => document.getElementById(id);
const esc = Render.esc;
const {MIN, DAY, NEW, REVIEW, STATE, LABEL, PIECES, VIBES} = RC;
const makeCard = RC.makeCard;
/*__DECKS__*/
/*__NOTES__*/
/*__SAMPLE_APKG__*/
const PRE = {"GCSE Biology":"bio","GCSE Chemistry":"chem","GCSE Spanish":"es","GCSE Science: longer answers":"sci"};
const deckCards = name => DECKS[name].map((r,i)=>makeCard(PRE[name]+"-"+(i+1), name, r[0], r[1], r[2]));
const HOSTS = [["tiktok","TikTok"],["instagram","Instagram"],["youtube","YouTube"]];

/* ---------------- Anki files (.apkg) ---------------- */
const MIME = {png:"image/png", jpeg:"image/jpeg", gif:"image/gif", webp:"image/webp", bmp:"image/bmp", mp3:"audio/mpeg", ogg:"audio/ogg", m4a:"audio/mp4", wav:"audio/wav", flac:"audio/flac"};
async function importApkg(bytes, name, src){
  const r = await Apkg.read(bytes, {name, now: now()});
  const urls = {};
  for (const k in r.images) { const t = Apkg.imageSize(r.images[k]); urls[k] = URL.createObjectURL(new Blob([r.images[k]], {type: MIME[t && t.type] || "application/octet-stream"})); }
  const sounds = {};
  for (const k in r.sounds) sounds[k] = URL.createObjectURL(new Blob([r.sounds[k]], {type: MIME[Apkg.audioType(r.sounds[k])] || "application/octet-stream"}));
  const cards = r.cards.map(c => {
    const m = makeCard(c.id, c.deck, c.f, c.b, c.x);
    m.img = c.img ? urls[c.img] : null; m.aimg = c.aimg ? urls[c.aimg] : null; m.io = c.io || null; m.cloze = !!c.cloze;
    const spoken = n => n && n.startsWith("tts:") ? n : null; m.snd = c.snd ? spoken(c.snd) || sounds[c.snd] : null; m.asnd = c.asnd ? spoken(c.asnd) || sounds[c.asnd] : null; m.src = src;
    Object.assign(m, {imgW: c.imgW || 0, imgH: c.imgH || 0, aimgW: c.aimgW || 0, aimgH: c.aimgH || 0});
    if (c.state === "review") Object.assign(m, {state: REVIEW, s: c.s, d: c.d, due: c.due, last: c.last, reps: c.reps, lapses: c.lapses});
    return m;
  });
  return {cards, stats: r.stats, name, src, bytes};
}
let samplePromise = null;
const sampleDeck = () => samplePromise || (samplePromise = importApkg(Uint8Array.from(atob(SAMPLE_APKG), ch => ch.charCodeAt(0)), "Sample Anki deck.apkg", "sample"));
function apkgSummary(r){
  const s = r.stats, kinds = [];
  if (s.occlusion) kinds.push(s.occlusion + " image occlusion");
  if (s.cloze) kinds.push(s.cloze + " cloze");
  const rest = s.imported - s.occlusion - s.cloze; if (rest) kinds.push(rest + (kinds.length ? " more" : " cards"));
  const left = [];
  if (s.suspended) left.push(s.suspended + " suspended");
  if (s.audio) left.push(s.audio + " audio-only");
  if (s.empty) left.push(s.empty + " blank or unsupported");
  if (s.unknown) left.push(s.unknown + " with a missing note type");
  return (kinds.length ? kinds.join(", ") : "No cards") + "." + (s.kept ? " " + s.kept + (s.kept === 1 ? " keeps its" : " keep their") + " Anki progress." : "")
    + (s.sounds ? " " + s.sounds + (s.sounds === 1 ? " plays" : " play") + " a sound." : "") + (left.length ? " Left out: " + left.join(", ") + "." : "");
}

/* ---------------- Add to Home Screen ---------------- */
// Opened from the home screen (or with ?app=1): just the phone, full screen. There's no accessibility
// switch on the web, so that setup step is skipped.
const APP = /[?&]app=1\b/.test(location.search) || matchMedia("(display-mode: standalone)").matches || navigator.standalone === true;
if (APP) document.documentElement.classList.add("appmode");
function installOffer(){
  const box = $("install");
  if (APP || !/^https?:$/.test(location.protocol)) return; // needs a real web address, not a file
  if ("serviceWorker" in navigator) navigator.serviceWorker.register("sw.js").catch(() => {});
  const ios = /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (ios) {
    box.innerHTML = '<p><b>Put it on your home screen:</b> tap Share <span aria-hidden="true">(the square with an arrow)</span>, then <b>Add to Home Screen</b>. It opens full screen, like an app, and works without signal.</p>';
    box.hidden = false;
    return;
  }
  let ask = null;
  window.addEventListener("beforeinstallprompt", e => {
    e.preventDefault(); ask = e;
    box.innerHTML = '<p><b>Put it on your home screen:</b> it opens full screen, like an app, and works without signal.</p><button class="btn primary" id="installBtn">Add to home screen</button>';
    box.hidden = false;
    $("installBtn").onclick = () => { if (!ask) return; ask.prompt(); ask.userChoice.then(() => { ask = null; box.hidden = true; }); };
  });
  window.addEventListener("appinstalled", () => { box.hidden = true; });
}

let E, UI, offset = 0;
const now = () => Date.now() + offset;
function freshUI(){ return {screen:"welcome", onboarded:false, addMode:false, decks:new Set(), notes:[], notesDeck:"My notes", notesText:"", notesResult:null,
  exam:14, intensity:"normal", vibes:new Set(), permission:APP, settingsOpen:false, err:"", current:null, reelN:0, why:null, toast:"", confirmReset:false,
  host:"tiktok", lastHue:null, firstDay:null, coachLeft:2, apkg:null, apkgCards:[]}; }
function resetAll(){ fresh(); forget(); }
function fresh(){ offset = 0; E = new RC.Engine(); E.settings.minutesPerDay = +$("mins").value; const h = UI ? UI.host : "tiktok"; UI = freshUI(); UI.host = h; }

/* ---------------- keeping progress ----------------
   Like the app's Store: once setup is done, the engine's state and the student's choices are kept in
   this browser, so the home-screen app carries on where it left off. Anki files with pictures or sounds
   are kept too (IndexedDB), because their pictures can't go in localStorage; they're read again on the
   next visit. Nothing leaves the device. A save that won't load is set aside, not overwritten. */
const SAVE_KEY = "reelcards-demo", MEDIA = ["img","aimg","snd","asnd"];
const ENGINE_FIELDS = ["adaptiveRhythm","sessionId","sessionReels","reelsSinceCard","cardsToday","newToday","postponed","lastSwipeAt","snoozeUntil",
  "dayKey","rhythmArm","lastHost","lastDeck","recentFast","recentReel","requeue","log","anki","tzOffsetMin"];
const Files = {
  db: null,
  open(){ return this.db || (this.db = new Promise((ok, no) => { const r = indexedDB.open("reelcards-demo", 1);
    r.onupgradeneeded = () => r.result.createObjectStore("files"); r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error); })); },
  run(mode, fn){ return this.open().then(db => new Promise((ok, no) => { const tx = db.transaction("files", mode), q = fn(tx.objectStore("files"));
    tx.oncomplete = () => ok(q.result); tx.onerror = () => no(tx.error); })); },
  put(k, v){ return this.run("readwrite", st => st.put(v, k)); },
  get(k){ return this.run("readonly", st => st.get(k)); },
  clear(){ return this.run("readwrite", st => st.clear()); }
};
const hasMedia = c => MEDIA.some(k => c[k]);
function keepFile(r, cards){ if (r && r.bytes && cards.some(hasMedia)) Files.put(r.src, r.bytes).catch(() => {}); }
let saveTimer = null;
function saveSoon(){ clearTimeout(saveTimer); saveTimer = setTimeout(save, 400); }
function save(){
  clearTimeout(saveTimer);
  if (!UI || !UI.onboarded) return; // nothing is kept until setup is done
  const st = {v:1, offset,
    controls:{mins:$("mins").value, night:$("night").value, tint:$("tint").checked, voice:$("voice").checked, persona:$("persona").value},
    ui:{screen:UI.screen==="dash"?"dash":"feed", exam:UI.exam, intensity:UI.intensity, vibes:[...UI.vibes], host:UI.host, firstDay:UI.firstDay,
      coachLeft:UI.coachLeft, reelN:UI.reelN, lastHue:UI.lastHue},
    engine:{cards:E.cards.map(c => { const o = Object.assign({}, c); MEDIA.forEach(k => { if (o[k] && !String(o[k]).startsWith("tts:")) o[k] = true; }); return o; }), // blob: links die with the page (spoken tts: sounds are kept as they are)
      settings:E.settings, stats:E.stats, bandit:E.bandit, calibration:E.calibration, rhythm:E.rhythm, personalScale:E.fsrs.personalScale, newShown:[...E.newShown]}};
  ENGINE_FIELDS.forEach(k => { st.engine[k] = E[k]; });
  try { localStorage.setItem(SAVE_KEY, JSON.stringify(st)); } catch (err) {}
}
function forget(){
  clearTimeout(saveTimer);
  try { localStorage.removeItem(SAVE_KEY); } catch (err) {}
  Files.clear().catch(() => {});
}
function restore(){
  let raw = null;
  try { raw = localStorage.getItem(SAVE_KEY); } catch (err) {}
  if (!raw) return false;
  const want = {}; // card id -> the pictures and sounds it had
  try {
    const st = JSON.parse(raw), s = st.engine;
    if (st.v !== 1) throw new Error("unknown save version");
    fresh();
    E.cards = s.cards.map(c => { const ks = MEDIA.filter(k => c[k] === true); ks.forEach(k => { c[k] = null; }); if (ks.length) want[c.id] = ks; return c; });
    Object.assign(E.settings, s.settings); Object.assign(E.stats, s.stats);
    Object.assign(E.bandit, s.bandit); Object.assign(E.calibration, s.calibration); Object.assign(E.rhythm, s.rhythm);
    E.fsrs.personalScale = s.personalScale || 1; E.newShown = new Set(s.newShown || []);
    ENGINE_FIELDS.forEach(k => { if (k in s) E[k] = s[k]; });
    offset = st.offset || 0;
    Object.assign(UI, st.ui, {vibes:new Set(st.ui.vibes || []), onboarded:true, permission:true});
    const c = st.controls || {};
    if (c.mins) { $("mins").value = c.mins; $("minsOut").textContent = c.mins + " min"; }
    if (c.night) $("night").value = c.night;
    if (c.persona) $("persona").value = c.persona;
    $("tint").checked = !!c.tint; $("voice").checked = !!c.voice;
  } catch (err) {
    try { localStorage.setItem(SAVE_KEY + "-damaged", raw); localStorage.removeItem(SAVE_KEY); } catch (e2) {}
    return false;
  }
  restoreMedia(want);
  return true;
}
/* Reads the Anki files again for their pictures and sounds. If a file has gone (storage cleared), its
   cards that need a picture or sound for the question are dropped; the rest keep their progress. */
function restoreMedia(want){
  const srcs = [...new Set(E.cards.filter(c => want[c.id] && c.src).map(c => c.src))];
  srcs.forEach(src => {
    const read = src === "sample" ? sampleDeck() : Files.get(src).then(b => b ? importApkg(b, src.slice(5), src) : null);
    read.catch(() => null).then(r => {
      const byId = {}; if (r) r.cards.forEach(c => { byId[c.id] = c; });
      E.cards = E.cards.filter(c => {
        if (c.src !== src || !want[c.id]) return true;
        const from = byId[c.id];
        if (from) { want[c.id].forEach(k => { c[k] = from[k]; }); return true; }
        return !(c.io || want[c.id].indexOf("img") >= 0 || want[c.id].indexOf("snd") >= 0);
      });
      render(false);
    });
  });
}
addEventListener("pagehide", save);
document.addEventListener("visibilitychange", () => { if (document.visibilityState === "hidden") save(); });

/* ---------------- night mode + colour match ---------------- */
function isNight(){ const m = $("night").value; if (m==="on") return true; if (m==="off") return false; const h = new Date(now()).getHours(); return h >= 22 || h < 6; }
function cardOpts(){ return {night:isNight(), tint: $("tint").checked && UI.lastHue!=null ? UI.lastHue : null}; }

/* ---------------- voiceover (browser speech) ---------------- */
function speak(t){ if (!$("voice").checked || !window.speechSynthesis) return; try { speechSynthesis.cancel(); const u = new SpeechSynthesisUtterance(t); u.rate = 1.08; speechSynthesis.speak(u); } catch(e){} }
function hush(){ try { window.speechSynthesis && speechSynthesis.cancel(); } catch(e){} }

/* ---------------- phone screens ---------------- */
const stepbar = n => '<div class="steplbl">Step '+n+' of 4</div><div class="stepbar">'+[1,2,3,4].map(i=>'<i class="'+(i<=n?"on":"")+'"></i>').join("")+'</div>';
const choice = (act,val,title,sub,on,dis) => '<button class="choice'+(on?" on":"")+'" data-act="'+act+'" data-v="'+esc(val)+'"'+(dis?" disabled":"")+'><span class="t"><b>'+esc(title)+'</b>'+(sub?'<small>'+esc(sub)+'</small>':'')+'</span><span class="m">'+(on?"✓":"")+'</span></button>';
const tabs = () => UI.onboarded ? '<div class="tabs"><button data-act="tab" data-v="dash" class="'+(UI.screen==="dash"?"on":"")+'">Reelcards</button><button data-act="tab" data-v="feed" class="'+(UI.screen==="feed"?"on":"")+'">For You</button></div>' : "";
let timers = [];
const clearTimers = () => { timers.forEach(clearTimeout); timers = []; };

function renderPhone(anim){
  clearTimers();
  const scr = $("screen"); let h = "";
  switch (UI.screen) {
    case "welcome":
      h = '<div class="app"><div class="steplbl">Reelcards</div><h3>Revise without trying</h3><p>Your flashcards slip in between your TikTok, Instagram and YouTube Shorts reels, looking just like another reel. Double-tap if you know it. Swipe if you don\'t.</p>'+
        '<div class="box"><b>How it feels</b><span class="muted" style="font-size:13px">Reel, reel, reel, <span style="color:var(--accent)">flashcard</span>, reel… Each card is styled for the app you\'re in and the things you watch, so it doesn\'t feel like homework.</span></div>'+
        '<button class="abtn" data-act="go" data-v="decks">Set up in 1 minute</button><p class="muted" style="font-size:12px">Free. Works offline. Nothing you watch is saved or sent anywhere.</p></div>'; break;
    case "decks": {
      const have = n => E.cards.some(c=>c.deck===n);
      h = '<div class="app">'+(UI.addMode?"":stepbar(1))+'<h3>'+(UI.addMode?"Add cards":"What are you revising?")+'</h3><p class="muted">Pick a ready-made deck, paste your notes, or use your Anki decks. Mix as many as you like.</p><div class="sec">Ready-made decks</div>'+
        Object.keys(DECKS).map(n=>choice("deck",n,n,have(n)?"Already added":DECKS[n].length+" cards",have(n)||UI.decks.has(n),have(n))).join("")+
        '<div class="sec">Your own cards</div>'+choice("go","notes","Paste your notes",UI.notes.length?UI.notes.length+" cards ready from “"+UI.notesDeck+"”":"Turns lines like “Osmosis - diffusion of water” into cards",UI.notes.length>0)+
        choice("apkg","","Open an Anki file (.apkg)",UI.apkgCards.length?UI.apkgCards.length+" cards ready":"Shared decks, or decks exported from Anki on a computer",UI.apkgCards.length>0)+
        '<button class="alink" data-act="apkgSample">No Anki file to hand? Try a sample deck</button>'+
        choice("anki","","Your Anki decks","In the Android app: reviews sync back to AnkiDroid",false,true)+
        '<div class="err">'+esc(UI.err)+'</div><button class="abtn" data-act="decksNext">'+(UI.addMode?"Add to my cards":"Next")+'</button></div>'; break; }
    case "notes": {
      const r = UI.notesResult;
      h = '<div class="app"><h3>Paste your notes</h3><p class="muted">One fact per line: “Osmosis - diffusion of water”, “Mitochondria: site of respiration”, “Enzymes are biological catalysts.”, Q:/A: pairs, or tab-separated from Quizlet or Anki.</p>'+
        '<div class="box"><b>Make cards with AI</b><span class="muted" style="font-size:12.5px">Copy our prompt into a chatbot you already use, paste your notes under it, then paste its reply in Notes below. Your notes go to that chatbot, not to Reelcards.</span>'+
        '<button class="abtn ghost" data-act="copyPrompt">Copy the AI prompt</button>'+
        (UI.aiCopied === true ? '<span class="muted" role="status" style="font-size:12.5px;color:var(--good,#3ecf8e)">Copied. Paste it into the chatbot, add your notes underneath, and send. Then copy the reply and paste it below.</span>' :
         UI.aiCopied === false ? '<span class="muted" role="status" style="font-size:12.5px">Couldn\'t copy here: select the prompt and copy it.</span><textarea id="aiprompt" readonly rows="6">'+esc(AI_PROMPT)+'</textarea>' : '')+'</div>'+
        '<label for="ndeck">Deck name<input type="text" id="ndeck" value="'+esc(UI.notesDeck)+'"></label><label for="ntext">Notes<textarea id="ntext" placeholder="Paste or type your notes here">'+esc(UI.notesText)+'</textarea></label>'+
        '<button class="alink" data-act="exampleNotes">Fill with example notes</button><button class="abtn" data-act="makeCards">Make cards</button>';
      if (r) h += r.cards.length ? '<div class="box"><b style="font-family:var(--display);font-weight:400;font-size:20px">Made '+r.cards.length+' cards</b>'+(r.skipped?'<span class="muted" style="font-size:12.5px">'+r.skipped+(r.skipped===1?" line didn't":" lines didn't")+' look like a fact, so we left '+(r.skipped===1?"it":"them")+' out.</span>':'')+
        r.cards.slice(0,6).map(c=>'<div class="mini2">'+esc(c.f)+'<br><span class="muted">→ '+esc(c.b)+'</span></div>').join("")+'<button class="abtn" data-act="useNotes">Use these '+r.cards.length+' cards</button></div>' : '<div class="err">No cards yet. Try one fact per line, like “Osmosis - diffusion of water”.</div>';
      h += '<button class="alink" data-act="go" data-v="decks">Back</button></div>'; break; }
    case "apkg": {
      const r = UI.apkg || {};
      h = '<div class="app"><h3>Your Anki deck</h3>';
      if (r.busy) h += '<p class="muted">Reading “'+esc(r.name)+'”…</p>';
      else if (r.error) h += '<div class="err">'+esc(r.error)+'</div><p class="muted" style="font-size:12.5px">Reelcards reads .apkg and .colpkg files from any Anki version, with no account. Audio and suspended cards are left out.</p>';
      else if (r.cards) h += '<div class="box"><b style="font-family:var(--display);font-weight:400;font-size:20px">'+r.cards.length+' cards from “'+esc(r.name)+'”</b><span class="muted" style="font-size:12.5px">'+esc(apkgSummary(r))+'</span>'+
        r.cards.slice(0,7).map(c=>'<div class="mini2" style="display:flex;gap:9px;align-items:center">'+(c.img?'<img src="'+esc(c.img)+'" alt="" style="width:44px;height:34px;object-fit:cover;border-radius:6px;flex-shrink:0">':'')+'<span>'+esc(c.io?c.f+" (image occlusion)":c.f)+(c.b?'<br><span class="muted">→ '+esc(c.b.replace(/\n/g,"; "))+'</span>':'')+'</span></div>').join("")+
        (r.cards.length ? '<button class="abtn" data-act="useApkg">Use these '+r.cards.length+' cards</button>' : '')+'</div>';
      h += '<button class="abtn ghost" data-act="apkgPick">'+(r.cards?'Open a different file':'Choose an .apkg file')+'</button><button class="alink" data-act="go" data-v="decks">Back</button></div>'; break; }
    case "exam": {
      const ex = [[7,"In 1 week"],[14,"In 2 weeks"],[30,"In about a month"],[90,"In about 3 months"],[0,"No exam yet"]];
      h = '<div class="app">'+stepbar(2)+'<h3>Your exam &amp; your feed</h3><p class="muted">Cards get more frequent as the exam gets closer, and nothing gets scheduled past exam day.</p>'+ex.map(e=>choice("exam",e[0],e[1],"",UI.exam===e[0])).join("")+
        '<div class="sec">What\'s on your For You page?</div><p class="muted" style="font-size:12.5px">Cards borrow the look of what you watch. Pick any.</p><div class="vchips">'+VIBES.map(v=>'<button data-act="vibe" data-v="'+v+'" class="'+(UI.vibes.has(v)?"on":"")+'">'+esc(LABEL[v])+'</button>').join("")+'</div>'+
        '<div class="sec">How often should cards appear?</div>'+[["chill","Starts at 1 card every 8 reels, up to 60 a day"],["normal","Starts at 1 card every 5 reels, up to 150 a day (recommended)"],["cram","Starts at 1 card every 3 reels, up to 300 a day"]].map(m=>choice("intensity",m[0],m[0][0].toUpperCase()+m[0].slice(1),m[1],UI.intensity===m[0])).join("")+
        '<p class="muted" style="font-size:12px">It then learns your rhythm: fewer cards if you skip them, more if you answer.</p><button class="abtn" data-act="examNext">Next</button></div>'; break; }
    case "permission":
      h = '<div class="app">'+stepbar(3)+'<h3>Switch it on</h3><p class="muted">Reelcards needs to notice when you swipe to a new reel and draw a card on top. Android calls this an accessibility permission. It\'s the only one it asks for.</p>'+
        '<div class="box"><b>What it can and can\'t do</b><span class="muted" style="font-size:13px">It only reacts to swipes in TikTok, Instagram, YouTube, Snapchat, Facebook, X, Reddit and Pinterest. It never reads your messages, never saves what you watch, and nothing leaves your phone.</span></div>'+
        ['Tap “Open settings” below','Find Reelcards (often under “Installed apps”)','Turn it on, then tap Allow','Come back here. The app notices'].map((s,i)=>'<div class="num"><i>'+(i+1)+'</i><span>'+s+'</span></div>').join("")+
        '<button class="abtn" data-act="openSettings">Open settings</button><div class="box"><b>Switch greyed out?</b><span class="muted" style="font-size:13px">Android blocks this for apps installed from a file: App info → ⋮ → “Allow restricted settings”, then try again.</span></div></div>'+
        (UI.settingsOpen?'<div class="settings-sim"><div class="lbl">Simulated phone settings · Accessibility</div><div class="lbl" style="margin-top:8px">Installed apps</div><div class="row2"><span><b>Reelcards</b><br><small style="color:#6b6b75">'+(UI.permission?"On":"Off")+'</small></span><button class="switch'+(UI.permission?" on":"")+'" data-act="toggleSwitch" role="switch" aria-checked="'+UI.permission+'" aria-label="Reelcards accessibility switch"></button></div>'+
        (UI.permission?'<button class="abtn" data-act="backFromSettings" style="background:#2f6bff;color:#fff">Back to Reelcards</button>':'<p style="font-size:12.5px;color:#6b6b75;margin:0">Tap the switch to turn Reelcards on.</p>')+'</div>':''); break;
    case "try":
      h = '<div class="app">'+stepbar(4)+'<h3>It\'s on. Try it</h3><p class="muted">Tap below for a test card. Then scroll as usual: after a short warm-up, a card slips in every few reels, styled for the app you\'re in.</p>'+
        '<button class="abtn" data-act="testCard">Show me a test card</button><button class="abtn ghost" data-act="finishSetup">Open the feed</button></div>'; break;
    case "feed": h = feedHTML(); break;
    case "dash": h = dashHTML(); break;
  }
  scr.innerHTML = h;
  fitCards(scr);
  $("phone").classList.toggle("feedmode", UI.screen==="feed");
  if (anim) { scr.classList.remove("enter"); void scr.offsetWidth; scr.classList.add("enter"); }
  if (UI.toast) { const t = document.createElement("div"); t.className = "toast"; t.textContent = UI.toast; scr.appendChild(t); timers.push(setTimeout(()=>t.style.opacity="0", 2200)); UI.toast = ""; }
  startCardTimers();
}

function feedHTML(){
  const it = UI.current;
  if (!it) return tabs()+'<div class="reel plain-reel" style="background:#16161b"><div class="big">For you</div><div class="small">Swipe up to start scrolling</div></div>';
  if (it.kind === "reel") return tabs()+Render.plainReel(it.n, it.hue, UI.host);
  return tabs()+Render.card(it, Object.assign(cardOpts(), {coach: it.coach && !it.revealed && !it.liked && it.pick<0}));
}

/* Fit pass: if a card's content would spill out of the host app's safe zone, shrink it a step at a
   time (data-fit 1-5). Measured from layout boxes, so entry animations don't fool it. */
function fitCards(root){
  root.querySelectorAll(".card").forEach(card => {
    card.removeAttribute("data-fit");
    const body = card.querySelector(".body"); if (!body) return;
    // content height from the layout boxes vs the body's box (a sheet's box stops growing at its max-height)
    const over = () => { const k = [...body.children].filter(e => e.offsetParent !== null); if (!k.length) return false; const cs = getComputedStyle(body);
      const used = k[k.length-1].offsetTop + k[k.length-1].offsetHeight - k[0].offsetTop;
      return used > body.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom) + 2; };
    for (let f = 1; f <= 5 && over(); f++) card.setAttribute("data-fit", f);
    const m = card.querySelector(".msgs"); if (m) m.classList.toggle("over", m.scrollHeight > m.clientHeight + 2);
  });
}
if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { fitCards($("screen")); fitCards($("lab")); });

/* countdown ring + auto-caption beats, like a real reel's timing */
function startCardTimers(){
  const it = UI.current;
  if (UI.screen!=="feed" || !it || it.kind!=="card" || it.revealed) return;
  if (it.f.layout==="captions" && it.s.beats.length && (it.beat||0) < it.s.beats.length) {
    const words = it.s.beats[it.beat||0].split(" ").length;
    timers.push(setTimeout(()=>{ if (UI.current===it && !it.revealed) { it.beat = (it.beat||0)+1; renderPhone(false); } }, 380 + 330*words));
    return;
  }
  if (it.count == null) it.count = Render.thinkSecs(it.s);
  if (it.count > 0) timers.push(setTimeout(()=>{ if (UI.current===it && !it.revealed) { it.count--; const r = $("screen").querySelector(".ring"); if (r) r.textContent = it.count>0 ? it.count : "?"; startCardTimers(); } }, 1000));
}

function dashHTML(){
  const t = now(), k = E.dk(t), d = E.day(k), rr = E.recallRate(k,7), f = E.forecast(t);
  let fs = "At "+f.mins+" min of reels a day you'll see about "+f.perDay+" cards a day"+(f.capped?" (your daily limit)":"")+": about "+f.minsOnCards+" min of revision you barely notice.";
  if (f.newLeft>0) { fs += " "+f.newLeft+" cards not seen yet: all seen in about "+f.days+(f.days===1?" day.":" days."); if (f.ex>0) fs += f.onTrack ? " That's before your exam." : " Your exam is in "+f.ex+" days, so try Cram or about "+f.need+" min a day."; }
  else if (E.cards.length) fs += " You've seen every card. Now it's all reviews.";
  const n = 14; let max = 1; const vals = [];
  for (let i=0;i<n;i++){ const x = E.stats.days[k-(n-1-i)]; const v = x?[x.reviews,x.skips]:[0,0]; vals.push(v); max = Math.max(max, v[0]+v[1]); }
  const bars = vals.map((v,i)=>'<div><i style="height:'+(v[1]/max*64)+'px;background:#4a4c5a"></i><i style="height:'+(v[0]/max*64)+'px;background:var(--accent)"></i>'+((i%2===(n-1)%2)?'<span>'+new Date(t-(n-1-i)*DAY).getDate()+'</span>':'')+'</div>').join("");
  const decks = [...new Set(E.cards.map(c=>c.deck))].map(dk=>{ const cs = E.cards.filter(c=>c.deck===dk); const nw = cs.filter(c=>c.state===NEW).length, ld = cs.filter(c=>c.state===REVIEW).length, lg = cs.length-nw-ld, due = cs.filter(c=>c.state!==NEW&&c.due<=t).length;
    return '<div class="mini2"><b>'+esc(dk)+'</b><br><span class="muted">'+ld+' learned · '+lg+' learning · '+nw+' new · '+due+' due now</span></div>'; }).join("");
  const saved = E.cards.filter(c=>c.saved);
  const favs = RC.DIMS.map(dm=>{ const fv = E.bandit.favourite(dm); if (!fv) return ""; return '<div style="font-size:13px">'+RC.DIMLABEL[dm]+': <b>'+esc(LABEL[fv])+'</b> <span class="muted">('+Math.round(E.bandit.mean(dm,fv)*100)+'% held)</span></div>'; }).join("");
  const quiet = E.snoozeUntil > t ? " Quiet mode for a few minutes after lots of fast skips." : "";
  return tabs()+'<div class="app" style="padding-top:52px"><div class="status"><b>On</b><div class="muted" style="font-size:12.5px">Cards appear in TikTok, Instagram, YouTube Shorts, Snapchat, Facebook, X, Reddit and Pinterest.'+quiet+'</div></div>'+
    '<div class="sec">Today</div><div class="tiles"><div class="tile"><b>'+d.reviews+'</b><small>cards done</small></div><div class="tile"><b>'+d.skips+'</b><small>skipped</small></div><div class="tile"><b>'+(rr<0?"–":Math.round(rr*100)+"%")+'</b><small>remembered, last 7 days</small></div><div class="tile"><b>'+E.streak(k)+'</b><small>day streak</small></div></div>'+
    '<div class="box"><span class="steplbl">Forecast</span><span style="font-size:13px">'+esc(fs)+'</span></div>'+
    '<div class="box"><span class="steplbl">Rhythm</span><span style="font-size:13px">'+esc(E.rhythmText())+'</span>'+(E.calibration.describe()?'<span class="steplbl" style="margin-top:4px">Your memory</span><span style="font-size:13px">'+esc(E.calibration.describe())+'</span>':'')+'</div>'+
    '<div class="box"><span class="steplbl">Last 14 days · yellow = reviewed, grey = skipped</span><div class="bars">'+bars+'</div></div>'+
    (saved.length?'<div class="box"><span class="steplbl">Saved ('+saved.length+')</span>'+saved.slice(0,5).map(c=>'<div class="mini2">'+esc(c.f)+'</div>').join("")+'</div>':'')+
    '<div class="box"><span class="steplbl">Your cards</span>'+(decks||'<span class="muted">No cards yet.</span>')+'<button class="abtn ghost" data-act="addCards">Add cards</button></div>'+
    '<div class="box"><span class="steplbl">How often</span><div class="seg">'+["chill","normal","cram","demo"].map(m=>'<button data-act="setIntensity" data-v="'+m+'" class="'+(E.settings.intensity===m?"on":"")+'">'+m[0].toUpperCase()+m.slice(1)+'</button>').join("")+'</div>'+
    '<span style="font-size:13px">'+(E.settings.examDate?"Exam in "+E.examDaysLeft(t)+" days":"No exam date set")+' · Themes: '+(E.settings.vibes.length?E.settings.vibes.map(v=>LABEL[v]).join(", "):"none")+'</span></div>'+
    '<div class="box"><span class="steplbl">What the engine has learned about you</span>'+(favs||'<span class="muted" style="font-size:13px">Nothing yet. After a few cards it learns which looks you actually watch.</span>')+'</div>'+
    '<button class="abtn ghost" data-act="testCard">Show a test card</button><button class="abtn ghost" data-act="resetApp" style="'+(UI.confirmReset?"color:var(--bad)":"")+'">'+(UI.confirmReset?"Tap again to erase everything":"Reset everything")+'</button></div>';
}

/* ---------------- judge panels ---------------- */
function renderSide(){
  const t = now(), k = E.dk(t), due = E.dueCount(t), it = UI.current;
  const tot = key => Object.values(E.stats.days).reduce((a,d)=>a+d[key],0);
  $("stats").innerHTML = [["Day",UI.firstDay==null?1:k-UI.firstDay+1],["Exam in",E.settings.examDate?E.examDaysLeft(t)+" d":"–"],["Reels",tot("reels")],["Cards shown",tot("cards")],["Due now",due],
    ["Next card",!UI.onboarded?"–":it&&it.kind==="card"?"now":E.lastDecision&&E.lastDecision.blocked?"paused":E.lastDecision?"~"+Math.max(0,E.lastDecision.gap-E.reelsSinceCard)+" reels":"–"]]
    .map(s=>'<div class="stat"><b>'+s[1]+'</b><small>'+s[0]+'</small></div>').join("");
  const y = UI.why; let w = "";
  if (!y) w = '<div><dt>–</dt><dd class="muted">Finish setup and swipe the feed. The first card comes after a short warm-up.</dd></div>';
  else {
    if (y.prev) w += '<div><dt>LAST</dt><dd>'+esc(y.prev)+'</dd></div>';
    if (y.noCard) w += '<div><dt>REEL</dt><dd>'+esc(y.noCard)+'</dd></div>';
    if (y.show) { const sh = y.show;
      w += '<div><dt>WHEN</dt><dd>'+sh.why.when.map(esc).join("<br>")+'</dd></div><div><dt>WHICH</dt><dd><b>'+esc(sh.card.f)+'</b><br><span class="muted">'+esc(sh.why.which)+'</span></dd></div>'+
        '<div><dt>LOOK</dt><dd>'+RC.DIMS.map(d=>RC.DIMLABEL[d]+': <b>'+esc(LABEL[sh.f[d]])+'</b> <span class="muted mono">'+esc(sh.detail[d]||"")+'</span>').join("<br>")+
        '<br>App: <b>'+esc(LABEL[sh.host]||sh.host)+'</b> (layout, rail and caption band follow its safe zones)'+(sh.s.vibe?'<br>Themed for: <b>'+esc(LABEL[sh.s.vibe])+'</b>':'')+(sh.explored?'<br><span class="muted">Explored a random '+RC.DIMLABEL[sh.explored].toLowerCase()+' (about 1 in 10 cards do).</span>':'')+'</dd></div>'+
        '<div><dt>WORDS</dt><dd class="muted">Written for the hook: “'+esc(sh.s.hook)+'”. Hint ready: '+esc(sh.s.hint)+'.</dd></div>'; }
  }
  $("why").innerHTML = w;
  $("dims").innerHTML = RC.DIMS.map(d=>'<div class="dim"><h3>'+RC.DIMLABEL[d]+'</h3>'+PIECES[d].map(p=>{ const s = E.bandit.sc(d+":"+p), m = s[0]/(s[0]+s[1]), used = UI.lastShow && UI.lastShow.f[d]===p;
    return '<div class="piece'+(used?' used':'')+'"><span class="nm">'+esc(LABEL[p])+'</span><span class="bar"><i style="width:'+Math.round(m*100)+'%"></i></span><span class="ct">'+Math.round(m*100)+'%</span></div>'; }).join("")+'</div>').join("");
  const per = HOSTS.map(([h,l])=>{ let best=null,bm=-1; for (const p of PIECES.layout){ const c = E.bandit.scores[h+"|layout:"+p]; if(!c||c[0]+c[1]<4)continue; const m=c[0]/(c[0]+c[1]); if(m>bm){bm=m;best=p;} } return l+": "+(best?LABEL[best]:"not enough data"); });
  $("perapp").textContent = "Favourite layout per app → " + per.join(" · ");
  const base = E.I().gap, arms = RC.MULT.map((m,i)=>{ const a=E.rhythm.a[i]||1,b=E.rhythm.b[i]||1; return {gap:Math.max(E.isDemo()?1:2,Math.round(base*m)), v:a/(a+b)/(base*m+1), on:i===E.rhythm.arm}; });
  const mx = Math.max(...arms.map(a=>a.v), .0001);
  $("arms").innerHTML = arms.map(a=>'<div class="'+(a.on?"on":"")+'" title="1 card every '+a.gap+' reels"><i style="height:'+Math.round(a.v/mx*70)+'px"></i><span>1/'+a.gap+'</span></div>').join("");
  $("rhythmText").innerHTML = esc(E.rhythmText())+'<br><span class="muted">Bars: expected answered cards per reel scrolled at each rhythm (Thompson sampling). Yellow = the rhythm in use.</span>';
  const cal = E.calibration.describe();
  $("memText").innerHTML = 'Scheduler: <b>FSRS-6</b> with its 21 default parameters (the algorithm Anki uses). Target recall 90%, rising to 92% three weeks before the exam and 95% in the final week.<br>'+
    'Personal calibration: '+(cal?esc(cal)+' (×'+E.calibration.scale+')':'waits for 100 long-term reviews ('+E.calibration.log.length+' so far) before adjusting.')+'<br>Grading uses response time: a fast “knew it” is Easy, a slow one Good, a hint caps it at Hard.';
  const rev = tot("reviews"), skips = tot("skips"), shown = tot("cards"), rr = E.recallRate(k,7);
  $("kpis").innerHTML = [[rev,"cards reviewed"],[E.day(k).reviews,"reviewed today"],[rr<0?"–":Math.round(rr*100)+"%","remembered (not Again)"],[tot("easy")+"/"+tot("good")+"/"+tot("hard")+"/"+tot("again"),"Easy / Good / Hard / Again"],[skips,"skipped"],[tot("fastSkips"),"fast skips (<1 s)"],[shown?Math.round(skips/shown*100)+"%":"–","skip rate"],[E.cards.filter(c=>c.saved).length,"saved cards"],[E.stats.avgReel.toFixed(1)+"s","avg time per reel"]]
    .map(x=>'<div class="kpi"><b>'+x[0]+'</b><small>'+x[1]+'</small></div>').join("");
  const f = E.forecast(t);
  $("forecast").innerHTML = 'At <b>'+f.mins+' min</b> of reels a day, with a card every '+f.gap+' reels: about <b>'+f.perDay+' cards a day</b>'+(f.capped?' (daily cap)':'')+', ~'+f.minsOnCards+' min of actual revision. '+(f.newLeft?f.newLeft+' unseen cards at ~'+f.newPer+' new a day → all seen in <b>'+f.days+' days</b>'+(f.ex>0?(f.onTrack?' <span style="color:var(--good)">(before the exam)</span>':' <span style="color:var(--bad)">(after the exam: needs ~'+f.need+' min a day)</span>'):'')+'.':'Every card has been seen.');
  $("skiplayout").innerHTML = PIECES.layout.map(l=>{ const v = E.stats.layout[l]||[0,0], r = v[0]?v[1]/v[0]:0; return '<div class="piece"><span class="nm">'+esc(LABEL[l])+'</span><span class="bar"><i style="width:'+Math.round(r*100)+'%;background:var(--bad)"></i></span><span class="ct">'+(v[0]?Math.round(r*100)+"% of "+v[0]:"–")+'</span></div>'; }).join("");
  const top = E.cards.filter(c=>c.skips>0).sort((a,b)=>b.skips-a.skips).slice(0,5);
  $("skipcards").innerHTML = top.length ? top.map(c=>'<div class="piece" style="grid-template-columns:minmax(0,1fr) 40px"><span>'+esc(c.f)+'</span><span class="ct">'+c.skips+'×</span></div>').join("") : '<span class="muted">No skips yet.</span>';
  const order = E.cards.slice().sort((a,b)=>(a.state===NEW)-(b.state===NEW)||a.due-b.due);
  $("deck").innerHTML = order.length ? order.map(c=>{ const isDue = c.state!==NEW&&c.due<=t; const st = c.state===NEW?'<span class="chip">new</span>':isDue?'<span class="chip due">due</span>':'<span class="chip">'+STATE[c.state]+'</span>';
    return '<tr><td>'+(c.saved?"★ ":"")+esc(c.f)+'</td><td>'+esc(c.deck)+'</td><td>'+st+'</td><td class="num">'+(c.state===NEW?"–":Math.round(E.fsrs.recallNow(c,t)*100)+"%")+'</td><td class="num">'+(c.state===NEW?"–":RC.human(c.s))+'</td><td class="num">'+(c.state===NEW?"–":(c.due<=t?"due now":"in "+RC.human((c.due-t)/DAY)))+'</td><td>'+(c.grade?'<span class="chip '+c.grade+'">'+c.grade+'</span>':'–')+'</td><td class="num">'+c.skips+'</td></tr>'; }).join("") : '<tr><td colspan="8" class="muted">No cards yet. Finish step 1 on the phone.</td></tr>';
  $("anki").innerHTML = E.anki.length ? E.anki.map(a=>'<div><span class="chip '+a.g+'" style="margin-right:6px">'+a.g+'</span>'+esc(a.line)+'</div>').join("") : '<span class="muted">Nothing graded yet.</span>';
  $("hint").hidden = UI.onboarded; $("gestures").hidden = !UI.onboarded;
  $("hostbar").innerHTML = HOSTS.map(([h,l])=>'<button data-host="'+h+'" class="'+(UI.host===h?"on":"")+'" aria-pressed="'+(UI.host===h)+'">'+(UI.host===h?"Scrolling in ":"")+l+'</button>').join("");
}
function render(anim){ renderPhone(anim); renderSide(); saveSoon(); }

/* ---------------- feed actions ---------------- */
function go(s){ UI.screen = s; UI.err = ""; render(true); }
function finishSetup(){ UI.onboarded = true; if (UI.firstDay==null) UI.firstDay = E.dk(now()); UI.screen = "feed"; render(true); }
function applyExam(){ E.settings.examDate = UI.exam ? now() + UI.exam*DAY : 0; E.settings.intensity = UI.intensity; E.setVibes([...UI.vibes]); }
function skipSetup(){ if (!E.cards.length) { E.addCards(deckCards("GCSE Biology")); E.addCards(deckCards("GCSE Chemistry")); E.addCards(deckCards("GCSE Science: longer answers")); sampleDeck().then(r => { E.addCards(r.cards); renderSide(); }, () => {}); } UI.exam = 14; if (!UI.vibes.size) { UI.vibes.add("football"); UI.vibes.add("gaming"); } applyExam(); UI.permission = true; finishSetup(); }
function outcomeOf(it, dwell){ return {revealed:it.revealed, liked:it.liked, likedBefore:it.likedBefore, pick:it.pick, likeAt:it.likeAt||0, pickAt:it.pickAt||0, hintAt:it.hintAt||0, saved:!!it.saved, shared:!!it.shared,
  got:(it.got||[]).filter(Boolean).length, dwell}; }
function asItem(sh){ return Object.assign({kind:"card", revealed:false, liked:false, likedBefore:false, pick:-1, saved:false, hinted:false, beat:0, count:null, got:[], moreOpen:false, settled:false, shownAt:performance.now()}, sh); }

function finishCurrent(dwell){
  const cur = UI.current; if (!cur || cur.kind!=="card") return null;
  cur.done = true; hush();
  return E.onCardFinished(cur, outcomeOf(cur, dwell!=null?dwell:performance.now()-cur.shownAt), now()).summary;
}
function showItem(sh, prev){ UI.current = asItem(sh); if (UI.coachLeft > 0) { UI.current.coach = true; UI.coachLeft--; } UI.lastShow = sh; UI.why = {show:sh, prev}; speak(sh.s.voice); }
function swipe(dwell){
  if (!UI.onboarded) return;
  UI.screen = "feed";
  const prev = finishCurrent(dwell);
  if (UI.current && UI.current.kind==="reel") UI.lastHue = UI.current.hue;
  UI.reelN++;
  const sh = E.onReelSwipe(now(), UI.host);
  if (sh) showItem(sh, prev);
  else { UI.current = {kind:"reel", n:UI.reelN, hue:(UI.reelN*47)%360}; const d = E.lastDecision; UI.why = {prev, noCard:"Reel "+UI.reelN+": no card. "+(d.blocked||d.reasons.join(" · "))}; }
  render(true);
}
function testCard(){
  if (!E.cards.length) E.addCards(deckCards("GCSE Biology"));
  if (!UI.onboarded) finishSetup();
  const prev = finishCurrent();
  const sh = E.forceShow(now(), UI.host); if (sh) showItem(sh, prev);
  UI.screen = "feed"; render(true);
}
function burst(x,y){ const h = document.createElement("div"); h.className = "bigheart"; h.style.left = (x==null?"50%":x+"px"); h.style.top = (y==null?"45%":y+"px"); h.innerHTML = UI.host==="youtube"?Render.I.thumb(true):Render.I.heart(true); $("screen").appendChild(h); setTimeout(()=>h.remove(), 700); }
function el(){ return UI.current && UI.current.kind==="card" ? performance.now()-UI.current.shownAt : 0; }
function like(x,y){
  const it = UI.current; if (!it || it.kind!=="card" || UI.screen!=="feed") return;
  if (it.liked) { burst(x,y); return; }
  it.liked = true; it.likeAt = el(); it.likedBefore = !it.revealed && it.pick<0; if (it.revealed) it.settled = true;
  if (it.s.vibe==="gaming" && (it.pick<0 || it.pick===it.s.correct)) { const xp = document.createElement("div"); xp.className="xp"; xp.textContent="+25 XP"; timers.push(setTimeout(()=>{ const c=$("screen").querySelector(".card"); c&&c.appendChild(xp); },10)); }
  renderPhone(false); burst(x,y);
  if (it.likedBefore && it.f.layout!=="quiz") setTimeout(()=>{ if (UI.current===it && !it.revealed) { it.revealed = true; it.revealAt = el(); renderPhone(false); } }, 600);
}
/* an Anki card's sound: one at a time */
let playing = null;
function playSound(url){
  if (playing) { playing.pause(); playing = null; }
  if (!url) return;
  if (url.startsWith("tts:")) { // Anki text-to-speech: the browser's own voice, in the card's language
    try { const i = url.indexOf(":", 4), u = new SpeechSynthesisUtterance(url.slice(i + 1)); u.lang = url.slice(4, i).replace("_", "-"); speechSynthesis.cancel(); speechSynthesis.speak(u); } catch (e) {}
    return;
  }
  playing = new Audio(url);
  playing.play().catch(() => {}); // a browser may refuse until the page has been tapped
}
function reveal(){
  const it = UI.current; if (!it || it.kind!=="card") return;
  if (it.moreOpen) { it.moreOpen = false; renderPhone(false); return; }
  if (it.revealed) { if (!it.settled) { it.settled = true; renderPhone(false); } return; } // a tap shows the rest of the points now
  if (it.f.layout==="quiz"&&it.s.correct>=0) return;
  it.revealed = true; it.revealAt = el(); renderPhone(false);
  if (it.s.asnd) playSound(it.s.asnd);
  const total = Render.ptDelays(it.s).pop() + 600; timers.push(setTimeout(()=>{ if (UI.current===it) it.settled = true; }, total));
}
/* self-marking: tap a point you got. Unticking a point on a card you'd liked turns it into part marks */
function tick(i){
  const it = UI.current; if (!it || it.kind!=="card" || !it.revealed || !it.s.points || it.s.points.length < 2) return;
  if (it.liked) { it.got = it.s.points.map(()=>true); it.liked = false; it.likedBefore = false; }
  it.got[i] = !it.got[i]; it.settled = true; renderPhone(false);
}
function toast(msg){ UI.toast = msg; renderPhone(false); }

let tapTimer = null;
$("screen").addEventListener("click", e => {
  const b = e.target.closest("[data-act]");
  if (b) {
    const a = b.dataset.act, v = b.dataset.v, it = UI.current;
    switch (a) {
      case "go": if (v==="notes") { UI.notesResult = null; UI.aiCopied = null; } go(v); return;
      case "copyPrompt": { UI.notesText = $("ntext").value; const done = ok => { UI.aiCopied = ok; renderPhone(false); };
        try { navigator.clipboard.writeText(AI_PROMPT).then(() => done(true), () => done(false)); } catch (err) { done(false); } return; }
      case "tab": UI.screen = v; UI.confirmReset = false; render(true); return;
      case "deck": UI.decks.has(v) ? UI.decks.delete(v) : UI.decks.add(v); renderPhone(false); return;
      case "decksNext": { for (const n of UI.decks) E.addCards(deckCards(n)); E.addCards(UI.notes); E.addCards(UI.apkgCards); keepFile(UI.apkgFile, UI.apkgCards); UI.decks.clear(); UI.notes = []; UI.apkgCards = []; UI.apkgFile = null;
        if (!E.cards.length) { UI.err = "Pick at least one deck, or paste some notes."; renderPhone(false); return; }
        if (UI.addMode) { UI.addMode = false; UI.screen = "dash"; render(true); } else go("exam"); return; }
      case "exampleNotes": $("ntext").value = EXAMPLE_NOTES; return;
      case "makeCards": UI.notesText = $("ntext").value; UI.notesDeck = $("ndeck").value.trim() || "My notes"; UI.notesResult = parseNotes(UI.notesText, UI.notesDeck); renderPhone(false); return;
      case "useNotes": UI.notes = UI.notesResult.cards; UI.notesText = ""; UI.notesResult = null; go("decks"); return;
      case "apkg": case "apkgPick": $("apkgFile").value = ""; $("apkgFile").click(); return;
      case "apkgSample": UI.apkg = {busy:true, name:"Sample Anki deck.apkg"}; go("apkg"); sampleDeck().then(r => { UI.apkg = r; renderPhone(false); }, e => { UI.apkg = {error: e.message}; renderPhone(false); }); return;
      case "useApkg": UI.apkgCards = UI.apkg.cards; UI.apkgFile = UI.apkg.src === "sample" ? null : UI.apkg; UI.apkg = null; go("decks"); return;
      case "exam": UI.exam = +v; renderPhone(false); return;
      case "vibe": UI.vibes.has(v) ? UI.vibes.delete(v) : UI.vibes.add(v); renderPhone(false); return;
      case "intensity": UI.intensity = v; renderPhone(false); return;
      case "examNext": applyExam(); go(UI.permission ? "try" : "permission"); return;
      case "openSettings": UI.settingsOpen = true; renderPhone(false); return;
      case "toggleSwitch": UI.permission = !UI.permission; renderPhone(false); return;
      case "backFromSettings": UI.settingsOpen = false; go("try"); return;
      case "testCard": testCard(); return;
      case "finishSetup": finishSetup(); return;
      case "addCards": UI.addMode = true; go("decks"); return;
      case "setIntensity": E.settings.intensity = v; renderPhone(false); renderSide(); return;
      case "resetApp": if (!UI.confirmReset) { UI.confirmReset = true; renderPhone(false); return; } resetAll(); render(true); return;
      case "like": { const r = b.getBoundingClientRect(), p = $("screen").getBoundingClientRect(); like(r.left-p.left+r.width/2, r.top-p.top+r.height/2); return; }
      case "reveal": reveal(); return;
      case "sound": if (it && it.s) playSound(v === "a" ? it.s.asnd : it.s.snd); return;
      // a tap on a point that hasn't dropped in yet just shows the rest; it doesn't mark it
      case "tick": if (it && !it.settled && parseFloat(getComputedStyle(b).opacity) < .99) { it.settled = true; renderPhone(false); return; } tick(+v); return;
      case "more": if (it) { it.moreOpen = !it.moreOpen; it.settled = true; renderPhone(false); } return;
      case "pick": if (it && it.pick<0) { it.pick = +v; it.pickAt = el(); it.revealed = true; if (it.s.vibe==="gaming" && it.pick===it.s.correct) { UI.toast = "+25 XP"; } renderPhone(false); } return;
      case "hint": if (it && !it.hinted && !it.revealed && it.pick<0) { it.hinted = true; it.hintAt = el(); renderPhone(false); } else if (it) toast(it.revealed?"Answer's already showing":"Hint's up there ↑"); return;
      case "save": if (it && !it.saved) { it.saved = true; toast("Saved: it'll come back within a day"); } return;
      case "share": if (it) { it.shared = true; const txt = "Can you answer this? “"+it.s.q+"” (answer: tap to see) · made with Reelcards";
        try { navigator.clipboard.writeText(txt).then(()=>toast("Copied: paste it to a friend"), ()=>toast("Share: "+it.s.q.slice(0,30)+"…")); } catch(err) { toast("Share: "+it.s.q.slice(0,30)+"…"); } } return;
    }
    return;
  }
  if (e.target.closest("[data-card]")) { clearTimeout(tapTimer); tapTimer = setTimeout(reveal, 260); }
});
$("screen").addEventListener("dblclick", e => { if (!e.target.closest("[data-card]") || e.target.closest("[data-act]")) return; clearTimeout(tapTimer); const p = $("screen").getBoundingClientRect(); like(e.clientX-p.left, e.clientY-p.top); });
$("apkgFile").addEventListener("change", e => {
  const f = e.target.files && e.target.files[0]; if (!f) return;
  UI.apkg = {busy:true, name:f.name}; go("apkg");
  f.arrayBuffer().then(b => importApkg(new Uint8Array(b), f.name, "file:" + f.name)).then(r => { UI.apkg = r; renderPhone(false); }, err => { UI.apkg = {error: "Couldn't read that file: " + err.message, name: f.name}; renderPhone(false); });
});
$("screen").addEventListener("input", e => { if (e.target.id==="ntext") UI.notesText = e.target.value; if (e.target.id==="ndeck") UI.notesDeck = e.target.value; });
$("hostbar").addEventListener("click", e => { const b = e.target.closest("[data-host]"); if (!b) return; UI.host = b.dataset.host; if (UI.current && UI.current.kind==="card") UI.current.host = UI.host; render(false); });

let busy = false, ty = null;
$("phone").addEventListener("wheel", e => { if (UI.screen!=="feed") return; e.preventDefault(); if (busy || Math.abs(e.deltaY) < 20) return; busy = true; swipe(); setTimeout(()=>busy=false, 450); }, {passive:false});
$("phone").addEventListener("touchstart", e => { ty = UI.screen==="feed" ? e.touches[0].clientY : null; }, {passive:true});
$("phone").addEventListener("touchend", e => { if (ty==null) return; const dy = e.changedTouches[0].clientY - ty; ty = null; if (dy < -50) swipe(); }, {passive:true});
document.addEventListener("keydown", e => { const tag = document.activeElement.tagName; if ((e.key==="ArrowDown"||e.key==="j") && !["SELECT","INPUT","TEXTAREA"].includes(tag) && UI.screen==="feed") { e.preventDefault(); swipe(); } });
$("swipe").onclick = () => { if (auto) return; if (!UI.onboarded) { skipSetup(); return; } swipe(); };
$("skipSetup").onclick = () => skipSetup();
$("nextday").onclick = () => { if (auto) return; if (!UI.onboarded) skipSetup(); const prev = finishCurrent(3000); const t = now(); const nx = new Date(t); nx.setDate(nx.getDate()+1); nx.setHours(8,0,0,0); offset += nx.getTime()-t; E.lastSwipeAt = 0; UI.current = null; UI.why = {prev:(prev?prev+" · ":"")+"Jumped to 8:00 tomorrow. New session."}; render(true); };
$("reset").onclick = () => { if (auto) return; resetAll(); render(true); };
$("mins").oninput = e => { E.settings.minutesPerDay = +e.target.value; $("minsOut").textContent = e.target.value+" min"; renderSide(); if (UI.screen==="dash") renderPhone(false); };
["night","tint"].forEach(id => $(id).addEventListener("change", () => { if (UI.screen==="feed") renderPhone(false); renderLab(); }));

/* ---------------- simulated student ---------------- */
const TASTE = {quiz:{"layout:quiz":.9,"hook:mostwrong":.85,"hook:rate":.6,"layout:comment":.5}, chat:{"layout:chat":.9,"hook:text2am":.9,"layout:comment":.8,"hook:pov":.6}, gamer:{"bg:gameplay":.95,"layout:split":.75,"hook:waitforit":.65,"layout:captions":.6}, random:{}};
let auto = null, sinceCard = 0;
function simAct(){
  const it = UI.current; if (!it || it.kind!=="card") { sinceCard++; return null; }
  const P = TASTE[$("persona").value]; let n = 0, sum = 0; for (const d of RC.DIMS) { const v = P[d+":"+it.f[d]]; if (v!=null) { sum += v; n++; } }
  let p = n ? sum/n*.9+.05 : ($("persona").value==="random" ? .55 : .18);
  const tolerance = 7; if (sinceCard+1 < tolerance) p *= .55 + .45*(sinceCard+1)/tolerance; // cards too close together annoy
  sinceCard = 0;
  if (Math.random() > p) return 300 + Math.random()*700;
  const c = it.card, known = c.state===NEW ? .2 : Math.min(.95, .35 + .6*E.fsrs.recallNow(c, now())), knows = Math.random() < known;
  const read = 1500 + 45*c.f.length;
  if (it.f.layout==="quiz" && it.s.correct>=0) { it.revealed = true; it.pick = knows || Math.random()<.5 ? it.s.correct : 1-it.s.correct; it.pickAt = read*(knows?.8:1.6); }
  else if (knows) { if (Math.random()<.5) { it.liked = true; it.likedBefore = true; it.likeAt = read*(Math.random()<.7?.7:1.8); } else { it.revealed = true; it.liked = true; it.revealAt = read; it.likeAt = read+900; } }
  else { if (Math.random()<.25) { it.hinted = true; it.hintAt = read*.6; } it.revealed = true; it.revealAt = read*1.2;
    if (it.s.points.length >= 2) it.got = it.s.points.map(()=>Math.random() < known + .25); // long answers: often partly right
    else if (it.hinted && Math.random()<.5) { it.liked = true; it.likeAt = read*1.5; } }
  if (Math.random()<.04) it.saved = true;
  return 3000 + Math.random()*4000;
}
function quietSwipe(dwell){
  const prev = finishCurrent(dwell!=null?dwell:1000);
  if (UI.current && UI.current.kind==="reel") UI.lastHue = UI.current.hue;
  UI.reelN++; const sh = E.onReelSwipe(now(), UI.host);
  if (sh) { UI.current = asItem(sh); UI.lastShow = sh; UI.why = {show:sh, prev}; }
  else { UI.current = {kind:"reel", n:UI.reelN, hue:(UI.reelN*47)%360}; const d = E.lastDecision; UI.why = {prev:prev||(UI.why&&UI.why.prev), noCard:"Reel "+UI.reelN+": no card. "+(d.blocked||d.reasons.join(" · "))}; }
}
function runAuto(total, perDayMins){
  if (auto) return; if (!UI.onboarded) skipSetup(); UI.screen = "feed"; hush();
  let left = total, dayLeft = perDayMins ? perDayMins*60 : Infinity;
  ["auto","autoWeek","swipe","nextday","reset"].forEach(id=>$(id).disabled=true);
  const step = () => {
    for (let i=0; i<(perDayMins?30:1); i++) {
      let dwell = simAct(); const isCard = dwell!=null; if (!isCard) dwell = (Math.random()<.1 ? 30+Math.random()*30 : 4+Math.random()*12)*1000;
      offset += dwell; dayLeft -= dwell/1000; quietSwipe(isCard?dwell:null);
      if (perDayMins && dayLeft<=0) { left--; dayLeft = perDayMins*60; if (left>0) { const t = now(); const nx = new Date(t); nx.setDate(nx.getDate()+1); nx.setHours(8,0,0,0); offset += nx.getTime()-t; E.lastSwipeAt = 0; } break; }
      if (!perDayMins) left--;
      if (left<=0) break;
    }
    render(false);
    if (left<=0) { clearInterval(auto); auto = null; ["auto","autoWeek","swipe","nextday","reset"].forEach(id=>$(id).disabled=false); }
  };
  auto = setInterval(step, perDayMins?25:70);
}
$("auto").onclick = () => runAuto(80, 0);
$("autoWeek").onclick = () => runAuto(7, 60);

/* ---------------- design lab ---------------- */
let labSeed = 1, labAnki = null, labFile = null, labPage = 0;
function renderLab(){
  const host = $("labHost").value, vibe = $("labVibe").value, state = $("labState").value, night = $("labNight").checked, long = $("labCards").value==="long";
  const sci = deckCards("GCSE Science: longer answers"), pool = deckCards("GCSE Biology").concat(deckCards("GCSE Chemistry"), sci);
  let s = labSeed; const rnd = () => { s = (s*16807)%2147483647; return (s-1)/2147483646; };
  const looks = PIECES.look, hooks = PIECES.hook;
  const vibeBg = {football:"pitch",gaming:"gameplay",beauty:"glow",music:"equalizer",fitness:"citywalk",food:"satisfying",comedy:"plain"}[vibe];
  // long mode: list, steps, paragraphs and long questions, in every layout their shape allows
  const anki = $("labCards").value === "anki";
  if (anki && !labAnki) { $("lab").innerHTML = '<p class="muted">Reading the sample Anki deck…</p>'; sampleDeck().then(r => { labAnki = r.cards; renderLab(); }); return; }
  const byId = id => (labAnki || []).find(c => c.id === "apkg:" + id);
  // your own file: 8 cards at a time, each in a layout its shape allows (Shuffle looks cycles them)
  const file = $("labCards").value === "file" && labFile, from = file ? labPage*8 % labFile.cards.length : 0;
  $("labMore").hidden = !file || labFile.cards.length <= 8;
  const cards = file ? labFile.cards.slice(from, from+8) : anki ? [3010, 3012, 3101, 3006, 3008, 3007, 3003, 3002].map(byId) : long ? [5,3,4,0,12,2,8,11].map(i=>sci[i]) : [3,14,0,9,20,1,5,16].map(i=>pool[i%pool.length]);
  const layouts = file ? cards.map((c,i) => { const ok = RC.layoutsFor(RC.analyse(c), true); return ok[(i+labSeed) % ok.length]; })
    : anki ? ["bigtext","bigtext","bigtext","bigtext","list","quiz","bigtext","chat"] : long ? ["list","bigtext","chat","captions","comment","list","bigtext","chat"] : ["bigtext","captions","chat","comment","split","quiz","bigtext","captions"];
  if (anki) pool.push(...labAnki);
  if (file) pool.push(...labFile.cards);
  let html = "";
  layouts.forEach((lay,i)=>{
    const f = {hook:hooks[(i+labSeed)%hooks.length], layout:lay, look:looks[(i+labSeed)%looks.length], bg:vibeBg||PIECES.bg[(i*3+labSeed)%PIECES.bg.length]};
    const c = cards[i];
    let sc = RC.write(c, f, pool, rnd, "due today", vibe?[vibe]:[]);
    if (lay==="quiz" && sc.correct<0) { if (!file) return; f.layout = lay = "bigtext"; sc = RC.write(c, f, pool, rnd, "due today", vibe?[vibe]:[]); } // no wrong answer to offer
    if (vibe && !sc.vibe) sc.vibe = vibe;
    const shown = state!=="q" && state!=="hint", n = sc.points.length;
    const got = state==="part" && n>=2 ? sc.points.map((_,k)=>k<n-1) : [];
    const it = {kind:"card", card:c, f, s:sc, host, revealed:shown, liked:state==="liked", likedBefore:false, pick:(lay==="quiz"&&shown)?(state==="part"?1-sc.correct:sc.correct):-1, saved:false,
      hinted:state==="hint", hintAt:state==="hint"?1:0, beat:lay==="captions"&&!shown?(state==="hint"?sc.beats.length:1):sc.beats.length, count:2, got, moreOpen:state==="more", settled:true};
    html += '<figure><div class="mini"><div class="scaler">'+Render.card(it,{night})+'</div></div><figcaption><b>'+esc(LABEL[lay])+'</b> · '+esc(LABEL[f.look])+(sc.io?' · image occlusion':sc.cloze?' · cloze':sc.img?' · picture':'')+(n>=2?' · '+n+' points ('+esc(sc.shape)+')':'')+(sc.ctx?' · long question':'')+'<br>'+esc(LABEL[f.bg])+' · hook “'+esc(LABEL[f.hook])+'”'+(file?'<br>Card '+(from+i+1)+' of '+labFile.cards.length:'')+'</figcaption></figure>';
  });
  $("lab").innerHTML = html;
  fitLab(); fitCards($("lab"));
}
function fitLab(){ document.querySelectorAll(".lab .mini").forEach(m => { const sc = m.querySelector(".scaler"); if (sc) sc.style.transform = "scale(" + (m.clientWidth/360) + ")"; }); }
VIBES.forEach(v => { const o = document.createElement("option"); o.value = v; o.textContent = LABEL[v]; $("labVibe").appendChild(o); });
["labCards","labHost","labVibe","labState","labNight"].forEach(id => $(id).addEventListener("change", renderLab));
$("labShuffle").onclick = () => { labSeed = labSeed % 30 + 1; renderLab(); };
$("labOpen").onclick = () => { $("labFile").value = ""; $("labFile").click(); };
$("labMore").onclick = () => { labPage++; renderLab(); };
$("labFile").addEventListener("change", e => {
  const f = e.target.files && e.target.files[0]; if (!f) return;
  $("lab").innerHTML = '<p class="muted">Reading '+esc(f.name)+'…</p>';
  f.arrayBuffer().then(b => importApkg(new Uint8Array(b), f.name)).then(r => {
    if (!r.cards.length) throw new Error("no cards Reelcards can show");
    labFile = r; labPage = 0; $("labFileOpt").disabled = false; $("labFileOpt").textContent = f.name; $("labCards").value = "file"; renderLab();
  }).catch(err => { $("lab").innerHTML = '<p class="err">Couldn\'t read '+esc(f.name)+': '+esc(err.message)+'</p>'; });
});
window.addEventListener("resize", fitLab);

if (!restore()) fresh(); render(true); if (!APP) renderLab(); installOffer();
})();
