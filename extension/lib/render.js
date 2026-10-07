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

/* ---- long answers: shape helpers ---- */
const isMulti = it => !!(it.s.points && it.s.points.length >= 2);
const answered = it => it.revealed || it.pick >= 0;
// once a multi-point answer is showing, the question shrinks up out of the way (like a caption morphing)
const compact = it => isMulti(it) && answered(it);
const gotCount = it => it.liked ? it.s.points.length : (it.got || []).filter(Boolean).length;
const readMs = t => 1500 + 45 * (t ? t.length : 0);
/* seconds on the "think" ring: grows with how long the question takes to read */
function thinkSecs(s){ return Math.max(3, Math.min(7, Math.ceil((readMs((s.ctx||"") + " " + (s.qMain||s.q)) - 1000) / 1000))); }
/* when each point drops in: quicker for short points, slower for long ones */
function ptDelays(s){ const d = []; let t = 0; for (const p of s.points) { d.push(t); t += Math.max(500, Math.min(1500, 380 + 26*p.length)); } d.push(t); return d; }
function ptSize(s){ const m = Math.max(...s.points.map(p=>p.length)); return m <= 28 ? 17.5 : m <= 60 ? 15.5 : 14.5; }
function ptLabel(p){ const m = p.match(/^([^:]{1,24}):\s+(.+)$/); return m ? '<b>'+esc(m[1])+'</b> '+esc(m[2]) : esc(p); }

function qPx(look, text, scale){
  const size = {caption:34, subtitle:27, clean:31, neon:36, paper:40}[look] || 32;
  // long questions shrink so they always fit the safe zone
  const len = text.length, fit = len > 110 ? .7 : len > 80 ? .8 : len > 55 ? .9 : 1;
  // never break a word: the longest word must fit the ~250px text column
  const longest = Math.max(...text.split(/\s+/).map(w=>w.length));
  const em = {caption:.66, subtitle:.6, clean:.58, neon:.5, paper:.45}[look] || .6;
  return Math.round(Math.min(size * scale * fit, 244 / (longest * em)));
}
function marksHTML(it){ return it.s.marks && !compact(it) ? '<div class="marks">'+it.s.marks+' marks</div>' : ''; }
/* a cloze gap ("[…]" or "[hint]") drawn as a blank to fill; no key-word highlight competes with it */
function gapify(html){ return html.replace(/\[([^\]]{0,40})\]/g, '<span class="gap">$1</span>'); }
function questionText(it, q){
  const look = it.f.look, up = (look==="caption"||look==="neon") && !RC.caseMatters(it.s.q);
  if (it.s.cloze) { const t = esc(up ? q.toUpperCase() : q); return gapify(t); }
  return withEm(q, up);
}
/* a picture from an Anki card; image occlusion draws its masks over it */
function pictureHTML(it){
  const s = it.s;
  if (s.io) {
    const shown = answered(it), rows = [];
    for (const sh of s.io.shapes) {
      const target = sh.ord === s.io.target;
      if (sh.kind === "text") continue;
      if (!target && (!sh.oi || it.hinted)) continue; // hint: the other labels uncover
      const cls = target ? (shown ? "m t open" : "m t") : "m o";
      if (sh.kind === "rect") rows.push('<rect class="'+cls+'" x="'+sh.x+'" y="'+sh.y+'" width="'+sh.w+'" height="'+sh.h+'" vector-effect="non-scaling-stroke"/>');
      else if (sh.kind === "ellipse") rows.push('<ellipse class="'+cls+'" cx="'+(sh.x+sh.w/2)+'" cy="'+(sh.y+sh.h/2)+'" rx="'+(sh.w/2)+'" ry="'+(sh.h/2)+'" vector-effect="non-scaling-stroke"/>');
      else if (sh.pts) rows.push('<polygon class="'+cls+'" points="'+sh.pts.map(a=>a[0]+","+a[1]).join(" ")+'" vector-effect="non-scaling-stroke"/>');
    }
    const labels = s.io.shapes.filter(sh => sh.kind === "text").map(sh => '<span class="iolabel" style="left:'+(sh.x*100)+'%;top:'+(sh.y*100)+'%">'+esc(sh.text)+'</span>').join("");
    return '<div class="pic io" style="'+picSize(s.imgW, s.imgH)+'"><img src="'+esc(s.img)+'" alt=""><svg viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">'+rows.join("")+'</svg>'+labels+'</div>';
  }
  return s.img ? '<div class="pic" style="'+picSize(s.imgW, s.imgH)+'"><img src="'+esc(s.img)+'" alt=""></div>' : '';
}
/* sized from the picture's own dimensions, so masks line up exactly; width and height limits are in CSS */
function picSize(w, h){ return w && h ? '--r:'+(w/h).toFixed(4)+';aspect-ratio:'+w+'/'+h : ''; }
function questionHTML(it, scale){
  const look = it.f.look, s = it.s, q = s.ctx ? s.qMain : s.q;
  const px = qPx(look, q, scale * (compact(it) ? .6 : 1) * (s.io ? .6 : s.img ? .7 : 1));
  const inner = look === "subtitle" ? '<span>'+questionText(it, q)+'</span>' : questionText(it, q);
  // a long question's set-up sits in a small quoted box; the actual question stays big
  return (s.ctx ? '<div class="ctx">'+esc(s.ctx)+'</div>' : '') +
    '<h2 class="q'+(RC.caseMatters(s.q)?' keepcase':'')+'" style="font-size:calc('+px+'px * var(--fit,1))">'+inner+'</h2>' + (it.f.layout==="list" ? '' : marksHTML(it));
}

/* the answer as points, dropping in one after another; tap the ones you got */
function pointsHTML(it){
  const s = it.s, n = s.points.length, d = ptDelays(s), got = it.got || [], k = gotCount(it);
  const lis = s.points.map((p,i)=>{ const on = it.liked || got[i];
    return '<li><button class="pt'+(on?' got':'')+'" data-act="tick" data-v="'+i+'" style="animation-delay:'+d[i]+'ms" aria-pressed="'+(!!on)+'" aria-label="Point '+(i+1)+': '+esc(p)+'. Tap if you got it"><b class="n">'+(i+1)+'</b><span class="tx">'+ptLabel(p)+'</span><i class="tk" aria-hidden="true"></i></button></li>'; }).join("");
  const note = k ? (k >= n ? 'Got all '+n : 'Got '+k+' of '+n) : 'Tap the ones you got';
  return '<ol class="pts'+(s.shape==="steps"?' steps':'')+'" style="--ptpx:'+ptSize(s)+'px">'+lis+'</ol><div class="selfcheck'+(k?' on':'')+'" style="animation-delay:'+d[n]+'ms">'+note+'</div>';
}
/* numbered list layout, before the reveal: one blank per point, so you know how many to recall */
function slotsHTML(it){
  const s = it.s;
  return '<ol class="pts slots'+(s.shape==="steps"?' steps':'')+'" style="--ptpx:'+ptSize(s)+'px">'+s.points.map((p,i)=>'<li><div class="pt slot"><b class="n">'+(i+1)+'</b>'+
    (it.hinted ? '<span class="tx hintx">'+esc(s.starts[i])+'…</span>' : '<span class="redact" style="width:'+Math.round(Math.min(86, 24 + p.length*1.2))+'%"></span>')+'</div></li>').join("")+'</ol>';
}
function revealOrAnswer(it){
  if (!it.revealed) {
    const hintChip = it.hinted && it.f.layout!=="list" ? '<div class="hintchip"><b>Hint</b> '+esc(it.s.hint)+'</div>' : '';
    return hintChip + revealButton(it);
  }
  if (isMulti(it)) return pointsHTML(it) + answerPic(it) + soundButton(it, true);
  if (it.s.io) return '<div class="selfcheck">'+(it.liked ? 'Got it' : 'Double-tap if you got it')+'</div>';
  const a = it.s.a, tier = a.length > 40 ? ' t3' : a.length > 22 ? ' t2' : '';
  if (!a) return (it.s.x ? '<div class="ans t3"><div class="a2">'+esc(it.s.x)+'</div></div>' : '') + answerPic(it) + soundButton(it, true); // the answer is a picture or a sound
  return '<div class="ans'+tier+'"><div class="a1">'+esc(a)+'</div>'+(it.s.x?'<div class="a2">'+esc(it.s.x)+'</div>':'')+'</div>' + answerPic(it) + soundButton(it, true);
}
/* an Anki card's sound ([sound:…]): "Listen" with the question, "Hear it" with the answer (mirrors CardView.addSound) */
const soundButton = (it, answer) => (answer ? it.s.asnd : it.s.snd) ? '<button class="snd" data-act="sound" data-v="'+(answer?"a":"q")+'" aria-label="Play the '+(answer?"answer":"question")+'\'s sound">▶&nbsp; '+(answer?"Hear it":"Listen")+'</button>' : '';
const answerPic = it => it.s.aimg ? '<div class="pic apic" style="'+picSize(it.s.aimgW, it.s.aimgH)+'"><img src="'+esc(it.s.aimg)+'" alt=""></div>' : '';
/* the first-cards tip, in words that fit the card: a quiz is answered by picking (mirrors CardView.coachText) */
function coachText(it){
  if (it.f.layout === "quiz" && it.s.correct >= 0) return "Tap the answer you think is right";
  if (it.s.io) return "Tap to uncover it · Double-tap if you knew it";
  return "Tap to see the answer · Double-tap if you knew it";
}
function revealButton(it){ return '<button class="reveal" data-act="reveal"><span class="ring">'+(it.count==null?thinkSecs(it.s):it.count)+'</span><span>'+esc(it.s.prompt)+'</span></button>'; }

function bodyHTML(it){
  const s = it.s, lay = it.f.layout;
  if (lay === "chat") {
    let mine;
    if (!it.revealed) mine = (it.hinted?'<div class="bub me" style="opacity:.8">'+esc(it.s.hint)+'…</div>':'')+'<button class="typing" data-act="reveal" aria-label="Show the reply"><span>•</span> <span>•</span> <span>•</span></button>';
    else if (isMulti(it)) {
      // a long answer arrives the way people text: one message per point. React ✓ to the ones you got
      const d = ptDelays(s), got = it.got || [], n = s.points.length, k = gotCount(it);
      mine = s.me.map((t,i)=>{ const on = it.liked || got[i]; return '<button class="bub me'+(on?' got':'')+'" data-act="tick" data-v="'+i+'" style="animation-delay:'+d[i]+'ms" aria-pressed="'+(!!on)+'">'+esc(t)+(on?'<i class="tb" aria-hidden="true">✓</i>':'')+'</button>'; }).join("")+
        '<div class="sysmsg" style="animation-delay:'+d[n]+'ms">'+(k ? (k>=n ? 'You got all '+n : 'You got '+k+' of '+n) : 'Tap the messages you got')+'</div>';
    } else mine = s.me.map(t=>'<div class="bub me">'+esc(t)+'</div>').join("")+'<div class="bub them late" style="animation-delay:500ms">'+esc(s.after)+'</div>';
    // once a long answer arrives, the small talk drops out so the question stays in view above it
    const them = it.revealed && isMulti(it) && s.themQ ? s.themQ : s.them;
    return '<div class="chat"><div class="chathead"><i>'+esc(s.name[0])+'</i><span><b>'+esc(s.name)+'</b><small>revision chat · now</small></span></div><div class="msgs">'+
      them.map(t=>'<div class="bub them">'+esc(t)+'</div>').join("")+mine+'</div></div>';
  }
  if (lay === "comment") {
    // a long question is carried by the comment itself, so it isn't repeated in big text
    return '<div class="sticker"><i></i><div><small>Reply to '+esc(s.commentUser)+'’s comment</small><span>'+esc(s.commentText)+'</span></div></div>'+
      (s.q.length <= 90 ? questionHTML(it,.82) : marksHTML(it))+revealOrAnswer(it);
  }
  if (lay === "list" && isMulti(it)) {
    return '<div class="hook">'+esc(s.hook)+'</div>'+questionHTML(it,.86)+(it.revealed ? pointsHTML(it) : slotsHTML(it)+revealButton(it));
  }
  if (lay === "quiz" && s.correct >= 0) {
    const opts = s.options.map((o,i)=>{ let cls = "opt"; if (it.pick >= 0) { if (i===s.correct) cls += " right"; else if (i===it.pick) cls += " wrong"; else cls += " dim"; }
      return '<button class="'+cls+'" data-act="pick" data-v="'+i+'"><span class="l">'+"AB"[i]+'</span>'+esc(o)+'</button>'; }).join("");
    return '<div class="hook">'+esc(s.hook)+'</div><div class="poll"><div class="pq">'+(s.cloze ? gapify(esc(s.q)) : esc(s.q))+'</div>'+(it.hinted&&it.pick<0?'<div class="pollnote"><b>Hint</b> '+esc(s.hint)+'</div>':'')+opts+
      (it.pick>=0?'<div class="pollnote"><b>'+(it.pick===s.correct?"You got it.":"Classic trap. It's "+esc(s.a)+".")+'</b> '+esc(s.x)+'</div>':'')+'</div>';
  }
  if (lay === "captions" && s.beats.length) {
    const n = it.beat == null ? 0 : it.beat;
    if (n < s.beats.length && !it.revealed) {
      const words = s.beats[n].split(" "), hi = s.hl[n];
      const upper = it.f.look !== "paper" && it.f.look !== "clean" && !RC.caseMatters(s.q);
      const inner = s.cloze ? gapify(esc(upper?s.beats[n].toUpperCase():s.beats[n])) : words.map((w,k)=> k===hi ? "<em>"+esc(upper?w.toUpperCase():w)+"</em>" : esc(upper?w.toUpperCase():w)).join(" ");
      const bl = Math.max(...words.map(w=>w.length)), bpx = Math.round(Math.min(40, 250 / (bl * (upper ? .66 : .5))));
      return '<div class="hook">'+esc(s.hook)+'</div><div class="beat'+(upper?'':' keepcase')+'" style="font-size:calc('+bpx+'px * var(--fit,1))">'+(it.f.look==="subtitle"?'<span>'+inner+'</span>':inner)+'</div>'+
        '<div class="beatcount">'+s.beats.map((_,k)=>'<i class="'+(k<=n?"on":"")+'"></i>').join("")+'</div>';
    }
    return '<div class="hook">'+esc(s.hook)+'</div>'+questionHTML(it,.8)+revealOrAnswer(it);
  }
  // image occlusion: the picture leads, full width at the top (clear of the rail); the header sits under it
  if (s.io) return pictureHTML(it)+questionHTML(it, .8)+revealOrAnswer(it);
  return (compact(it) ? '' : '<div class="hook">'+esc(s.hook)+'</div>')+questionHTML(it, lay==="split"?.8:1)+pictureHTML(it)+soundButton(it, false)+revealOrAnswer(it);
}

function vibeHTML(it){
  const v = it.s.vibe; if (!v) return "";
  const good = it.liked || (it.pick>=0 && it.pick===it.s.correct) || (isMulti(it) && gotCount(it) >= it.s.points.length), bad = (it.pick>=0 && it.pick!==it.s.correct) || (it.revealed && !it.liked && it.f.layout!=="quiz" && it.done);
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

/* The caption line. Once the answer is showing, a long explanation lives here behind "more",
   the way a long caption does on every reel app; tapping it opens the caption over a dimmed card. */
function capLine(it, tags){
  const s = it.s;
  if (!answered(it) || !s.more) return esc(s.cap)+' <span class="tags">'+esc(tags)+'</span>';
  if (it.moreOpen || s.more.length <= 64) return '<span class="mtxt">'+esc(s.more)+'</span> <span class="tags">'+esc(tags)+'</span>'+(s.more.length > 64 ? ' <button class="morebtn" data-act="more">less</button>' : '');
  const sp = s.more.lastIndexOf(" ", 58), cut = s.more.slice(0, sp > 24 ? sp : 58).replace(/[\s,;:.]+$/,"");
  return '<span class="mtxt">'+esc(cut)+'…</span> <button class="morebtn" data-act="more" aria-label="Show the full explanation">more</button>';
}
function bandHTML(it, host){
  const s = it.s, deck = it.card.deck || "Study";
  const tags = "#" + deck.toLowerCase().replace(/[^a-z0-9 ]/g,"").trim().split(/\s+/).slice(0,2).join(" #");
  const ini = esc(deck.replace(/^(GCSE|A-level)\s+/i,"")[0]||"R");
  if (host === "youtube") return '<div class="band"><div class="title">'+capLine(it, tags)+'</div><div class="who"><span class="dot">'+ini+'</span>'+esc(s.account)+' <span class="pill">'+esc(RC.deckLabel(deck))+'</span></div><div class="sound">'+I.note+'<span>'+esc(s.sound)+'</span></div></div>';
  if (host === "instagram") return '<div class="band"><div class="who"><span class="dot">'+ini+'</span>'+esc(s.account.slice(1))+' <span class="pill">'+esc(RC.deckLabel(deck))+'</span></div><div class="capline">'+capLine(it, tags)+'</div><div class="sound">'+I.note+'<span>'+esc(s.sound.replace("original sound","Original audio"))+'</span></div></div>';
  return '<div class="band"><div class="who">'+esc(s.account)+'</div><div class="capline">'+capLine(it, tags)+'</div><div class="sound">'+I.note+'<span>'+esc(s.sound)+'</span></div></div>';
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
  const cls = ["card","h-"+host,"k-"+it.f.look,"l-"+it.f.layout, sheet?"sheet":"", opts.night?"night":"",
    answered(it)?"rv":"", isMulti(it)?"mp":"", it.moreOpen?"moreopen":"", it.settled?"settled":""].join(" ").replace(/\s+/g," ");
  const vars = "--fg:"+L.fg+";--acc:"+L.acc+";--accfg:"+L.accfg+";--panel:"+L.panel+";--bubble:"+L.bubble+";--bgc:"+(sheetLook?(opts.tint!=null?"hsl("+opts.tint+" 42% 12%)":"#0f0f12"):L.bg)+";--sheet:"+L.bg;
  const bg = it.f.bg, split = it.f.layout==="split";
  const bgOpacity = sheetLook ? .6 : (it.f.look==="subtitle" ? .75 : .42);
  let back = "";
  if (split) back += '<div class="splittop"><div class="bgl bg-'+(bg==="plain"?"gameplay":bg)+'"></div></div>'; // split-screen always has footage on top
  else if (bg!=="plain") back += '<div class="bgl bg-'+bg+'" style="opacity:'+bgOpacity+'"></div>';
  const n = isMulti(it) ? it.s.points.length : 1, k = isMulti(it) ? gotCount(it) : 0;
  const partGrade = k >= n ? "Good" : k*2 >= n ? "Hard" : "Again";
  const badge = it.liked ? (it.hinted ? "With a hint · Hard" : it.likedBefore ? "Knew it · Easy" : "Knew it · Good") : (it.pick>=0 ? (it.pick===it.s.correct ? "Correct" : "Not quite") :
    k ? (k>=n ? "All "+n : k+"/"+n)+" · "+(it.hinted && partGrade==="Good" ? "Hard" : partGrade) : "");
  // the progress line runs for as long as the card takes to read
  const dur = Math.max(10, Math.min(30, (readMs(it.s.q) + readMs(it.s.points ? it.s.points.join(" ") : it.s.a) + 3000) / 1000));
  const coach = opts.coach ? '<div class="coach" aria-hidden="true">'+coachText(it)+'</div>' : '';
  return '<div class="'+cls+'" data-card style="'+vars+'">'+back+'<div class="scrim"></div>'+vibeHTML(it)+coach+
    '<div class="body">'+bodyHTML(it)+'</div>'+
    (badge?'<span class="badge">'+badge+'</span>':'')+
    railHTML(it, host)+bandHTML(it, host)+'<div class="prog"><i style="animation-duration:'+dur.toFixed(1)+'s"></i></div></div>';
}

/* A normal reel from someone's feed (the thing the card sits between). */
function plainReel(n, hue, host){
  const creators=["@dan.cooks","@footy.clips","@mia.dance","@techtalk","@catsofldn","@gymtok.sam","@fitcheck","@minecraft.builds"],caps=["wait for the ending","day 12 of learning to cook","this goal though","rate this fit","he really did that","tutorial in comments"];
  return '<div class="reel plain-reel" style="background:radial-gradient(circle at 50% 35%,hsl('+hue+' 45% 30%),hsl('+hue+' 40% 12%))"><div class="big">Reel '+n+'</div><div class="small">(their normal '+({instagram:"Instagram",youtube:"YouTube"}[host]||"TikTok")+' video plays here)</div>'+
    '<div style="position:absolute;left:18px;bottom:15%;text-align:left;color:#fff;font-size:13px;text-shadow:0 1px 2px #000"><b style="font-size:14px">'+creators[n%8]+'</b><br>'+caps[n%6]+'</div></div>';
}

return {card, plainReel, esc, LOOKS, I, thinkSecs, ptDelays};
})();
