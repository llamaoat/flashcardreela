/* Reelcards extension: runs on TikTok, Instagram, YouTube and X. It notices when the student moves on
   to the next short video and, every few videos, pauses it and shows one of their flashcards on top,
   drawn like that site's own reels (the same engine and card designs as the web demo and the app).

   What it looks at: the position and size of <video> elements on the page, to tell which video is the
   one being watched, and the page address on YouTube Shorts and Instagram Reels (which changes with
   each video). It doesn't read text on the page and sends nothing anywhere. To know what "back" is it
   remembers the last 30 videos of this visit (an address, or a marker for the player), in this page's
   memory only: never saved, gone when the tab is. Everything else stays in the browser's extension storage. */
"use strict";
(function(){
if (window.__reelcards) return; window.__reelcards = true;

const SITE = /tiktok\.com$/.test(location.hostname) ? "tiktok" : /instagram\.com$/.test(location.hostname) ? "instagram"
  : /youtube\.com$/.test(location.hostname) ? "youtube" : "x";
/* where a short-video feed is being watched */
function inFeed(){
  const p = location.pathname;
  if (SITE === "youtube") return p.startsWith("/shorts/");
  if (SITE === "instagram") return /^\/(reels?|reel)\//.test(p) || p === "/reels/";
  return true; // TikTok is all short videos; on X any video that plays counts
}

let E = null, meta = null, cur = null, lastKey = null, paused = [], host = null, root = null, timers = [];
const now = () => Date.now();
RCState.load().then(r => { E = r.E; meta = r.meta; });
RCState.onChange(() => { if (!cur) RCState.load().then(r => { if (!cur) { E = r.E; meta = r.meta; } }); });
/* every change goes through RCState.update: the newest save, changed, saved at once (no stale copies between tabs) */
function change(fn){ return RCState.update(fn).then(r => { E = r.E; meta = r.meta; return r.out; }); }

/* ---------------- which video is being watched ---------------- */
const ids = new WeakMap(); let nextId = 1;
const idOf = v => { if (!ids.has(v)) ids.set(v, nextId++); return ids.get(v); };
function mainVideo(){
  let best = null, bestA = 0;
  for (const v of document.querySelectorAll("video")) {
    const r = v.getBoundingClientRect(), whole = r.width * r.height; if (whole < 40000) continue;
    const w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0)), h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
    const a = w * h; if (a > bestA && a > .5 * whole) { best = v; bestA = a; }
  }
  return best;
}
function keyOf(v){
  if (SITE === "youtube" || SITE === "instagram") return location.pathname; // the address changes with each video
  if (SITE === "x" && v.paused && !v.autoplay) return null;                 // X: only a video that's playing
  return idOf(v) + "|" + (v.currentSrc || v.src || "");
}
/* The videos watched this visit, in order, and where we are in them. A card is shown on arriving at a video and sits
   just before it, so scrolling back from that video to the one before it passes through the card's place: the card
   comes back, with a note if it was done already (as in the Android app). Going back never counts as a new video. */
const TRAIL_MAX = 30, SLOTS_MAX = 8;
let trail = [], at = -1, slots = [], slotNow = null;
function resetTrail(){ trail = []; at = -1; slots = []; slotNow = null; lastKey = null; }
function watch(){
  if (!E || !meta) return; // the saved state isn't loaded yet: don't note a video we couldn't act on
  if (!inFeed()) { if (trail.length) resetTrail(); return; }
  const v = mainVideo(); if (!v) return;
  const k = keyOf(v); if (!k || k === lastKey) return;
  lastKey = k;
  let step = "new";
  if (at > 0 && k === trail[at - 1]) { at--; step = "back"; }                                 // back to the video before
  else if (at >= 0 && at < trail.length - 1 && k === trail[at + 1]) { at++; step = "on"; }    // on again to one seen before
  else {                                                                                      // a video we haven't seen
    trail.length = at + 1; trail.push(k); at = trail.length - 1;
    if (trail.length > TRAIL_MAX) { trail.shift(); at--; slots.forEach(sl => { sl.at--; }); slots = slots.filter(sl => sl.at >= 0); }
  }
  if (cur) return; // the video changed under a card: keep our place, but that isn't a reason for another card
  if (step === "back") backTo(); else nextVideo();
}
function backTo(){
  const slot = slots.find(sl => sl.at === at + 1);
  if (slot && slot.it && E && meta && !meta.off && E.cards.length) revisit(slot);
}
const cap = t => t ? t[0].toUpperCase() + t.slice(1) : t;
/* The card shown again. One they did shows its answer, with a note, and isn't graded twice; one they scrolled past can be answered now. */
function revisit(slot){
  const old = slot.it, did = !!slot.grade || !!(slot.out && slot.out.shared);
  const it = did ? Object.assign({}, old, {revealed:true, settled:true, got:(old.got || []).slice()})
    : Object.assign({}, old, {revealed:false, liked:false, likedBefore:false, pick:-1, saved:false, hinted:false, beat:0, count:null, got:[], moreOpen:false, settled:false});
  it.coach = false; it.shownAt = performance.now();
  it.revisit = {slot, looking:did, note: slot.grade ? "You did this card already · " + cap(slot.grade) : did ? "You shared this card already" : "You skipped this one. Have a go?"};
  show(it, {back:true});
}
setInterval(watch, 600);
addEventListener("scroll", () => setTimeout(watch, 250), {passive:true, capture:true});

function nextVideo(){
  if (!E || !meta || !meta.onboarded || meta.off || !E.cards.length) return;
  // straight after Get started, the first video brings a card, so it's clear at once that it works
  change((E2, m2) => { if (m2.firstCard) { m2.firstCard = false; return E2.forceShow(now(), SITE); } return E2.onReelSwipe(now(), SITE); })
    .then(sh => { if (sh && !cur) show(sh, {slotAt:at}); });
}

/* ---------------- the card overlay ---------------- */
function pauseVideos(){ paused = []; document.querySelectorAll("video").forEach(v => { if (!v.paused) { paused.push(v); try { v.pause(); } catch (e) {} } }); }
function resumeVideos(){ paused.forEach(v => { try { v.play().catch(() => {}); } catch (e) {} }); paused = []; }
function mount(back){
  host = document.createElement("div"); host.id = "reelcards-overlay";
  host.style.cssText = "position:fixed;inset:0;z-index:2147483647;touch-action:none;overscroll-behavior:contain;-webkit-tap-highlight-color:transparent;";
  root = host.attachShadow({mode:"open"});
  root.innerHTML = '<style>' + CARD_CSS + OVERLAY_CSS + '</style><div class="dim"></div><div class="wrap"><div class="frame' + (back ? ' back' : '') + '"><div class="stage"></div><div class="note" hidden></div></div>' +
    '<div class="bar"><button data-x="next">Next ↓</button><span>Tap to see the answer · double-tap if you knew it · scroll on to skip</span><button data-x="pause">Pause 1 hour</button></div></div>';
  document.documentElement.appendChild(host);
  root.addEventListener("click", onClick); root.addEventListener("dblclick", onDbl);
  root.addEventListener("pointerdown", onDown); root.addEventListener("pointermove", onMove);
  root.addEventListener("pointerup", onUp); root.addEventListener("pointercancel", onCancel);
  // touches and clicks on the card stay with the card: the site's own swipe (to its next video) or tap (pause) never sees
  // them, and the page underneath doesn't scroll. A long explanation that's open can still be scrolled with a finger.
  ["touchstart","touchmove","touchend","touchcancel","pointerdown","pointermove","pointerup","pointercancel","click","dblclick","mousedown","mouseup"].forEach(t =>
    host.addEventListener(t, e => { e.stopPropagation(); if (t === "touchmove" && !inMore(e.composedPath()[0])) e.preventDefault(); }, {passive:false}));
  addEventListener("wheel", onWheel, {capture:true, passive:false}); // scrolling on = next, and the site underneath doesn't scroll
  addEventListener("keydown", onKey, true);
  scale(); addEventListener("resize", scale);
}
function onWheel(e){
  if (!cur) return; e.preventDefault(); e.stopImmediatePropagation();
  const z = root && root.querySelector(".zoomer"); if (z) { z._wheel(e); return; } // a picture is open: scrolling zooms it
  if (e.deltaY > 30 || (cur.revisit && e.deltaY < -30)) done();
}
function unmount(){ removeEventListener("wheel", onWheel, {capture:true}); removeEventListener("keydown", onKey, true); removeEventListener("resize", scale); if (host) host.remove(); host = root = null; }
/* The card is designed at 360 x 740 (a phone). On a phone held upright it fills the screen, like a reel; on a phone on
   its side it's the screen's height; anywhere else it's that design in a frame, scaled to the window. */
function scale(){
  if (!root) return;
  const wrap = root.querySelector(".wrap"), small = matchMedia("(pointer: coarse)").matches && Math.min(innerWidth, innerHeight) < 600, upright = small && innerHeight >= innerWidth;
  wrap.classList.toggle("phone", upright); wrap.classList.toggle("bare", small && !upright);
  const k = upright ? 1 : small ? innerHeight / 740 : Math.min((innerHeight - 70) / 740, (innerWidth - 20) / 360, 1.25);
  root.querySelector(".frame").style.setProperty("--k", k.toFixed(3));
  if (cur) fitCards(root.querySelector(".stage")); // the screen turned: the text may fit differently
}

/* ---------------- touch: drag the card, as the app's CardView does ----------------
   Up to move on, and down too on a card they came back to. It follows the finger, goes past a fifth of the screen or
   on a quick flick, and springs back otherwise. A mouse uses the wheel instead. */
let drag = null, noClickUntil = 0;
const frameEl = () => root && root.querySelector(".frame");
const inMore = el => !!(el && el.closest && el.closest(".moreopen .band"));
function onDown(e){
  if (!cur || cur.leaving || e.pointerType === "mouse" || !e.isPrimary || root.querySelector(".zoomer")) return;
  if (e.target.closest(".bar") || inMore(e.target)) return; // a drag can start anywhere on the card, even on a button (a tap is still only that button), but an open explanation scrolls
  drag = {id:e.pointerId, y0:e.clientY, dy:0, moving:false, pts:[[e.timeStamp, e.clientY]]};
}
function onMove(e){
  const d = drag; if (!d || e.pointerId !== d.id || !cur) return;
  d.dy = e.clientY - d.y0; d.pts.push([e.timeStamp, e.clientY]); if (d.pts.length > 6) d.pts.shift();
  if (!d.moving && (d.dy < -12 || (cur.revisit && d.dy > 12))) { d.moving = true; clearTimeout(tapTimer); }
  if (d.moving) { const f = frameEl(); f.classList.add("drag"); f.style.transform = "translateY(" + (cur.revisit ? d.dy : Math.min(0, d.dy)) + "px)"; }
}
function onUp(e){
  const d = drag; if (!d || e.pointerId !== d.id) return; drag = null;
  const f = frameEl(); if (!d.moving || !cur || !f) return;
  noClickUntil = performance.now() + 400; // the lift at the end of a drag isn't a tap
  const p = d.pts[0], v = (e.clientY - p[1]) / Math.max(16, e.timeStamp - p[0]) * 1000; // px per second
  f.classList.remove("drag");
  if (d.dy < -innerHeight * .2 || v < -1400) leave(-1);
  else if (cur.revisit && (d.dy > innerHeight * .2 || v > 1400)) leave(1);
  else f.style.transform = "";
}
function onCancel(e){ if (!drag || e.pointerId !== drag.id) return; drag = null; const f = frameEl(); if (f) { f.classList.remove("drag"); f.style.transform = ""; } }
/* slides the card off the way the finger went, then moves on. The video starts again straight away, while the finger's
   still the reason: phones only let a page start a video with sound from a tap or a swipe. */
function leave(dir){ const it = cur, f = frameEl(); it.leaving = true; f.classList.add("leaving"); f.style.transform = "translateY(" + (dir * 110) + "%)"; resumeVideos(); setTimeout(() => { if (cur === it) done(); }, 190); }

function show(sh, opt){
  opt = opt || {};
  // an Anki card's pictures and sounds live in storage: look them up first
  const keys = RCState.MEDIA.map(k => sh.s[k]).filter(Boolean);
  if (keys.length && !sh.mediaReady) { RCState.media(keys).then(m => { RCState.MEDIA.forEach(k => { if (sh.s[k]) sh.s[k] = m[sh.s[k]] || null; }); sh.mediaReady = true; show(sh, opt); }); return; }
  pauseVideos();
  cur = Object.assign({kind:"card", revealed:false, liked:false, likedBefore:false, pick:-1, saved:false, hinted:false, beat:0, count:null, got:[], moreOpen:false, settled:false, shownAt:performance.now()}, sh);
  cur.leaving = false; // a card brought back was last seen sliding away
  if (meta.coachLeft > 0 && !cur.revisit) cur.coach = true; // counted down when the card is finished
  if (opt.slotAt != null) { slots = slots.filter(sl => sl.at !== opt.slotAt); slotNow = {at:opt.slotAt, it:null, out:null, grade:null}; slots.push(slotNow); if (slots.length > SLOTS_MAX) slots.shift(); }
  else slotNow = null;
  mount(!!opt.back); draw();
  if (cur.s.snd) play(cur.s.snd);
}
let sound = null;
function play(url){ if (sound) sound.pause(); sound = null; if (!url) return;
  if (url.startsWith("tts:")) { try { const i = url.indexOf(":", 4), u = new SpeechSynthesisUtterance(url.slice(i + 1)); u.lang = url.slice(4, i).replace("_", "-"); speechSynthesis.cancel(); speechSynthesis.speak(u); } catch (e) {} return; } sound = new Audio(url); sound.play().catch(() => {}); }
function draw(){
  if (!root || !cur) return;
  clearTimers();
  const h = new Date().getHours();
  root.querySelector(".stage").innerHTML = Render.card(cur, {night: h >= 22 || h < 6, tint:null, coach: cur.coach && !cur.revealed && !cur.liked && cur.pick < 0});
  fitCards(root.querySelector(".stage"));
  const note = root.querySelector(".note"), hint = root.querySelector(".bar span");
  if (cur.revisit) { note.textContent = cur.revisit.note; note.hidden = false; hint.textContent = cur.revisit.looking ? "Scroll either way or press an arrow key to carry on" : "Tap to see the answer · double-tap if you knew it · scroll either way to carry on"; }
  else { note.hidden = true; hint.textContent = "Tap to see the answer · double-tap if you knew it · scroll on to skip"; }
  startTimers();
}
function done(){
  if (!cur) return;
  const it = cur; cur = null; clearTimers();
  const outcome = {revealed:it.revealed, liked:it.liked, likedBefore:it.likedBefore, pick:it.pick, likeAt:it.likeAt||0, pickAt:it.pickAt||0, hintAt:it.hintAt||0,
    saved:!!it.saved, shared:!!it.shared, got:(it.got||[]).filter(Boolean).length, dwell:performance.now()-it.shownAt}, coach = !!it.coach;
  const rv = it.revisit, slot = rv ? rv.slot : slotNow; slotNow = null;
  const answered = it.revealed || it.liked || it.pick >= 0 || (it.got || []).some(Boolean);
  if (slot && !rv) { slot.it = it; slot.out = outcome; }
  if (rv && (rv.looking || !answered)) { /* just looking back, or skipped again: nothing to grade, and not a second skip */ }
  else {
    // grade the newest saved copy of this card (another tab may have changed others meanwhile)
    change((E2, m2) => { const c = E2.cards.find(x => x.id === it.card.id); if (coach && m2.coachLeft > 0) m2.coachLeft--;
      if (!c) return null; it.card = c; const r = E2.onCardFinished(it, outcome, now()); if (rv && r.grade) E2.dropRequeue(c); return r; })
      .then(r => { if (slot && r) { slot.grade = r.grade; if (rv) { slot.it = it; slot.out = outcome; } } });
  }
  if (sound) { sound.pause(); sound = null; }
  unmount(); resumeVideos();
}

/* ---------------- gestures (as in the app and the web demo) ---------------- */
const el = () => cur ? performance.now() - cur.shownAt : 0;
const clearTimers = () => { timers.forEach(clearTimeout); timers = []; };
function startTimers(){
  const it = cur; if (!it || it.revealed) return;
  if (it.f.layout === "captions" && it.s.beats.length && (it.beat||0) < it.s.beats.length) {
    const words = it.s.beats[it.beat||0].split(" ").length;
    timers.push(setTimeout(() => { if (cur === it && !it.revealed) { it.beat = (it.beat||0) + 1; draw(); } }, 380 + 330*words)); return;
  }
  if (it.count == null) it.count = Render.thinkSecs(it.s);
  if (it.count > 0) timers.push(setTimeout(() => { if (cur === it && !it.revealed) { it.count--; const r = root && root.querySelector(".ring"); if (r) r.textContent = it.count > 0 ? it.count : "?"; startTimers(); } }, 1000));
}
function reveal(){
  const it = cur; if (!it) return;
  if (it.moreOpen) { it.moreOpen = false; draw(); return; }
  if (it.revealed) { if (!it.settled) { it.settled = true; draw(); } return; }
  if (it.f.layout === "quiz" && it.s.correct >= 0) return;
  it.revealed = true; it.revealAt = el(); draw();
  if (it.s.asnd) play(it.s.asnd);
  timers.push(setTimeout(() => { if (cur === it) it.settled = true; }, Render.ptDelays(it.s).pop() + 600));
}
function like(){
  const it = cur; if (!it || it.liked) return;
  it.liked = true; it.likeAt = el(); it.likedBefore = !it.revealed && it.pick < 0; if (it.revealed) it.settled = true; draw();
  if (it.likedBefore && it.f.layout !== "quiz") setTimeout(() => { if (cur === it && !it.revealed) { it.revealed = true; it.revealAt = el(); draw(); } }, 600);
}
function tick(i){
  const it = cur; if (!it || !it.revealed || !it.s.points || it.s.points.length < 2) return;
  if (it.liked) { it.got = it.s.points.map(() => true); it.liked = false; it.likedBefore = false; }
  it.got[i] = !it.got[i]; it.settled = true; draw();
}
function toast(msg){ if (!root) return; const t = document.createElement("div"); t.className = "toast"; t.textContent = msg; root.querySelector(".stage").appendChild(t); setTimeout(() => t.remove(), 1800); }
let tapTimer = null, lastTap = {t:0, x:0, y:0};
function onClick(e){
  if (performance.now() < noClickUntil || (cur && cur.leaving)) return;
  const x = e.target.closest("[data-x]");
  if (x) { if (x.dataset.x === "pause") change(E2 => { E2.settings.pausedUntil = now() + 60*60000; }); done(); return; }
  if (e.target.classList.contains("dim")) { done(); return; }
  const b = e.target.closest("[data-act]"), it = cur; if (!it) return;
  if (b) {
    const a = b.dataset.act, v = b.dataset.v;
    switch (a) {
      case "like": like(); return;
      case "reveal": reveal(); return;
      case "sound": play(v === "a" ? it.s.asnd : it.s.snd); return;
      case "tick": if (!it.settled && parseFloat(getComputedStyle(b).opacity) < .99) { it.settled = true; draw(); return; } tick(+v); return;
      case "more": it.moreOpen = !it.moreOpen; it.settled = true; draw(); return;
      case "pick": if (it.pick < 0) { it.pick = +v; it.pickAt = el(); it.revealed = true; draw(); } return;
      case "hint": if (!it.hinted && !it.revealed && it.pick < 0) { it.hinted = true; it.hintAt = el(); draw(); } return;
      case "save": if (!it.saved) { it.saved = true; toast("Saved: it'll come back within a day"); } return;
      case "share": it.shared = true; try { navigator.clipboard.writeText("Can you answer this? “" + it.s.q + "” · made with Reelcards").then(() => toast("Copied: paste it to a friend"), () => {}); } catch (err) {} return;
    }
    return;
  }
  if (e.target.closest("[data-card]")) {
    clearTimeout(tapTimer);
    const t = performance.now();
    if (t - lastTap.t < 320 && Math.abs(e.clientX - lastTap.x) < 40 && Math.abs(e.clientY - lastTap.y) < 40) { lastTap.t = 0; like(); return; } // a double tap
    lastTap = {t, x:e.clientX, y:e.clientY};
    const pic = e.target.closest(".pic.zoomable");
    if (pic && pic.dataset.src && cur.revealed) tapTimer = setTimeout(() => Render.zoomIn(root, pic.dataset.src), 260); // once the answer's showing, a click on a picture opens it full screen
    else tapTimer = setTimeout(reveal, 260);
  }
}
function onDbl(e){ if (!e.target.closest("[data-card]") || e.target.closest("[data-act]")) return; clearTimeout(tapTimer); like(); }
function onKey(e){
  if (!cur) return;
  const k = e.key, z = root && root.querySelector(".zoomer");
  if (z) { if (k === "Escape") { z.remove(); e.preventDefault(); e.stopPropagation(); } return; } // a picture is open: Esc closes it, not the card
  if (k === "ArrowDown" || k === "j" || k === "Escape" || k === "PageDown" || (cur.revisit && (k === "ArrowUp" || k === "k"))) done();
  else if (k === " " || k === "Enter") reveal();
  else if (k === "l") like();
  else return;
  e.preventDefault(); e.stopImmediatePropagation();
}
// while a card is up, the site doesn't get to start the next video
setInterval(() => { if (cur && !cur.leaving) document.querySelectorAll("video").forEach(v => { if (!v.paused) { try { v.pause(); } catch (e) {} if (paused.indexOf(v) < 0) paused.push(v); } }); }, 400);

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

/* fonts: the app's own, packed with the extension (declared on the page, as shadow roots can't) */
const st = document.createElement("style");
st.textContent = [["TikTok Sans","TikTokSans-Regular.ttf",400],["TikTok Sans","TikTokSans-Bold.ttf",700],["TikTok Sans","TikTokSans-Black.ttf",900],
  ["Anton","Anton-Regular.ttf",400],["Caveat","Caveat-Bold.ttf",700],["Fraunces","Fraunces-Bold.ttf",700]]
  .map(f => "@font-face{font-family:'" + f[0] + "';src:url('" + chrome.runtime.getURL("fonts/" + f[1]) + "');font-weight:" + f[2] + ";font-display:swap}").join("");
(document.head || document.documentElement).appendChild(st);

/* the popup's "Show a test card on this tab" */
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg || msg.type !== "testCard") return;
  RCState.load().then(r => {
    E = r.E; meta = r.meta;
    if (!E.cards.length) { reply({ok:false, why:"Add some cards first."}); return; }
    if (cur) done();
    change((E2, m2) => { m2.firstCard = false; return E2.forceShow(now(), SITE); }).then(sh => { if (sh) show(sh); reply({ok:!!sh}); });
  });
  return true;
});

const OVERLAY_CSS = `
:host{all:initial}
.dim{position:fixed;inset:0;background:rgba(0,0,0,.72);backdrop-filter:blur(3px)}
.wrap{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;pointer-events:none}
.frame{--k:1;width:calc(360px * var(--k));height:calc(740px * var(--k));position:relative;pointer-events:auto;border-radius:calc(22px * var(--k));overflow:hidden;box-shadow:0 30px 80px -20px #000}
.stage{position:absolute;left:0;top:0;width:360px;height:740px;transform:scale(var(--k));transform-origin:0 0}
.frame{animation:rcin .26s cubic-bezier(.2,.8,.2,1);transition:transform .18s ease-out;touch-action:none}.frame.back{animation-name:rcback}
.frame.drag{transition:none}.frame.leaving{transition:transform .19s ease-in,opacity .19s ease-in;opacity:.4}
/* a phone held upright: the card is the whole screen, like a reel; on its side, the screen's height. No bar either way:
   swipe up to move on, as in the app */
.wrap.phone{gap:0}
.wrap.phone .frame{width:100vw;height:100vh;height:100dvh;border-radius:0;box-shadow:none}
.wrap.phone .stage{width:100%;height:100%;transform:none}
.wrap.phone .bar,.wrap.bare .bar{display:none}
.card.moreopen .band{touch-action:pan-y}
@keyframes rcin{from{transform:translateY(28px);opacity:0}to{transform:none;opacity:1}}
@keyframes rcback{from{transform:translateY(-28px);opacity:0}to{transform:none;opacity:1}}
@media (prefers-reduced-motion:reduce){.frame{animation:none}}
.note{position:absolute;top:calc(46px * var(--k));left:50%;transform:translateX(-50%);z-index:5;max-width:88%;text-align:center;font:600 calc(13px * var(--k))/1.3 system-ui,sans-serif;color:#111;background:rgba(255,255,255,.96);padding:calc(8px * var(--k)) calc(14px * var(--k));border-radius:calc(16px * var(--k));box-shadow:0 6px 20px rgba(0,0,0,.35);pointer-events:none}
.note[hidden]{display:none}
.bar{pointer-events:auto;display:flex;gap:10px;align-items:center;font:600 12px/1.3 system-ui,sans-serif;color:#ddd;max-width:92vw;flex-wrap:wrap;justify-content:center}
.bar button{font:700 12px system-ui,sans-serif;border:0;border-radius:999px;padding:8px 14px;cursor:pointer;background:#fff;color:#111}
.bar button[data-x=pause]{background:#2a2a30;color:#eee}
`;
})();
