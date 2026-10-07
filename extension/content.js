/* Reelcards extension: runs on TikTok, Instagram, YouTube and X. It notices when the student moves on
   to the next short video and, every few videos, pauses it and shows one of their flashcards on top,
   drawn like that site's own reels (the same engine and card designs as the web demo and the app).

   What it looks at: the position and size of <video> elements on the page, to tell which video is the
   one being watched, and the page address on YouTube Shorts and Instagram Reels (which changes with
   each video). It doesn't read text on the page, doesn't keep the addresses, and sends nothing
   anywhere: everything stays in the browser's extension storage. */
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

let E = null, meta = null, cur = null, lastKey = null, paused = [], host = null, root = null, timers = [], dirty = false;
const now = () => Date.now();
RCState.load().then(r => { E = r.E; meta = r.meta; });
RCState.onChange(() => { if (!cur && !dirty) RCState.load().then(r => { if (!cur) { E = r.E; meta = r.meta; } }); });
let saveTimer = null;
function persist(){ dirty = true; clearTimeout(saveTimer); saveTimer = setTimeout(() => { RCState.save(E, meta).then(() => { dirty = false; }); }, 800); }

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
function watch(){
  if (cur || !inFeed()) return;
  const v = mainVideo(); if (!v) return;
  const k = keyOf(v); if (!k || k === lastKey) return;
  lastKey = k; nextVideo();
}
setInterval(watch, 600);
addEventListener("pagehide", () => { if (dirty) RCState.save(E, meta); });
addEventListener("scroll", () => setTimeout(watch, 250), {passive:true, capture:true});

function nextVideo(){
  if (!E || !meta || !meta.onboarded || !E.cards.length) return;
  const sh = E.onReelSwipe(now(), SITE); persist();
  if (sh) show(sh);
}

/* ---------------- the card overlay ---------------- */
function pauseVideos(){ paused = []; document.querySelectorAll("video").forEach(v => { if (!v.paused) { paused.push(v); try { v.pause(); } catch (e) {} } }); }
function resumeVideos(){ paused.forEach(v => { try { v.play().catch(() => {}); } catch (e) {} }); paused = []; }
function mount(){
  host = document.createElement("div"); host.id = "reelcards-overlay";
  host.style.cssText = "position:fixed;inset:0;z-index:2147483647;";
  root = host.attachShadow({mode:"open"});
  root.innerHTML = '<style>' + CARD_CSS + OVERLAY_CSS + '</style><div class="dim"></div><div class="wrap"><div class="frame"><div class="stage"></div></div>' +
    '<div class="bar"><button data-x="next">Next ↓</button><span>Tap to see the answer · double-tap if you knew it · scroll on to skip</span><button data-x="pause">Pause 1 hour</button></div></div>';
  document.documentElement.appendChild(host);
  root.addEventListener("click", onClick); root.addEventListener("dblclick", onDbl);
  addEventListener("wheel", onWheel, {capture:true, passive:false}); // scrolling on = next, and the site underneath doesn't scroll
  addEventListener("keydown", onKey, true);
  scale(); addEventListener("resize", scale);
}
function onWheel(e){ if (!cur) return; e.preventDefault(); e.stopImmediatePropagation(); if (e.deltaY > 30) done(); }
function unmount(){ removeEventListener("wheel", onWheel, {capture:true}); removeEventListener("keydown", onKey, true); removeEventListener("resize", scale); if (host) host.remove(); host = root = null; }
/* the card is designed at 360 x 740 (a phone); scale it to the window's height */
function scale(){ if (!root) return; const k = Math.min((innerHeight - 70) / 740, (innerWidth - 20) / 360, 1.25); root.querySelector(".frame").style.setProperty("--k", k.toFixed(3)); }

function show(sh){
  pauseVideos();
  cur = Object.assign({kind:"card", revealed:false, liked:false, likedBefore:false, pick:-1, saved:false, hinted:false, beat:0, count:null, got:[], moreOpen:false, settled:false, shownAt:performance.now()}, sh);
  if (meta.coachLeft > 0) { cur.coach = true; meta.coachLeft--; }
  mount(); draw();
}
function draw(){
  if (!root || !cur) return;
  clearTimers();
  const h = new Date().getHours();
  root.querySelector(".stage").innerHTML = Render.card(cur, {night: h >= 22 || h < 6, tint:null, coach: cur.coach && !cur.revealed && !cur.liked && cur.pick < 0});
  fitCards(root.querySelector(".stage"));
  startTimers();
}
function done(){
  if (!cur) return;
  const it = cur; cur = null; clearTimers();
  E.onCardFinished(it, {revealed:it.revealed, liked:it.liked, likedBefore:it.likedBefore, pick:it.pick, likeAt:it.likeAt||0, pickAt:it.pickAt||0, hintAt:it.hintAt||0,
    saved:!!it.saved, shared:!!it.shared, got:(it.got||[]).filter(Boolean).length, dwell:performance.now()-it.shownAt}, now());
  clearTimeout(saveTimer); RCState.save(E, meta).then(() => { dirty = false; }); // straight away: the tab may close next
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
let tapTimer = null;
function onClick(e){
  const x = e.target.closest("[data-x]");
  if (x) { if (x.dataset.x === "next") done(); else { E.settings.pausedUntil = now() + 60*60000; done(); } return; }
  if (e.target.classList.contains("dim")) { done(); return; }
  const b = e.target.closest("[data-act]"), it = cur; if (!it) return;
  if (b) {
    const a = b.dataset.act, v = b.dataset.v;
    switch (a) {
      case "like": like(); return;
      case "reveal": reveal(); return;
      case "tick": if (!it.settled && parseFloat(getComputedStyle(b).opacity) < .99) { it.settled = true; draw(); return; } tick(+v); return;
      case "more": it.moreOpen = !it.moreOpen; it.settled = true; draw(); return;
      case "pick": if (it.pick < 0) { it.pick = +v; it.pickAt = el(); it.revealed = true; draw(); } return;
      case "hint": if (!it.hinted && !it.revealed && it.pick < 0) { it.hinted = true; it.hintAt = el(); draw(); } return;
      case "save": if (!it.saved) { it.saved = true; toast("Saved: it'll come back within a day"); } return;
      case "share": it.shared = true; try { navigator.clipboard.writeText("Can you answer this? “" + it.s.q + "” · made with Reelcards").then(() => toast("Copied: paste it to a friend"), () => {}); } catch (err) {} return;
    }
    return;
  }
  if (e.target.closest("[data-card]")) { clearTimeout(tapTimer); tapTimer = setTimeout(reveal, 260); }
}
function onDbl(e){ if (!e.target.closest("[data-card]") || e.target.closest("[data-act]")) return; clearTimeout(tapTimer); like(); }
function onKey(e){
  if (!cur) return;
  const k = e.key;
  if (k === "ArrowDown" || k === "j" || k === "Escape" || k === "PageDown") done();
  else if (k === " " || k === "Enter") reveal();
  else if (k === "l") like();
  else return;
  e.preventDefault(); e.stopImmediatePropagation();
}
// while a card is up, the site doesn't get to start the next video
setInterval(() => { if (cur) document.querySelectorAll("video").forEach(v => { if (!v.paused) { try { v.pause(); } catch (e) {} if (paused.indexOf(v) < 0) paused.push(v); } }); }, 400);

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
    const sh = E.forceShow(now(), SITE); persist(); if (sh) show(sh);
    reply({ok:!!sh});
  });
  return true;
});

const OVERLAY_CSS = `
:host{all:initial}
.dim{position:fixed;inset:0;background:rgba(0,0,0,.72);backdrop-filter:blur(3px)}
.wrap{position:fixed;inset:0;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;pointer-events:none}
.frame{--k:1;width:calc(360px * var(--k));height:calc(740px * var(--k));position:relative;pointer-events:auto;border-radius:calc(22px * var(--k));overflow:hidden;box-shadow:0 30px 80px -20px #000}
.stage{position:absolute;left:0;top:0;width:360px;height:740px;transform:scale(var(--k));transform-origin:0 0}
.bar{pointer-events:auto;display:flex;gap:10px;align-items:center;font:600 12px/1.3 system-ui,sans-serif;color:#ddd;max-width:92vw;flex-wrap:wrap;justify-content:center}
.bar button{font:700 12px system-ui,sans-serif;border:0;border-radius:999px;padding:8px 14px;cursor:pointer;background:#ffe14d;color:#141208}
.bar button[data-x=pause]{background:#2a2a30;color:#eee}
`;
})();
