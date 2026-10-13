/* Reelcards Feed: a separate page that scrolls a list of YouTube Shorts / TikTok links the student pastes,
   with their flashcards slipping in between, drawn like a reel. It works in any browser, including Chrome
   on an iPhone, where extensions can't run.

   Unlike the rest of Reelcards this page DOES contact other companies: the videos are loaded from YouTube
   (its privacy-enhanced domain) or TikTok in embedded players, so they see the device's IP address and may
   set their own cookies. The page says so and asks first (see PRIVACY.md). Everything else stays on the
   device: cards, answers and the pasted links are kept in this browser's localStorage and are never sent to us.

   Safety rules for the pasted links: only https/http links to a short list of hosts are accepted, the video
   id must match a strict pattern, and nothing else from the link is ever used; the page's Content Security
   Policy only allows frames from those two hosts and runs only this script (by hash). */
"use strict";
(function(){
const $ = id => document.getElementById(id);
const esc = Render.esc;
const DAY = 864e5;
const KEY_CFG = "reelcards-feed-config", KEY_STATE = "reelcards-feed-state", KEY_OK = "reelcards-feed-consent";
const CONSENT_V = 1, MAX_LINKS = 40, MAX_LINK_LEN = 300, MAX_NOTES = 20000;
const store = {
  get(k){ try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } },
  set(k, v){ try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) {} },
  del(k){ try { localStorage.removeItem(k); } catch (e) {} }
};
const PRE = {"GCSE Biology":"bio","GCSE Chemistry":"chem","GCSE Spanish":"es","GCSE Science: longer answers":"sci"};
const deckCards = name => DECKS[name].map((r, i) => makeCard(PRE[name] + "-" + (i+1), name, r[0], r[1], r[2]));
const INTENSITY = [["chill","Relaxed: a card every so often"],["normal","Normal"],["cram","Cram: more cards"],["demo","Demo: a card every 2 videos"]];

/* ---------------- the pasted links ---------------- */
const YT_ID = /^[A-Za-z0-9_-]{11}$/, TT_ID = /^\d{8,25}$/;
/* one link -> {k:"yt"|"tt", id} or null. Only these hosts and these shapes; everything else is refused. */
function parseLink(raw){
  raw = String(raw == null ? "" : raw).trim();
  if (!raw || raw.length > MAX_LINK_LEN) return null;
  if (!/^https?:\/\//i.test(raw)) raw = "https://" + raw;
  let u; try { u = new URL(raw); } catch (e) { return null; }
  if ((u.protocol !== "https:" && u.protocol !== "http:") || u.username || u.password) return null;
  const h = u.hostname.toLowerCase().replace(/^(www|m)\./, ""), parts = u.pathname.split("/").filter(Boolean);
  if (h === "youtube.com") {
    const id = parts[0] === "shorts" ? parts[1] : parts[0] === "watch" ? u.searchParams.get("v") : null;
    return id && YT_ID.test(id) ? {k:"yt", id} : null;
  }
  if (h === "youtu.be") return parts[0] && YT_ID.test(parts[0]) ? {k:"yt", id:parts[0]} : null;
  if (h === "tiktok.com") return parts.length >= 3 && parts[0].charAt(0) === "@" && parts[1] === "video" && TT_ID.test(parts[2]) ? {k:"tt", id:parts[2]} : null;
  return null;
}
function parseLinks(text){
  const out = [], seen = new Set(); let bad = 0;
  String(text || "").slice(0, 20000).split(/\s+/).filter(Boolean).forEach(t => {
    const v = parseLink(t); if (!v) { bad++; return; }
    const key = v.k + v.id; if (seen.has(key)) return; seen.add(key); out.push(v);
  });
  return {links: out.slice(0, MAX_LINKS), bad, over: Math.max(0, out.length - MAX_LINKS)};
}
function embedUrl(v){
  return v.k === "yt"
    ? "https://www.youtube-nocookie.com/embed/" + v.id + "?autoplay=1&mute=1&playsinline=1&loop=1&playlist=" + v.id + "&rel=0&modestbranding=1"
    : "https://www.tiktok.com/embed/v2/" + v.id;
}

/* ---------------- the engine's state, kept in this browser (same format as the extension's state.js) ---------------- */
const ENGINE_FIELDS = ["adaptiveRhythm","sessionId","sessionReels","reelsSinceCard","cardsToday","newToday","postponed","lastSwipeAt","snoozeUntil",
  "dayKey","rhythmArm","lastHost","lastDeck","recentFast","recentReel","requeue","log","anki","tzOffsetMin"];
function toJSON(E, meta){
  const engine = {cards:E.cards.map(c => Object.assign({}, c)), settings:E.settings, stats:E.stats, bandit:E.bandit, calibration:E.calibration, rhythm:E.rhythm,
    personalScale:E.fsrs.personalScale, newShown:[...E.newShown]};
  ENGINE_FIELDS.forEach(k => { engine[k] = E[k]; });
  return {v:1, meta, engine};
}
function fromJSON(st){
  const E = new RC.Engine(), meta = {coachLeft:2};
  if (!st || st.v !== 1 || !st.engine) return {E, meta};
  const s = st.engine;
  E.cards = Array.isArray(s.cards) ? s.cards : [];
  Object.assign(E.settings, s.settings); Object.assign(E.stats, s.stats);
  Object.assign(E.bandit, s.bandit); Object.assign(E.calibration, s.calibration); Object.assign(E.rhythm, s.rhythm);
  E.fsrs.personalScale = s.personalScale || 1; E.newShown = new Set(s.newShown || []);
  ENGINE_FIELDS.forEach(k => { if (k in s) E[k] = s[k]; });
  return {E, meta: Object.assign(meta, st.meta)};
}

let E = null, meta = {coachLeft:2}, cfg = null;
const app = $("app");

/* ---------------- setup screen ---------------- */
function setup(msg){
  closeFeed();
  const consent = store.get(KEY_OK), ok = !!(consent && consent.v === CONSENT_V);
  cfg = store.get(KEY_CFG) || {decks:["GCSE Biology"], notes:"", links:"", intensity:"normal"};
  app.innerHTML =
    '<div class="setup">'+
    '<a class="back" href="./">← Reelcards</a>'+
    '<h1>Reelcards Feed<span>your cards between the videos</span></h1>'+
    '<p>Paste links to YouTube Shorts or TikToks. Reelcards plays them one after another and slips your flashcards in between, like the app does on Android. It works in any browser, including Chrome on an iPhone.</p>'+
    '<div class="box notice"><h2>Read this first: this page is different</h2>'+
    '<p><b>To play the videos, your browser loads them from YouTube or TikTok.</b> That means those companies can see your device\'s internet address, and may set their own cookies, as on their own sites. Nothing else leaves your device: your cards, your answers and the links you paste stay in this browser, and we never receive them. The rest of Reelcards makes no such connection. This feed is for people aged 13 or over.</p>'+
    '<p class="hint">YouTube videos use YouTube\'s privacy-enhanced mode (youtube-nocookie.com). TikTok videos use TikTok\'s own player. Full details: <a href="privacy.html#the-reelcards-feed">privacy policy</a>.</p>'+
    '<label><input type="checkbox" id="consent"'+(ok?' checked':'')+'> I understand, and I\'m 13 or over.</label></div>'+
    '<div class="box"><h2>1. What to revise</h2><div class="decks">'+Object.keys(DECKS).map((n,i) => '<label><input type="checkbox" data-deck="'+esc(n)+'"'+(cfg.decks.indexOf(n) >= 0 ? ' checked' : '')+'> '+esc(n)+'</label>').join("")+'</div>'+
    '<textarea id="notes" maxlength="'+MAX_NOTES+'" placeholder="Or paste notes, one fact per line: Osmosis - diffusion of water">'+esc(cfg.notes)+'</textarea></div>'+
    '<div class="box"><h2>2. Videos to scroll</h2>'+
    '<textarea id="links" placeholder="https://www.youtube.com/shorts/…&#10;https://www.tiktok.com/@name/video/…">'+esc(cfg.links)+'</textarea>'+
    '<p class="hint" id="linkmsg"></p>'+
    '<p class="hint">Supported: YouTube Shorts and videos (youtube.com, youtu.be) and TikTok video links that contain /video/ and a number. Short links like vm.tiktok.com can\'t be used: open them and copy the full address.</p></div>'+
    '<div class="box"><h2>3. How often</h2><select id="intensity">'+INTENSITY.map(i => '<option value="'+i[0]+'"'+(cfg.intensity === i[0] ? ' selected' : '')+'>'+esc(i[1])+'</option>').join("")+'</select></div>'+
    (msg ? '<p class="bad" role="alert">'+esc(msg)+'</p>' : '')+
    '<button class="btn" id="go" disabled>Start scrolling</button>'+
    '<button class="btn ghost" id="wipe">Delete everything this page keeps on this device</button>'+
    '</div>';
  const refresh = () => {
    const r = parseLinks($("links").value), anyDeck = !!app.querySelector("[data-deck]:checked") || $("notes").value.trim().length > 0;
    $("linkmsg").innerHTML = r.links.length || r.bad ? '<span class="'+(r.links.length ? 'ok' : 'bad')+'">'+r.links.length+' link'+(r.links.length === 1 ? '' : 's')+' ready</span>'+(r.bad ? ' · <span class="bad">'+r.bad+' not recognised</span>' : '')+(r.over ? ' · only the first '+MAX_LINKS+' are used' : '') : '';
    $("go").disabled = !($("consent").checked && anyDeck && r.links.length);
  };
  app.addEventListener("input", refresh); app.addEventListener("change", refresh); refresh();
  $("go").onclick = () => start();
  let armed = false;
  $("wipe").onclick = () => { if (!armed) { armed = true; $("wipe").textContent = "Tap again to delete everything"; return; } [KEY_CFG, KEY_STATE, KEY_OK].forEach(store.del); E = null; setup("Deleted. Nothing is kept on this device now."); };
}

/* ---------------- starting ---------------- */
function start(){
  const decks = [...app.querySelectorAll("[data-deck]:checked")].map(x => x.dataset.deck).filter(n => DECKS[n]);
  const notes = $("notes").value.slice(0, MAX_NOTES), links = $("links").value.slice(0, 20000), intensity = INTENSITY.some(i => i[0] === $("intensity").value) ? $("intensity").value : "normal";
  const parsed = parseLinks(links);
  if (!$("consent").checked || !parsed.links.length) return;
  store.set(KEY_OK, {v:CONSENT_V, at:new Date().toISOString()});
  store.set(KEY_CFG, {decks, notes, links, intensity});
  const st = fromJSON(store.get(KEY_STATE)); E = st.E; meta = st.meta;
  E.cards = E.cards.filter(c => c.deck !== "My notes" && (DECKS[c.deck] ? decks.indexOf(c.deck) >= 0 : true));
  decks.forEach(n => { if (!E.cards.some(c => c.deck === n)) E.addCards(deckCards(n)); });
  if (notes.trim()) { const r = parseNotes(notes, "My notes"); if (r && r.cards && r.cards.length) E.addCards(r.cards); }
  if (!E.cards.length) { setup("Add a deck or some notes first."); return; }
  E.settings.intensity = intensity; E.settings.examDate = E.settings.examDate || 0;
  saveState(); openFeed(parsed.links);
}
const saveState = () => { if (E) store.set(KEY_STATE, toJSON(E, meta)); };

/* ---------------- the feed ---------------- */
let feed = null, links = [], io = null, active = null, timers = [], tapTimer = null, resizeBound = false;
function closeFeed(){
  if (io) { io.disconnect(); io = null; }
  if (active && active._item && !active._item.done) finishCard(active);
  clearTimers(); active = null; feed = null;
}
function openFeed(list){
  links = list;
  app.innerHTML = '<div class="topbar"><button id="back" type="button">← Setup</button></div><div class="feed" id="feed" tabindex="-1"></div>';
  feed = $("feed");
  $("back").onclick = () => { saveState(); setup(); };
  io = new IntersectionObserver(es => es.forEach(e => { if (e.isIntersecting && e.intersectionRatio >= .6) land(e.target); }), {root: feed, threshold: [.6]});
  appendPass(); appendPass();
  feed.addEventListener("click", onClick); feed.addEventListener("dblclick", onDbl);
  if (!resizeBound) { addEventListener("resize", scaleAll); resizeBound = true; }
  scaleAll(); feed.focus({preventScroll:true});
}
function videoSlide(v){
  const s = document.createElement("section"); s.className = "slide video"; s._v = v;
  s.innerHTML = '<div class="vframe"><span class="ph">Loading the video…</span></div><span class="vlabel">'+(v.k === "yt" ? "YouTube" : "TikTok")+'</span>';
  return s;
}
function appendPass(){ links.forEach(v => { const s = videoSlide(v); feed.appendChild(s); io.observe(s); }); }
function load(slide){
  if (slide.querySelector("iframe") || !slide._v) return;
  const f = document.createElement("iframe");
  f.src = embedUrl(slide._v); f.title = "Video";
  f.allow = "autoplay; encrypted-media; picture-in-picture; fullscreen"; f.referrerPolicy = "strict-origin-when-cross-origin";
  f.setAttribute("sandbox", "allow-scripts allow-same-origin allow-presentation allow-popups");
  slide.querySelector(".vframe").appendChild(f);
}
function unload(slide){ const f = slide.querySelector("iframe"); if (f) f.remove(); }
function scaleAll(){
  const k = Math.max(.4, Math.min((innerHeight - 24) / 740, (innerWidth - 12) / 360, 1.4)).toFixed(3);
  if (feed) feed.querySelectorAll(".frame").forEach(f => f.style.setProperty("--k", k));
}

/* a slide has landed in view */
function land(slide){
  if (active === slide) return;
  const leaving = active; active = slide;
  if (leaving) { if (leaving._item && !leaving._item.done) finishCard(leaving); if (leaving._v) unload(leaving); }
  if (slide._v) {
    load(slide);
    if (!slide._counted) { // the first time this video comes up: one more swipe for the engine, which may want a card next
      slide._counted = true;
      const sh = E.onReelSwipe(Date.now(), "youtube");
      if (sh) insertCard(slide, sh);
    }
    if (feed && feed.children.length - [...feed.children].indexOf(slide) <= 3) appendPass(); // keep the list going round
  } else if (slide._item && !slide._item.done) startCard(slide);
}
function insertCard(after, sh){
  const s = document.createElement("section"); s.className = "slide cardslide";
  s.innerHTML = '<div class="frame"><div class="stage"></div></div>';
  s._item = Object.assign({kind:"card", revealed:false, liked:false, likedBefore:false, pick:-1, saved:false, hinted:false, beat:0, count:null, got:[], moreOpen:false, settled:false, shownAt:0, done:false}, sh);
  after.after(s); io.observe(s); scaleAll();
  draw(s);
}
function draw(slide){
  const it = slide._item; if (!it) return;
  const h = new Date().getHours();
  const stage = slide.querySelector(".stage");
  stage.innerHTML = Render.card(it, {night: h >= 22 || h < 6, tint:null, coach: !!it.coach && !it.revealed && !it.liked && it.pick < 0});
  fitCards(stage);
  if (slide === active) startTimers(slide);
}
function startCard(slide){
  const it = slide._item; it.shownAt = performance.now();
  if (meta.coachLeft > 0) it.coach = true;
  draw(slide);
  if (it.s.snd) play(it.s.snd);
}
function finishCard(slide){
  const it = slide._item; if (!it || it.done) return; it.done = true; clearTimers();
  const outcome = {revealed:it.revealed, liked:it.liked, likedBefore:it.likedBefore, pick:it.pick, likeAt:it.likeAt||0, pickAt:it.pickAt||0, hintAt:it.hintAt||0,
    saved:!!it.saved, shared:!!it.shared, got:(it.got||[]).filter(Boolean).length, dwell:it.shownAt ? performance.now() - it.shownAt : 0};
  if (it.coach && meta.coachLeft > 0) meta.coachLeft--;
  try { E.onCardFinished(it, outcome, Date.now()); } catch (e) {}
  saveState(); stopSound();
}

/* ---------------- gestures (as in the app, the web demo and the extension) ---------------- */
const el = it => it.shownAt ? performance.now() - it.shownAt : 0;
const clearTimers = () => { timers.forEach(clearTimeout); timers = []; };
let sound = null;
function stopSound(){ if (sound && sound.pause) { try { sound.pause(); } catch (e) {} } sound = null; try { speechSynthesis.cancel(); } catch (e) {} }
function play(url){ stopSound(); if (typeof url !== "string" || url.indexOf("tts:") !== 0) return;
  try { const i = url.indexOf(":", 4), u = new SpeechSynthesisUtterance(url.slice(i + 1)); u.lang = url.slice(4, i).replace("_", "-"); speechSynthesis.speak(u); } catch (e) {} }
function startTimers(slide){
  const it = slide._item; if (!it || it.revealed || it.done) return;
  if (it.f.layout === "captions" && it.s.beats.length && (it.beat||0) < it.s.beats.length) {
    const words = it.s.beats[it.beat||0].split(" ").length;
    timers.push(setTimeout(() => { if (active === slide && !it.revealed && !it.done) { it.beat = (it.beat||0) + 1; draw(slide); } }, 380 + 330*words)); return;
  }
  if (it.count == null) it.count = Render.thinkSecs(it.s);
  if (it.count > 0) timers.push(setTimeout(() => { if (active === slide && !it.revealed && !it.done) { it.count--; const r = slide.querySelector(".ring"); if (r) r.textContent = it.count > 0 ? it.count : "?"; startTimers(slide); } }, 1000));
}
function reveal(slide){
  const it = slide._item; if (!it || it.done) return;
  if (it.moreOpen) { it.moreOpen = false; draw(slide); return; }
  if (it.revealed) { if (!it.settled) { it.settled = true; draw(slide); } return; }
  if (it.f.layout === "quiz" && it.s.correct >= 0) return;
  it.revealed = true; it.revealAt = el(it); draw(slide);
  if (it.s.asnd) play(it.s.asnd);
  timers.push(setTimeout(() => { it.settled = true; }, Render.ptDelays(it.s).pop() + 600));
}
function like(slide){
  const it = slide._item; if (!it || it.done || it.liked) return;
  it.liked = true; it.likeAt = el(it); it.likedBefore = !it.revealed && it.pick < 0; if (it.revealed) it.settled = true; draw(slide);
  if (it.likedBefore && it.f.layout !== "quiz") setTimeout(() => { if (!it.done && !it.revealed) { it.revealed = true; it.revealAt = el(it); draw(slide); } }, 600);
}
function tick(slide, i){
  const it = slide._item; if (!it || it.done || !it.revealed || !it.s.points || it.s.points.length < 2) return;
  if (it.liked) { it.got = it.s.points.map(() => true); it.liked = false; it.likedBefore = false; }
  it.got[i] = !it.got[i]; it.settled = true; draw(slide);
}
function toast(slide, msg){ const t = document.createElement("div"); t.className = "toast"; t.textContent = msg; slide.querySelector(".stage").appendChild(t); setTimeout(() => t.remove(), 1800); }
function onClick(e){
  const slide = e.target.closest(".cardslide"); if (!slide || !slide._item) return;
  const it = slide._item; if (it.done) return;
  const z = e.target.closest(".zoomer"); if (z) return;
  const b = e.target.closest("[data-act]");
  if (b) {
    const a = b.dataset.act, v = b.dataset.v;
    switch (a) {
      case "like": like(slide); return;
      case "reveal": reveal(slide); return;
      case "sound": play(v === "a" ? it.s.asnd : it.s.snd); return;
      case "tick": if (!it.settled && parseFloat(getComputedStyle(b).opacity) < .99) { it.settled = true; draw(slide); return; } tick(slide, +v); return;
      case "more": it.moreOpen = !it.moreOpen; it.settled = true; draw(slide); return;
      case "pick": if (it.pick < 0) { it.pick = +v; it.pickAt = el(it); it.revealed = true; draw(slide); } return;
      case "hint": if (!it.hinted && !it.revealed && it.pick < 0) { it.hinted = true; it.hintAt = el(it); draw(slide); } return;
      case "save": if (!it.saved) { it.saved = true; toast(slide, "Saved: it'll come back within a day"); } return;
      case "share": it.shared = true; try { navigator.clipboard.writeText("Can you answer this? “" + it.s.q + "” · made with Reelcards").then(() => toast(slide, "Copied: paste it to a friend"), () => {}); } catch (err) {} return;
    }
    return;
  }
  if (e.target.closest("[data-card]")) {
    clearTimeout(tapTimer);
    const pic = e.target.closest(".pic.zoomable");
    if (pic && pic.dataset.src && it.revealed) tapTimer = setTimeout(() => Render.zoomIn(slide, pic.dataset.src), 260);
    else tapTimer = setTimeout(() => reveal(slide), 260);
  }
}
function onDbl(e){
  const slide = e.target.closest(".cardslide"); if (!slide || !slide._item || e.target.closest("[data-act]")) return;
  clearTimeout(tapTimer); like(slide);
}

/* fit pass: shrink text a step at a time if it would spill out of the safe zone (as in the web demo) */
function fitCards(r){
  r.querySelectorAll(".card").forEach(card => {
    card.removeAttribute("data-fit");
    const body = card.querySelector(".body"); if (!body) return;
    const over = () => { const k = [...body.children].filter(e => e.offsetParent !== null); if (!k.length) return false; const cs = getComputedStyle(body);
      const used = k[k.length-1].offsetTop + k[k.length-1].offsetHeight - k[0].offsetTop;
      return used > body.clientHeight - parseFloat(cs.paddingTop) - parseFloat(cs.paddingBottom) + 2; };
    for (let f = 1; f <= 5 && over(); f++) card.setAttribute("data-fit", f);
  });
}
if (document.fonts && document.fonts.ready) document.fonts.ready.then(() => { if (feed) feed.querySelectorAll(".stage").forEach(fitCards); });

setup();
})();
