/* Reelcards extension: the engine's state in chrome.storage.local, shared by the popup and every tab.
   Same format as the web demo's save() in web-demo/src/app.js. Pictures and sounds from Anki files are
   kept as separate entries ("media:…", data: URLs), and a card's img/aimg/snd/asnd holds that key, so the
   main save stays small. Nothing leaves the browser. */
"use strict";
const RCState = (function(){
const KEY = "reelcards";
const ENGINE_FIELDS = ["adaptiveRhythm","sessionId","sessionReels","reelsSinceCard","cardsToday","newToday","postponed","lastSwipeAt","snoozeUntil",
  "dayKey","rhythmArm","lastHost","lastDeck","recentFast","recentReel","requeue","log","anki","tzOffsetMin"];
const freshMeta = () => ({onboarded:false, coachLeft:2, exam:14, vibes:[], firstDay:null});

function toJSON(E, meta){
  const engine = {cards:E.cards.map(c => Object.assign({}, c)),
    settings:E.settings, stats:E.stats, bandit:E.bandit, calibration:E.calibration, rhythm:E.rhythm,
    personalScale:E.fsrs.personalScale, newShown:[...E.newShown]};
  ENGINE_FIELDS.forEach(k => { engine[k] = E[k]; });
  return {v:1, meta, engine};
}
function fromJSON(st){
  const E = new RC.Engine();
  if (!st || st.v !== 1) return {E, meta:freshMeta()};
  const s = st.engine;
  E.cards = s.cards || [];
  Object.assign(E.settings, s.settings); Object.assign(E.stats, s.stats);
  Object.assign(E.bandit, s.bandit); Object.assign(E.calibration, s.calibration); Object.assign(E.rhythm, s.rhythm);
  E.fsrs.personalScale = s.personalScale || 1; E.newShown = new Set(s.newShown || []);
  ENGINE_FIELDS.forEach(k => { if (k in s) E[k] = s[k]; });
  return {E, meta:Object.assign(freshMeta(), st.meta)};
}
function load(){ return new Promise(ok => chrome.storage.local.get(KEY, r => ok(fromJSON(r && r[KEY])))); }
function save(E, meta){ return new Promise(ok => chrome.storage.local.set({[KEY]: toJSON(E, meta)}, ok)); }
function clear(){ return new Promise(ok => chrome.storage.local.clear(ok)); }
const MEDIA = ["img","aimg","snd","asnd"];
/* key -> data: URL for the given media keys */
function media(keys){ keys = keys.filter(k => typeof k === "string" && k.startsWith("media:")); return keys.length ? new Promise(ok => chrome.storage.local.get(keys, ok)) : Promise.resolve({}); }
function putMedia(map){ return new Promise(ok => chrome.storage.local.set(map, ok)); }
/* deletes pictures and sounds no card uses any more (a removed deck) */
function cleanMedia(E){
  const used = new Set(); E.cards.forEach(c => MEDIA.forEach(k => { if (c[k]) used.add(c[k]); }));
  chrome.storage.local.get(null, all => { const gone = Object.keys(all).filter(k => k.startsWith("media:") && !used.has(k)); if (gone.length) chrome.storage.local.remove(gone); });
}
/* calls back when another page (the popup, another tab) saves */
function onChange(fn){ chrome.storage.onChanged.addListener((ch, area) => { if (area === "local" && ch[KEY]) fn(); }); }
return {load, save, clear, onChange, fromJSON, toJSON, KEY, MEDIA, media, putMedia, cleanMedia};
})();
