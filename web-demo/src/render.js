/* Card reel renderer: draws a Show exactly like a reel in the host app. Mirrors app/.../CardView.java. */
"use strict";
const Render = (function(){
const esc = s => String(s == null ? "" : s).replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));

/* Look palettes. Light looks get a night version between 22:00 and 06:00 (no bright white at 1am). */
const LOOKS = {
  caption: {bg:"#0e0e10", fg:"#ffffff", acc:"#ffe14d", accfg:"#0e0e10", panel:"rgba(0,0,0,.55)", bubble:"#2a2a30", light:false},
  subtitle:{bg:"#101014", fg:"#ffffff", acc:"#ffe14d", accfg:"#101014", panel:"rgba(0,0,0,.72)", bubble:"#2a2a30", light:false},
  clean:   {bg:"#f3f1ec", fg:"#16161a", acc:"#2f6bff", accfg:"#ffffff", panel:"#e2dfd6", bubble:"#ffffff", light:true,
            night:{bg:"#141312", fg:"#ede7da", acc:"#8fb0ff", accfg:"#0d0d10", panel:"#26231f", bubble:"#2d2a25", light:false}},
  neon:    {bg:"#0b0620", fg:"#f4f0ff", acc:"#ff4fd8", accfg:"#0b0620", panel:"rgba(35,26,69,.85)", bubble:"#342866", light:false},
  paper:   {bg:"#fff6d6", fg:"#2b2118", acc:"#e4572e", accfg:"#ffffff", panel:"#f1e5b8", bubble:"#fffbea", light:true,
            night:{bg:"#1f2a24", fg:"#eef2e6", acc:"#ffd166", accfg:"#1f2a24", panel:"#2b3a31", bubble:"#2f3f35", light:false}} // chalkboard
};
function palette(look, night, tint){
  let L = LOOKS[look] || LOOKS.caption;
  if (night && L.night) L = L.night;
  L = Object.assign({}, L);
  if (tint != null && !L.light) { // match the colour of the reel they just watched (opt-in)
    L.bg = "hsl(" + tint + " 42% 12%)"; L.panel = "hsla(" + tint + ",40%,8%,.72)"; L.bubble = "hsl(" + tint + " 30% 22%)";
  }
  return L;
}

/* Icons: generic outline shapes drawn for Reelcards (no platform logos). */
const I = {
  heart: f => '<svg viewBox="0 0 24 24" fill="'+(f?"currentColor":"none")+'" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 20.5s-7.5-4.6-9.2-9.4C1.6 7.6 3.8 4.2 7.2 4.2c2 0 3.6 1.1 4.8 2.9 1.2-1.8 2.8-2.9 4.8-2.9 3.4 0 5.6 3.4 4.4 6.9-1.7 4.8-9.2 9.4-9.2 9.4z"/></svg>',
  thumb: f => '<svg viewBox="0 0 24 24" fill="'+(f?"currentColor":"none")+'" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M7 10v11H3V10h4zm0 0 4.5-7.5c1.6 0 2.7 1.3 2.4 2.9L13.2 10H19a2 2 0 0 1 2 2.3l-1.3 7A2 2 0 0 1 17.7 21H7"/></svg>',
  bubble: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M12 3.5c5 0 9 3.4 9 7.7s-4 7.7-9 7.7c-1 0-2-.1-2.9-.4L4 20.5l1.3-4C3.8 15 3 13.2 3 11.2 3 6.9 7 3.5 12 3.5z"/><circle cx="8" cy="11.2" r="1" fill="currentColor"/><circle cx="12" cy="11.2" r="1" fill="currentColor"/><circle cx="16" cy="11.2" r="1" fill="currentColor"/></svg>',
  bookmark: f => '<svg viewBox="0 0 24 24" fill="'+(f?"currentColor":"none")+'" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M6 3.5h12v17l-6-4.2-6 4.2z"/></svg>',
  share: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M13.5 4.5 21 11l-7.5 6.5v-3.8C8 13.7 5 15.5 3 19.5c.6-5.8 3.6-9.9 10.5-11z"/></svg>',
  send: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round"><path d="M21 3.5 3 10.2l7.2 2.6L21 3.5zm0 0-7.6 17-3.2-7.7"/></svg>',
  note: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M9 17.5a3 3 0 1 1-2-2.8V5l11-2v11.5a3 3 0 1 1-2-2.8V7.3l-7 1.3z"/></svg>'
};

/* The key word in a question: the longest word of 5+ letters (what auto-captions highlight). */
function keyWord(q){ let best = -1, len = 0; q.split(/\s+/).forEach((w,i)=>{ const b = w.replace(/[^\p{L}\p{N}]/gu,""); if (b.length > len && b.length >= 5) { len = b.length; best = i; } }); return best; }
function withEm(q, upper){ const words = q.split(/\s+/), k = keyWord(q); return words.map((w,i)=> i===k ? "<em>"+esc(upper?w.toUpperCase():w)+"</em>" : esc(upper?w.toUpperCase():w)).join(" "); }

function questionHTML(it, scale){
  const look = it.f.look, q = it.s.q;
  const size = {caption:34, subtitle:27, clean:31, neon:36, paper:40}[look] || 32;
  // long questions shrink so they always fit the safe zone
  const len = q.length, fit = len > 110 ? .7 : len > 80 ? .8 : len > 55 ? .9 : 1;
  // never break a word: the longest word must fit the ~250px text column
  const longest = Math.max(...q.split(/\s+/).map(w=>w.length));
  const em = {caption:.66, subtitle:.6, clean:.58, neon:.5, paper:.45}[look] || .6;
  const px = Math.round(Math.min(size * scale * fit, 244 / (longest * em)));
  if (look === "subtitle") return '<h2 class="q" style="font-size:'+px+'px"><span>'+withEm(q,false)+'</span></h2>';
  return '<h2 class="q" style="font-size:'+px+'px">'+withEm(q, look==="caption"||look==="neon")+'</h2>';
}

function revealOrAnswer(it){
  const hintChip = it.hinted && !it.revealed ? '<div class="hintchip"><b>Hint</b> '+esc(it.s.hint)+'</div>' : '';
  if (!it.revealed) return hintChip + revealButton(it);
  if (it.revealed) return '<div class="ans"><div class="a1">'+esc(it.s.a)+'</div>'+(it.s.x?'<div class="a2">'+esc(it.s.x)+'</div>':'')+'</div>';
  return revealButton(it);
}
function revealButton(it){ return '<button class="reveal" data-act="reveal"><span class="ring">'+(it.count==null?3:it.count)+'</span><span>'+esc(it.s.prompt)+'</span></button>'; }

function bodyHTML(it){
  const s = it.s, lay = it.f.layout;
  if (lay === "chat") {
    return '<div class="chat"><div class="chathead"><i>'+esc(s.name[0])+'</i><span><b>'+esc(s.name)+'</b><small>revision chat · now</small></span></div>'+
      s.them.map(t=>'<div class="bub them">'+esc(t)+'</div>').join("")+
      (it.revealed ? s.me.map(t=>'<div class="bub me">'+esc(t)+'</div>').join("")+'<div class="bub them">'+esc(s.after)+'</div>'
                   : (it.hinted?'<div class="bub me" style="opacity:.8">'+esc(it.s.hint)+'…</div>':'')+'<button class="typing" data-act="reveal" aria-label="Show the reply"><span>•</span> <span>•</span> <span>•</span></button>')+'</div>';
  }
  if (lay === "comment") {
    return '<div class="sticker"><i></i><div><small>Reply to '+esc(s.commentUser)+'’s comment</small><span>'+esc(s.commentText)+'</span></div></div>'+
      questionHTML(it,.82)+revealOrAnswer(it);
  }
  if (lay === "quiz" && s.correct >= 0) {
    const opts = s.options.map((o,i)=>{ let cls = "opt"; if (it.pick >= 0) { if (i===s.correct) cls += " right"; else if (i===it.pick) cls += " wrong"; else cls += " dim"; }
      return '<button class="'+cls+'" data-act="pick" data-v="'+i+'"><span class="l">'+"AB"[i]+'</span>'+esc(o)+'</button>'; }).join("");
    return '<div class="hook">'+esc(s.hook)+'</div><div class="poll"><div class="pq">'+esc(s.q)+'</div>'+(it.hinted&&it.pick<0?'<div class="pollnote"><b>Hint</b> '+esc(s.hint)+'</div>':'')+opts+
      (it.pick>=0?'<div class="pollnote"><b>'+(it.pick===s.correct?"You got it.":"Classic trap. It's "+esc(s.a)+".")+'</b> '+esc(s.x)+'</div>':'')+'</div>';
  }
  if (lay === "captions" && s.beats.length) {
    const n = it.beat == null ? 0 : it.beat;
    if (n < s.beats.length && !it.revealed) {
      const words = s.beats[n].split(" "), hi = s.hl[n];
      const upper = it.f.look !== "paper" && it.f.look !== "clean";
      const inner = words.map((w,k)=> k===hi ? "<em>"+esc(upper?w.toUpperCase():w)+"</em>" : esc(upper?w.toUpperCase():w)).join(" ");
      const bl = Math.max(...words.map(w=>w.length)), bpx = Math.round(Math.min(40, 250 / (bl * (upper ? .66 : .5))));
      return '<div class="hook">'+esc(s.hook)+'</div><div class="beat" style="font-size:'+bpx+'px">'+(it.f.look==="subtitle"?'<span>'+inner+'</span>':inner)+'</div>'+
        '<div class="beatcount">'+s.beats.map((_,k)=>'<i class="'+(k<=n?"on":"")+'"></i>').join("")+'</div>';
    }
    return '<div class="hook">'+esc(s.hook)+'</div>'+questionHTML(it,.8)+revealOrAnswer(it);
  }
  return '<div class="hook">'+esc(s.hook)+'</div>'+questionHTML(it, lay==="split"?.8:1)+revealOrAnswer(it);
}

function vibeHTML(it){
  const v = it.s.vibe; if (!v) return "";
  const good = it.liked || (it.pick>=0 && it.pick===it.s.correct), bad = (it.pick>=0 && it.pick!==it.s.correct) || (it.revealed && !it.liked && it.f.layout!=="quiz" && it.done);
  switch (v) {
    case "football": return '<div class="vibe"><div class="score"><span class="t">YOU</span><b>'+(good?1:0)+'–'+(bad?1:0)+'</b><span>EXAM</span></div><span class="vpill" style="background:#101418;color:#fff">90+3′</span></div>';
    case "gaming": return '<div class="vibe"><div class="hud">LVL 7 <i><b style="width:'+(good?"78%":"35%")+'"></b></i></div></div>';
    case "music": return '<div class="vibe"><span class="vpill"><span class="eqmini"><i></i><i></i><i></i></span> revision mix</span></div>';
    case "fitness": return '<div class="vibe"><span class="vpill">'+(good?"PR ✓":"SET 1 · REP 1")+'</span></div>';
    case "beauty": return '<div class="vibe"><span class="vpill">✦ GRWM · revision edition</span></div>';
    case "food": return '<div class="vibe"><span class="vpill">let it cook · 0:03</span></div>';
    case "comedy": return '<div class="vibe"><span class="vpill">no laughing</span></div>';
    default: return "";
  }
}

function railHTML(it, host){
  const liked = it.liked, s = it.s;
  const like = host==="youtube" ? I.thumb(liked) : I.heart(liked);
  const share = host==="instagram" ? I.send : I.share;
  const initial = esc((it.card.deck||"R").replace(/^(GCSE|A-level)\s+/i,"")[0]||"R");
  return '<div class="rail"><div class="av" aria-hidden="true">'+initial+'</div>'+
    '<button data-act="like" class="'+(liked?"on":"")+'" aria-label="Like: I knew it">'+like+'<span>'+(liked?(it.likedBefore?"Easy":"Good"):"Knew it")+'</span></button>'+
    '<button data-act="hint" aria-label="Hint">'+I.bubble+'<span>Hint</span></button>'+
    '<button data-act="save" class="'+(it.saved?"saved":"")+'" aria-label="Save for later">'+I.bookmark(it.saved)+'<span>'+(it.saved?"Saved":"Save")+'</span></button>'+
    '<button data-act="share" aria-label="Share with a friend">'+share+'<span>Share</span></button></div>';
}

function bandHTML(it, host){
  const s = it.s, deck = it.card.deck || "Study";
  const tags = "#" + deck.toLowerCase().replace(/[^a-z0-9 ]/g,"").trim().split(/\s+/).slice(0,2).join(" #");
  const ini = esc(deck.replace(/^(GCSE|A-level)\s+/i,"")[0]||"R");
  if (host === "youtube") return '<div class="band"><div class="title">'+esc(s.cap)+' '+esc(tags)+'</div><div class="who"><span class="dot">'+ini+'</span>'+esc(s.account)+' <span class="pill">'+esc(deck.replace(/^GCSE\s+/,""))+'</span></div><div class="sound">'+I.note+'<span>'+esc(s.sound)+'</span></div></div>';
  if (host === "instagram") return '<div class="band"><div class="who"><span class="dot">'+ini+'</span>'+esc(s.account.slice(1))+' <span class="pill">'+esc(deck.replace(/^GCSE\s+/,""))+'</span></div><div>'+esc(s.cap)+' <span class="tags">'+esc(tags)+'</span></div><div class="sound">'+I.note+'<span>'+esc(s.sound.replace("original sound","Original audio"))+'</span></div></div>';
  return '<div class="band"><div class="who">'+esc(s.account)+'</div><div>'+esc(s.cap)+' <span class="tags">'+esc(tags)+'</span></div><div class="sound">'+I.note+'<span>'+esc(s.sound)+'</span></div></div>';
}

/* it: {card, f, s, host, revealed, liked, likedBefore, pick, saved, hinted, beat, count}; opts: {night, tint} */
function card(it, opts){
  opts = opts || {};
  const host = it.host==="instagram"||it.host==="youtube" ? it.host : "tiktok";
  const sheetLook = it.f.look==="clean"||it.f.look==="paper";
  const L = palette(it.f.look, opts.night && sheetLook, opts.tint);
  // light looks are drawn as a notes "sheet" floating over dark footage (like a creator screenshotting
  // their notes), so the card still reads as a reel rather than a slide
  const sheet = sheetLook && it.f.layout!=="quiz";
  const cls = ["card","h-"+host,"k-"+it.f.look,"l-"+it.f.layout, sheet?"sheet":"", opts.night?"night":""].join(" ");
  const vars = "--fg:"+L.fg+";--acc:"+L.acc+";--accfg:"+L.accfg+";--panel:"+L.panel+";--bubble:"+L.bubble+";--bgc:"+(sheetLook?(opts.tint!=null?"hsl("+opts.tint+" 42% 12%)":"#0f0f12"):L.bg)+";--sheet:"+L.bg;
  const bg = it.f.bg, split = it.f.layout==="split";
  const bgOpacity = sheetLook ? .6 : (it.f.look==="subtitle" ? .75 : .42);
  let back = "";
  if (split) back += '<div class="splittop"><div class="bgl bg-'+(bg==="plain"?"gameplay":bg)+'"></div></div>'; // split-screen always has footage on top
  else if (bg!=="plain") back += '<div class="bgl bg-'+bg+'" style="opacity:'+bgOpacity+'"></div>';
  const badge = it.liked ? (it.hinted ? "With a hint · Hard" : it.likedBefore ? "Knew it · Easy" : "Knew it · Good") : (it.pick>=0 ? (it.pick===it.s.correct ? "Correct" : "Not quite") : "");
  const coach = opts.coach ? '<div class="coach" aria-hidden="true">Tap to see the answer · Double-tap if you knew it</div>' : '';
  return '<div class="'+cls+'" data-card style="'+vars+'">'+back+'<div class="scrim"></div>'+vibeHTML(it)+coach+
    '<div class="body">'+bodyHTML(it)+'</div>'+
    (badge?'<span class="badge">'+badge+'</span>':'')+
    railHTML(it, host)+bandHTML(it, host)+'<div class="prog"><i></i></div></div>';
}

/* A normal reel from someone's feed (the thing the card sits between). */
function plainReel(n, hue, host){
  const creators=["@dan.cooks","@footy.clips","@mia.dance","@techtalk","@catsofldn","@gymtok.sam","@fitcheck","@minecraft.builds"],caps=["wait for the ending","day 12 of learning to cook","this goal though","rate this fit","he really did that","tutorial in comments"];
  return '<div class="reel plain-reel" style="background:radial-gradient(circle at 50% 35%,hsl('+hue+' 45% 30%),hsl('+hue+' 40% 12%))"><div class="big">Reel '+n+'</div><div class="small">(their normal '+({instagram:"Instagram",youtube:"YouTube"}[host]||"TikTok")+' video plays here)</div>'+
    '<div style="position:absolute;left:18px;bottom:15%;text-align:left;color:#fff;font-size:13px;text-shadow:0 1px 2px #000"><b style="font-size:14px">'+creators[n%8]+'</b><br>'+caps[n%6]+'</div></div>';
}

return {card, plainReel, esc, LOOKS, I};
})();
