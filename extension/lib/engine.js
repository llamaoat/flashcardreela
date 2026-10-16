/* Reelcards engine, JavaScript port of engine/src/main/java/com/reelcards/engine (same rules, same numbers).
   Kept deliberately close to the Java so the browser demo and the Android app behave the same. */
"use strict";
const RC = (function(){
const MIN = 60000, DAY = 86400000, SESSION_GAP = 10 * MIN, ABSORBED_SEC = 25, SKIMMING_SEC = 3;
const clamp = (v, a, b) => Math.max(a, Math.min(b, v));
const NEW = 0, LEARNING = 1, REVIEW = 2, RELEARNING = 3, LEECH_LAPSES = 6;
const STATE = ["new", "learning", "review", "relearning"];
const G = {again: 1, hard: 2, good: 3, easy: 4};
const GL = ["", "again", "hard", "good", "easy"];

/* ---------------- random ---------------- */
function randn(rnd){let u=0,v=0;while(!u)u=rnd();while(!v)v=rnd();return Math.sqrt(-2*Math.log(u))*Math.cos(2*Math.PI*v);}
function gamma(rnd,k){if(k<1)return gamma(rnd,k+1)*Math.pow(rnd(),1/k);const d=k-1/3,c=1/Math.sqrt(9*d);for(;;){let x,v;do{x=randn(rnd);v=1+c*x;}while(v<=0);v=v*v*v;const u=rnd();if(u<1-.0331*x*x*x*x)return d*v;if(Math.log(u)<.5*x*x+d*(1-v+Math.log(v)))return d*v;}}
function beta(rnd,a,b){const x=gamma(rnd,a),y=gamma(rnd,b);return x/(x+y);}
const pickOf = (rnd, a) => a[Math.floor(rnd() * a.length)];

/* ---------------- FSRS-6 (py-fsrs defaults) ---------------- */
const DEFAULT_W = [0.212,1.2931,2.3065,8.2956,6.4133,0.8334,3.0194,0.001,1.8722,0.1666,0.796,1.4835,0.0614,0.2629,1.6483,0.6014,1.8729,0.5425,0.0912,0.0658,0.1542];
const FUZZ = [[2.5,7,0.15],[7,20,0.1],[20,Infinity,0.05]];
function Fsrs(rnd){
  this.w = DEFAULT_W.slice(); this.learningSteps=[1*MIN,10*MIN]; this.relearningSteps=[10*MIN];
  this.maxIntervalDays=365; this.fuzz=true; this.personalScale=1; this.rnd=rnd;
}
Fsrs.prototype = {
  decay(){return -this.w[20];}, factor(){return Math.pow(0.9,1/this.decay())-1;},
  R(el,s){ if(s<=0)return 0; return Math.pow(1+this.factor()*Math.max(0,el)/s,this.decay()); },
  I(s,r){ return s/this.factor()*(Math.pow(r,1/this.decay())-1); },
  initS(g){return Math.max(0.001,this.w[g-1]);},
  initD(g,cl){const d=this.w[4]-Math.exp(this.w[5]*(g-1))+1;return cl?clamp(d,1,10):d;},
  nextD(d,g){const delta=-(this.w[6]*(g-3));const damped=d+(10-d)*delta/9;return clamp(this.w[7]*this.initD(4,false)+(1-this.w[7])*damped,1,10);},
  shortS(s,g){let inc=Math.exp(this.w[17]*(g-3+this.w[18]))*Math.pow(s,-this.w[19]);if(g!==1)inc=Math.max(inc,1);return Math.max(0.001,s*inc);},
  recallS(d,s,r,g){const w=this.w;return s*(1+Math.exp(w[8])*(11-d)*Math.pow(s,-w[9])*(Math.exp((1-r)*w[10])-1)*(g===2?w[15]:1)*(g===4?w[16]:1));},
  forgetS(d,s,r){const w=this.w;return Math.min(w[11]*Math.pow(d,-w[12])*(Math.pow(s+1,w[13])-1)*Math.exp((1-r)*w[14]), s/Math.exp(w[17]*w[18]));},
  nextS(d,s,r,g){return Math.max(0.001,g===1?this.forgetS(d,s,r):this.recallS(d,s,r,g));},
  recallNow(c,t){ if(c.state===NEW||!c.last)return 0; return this.R((t-c.last)/DAY,c.s*this.personalScale); },
  recallAt(c,t){ return this.recallNow(c,t); },
  target(t,exam){ if(exam>t){const left=exam-t;if(left<=7*DAY)return .95;if(left<=21*DAY)return .92;} return .9; },
  fuzzed(days){ if(days<2.5)return days; let delta=1; for(const f of FUZZ)delta+=f[2]*Math.max(Math.min(days,f[1])-f[0],0);
    let lo=Math.round(days-delta),hi=Math.round(days+delta);lo=Math.max(2,lo);hi=Math.min(hi,this.maxIntervalDays);lo=Math.min(lo,hi);
    return Math.min(Math.round(this.rnd()*(hi-lo+1)+lo),this.maxIntervalDays); },
  reviewInterval(s,t,exam){ let days=Math.round(this.I(s*this.personalScale,this.target(t,exam))); days=Math.max(1,Math.min(this.maxIntervalDays,days));
    if(this.fuzz)days=this.fuzzed(days); if(exam>t){const left=Math.floor((exam-t)/DAY);const cap=Math.max(1,left-1);if(days>cap)days=cap;} return days*DAY; },
  review(c,g,t,exam){
    const elapsed=c.last?Math.max(0,Math.floor((t-c.last)/DAY)):-1, sameDay=elapsed>=0&&elapsed<1; let next;
    if(c.state!==REVIEW){
      const relearning=c.state===RELEARNING;
      if(c.state===NEW||c.s<=0){c.s=this.initS(g);c.d=this.initD(g,true);c.state=LEARNING;c.step=0;}
      else if(sameDay){c.s=this.shortS(c.s,g);c.d=this.nextD(c.d,g);}
      else{const r=this.R(elapsed,c.s);c.s=this.nextS(c.d,c.s,r,g);c.d=this.nextD(c.d,g);}
      const steps=relearning?this.relearningSteps:this.learningSteps;
      const grad=()=>{c.state=REVIEW;c.step=0;return this.reviewInterval(c.s,t,exam);};
      if(!steps.length||c.step>=steps.length)next=grad();
      else if(g===1){c.step=0;next=steps[0];}
      else if(g===2){next=c.step===0&&steps.length===1?Math.round(steps[0]*1.5):c.step===0?(steps[0]+steps[1])/2:steps[c.step];}
      else if(g===3){if(c.step+1===steps.length)next=grad();else{c.step++;next=steps[c.step];}}
      else next=grad();
    }else{
      if(sameDay){c.s=this.shortS(c.s,g);c.d=this.nextD(c.d,g);}
      else{const r=this.R(elapsed,c.s);c.s=this.nextS(c.d,c.s,r,g);c.d=this.nextD(c.d,g);}
      if(g===1){c.lapses++;if(!this.relearningSteps.length)next=this.reviewInterval(c.s,t,exam);else{c.state=RELEARNING;c.step=0;next=this.relearningSteps[0];}}
      else next=this.reviewInterval(c.s,t,exam);
    }
    c.reps++;c.last=t;c.grade=GL[g];c.due=t+next;return next/DAY;
  }
};

/* ---------------- Calibration (one-number personal forgetting rate) ---------------- */
function Calibration(){this.log=[];this.scale=1;this.fittedOn=0;}
Calibration.prototype={
  add(days,s,ok){if(days<1||s<=0)return;this.log.push([days,s,ok]);if(this.log.length>2000)this.log.shift();},
  maybeRefit(f){ if(this.log.length<100||this.log.length-this.fittedOn<10)return false; let best=1,bl=Infinity;
    for(let k=0.4;k<=2.51;k+=0.05){let loss=Math.pow(Math.log(k),2);for(const e of this.log){const r=clamp(f.R(e[0],e[1]*k),1e-4,1-1e-4);loss-=e[2]?Math.log(r):Math.log(1-r);}if(loss<bl){bl=loss;best=k;}}
    this.fittedOn=this.log.length;const changed=Math.abs(best-this.scale)>1e-9;this.scale=Math.round(best*100)/100;f.personalScale=this.scale;return changed; },
  describe(){ if(!this.fittedOn)return null; const pct=Math.round((this.scale-1)*100); if(Math.abs(pct)<8)return "You forget at about the rate FSRS expects."; return pct>0?"You remember about "+pct+"% longer than FSRS's average learner.":"You forget about "+(-pct)+"% faster than FSRS's average learner."; }
};

/* ---------------- Rhythm (learned spacing between cards) ---------------- */
const MULT=[0.6,0.8,1.0,1.3,1.7,2.2];
function Rhythm(){this.a=MULT.map(()=>0);this.b=MULT.map(()=>0);this.arm=2;this.seeded=false;}
Rhythm.prototype={
  seed(base){for(let i=0;i<MULT.length;i++){const g=base*MULT[i];const m=Math.min(.9,.5*(g+1)/(base+1));this.a[i]=4*m;this.b[i]=4*(1-m);}this.seeded=true;},
  multiplier(){return MULT[this.arm];},
  choose(rnd,base){if(!this.seeded)this.seed(base);let best=this.arm,bv=-1;for(let i=0;i<MULT.length;i++){const v=beta(rnd,this.a[i],this.b[i])/(base*MULT[i]+1);if(v>bv){bv=v;best=i;}}this.arm=best;return best;},
  update(i,r){if(!this.seeded)return;r=clamp(r,0,1);this.a[i]=this.a[i]*.98+r;this.b[i]=this.b[i]*.98+(1-r);},
  bestArm(base){let best=2,bv=-1;for(let i=0;i<MULT.length;i++){const v=this.a[i]/(this.a[i]+this.b[i])/(base*MULT[i]+1);if(v>bv){bv=v;best=i;}}return best;},
  evidence(){let n=0;for(let i=0;i<MULT.length;i++)n+=this.a[i]+this.b[i]-4;return Math.round(n);}
};

/* ---------------- Formats ---------------- */
const DIMS=["hook","layout","look","bg"];
const PIECES={
  hook:["pov","waitforit","mostwrong","text2am","rate","storytime"],
  layout:["bigtext","captions","chat","comment","split","quiz","list"],
  look:["caption","subtitle","clean","neon","paper"],
  bg:["satisfying","gameplay","citywalk","pitch","glow","equalizer","plain"]
};
const VIBES=["football","gaming","beauty","comedy","music","fitness","food"];
const LABEL={pov:"POV",waitforit:"Wait for it",mostwrong:"Most get it wrong",text2am:"2am text",rate:"Rate my answer",storytime:"Storytime",
  bigtext:"Big text",captions:"Word-by-word captions",chat:"Text chat",comment:"Reply to a comment",split:"Split-screen",quiz:"Quiz buttons",list:"Numbered list",
  caption:"Bold captions",subtitle:"Highlight boxes",clean:"Clean serif",neon:"Neon",paper:"Handwritten notes",
  satisfying:"Satisfying clip",gameplay:"Gameplay",citywalk:"City walk",pitch:"Football pitch",glow:"Soft glow",equalizer:"Music visualiser",plain:"Plain",
  football:"Football",gaming:"Gaming",beauty:"Beauty & fashion",comedy:"Comedy",music:"Music",fitness:"Gym & fitness",food:"Food",
  tiktok:"TikTok",instagram:"Instagram Reels",youtube:"YouTube Shorts",practice:"Practice feed"};
const DIMLABEL={hook:"Hook",layout:"Layout",look:"Look",bg:"Background"};
function vibePriors(v){switch(v){
  case "football":return[["bg","pitch",3],["hook","pov",1]];
  case "gaming":return[["bg","gameplay",3],["layout","split",1],["hook","waitforit",1]];
  case "beauty":return[["bg","glow",3],["look","clean",1],["look","paper",1]];
  case "comedy":return[["hook","text2am",2],["layout","comment",2],["layout","chat",1]];
  case "music":return[["bg","equalizer",3],["look","neon",2],["layout","captions",1]];
  case "fitness":return[["bg","citywalk",2],["hook","pov",1],["look","caption",1]];
  case "food":return[["bg","satisfying",3],["look","paper",1]];
  default:return[];}}
const fkey=f=>DIMS.map(d=>f[d]).join("/");

/* ---------------- FormatBandit (recovering, contextual Thompson sampling) ---------------- */
function Bandit(){this.scores={};this.lastUsed={};this.shown=0;this.lastKey=null;this.recent={};this.learn=true;for(const d of DIMS)for(const p of PIECES[d])this.scores[d+":"+p]=[1,1];}
Bandit.prototype={
  sc(k){if(!this.scores[k])this.scores[k]=[1,1];return this.scores[k];},
  mean(d,p){const s=this.sc(d+":"+p);return s[0]/(s[0]+s[1]);},
  applyVibes(vs){for(const v of vs)for(const p of vibePriors(v))this.sc(p[0]+":"+p[1])[0]+=p[2];},
  pooled(ctx,d,p){const g=this.sc(d+":"+p);if(!ctx)return g;const c=this.scores[ctx+"|"+d+":"+p];if(!c)return g;const nc=c[0]+c[1]-2,wg=1/(1+nc/8);return[1+wg*(g[0]-1)+(c[0]-1),1+wg*(g[1]-1)+(c[1]-1)];},
  runFull(d,p){const r=this.recent[d]||[];const n=d==="hook"?1:d==="layout"?2:3;if(r.length<n)return false;for(let i=r.length-n;i<r.length;i++)if(r[i]!==p)return false;return true;},
  /* allowed: the layouts this card's shape can use (see layoutsFor); omitted = all of them */
  pick(rnd,ctx,avoid,allowed){
    const f={},detail={};const explore=rnd()<.1?pickOf(rnd,DIMS):null;
    const pen={hook:.3,layout:.25,look:.18,bg:.08};
    for(const d of DIMS){
      let c=PIECES[d].filter(p=>!(d==="layout"&&((allowed&&allowed.indexOf(p)<0)||p===avoid))&&!this.runFull(d,p));
      if(!c.length)c=[PIECES[d][0]];
      if(d===explore){f[d]=pickOf(rnd,c);detail[d]="explored";continue;}
      let best=c[0],bv=-9,bd=0,bp=0;
      for(const p of c){const s=this.pooled(ctx,d,p);const draw=beta(rnd,s[0],s[1]);const at=this.lastUsed[d+":"+p];const pn=at==null?0:pen[d]*Math.pow(.5,(this.shown-at)/1.5);if(draw-pn>bv){bv=draw-pn;best=p;bd=draw;bp=pn;}}
      f[d]=best;detail[d]=bp>.005?"score "+bd.toFixed(2)+" − recent-use "+bp.toFixed(2):"score "+bd.toFixed(2);
    }
    if(fkey(f)===this.lastKey){let alt=f.look;while(alt===f.look)alt=pickOf(rnd,PIECES.look);f.look=alt;detail.look="swapped: no repeats";}
    this.shown++;
    for(const d of DIMS){this.lastUsed[d+":"+f[d]]=this.shown;(this.recent[d]=this.recent[d]||[]).push(f[d]);if(this.recent[d].length>4)this.recent[d].shift();}
    this.lastKey=fkey(f);
    return{format:f,explored:explore,detail};
  },
  update(f,ctx,r){if(!f||!this.learn)return;r=clamp(r,0,1);const bump=s=>{s[0]=1+(s[0]-1)*.98+r;s[1]=1+(s[1]-1)*.98+(1-r);};for(const d of DIMS){bump(this.sc(d+":"+f[d]));if(ctx)bump(this.sc(ctx+"|"+d+":"+f[d]));}},
  favourite(d){let best=null,bm=-1;for(const p of PIECES[d]){const s=this.sc(d+":"+p);if(s[0]+s[1]<5)continue;const m=s[0]/(s[0]+s[1]);if(m>bm){bm=m;best=p;}}return best;}
};

/* ---------------- Answer shapes ----------------
   A reel is read in seconds, so a card is drawn by the shape of its answer, not just its words:
   short (a word or phrase), medium (one long sentence), list, steps, or prose (a few sentences).
   Lists, steps and prose are split into 2-6 points, revealed one after another and self-marked
   point by point, like a mark scheme. Long questions are split into the set-up and the question. */
const SHORT_MAX=48, MAX_POINTS=6, LONG_Q=90, INLINE_X=90, COMMENT_MAX=180;
const BULLET=/^(?:[-•*·▪●–]|\d{1,2}[.)]|\(?[a-z]\))\s+/;
const COMMAND=/^(explain|describe|suggest|compare|contrast|name|state|give|calculate|evaluate|define|outline|identify|predict|use|write|complete)\b/i;
function plainText(t){
  return tableLines(String(t==null?"":t)).replace(/<\s*br\s*\/?>/gi,"\n").replace(/<\/(?:li|p|div|h\d)>/gi,"\n").replace(/<li[^>]*>/gi,"\n").replace(/<[^>]+>/g,"")
    .replace(/&nbsp;/g," ").replace(/&lt;/g,"<").replace(/&gt;/g,">").replace(/&quot;/g,"\"").replace(/&#39;/g,"'").replace(/&amp;/g,"&")
    .replace(/[\u00a0\u2000-\u200a\u202f\u205f\u3000]/g," ").replace(/[ \t]+/g," ").split("\n").map(x=>x.trim()).filter(x=>x).join("\n");
}
/* A table becomes one line per row, each value labelled with its column's header when there is one
   ("Ferritin: Iron deficiency ↓, Chronic disease ↑"; with one value column just "Warfarin: VKOR"), so a row can be a point to mark. */
const CELLSPACE=/[ \t\r\n  -   　]+/g;
function tableLines(t){return t.replace(/<table[^>]*>([\s\S]*?)<\/table>/gi,(m,body)=>{
  const rows=body.split(/<tr[^>]*>/i).slice(1).map(row=>{const cells=[],re=/<t([dh])(?:\s[^>]*)?>([\s\S]*?)<\/t[dh]>/gi;let c;
    while((c=re.exec(row)))cells.push({h:c[1].toLowerCase()==="h",v:c[2].replace(/<\s*br\s*\/?>/gi," ").replace(/<[^>]+>/g,"").replace(/&nbsp;/g," ").replace(CELLSPACE," ").trim()});return cells;}).filter(r=>r.some(c=>c.v));
  if(!rows.length)return"";
  const head=rows.length>1&&rows[0].length>1&&(rows[0].every(c=>c.h)||!rows[0][0].v)?rows.shift().map(c=>c.v):null;
  return"\n"+rows.map(r=>{const vals=r.slice(1).map((c,j)=>!c.v?"":head&&head.length>2&&head[j+1]?head[j+1]+" "+c.v:c.v).filter(v=>v);
    return r[0].v&&vals.length?r[0].v+": "+vals.join(", "):r[0].v||vals.join(", ");}).join("\n")+"\n";});}
/* text whose capitals carry meaning (Na+, Ca2+, Cl−, NaCl, pH, mEq/L, IgG) isn't put in capitals by the bold looks */
const CASE_MATTERS=/[a-z][A-Z]|\b[A-Z][a-z]\d*[+\u2212-](?![a-z])|\b[A-Z]\d*[+\u2212]/;
function caseMatters(t){return CASE_MATTERS.test(t||"");}
/* lines on one line: "Glycols; Oxoproline; L-lactate", but sentences just follow each other */
function inline(t){return t.split("\n").reduce((o,l)=>!o?l:o+(/[.!?;:,]$/.test(o)?" ":"; ")+l,"");}
const cap1=x=>x?x.charAt(0).toUpperCase()+x.slice(1):x;
const words=x=>x.trim().split(/\s+/).length;
function tidy(p){return cap1(p.replace(BULLET,"").replace(/[\s;,.]+$/,"").trim());}
function shaped(pts,kind){pts=pts.map(tidy).filter(x=>x);if(pts.length<2){const one=pts.join(" ");return{kind:one.length>SHORT_MAX?"medium":"short",points:pts,over:[]};}
  return{kind,points:pts.slice(0,MAX_POINTS),over:pts.slice(MAX_POINTS)};}
/* sentence ends, but not after e.g. / i.e. / etc. */
const SENTENCE=/(?<!\b(?:e\.g|i\.e|etc|vs|approx|cf|Dr|Mr|Mrs|Ms|St)\.)(?<=[.!?])\s+(?=[A-Z0-9“"(])/;
const QSENTENCE=/(?<!\b(?:e\.g|i\.e|etc|vs|approx|cf|Dr|Mr|Mrs|Ms|St)\.)(?<=[.!?:])\s+(?=[A-Z0-9“"(])/;
/* "1. Warm… 2. Filter… 3. Heat…": split only where the numbers run 1, 2, 3 in order */
function numberedSteps(one){const re=/(^|\s)(\d{1,2})[.)]\s+/g;let m,want=1;const cuts=[];
  while((m=re.exec(one))){if(+m[2]===want){cuts.push([m.index+m[1].length,m.index+m[0].length]);want++;}}
  if(cuts.length<2)return null;const out=[];for(let i=0;i<cuts.length;i++)out.push(one.slice(cuts[i][1],i+1<cuts.length?cuts[i+1][0]:one.length));return out;}
function splitPoints(b){
  const t=plainText(b),lines=t.split("\n");
  if(lines.length>=2)return shaped(lines,lines.every(l=>/^\d{1,2}[.)]\s/.test(l))?"steps":"list");
  const one=lines[0]||"";
  if(/^1[.)]\s/.test(one)){const p=numberedSteps(one);if(p&&p.filter(x=>x.trim()).length>=2)return shaped(p,"steps");}
  if(one.indexOf(";")>=0){const p=one.split(/\s*;\s*/).filter(x=>x);if(p.length>=2)return shaped(p,"list");}
  const ar=one.split(/\s*(?:→|->)\s*/);if(ar.length>=3&&!/[+=]/.test(one))return shaped(ar,"steps");
  if(one.length>SHORT_MAX){
    const se=one.split(SENTENCE);if(se.length>=2)return shaped(se,"prose");
    const items=one.split(/,\s+/),last=items.pop(),lp=last.split(/\s+(?:and|or)\s+/);
    if(items.length&&lp.length===2){const all=items.concat(lp);if(all.length>=3&&all.every(x=>x&&words(x)<=4))return shaped(all,"list");}
    const cl=one.split(/,\s+(?=(?:so|which|meaning|because)\s)|\s+(?=because\s)/);
    if(cl.length>=2&&cl.every(x=>words(x)>=3))return shaped(cl,"prose");
  }
  return{kind:one.length>SHORT_MAX?"medium":"short",points:one?[one]:[],over:[]};
}
const shortCache=new Map();
function isShort(b){let v=shortCache.get(b);if(v===undefined){v=splitPoints(b).kind==="short";if(shortCache.size>4000)shortCache.clear();shortCache.set(b,v);}return v;}
/* a cloze card's question starts at or before the sentence with the gap, so the gap stays big */
const GAP=/\[[^\]]*\]/;
function splitQuestion(q,cloze){
  const t=plainText(q).replace(/\n/g," ");
  if(t.length<=LONG_Q)return{ctx:"",main:t};
  const s=t.split(QSENTENCE);if(s.length<2)return{ctx:"",main:t};
  let i=s.length-1;for(let k=s.length-1;k>=1;k--){if(/\?$/.test(s[k])||COMMAND.test(s[k])){i=k;}else if(k<s.length-1)break;}
  if(cloze){const g=s.findIndex(x=>GAP.test(x));if(g>=0&&g<i)i=g;}
  const ctx=s.slice(0,i).join(" "),main=s.slice(i).join(" ");
  if(ctx.length<24||main.length<10)return{ctx:"",main:t};
  return{ctx,main};
}
/* Everything the layouts need to know about a card's length and shape. */
function analyse(c){
  const sp=splitPoints(c.b),sq=splitQuestion(c.f,!!c.cloze),x=inline(plainText(c.x));
  const multi=sp.points.length>=2,pic=!!(c.img||c.io||c.snd),io=!!c.io;
  // image occlusion has no written answer: the picture is the answer, the extra goes behind "more"
  const more=(sp.over.length?"Also: "+sp.over.join(". ")+". ":"")+(multi||io||x.length>INLINE_X?x:"");
  return{kind:io?"image":sp.kind,points:io?[]:sp.points,multi:multi&&!io,marks:multi&&!io?sp.points.length:0,ctx:sq.ctx,qMain:sq.main,
    a2:multi||io||x.length>INLINE_X?"":x,more:more.trim(),pic,io,cloze:!!c.cloze,
    quizable:sp.kind==="short"&&!pic,captionable:!sq.ctx&&sq.main.length<=140&&!pic,splittable:!multi&&!pic&&plainText(c.b).length<=60&&sq.main.length<=110&&!sq.ctx};
}

/* ---------------- CopyWriter (reel-native words) ---------------- */
function themed(v,h,gen){if(!v)return gen;const t={
  "football/pov":["POV: VAR is checking your answer","POV: last minute of the mock, extra time"],"football/mostwrong":["Even the pundits get this wrong"],"football/waitforit":["Wait for the replay…"],
  "gaming/waitforit":["Speedrun this question","Boss level question. Wait for it…"],"gaming/rate":["Clutch or choke? Answer first"],"gaming/pov":["POV: you respawn in the exam hall"],
  "beauty/pov":["GRWM while I explain this","POV: 5-minute revision routine"],"beauty/mostwrong":["The revision tip nobody gatekeeps"],
  "comedy/pov":["POV: the teacher picks you","POV: you said the answer with confidence"],"comedy/rate":["Rate my answer (be nice)"],
  "music/waitforit":["Wait for the drop…","Answer drops on the beat…"],"music/pov":["POV: this is stuck in your head now"],
  "fitness/pov":["POV: rest set, quick question","POV: brain day, not leg day"],"fitness/rate":["One rep: answer this"],
  "food/pov":["POV: revising while dinner cooks"],"food/waitforit":["Let it cook… answer in 3"]}[v+"/"+h];return t||gen;}
const STOP=new Set("the a an it its they their them this these that those so and or but of to in on is are was were be by with for as at from because which then there".split(" "));
/* how each point starts ("Active…", "Aerobic makes…"): one word, or more when two points would start alike */
const NUMTOK=/^\(?\d{1,2}[:.)]$/;
function starts(points){const used=new Set();return points.map(p=>{const ws=p.split(/\s+/);let i=ws.findIndex(w=>{const b=w.replace(/[^\p{L}\p{N}]/gu,"");return b&&!STOP.has(b.toLowerCase())&&!NUMTOK.test(w);});if(i<0)i=0;
  let k=i+1,st=ws.slice(i,k).join(" ");while(used.has(st.toLowerCase())&&k<ws.length){k++;st=ws.slice(i,k).join(" ");}used.add(st.toLowerCase());
  const clean=st.replace(/^[^\p{L}\p{N}]+/u,"").replace(/[^\p{L}\p{N}]+$/u,"");return cap1(clean||ws[i]);});}
function hint(ans,points){if(points&&points.length>=2)return points.length+" points: "+starts(points).slice(0,3).map(p=>"“"+p+"…”").join(" ")+(points.length>3?" …":"");
  const a=String(ans||"").trim();if(!a)return"No hint for this one";const w=a.split(/\s+/);let first=w[0].replace(/^[^\p{L}\p{N}]+/u,"")||w[0];
  if(w.length===1){const n=first.replace(/[^\p{L}\p{N}]/gu,"").length;return"Starts with “"+first[0].toUpperCase()+"” · "+n+(n===1?" letter":" letters");}
  return"Starts with “"+first+"…” · "+w.length+" words";}
/* a deck path's parts: "USMLE Step 1::Renal", or "USMLE Step 1 · Renal" as imported */
const DECKSEP=/::| · |:/;
/* the deck chip: its most specific part ("Renal"), without "GCSE" */
function deckLabel(deck){if(!deck)return"Study";const p=deck.split(DECKSEP);return p[p.length-1].trim().replace(/^(gcse|a-level|a level|ks3)\s+/i,"")||"Study";}
function slug(deck){if(!deck)return"study";let s=deck.split(DECKSEP)[0].toLowerCase().replace(/^(gcse|a-level|a level|ks3)\s+/,"").replace(/[^a-z0-9]+/g,"");return s?s.slice(0,14):"study";}
function casual(q){let t=q.trim().replace(/[?.!]+$/,"");if(t.length>1&&t[1]!==t[1].toUpperCase())t=t[0].toLowerCase()+t.slice(1);return t;}
function beats(q){const raw=q.trim().split(/\s+/),words=[],out=[],hl=[];let cur=[];
  for(let i=0;i<raw.length;i++){let w=raw[i];if(w.indexOf("[")>=0&&w.indexOf("]")<0)while(i+1<raw.length&&w.indexOf("]")<0)w+=" "+raw[++i];words.push(w);} // a cloze [gap] stays in one beat
  words.forEach((w,i)=>{cur.push(w);if(cur.length>=3||/[,?]$/.test(w)||i===words.length-1){let hi=-1,len=0;cur.forEach((x,k)=>{const b=x.replace(/[^\p{L}\p{N}]/gu,"");if(b.length>len&&b.length>=5){len=b.length;hi=k;}});out.push(cur.join(" "));hl.push(hi);cur=[];}});return{beats:out,hl};}
/* not an answer the question already shows (a sibling cloze's answer is in the sentence) */
function distractor(c,pool,rnd){const mine=c.b.trim().toLowerCase(),qt=plainText(c.f).toLowerCase(),same=[],any=[];for(const o of pool){if(o===c||!o.b)continue;const ob=o.b.trim().toLowerCase();if(!ob||ob===mine||qt.includes(ob)||!isShort(o.b))continue;const r=(o.b.length+4)/(c.b.length+4);if(r<.4||r>2.5)continue;(o.deck===c.deck?same:any).push(o);}const src=same.length?same:any;if(!src.length)return null;const me=plainText(c.b),q=plainText(c.f),sc=src.map(o=>likeness(me,/[<&]/.test(o.b)?plainText(o.b):o.b)+3*kin(q,plainText(o.f))),top=Math.max(...sc);return pickOf(rnd,src.filter((_,i)=>sc[i]===top)).b;}
/* how many opening words two questions share (up to 3): "Antidote for heparin?" and "Antidote for acetaminophen overdose?"
   share 2, so their answers are the same kind of thing (two drugs) and one makes a believable wrong answer for the other */
function kin(a,b){const wa=a.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(w=>w),wb=b.toLowerCase().split(/[^\p{L}\p{N}]+/u).filter(w=>w);let n=0;while(n<3&&n<wa.length&&n<wb.length&&wa[n]===wb[n])n++;return n;}
/* how alike two answers look, 0-5: the same pattern of capitals ("Streptococcus pneumoniae" and
   "Staphylococcus aureus" are both Aa), word count, digits or none, and ending ("-ase", "-ine") */
function wordsOf(t){return t.split(/\s+/).map(w=>w.match(/[\p{L}\p{Nd}]/u)).filter(m=>m).map(m=>/\p{Lu}/u.test(m[0])?"A":/\p{Nd}/u.test(m[0])?"9":"a");}
function ending(t){const ws=t.toLowerCase().split(/\s+/).map(w=>w.replace(/[^\p{L}]/gu,"")).filter(w=>w);const w=ws.length?ws[ws.length-1]:"";return w.length>=3?w.slice(-3):"";}
function likeness(a,b){const wa=wordsOf(a),wb=wordsOf(b),ea=ending(a);
  return(wa.join("")===wb.join("")?2:0)+(wa.length===wb.length?1:0)+(/\p{Nd}/u.test(a)===/\p{Nd}/u.test(b)?1:0)+(ea&&ea===ending(b)?1:0);}
function textish(p){return p.length>1&&p[1]===p[1].toLowerCase()?p[0].toLowerCase()+p.slice(1):p;}
/* the layouts a card's shape can use: pictures stay in big text; a cloze gap doesn't read as a comment */
function layoutsFor(a,hasDistractor){if(a.pic)return["bigtext"];return PIECES.layout.filter(l=>l==="quiz"?a.quizable&&hasDistractor:l==="captions"?a.captionable:l==="list"?a.multi:l==="split"?a.splittable:l==="comment"?!a.cloze&&a.ctx.length+a.qMain.length<=COMMENT_MAX:true);}
function write(c,f,pool,rnd,reason,vibes){
  const a=analyse(c),q=plainText(c.f).replace(/\n/g," ");
  const s={q,a:a.multi?a.points.join("; "):(a.points[0]||""),x:a.a2,options:[],correct:-1,beats:[],hl:[],
    shape:a.kind,points:a.points,marks:a.marks,ctx:a.ctx,qMain:a.qMain,more:a.more,img:c.img||null,imgW:c.imgW||0,imgH:c.imgH||0,aimg:c.aimg||null,aimgW:c.aimgW||0,aimgH:c.aimgH||0,io:c.io||null,cloze:!!c.cloze,snd:c.snd||null,asnd:c.asnd||null};
  s.account="@"+slug(c.deck)+pickOf(rnd,[".mocks",".revision",".explained",".facts"]);s.tag=c.deck+" · "+reason;
  const vl=[...(vibes||[])];const v=!vl.length||rnd()<.4?null:pickOf(rnd,vl);s.vibe=v;
  let hooks,caps;
  switch(f.hook){
    case "pov":hooks=themed(v,"pov",["POV: it's 2 days before your mock","POV: you actually revised this time","POV: this comes up and you blank"]);caps=["if you don't know this we need to talk","tag someone who'd get this wrong","be honest"];break;
    case "waitforit":hooks=themed(v,"waitforit",["Wait for it…","Watch till the end","Answer drops in 3…"]);caps=["watch till the end","say it before the reveal","no pausing"];break;
    case "mostwrong":hooks=themed(v,"mostwrong",["Most people get this wrong","Everyone forgets this one","This one catches people out"]);caps=["be honest in the comments","no googling","first answer only"];break;
    case "text2am":hooks=["She texted me this at 2am","My mate asked this in the group chat","Got this text in the middle of lunch"];caps=["would you have got it?","reply before the dots do","the group chat went silent"];break;
    case "storytime":hooks=["Storytime: this cost me marks","The mock question I'll never forget","I got this wrong so you don't have to"];caps=["learn from my pain","part 2?","don't be me"];break;
    default:hooks=themed(v,"rate",["Rate my answer 1–10","Can you beat my answer?","Say it out loud first"]);caps=["no googling","rate it honestly","answer in your head first"];
  }
  s.hook=pickOf(rnd,hooks);s.cap=pickOf(rnd,caps);s.prompt=pickOf(rnd,["wait for it… (tap)","tap for the answer","think… then tap"]);
  s.hint=a.io?"Other labels uncovered: rule them out":hint(c.b,a.points);s.starts=a.multi?starts(a.points):[];s.sound="original sound · "+s.account.slice(1);
  if(f.layout==="chat"){s.name=pickOf(rnd,["Jess","Sam","Priya","Tom","Aisha","Leo"]);const open=pickOf(rnd,["ok random q","wait quick one","don't laugh but"]),close=pickOf(rnd,["and don't google it","fast","i've forgotten already"]);
    s.them=a.ctx?[open,a.ctx,a.qMain]:[open,q,close];s.themQ=a.ctx?[a.ctx,a.qMain]:[q];
    s.me=a.multi?a.points.map(textish):[s.a].concat(s.x?[textish(s.x)]:[]);
    s.after=pickOf(rnd,a.multi?["ok that's full marks","saving this for the mock","how do you remember all that"]:["ok genius go to sleep","how do you just know that","saving this"]);}
  if(f.layout==="comment"){s.commentUser=pickOf(rnd,["@revision.panic","@mocks_in_2_weeks","@year11.survivor","@lost.in.chem","@notes.pls"]);s.commentText=casual(q)+pickOf(rnd,[" pls"," someone explain"," i'm begging"," help"]);s.hook="Replying to "+s.commentUser;}
  if(f.layout==="captions"){const b=beats(q);s.beats=b.beats;s.hl=b.hl;}
  if(f.layout==="list"&&a.multi){const n=a.marks;s.hook=pickOf(rnd,a.kind==="steps"?[n+" steps. Say them in order",n+" steps, in order. Go",
      "Can you do all "+n+" steps?"]:[n+" marks. Can you get all "+n+"?","Name all "+n+" (most people miss one)",n+" things examiners look for here"]);}
  if(f.layout==="quiz"){const w=distractor(c,pool,rnd);if(w){if(rnd()<.5){s.options=[c.b,w];s.correct=0;}else{s.options=[w,c.b];s.correct=1;}}}
  s.voice=(f.layout==="comment"?"Someone asked: "+s.commentText+".":s.hook.replace("…",".").replace("1–10","one to ten"))+" "+(s.cloze?spokenGaps(q):q);
  return s;
}

/* ---------------- Grading (response-time aware) ---------------- */
const readingMs=t=>1500+45*(t?t.length:0);
/* points: how many points the answer has. Answers with 2+ points can be self-marked point by point
   (o.got = points ticked): all = Good, half or more = Hard, fewer = Again. A hint caps the grade at Hard. */
function gradeOf(o,quiz,correct,q,opts,points){
  const n=points||1,read=readingMs(q)+1200*Math.max(0,n-1),got=o.got||0;let g;
  if(quiz&&o.pick>=0&&o.pick!==correct)return 1;
  if(o.likedBefore)g=(!o.likeAt||o.likeAt<=read+2500)?4:3;
  else if(quiz&&o.pick>=0)g=(!o.pickAt||o.pickAt<=read+readingMs(opts)+4000)?3:2;
  else if(o.liked)g=3;
  else if(n>=2&&got>0)g=got>=n?3:got*2>=n?2:1;
  else if(o.revealed)g=1;
  else g=0;
  if(o.hintAt>0&&g>2)g=2;
  return g;
}
const fastSkip=o=>!o.revealed&&!o.liked&&!o.saved&&!o.shared&&!o.hintAt&&o.pick<0&&!o.got&&o.dwell<1200;
const engaged=o=>o.liked||o.shared||o.saved||o.hintAt>0||o.pick>=0||o.got>0||(o.revealed&&o.dwell>=2000);
function reward(o){if(o.liked||o.shared)return 1;if(o.saved)return .85;if(o.pick>=0||o.got>0)return .9;if(o.revealed&&o.dwell>=2000)return .7;if(o.revealed)return .45;if(o.dwell>=3000)return .35;if(o.dwell>=1200)return .15;return 0;}

/* ---------------- Settings ---------------- */
const INT={chill:{gap:8,cap:60,newPer:10},normal:{gap:5,cap:150,newPer:25},cram:{gap:3,cap:300,newPer:50},demo:{gap:2,cap:1000,newPer:500}};

/* ---------------- Engine ---------------- */
function Engine(seed){
  let s=seed||Date.now()%2147483647;const rnd=()=>{s=(s*16807)%2147483647;return(s-1)/2147483646;};
  this.rnd=rnd;this.cards=[];this.settings={intensity:"normal",examDate:0,pausedUntil:0,minutesPerDay:60,vibes:[]};
  this.bandit=new Bandit();this.fsrs=new Fsrs(rnd);this.calibration=new Calibration();this.rhythm=new Rhythm();this.adaptiveRhythm=true;
  this.stats={days:{},avgReel:12,avgCard:6,reelN:0,cardN:0,layout:{}};
  this.sessionId=0;this.sessionReels=0;this.reelsSinceCard=0;this.cardsToday=0;this.newToday=0;this.postponed=0;this.lastSwipeAt=0;this.snoozeUntil=0;this.dayKey=null;
  this.rhythmArm=-1;this.lastHost=null;this.lastDeck=null;this.recentFast=[];this.recentReel=[];this.requeue=[];this.newShown=new Set();this.hidden=new Set();this.lastDecision=null;this.log=[];this.anki=[];this.tzOffsetMin=null;
}
Engine.prototype={
  I(){return INT[this.settings.intensity];}, isDemo(){return this.settings.intensity==="demo";},
  dk(t){return Math.floor((t-new Date(t).getTimezoneOffset()*MIN)/DAY);},
  day(k){return this.stats.days[k]||(this.stats.days[k]={reels:0,cards:0,reviews:0,again:0,hard:0,good:0,easy:0,skips:0,fastSkips:0,newSeen:0,reelSec:0,cardSec:0});},
  examDaysLeft(t){const e=this.settings.examDate;if(!e)return -1;if(e<=t)return 0;return Math.ceil((e-t)/DAY);},
  addCards(list){const have=new Set(this.cards.map(c=>c.id));let n=0;for(const c of list){if(have.has(c.id))continue;this.cards.push(c);have.add(c.id);n++;}return n;},
  find(id){return this.cards.find(c=>c.id===id);},
  setVibes(vs){const added=vs.filter(v=>!this.settings.vibes.includes(v));this.settings.vibes=vs.slice();this.bandit.applyVibes(added);},
  buried(c){return c.buriedUntil>=this.sessionId||this.hidden.has(c.id);} /* hidden: held back, e.g. a missing picture (not saved) */, inRequeue(c){return this.requeue.some(r=>r.id===c.id);}, dropRequeue(c){this.requeue=this.requeue.filter(r=>r.id!==c.id);},
  dueCount(t){return this.cards.filter(c=>c.state!==NEW&&c.due<=t&&!this.buried(c)).length;},
  newRemaining(){return this.cards.filter(c=>c.state===NEW).length;}, learnedCount(){return this.cards.filter(c=>c.state===REVIEW).length;},
  rollDay(t){const k=this.dk(t);if(k!==this.dayKey){this.dayKey=k;this.cardsToday=0;this.newToday=0;this.newShown=new Set();}},
  rollSession(t){if(!this.lastSwipeAt||t-this.lastSwipeAt>SESSION_GAP){this.sessionId++;this.sessionReels=0;this.reelsSinceCard=0;this.postponed=0;this.requeue=[];this.recentReel=[];this.cards.forEach(c=>c.bounces=0);return true;}return false;},
  newLimit(t){const base=this.I().newPer,ex=this.examDaysLeft(t);if(ex<=0||this.isDemo())return base;const need=Math.ceil(this.newRemaining()/Math.max(1,ex*.8));return Math.max(base,Math.min(need,base*3));},
  currentGap(t){let g=this.I().gap*(this.isDemo()||!this.adaptiveRhythm||!this.rhythm.seeded?1:this.rhythm.multiplier());const ex=this.examDaysLeft(t);if(this.dueCount(t)>=20)g*=.85;if(ex>=0&&ex<=2)g*=.6;else if(ex>=0&&ex<=7)g*=.75;return Math.max(this.isDemo()?1:2,Math.min(15,Math.round(g)));},
  rhythmText(){const base=this.I().gap;if(this.isDemo()||!this.rhythm.seeded||this.rhythm.evidence()<15)return"1 card every "+base+" reels (your setting). It adapts after a few cards.";const best=Math.round(base*MULT[this.rhythm.bestArm(base)]);return best===base?"1 card every "+base+" reels suits you best so far.":"Learned from you: 1 card every "+best+" reels gets you the most cards answered (you started at "+base+").";},
  newAvailable(t){if(this.newToday>=this.newLimit(t))return false;return this.cards.some(c=>c.state===NEW&&!this.buried(c)&&!this.inRequeue(c));},
  readyRequeue(){return this.requeue.find(r=>this.sessionReels>=r.at);},
  decide(t){
    const d={show:false,gap:this.I().gap,blocked:null,reasons:[]};const base=this.I().gap;
    if(this.settings.pausedUntil>t){d.blocked="Paused by you";return d;}
    if(this.snoozeUntil>t){d.blocked="Quiet mode after lots of fast skips (back in "+Math.max(1,Math.round((this.snoozeUntil-t)/MIN))+" min)";return d;}
    if(this.sessionReels<(this.isDemo()?1:3)){d.blocked="Warm-up: no cards in the first few reels of a session";return d;}
    if(this.cardsToday>=this.I().cap){d.blocked="Daily limit of "+this.I().cap+" cards reached";return d;}
    const gap=this.currentGap(t);d.gap=gap;const ex=this.examDaysLeft(t),due=this.dueCount(t);
    const avail=due>0||!!this.readyRequeue()||this.newAvailable(t)||(ex>=0&&ex<=3&&this.learnedCount()>0);
    if(!avail){d.blocked="Nothing due, and today's new cards are done";return d;}
    d.reasons.push("1 card every "+gap+" reels"+(this.rhythm.seeded&&this.adaptiveRhythm&&!this.isDemo()&&this.rhythm.multiplier()!==1?" (rhythm being learned; you started at "+base+")":" (your setting)"));
    if(due>=20)d.reasons.push(due+" cards due, so a little more often");
    if(ex>=0&&ex<=7)d.reasons.push("Exam in "+ex+" days: cram mode");
    const last=this.recentReel.length?this.recentReel[this.recentReel.length-1]:0;
    let skimming=this.recentReel.length>=3;for(let i=Math.max(0,this.recentReel.length-3);i<this.recentReel.length;i++)if(this.recentReel[i]>=SKIMMING_SEC)skimming=false;
    if(this.reelsSinceCard>=gap){
      if(this.adaptiveRhythm&&last>=ABSORBED_SEC&&this.postponed<2&&!this.isDemo()){this.postponed++;d.reasons.push("You watched that reel for "+Math.round(last)+" s, so waiting for a natural break");return d;}
      d.show=true;
    }else if(this.adaptiveRhythm&&this.reelsSinceCard===gap-1&&gap>=3&&skimming){d.show=true;d.reasons.push("You're skimming past reels: a good moment for a card");}
    else d.reasons.push("Next card in "+(gap-this.reelsSinceCard)+" reels");
    return d;
  },
  pickCard(t,force){
    const r=this.readyRequeue();
    if(r){this.requeue.splice(this.requeue.indexOf(r),1);const c=this.find(r.id);if(c&&!this.buried(c))return{card:c,why:"You swiped past it earlier without looking, so it's back in a different look.",short:"back again"};}
    const ex=this.examDaysLeft(t),focus=ex>0&&ex<=14;const learning=[],reviews=[];
    for(const c of this.cards){if(c.state===NEW||c.due>t||this.buried(c)||this.inRequeue(c))continue;(c.state===LEARNING||c.state===RELEARNING?learning:reviews).push(c);}
    if(learning.length){const b=learning.reduce((a,c)=>c.due<a.due?c:a);return{card:b,short:"learning",why:b.state===RELEARNING?"You forgot this one recently, so it's back after a short step.":"Still learning this one: a short step after you last saw it."};}
    if(reviews.length){
      const rr=c=>focus?this.fsrs.recallAt(c,this.settings.examDate):this.fsrs.recallNow(c,t);
      let best=null,bR=2;for(const c of reviews){const v=rr(c);if(v<bR){bR=v;best=c;}}
      let switched=false;
      if(best&&best.deck===this.lastDeck){let alt=null,aR=2;for(const c of reviews){if(c.deck===this.lastDeck)continue;const v=rr(c);if(v<aR){aR=v;alt=c;}}if(alt&&aR<=bR+.1){best=alt;bR=aR;switched=true;}}
      let why="Due. Predicted recall "+(focus?"on exam day":"now")+" "+Math.round(bR*100)+"%, the lowest of your due cards"+(switched?" from a different subject (mixing subjects helps)":"")+".";
      if(best.lapses>=LEECH_LAPSES)why+=" It's a tricky one: forgotten "+best.lapses+" times.";
      if(best.saved)why+=" You saved this one.";
      return{card:best,why,short:best.lapses>=LEECH_LAPSES?"tricky one":"due today"};
    }
    const limit=this.newLimit(t);
    if(this.newToday<limit||force){const per={};for(const id of this.newShown){const c=this.find(id);if(c)per[c.deck]=(per[c.deck]||0)+1;}
      let pn=null,few=1e9;for(const c of this.cards){if(c.state!==NEW||this.buried(c)||this.inRequeue(c))continue;const k=(per[c.deck]||0)*2+(c.deck===this.lastDeck?1:0);if(k<few){few=k;pn=c;}}
      if(pn)return{card:pn,short:"new",why:"New card ("+(this.newToday+1)+" of "+limit+" today"+(limit>this.I().newPer?", raised so you cover the deck before your exam":"")+"). No reviews were due."};}
    if((ex>=0&&ex<=3)||force){let weak=null,wr=2;const at=ex>=0?this.settings.examDate:t;for(const c of this.cards){if(c.state===NEW||this.buried(c)||this.inRequeue(c))continue;const v=this.fsrs.recallAt(c,at);if(v<wr){wr=v;weak=c;}}
      if(!weak&&force&&this.cards.length)weak=pickOf(this.rnd,this.cards);
      if(weak&&(force||wr<.97))return{card:weak,short:force?"test":"exam soon",why:force?"Test card.":"Exam in "+ex+" days: final sweep of your weakest card ("+Math.round(wr*100)+"% predicted on the day)."};}
    return null;
  },
  onReelSwipe(t,host){
    this.rollDay(t);const fresh=this.rollSession(t);const dd=this.day(this.dayKey);
    if(!fresh){const sec=(t-this.lastSwipeAt)/1000;dd.reels++;if(sec>0&&sec<=120){dd.reelSec+=sec;this.stats.reelN++;const a=this.stats.reelN<20?1/this.stats.reelN:.05;this.stats.avgReel+=a*(sec-this.stats.avgReel);this.recentReel.push(sec);if(this.recentReel.length>6)this.recentReel.shift();}}
    else dd.reels++;
    this.lastSwipeAt=t;this.lastHost=host;this.sessionReels++;this.reelsSinceCard++;
    const d=this.decide(t);this.lastDecision=d;if(!d.show)return null;return this.makeShow(t,d,false);
  },
  forceShow(t,host){this.rollDay(t);if(!this.lastSwipeAt){this.lastSwipeAt=t;this.sessionId++;}if(host)this.lastHost=host;return this.makeShow(t,{show:true,gap:this.currentGap(t),reasons:["Requested now (test card)"]},true);},
  /* the home-screen widget's card (Android): the one a reel would show now, without counting as a reel (mirrors Engine.pickForWidget) */
  pickForWidget(t){this.rollDay(t);const p=this.pickCard(t,true);return p?p.card:null;},
  /* the widget's answer, g = 3 (knew it) or 1 (again): scheduled like a reel card, looks and rhythm untouched (mirrors Engine.gradeOutside) */
  gradeOutside(c,g,t){this.rollDay(t);const dd=this.day(this.dayKey);dd.reviews++;dd[GL[g]]++;if(c.state===NEW&&!this.newShown.has(c.id)){this.newShown.add(c.id);this.newToday++;dd.newSeen++;}c.bounces=0;
    if(c.state===REVIEW&&c.last)this.calibration.add((t-c.last)/DAY,c.s,g!==1);const ivl=this.fsrs.review(c,g,t,this.settings.examDate);this.calibration.maybeRefit(this.fsrs);
    const summary="Graded "+GL[g][0].toUpperCase()+GL[g].slice(1)+" on the widget → next in "+human(ivl);this.log.unshift(summary);this.log.length=Math.min(this.log.length,40);return{grade:g,ivl,summary};},
  makeShow(t,d,force){
    const p=this.pickCard(t,force);if(!p)return null;const c=p.card;
    const allowed=layoutsFor(analyse(c),!!distractor(c,this.cards,()=>0));
    const avoid=(c.bounces>0||(c.skips>0&&!c.grade))?c.layout:null;
    const fp=this.bandit.pick(this.rnd,this.lastHost,avoid,allowed);
    let sc=write(c,fp.format,this.cards,this.rnd,p.short,this.settings.vibes);
    if(fp.format.layout==="quiz"&&sc.correct<0){fp.format.layout="bigtext";sc=write(c,fp.format,this.cards,this.rnd,p.short,this.settings.vibes);}
    this.reelsSinceCard=0;this.postponed=0;this.cardsToday++;const dd=this.day(this.dayKey);dd.cards++;
    (this.stats.layout[fp.format.layout]=this.stats.layout[fp.format.layout]||[0,0])[0]++;
    if(c.state===NEW&&!this.newShown.has(c.id)){this.newShown.add(c.id);this.newToday++;dd.newSeen++;}
    c.layout=fp.format.layout;this.lastDeck=c.deck;
    this.log.unshift("Showed “"+(c.f.length>48?c.f.slice(0,47)+"…":c.f)+"” as "+LABEL[fp.format.layout]+" ("+p.short+")");this.log.length=Math.min(this.log.length,40);
    return{card:c,f:fp.format,s:sc,host:this.lastHost,test:force,short:p.short,explored:fp.explored,detail:fp.detail,why:{when:d.reasons,which:p.why}};
  },
  onCardFinished(sh,o,t){
    this.rollDay(t);const c=sh.card;const quiz=sh.f.layout==="quiz"&&sh.s.correct>=0;
    const n=sh.s.points.length,g=gradeOf(o,quiz,sh.s.correct,sh.s.q,sh.s.options.join(" "),n);const dd=this.day(this.dayKey);let summary;
    const sec=o.dwell/1000;if(sec>0&&sec<=120){dd.cardSec+=sec;this.stats.cardN++;const a=this.stats.cardN<20?1/this.stats.cardN:.05;this.stats.avgCard+=a*(sec-this.stats.avgCard);}
    let ivl=-1;
    if(g){
      dd.reviews++;dd[GL[g]]++;c.bounces=0;
      if(c.state===REVIEW&&c.last)this.calibration.add((t-c.last)/DAY,c.s,g!==1);
      ivl=this.fsrs.review(c,g,t,this.settings.examDate);
      const pts=n>=2&&o.got>0&&!o.liked?" ("+o.got+" of "+n+" points)":"";
      const note=pts&&!o.hintAt?pts:g===3&&o.likedBefore?" (knew it, but slowly)":g===2?(o.hintAt>0?" (with a hint)":" (right, but slowly)"):pts;
      summary="Graded "+GL[g][0].toUpperCase()+GL[g].slice(1)+note+" → next in "+human(ivl);
      if(this.calibration.maybeRefit(this.fsrs))summary+=" · memory calibration updated (×"+this.calibration.scale+")";
      if(c.lapses>=LEECH_LAPSES&&g===1)summary+=" · flagged as a tricky card";
      this.anki.unshift({g:GL[g],line:"answerCard(note="+c.id+", ease="+g+", time_taken="+Math.round(o.dwell)+"ms) → next in "+human(ivl)});this.anki.length=Math.min(this.anki.length,40);
    }else if(o.shared){
      // shared without answering: engaged, so not a skip and not re-queued; no grade, schedule left as it was
      summary="Shared (not graded)";
    }else{
      dd.skips++;c.skips++;c.bounces++;(this.stats.layout[sh.f.layout]=this.stats.layout[sh.f.layout]||[0,0])[1]++;if(fastSkip(o))dd.fastSkips++;
      if(c.bounces>=2){c.buriedUntil=this.sessionId;summary="Skipped twice this session → saved for next session, in a different look";}
      else{this.requeue.push({id:c.id,at:this.sessionReels+3});summary="Not graded (swiped past) → back in 3 reels";}
    }
    if(o.saved&&!c.saved){c.saved=true;if(c.state===REVIEW&&c.due>t+DAY)c.due=t+DAY;summary+=" · saved: back within a day";}
    if(o.shared&&g)summary+=" · shared";
    const rw=reward(o);this.bandit.update(sh.f,sh.host,rw);summary+=" · look scored "+rw.toFixed(2);
    if(this.adaptiveRhythm&&!this.isDemo()&&!sh.test){const before=this.currentGap(t);if(this.rhythmArm>=0)this.rhythm.update(this.rhythmArm,engaged(o)?1:0);this.rhythmArm=this.rhythm.choose(this.rnd,this.I().gap);const after=this.currentGap(t);if(after!==before)summary+=" · next card in ~"+after+" reels";}
    this.recentFast.push(fastSkip(o));if(this.recentFast.length>10)this.recentFast.shift();
    let tail=0;for(let i=this.recentFast.length-1;i>=0&&this.recentFast[i];i--)tail++;
    if(tail>=5){this.snoozeUntil=t+15*MIN;this.recentFast=[];summary+=" · 5 fast skips in a row → quiet mode for 15 min";}
    this.lastSwipeAt=t;this.log.unshift(summary);this.log.length=Math.min(this.log.length,40);
    return{grade:g?GL[g]:null,summary,ivl};
  },
  recallRate(k,n){let ok=0,all=0;for(const key in this.stats.days){if(+key<=k-n)continue;const d=this.stats.days[key];ok+=d.good+d.easy+d.hard;all+=d.reviews;}return all?ok/all:-1;},
  streak(k){let s=0,x=k;const t=this.stats.days[x];if(!t||!t.reviews)x--;while(this.stats.days[x]&&this.stats.days[x].reviews){s++;x--;}return s;},
  forecast(t){
    const f={mins:this.settings.minutesPerDay||60};const ex=this.examDaysLeft(t);const gap=this.currentGap(t);f.gap=gap;
    const cycle=gap*this.stats.avgReel+this.stats.avgCard;const raw=Math.floor(f.mins*60/cycle);f.perDay=Math.min(this.I().cap,raw);f.capped=raw>this.I().cap;f.minsOnCards=Math.round(f.perDay*this.stats.avgCard/60);
    f.newLeft=this.newRemaining();f.newPer=Math.max(1,Math.min(this.newLimit(t),Math.floor(f.perDay/5)));f.days=f.newLeft?Math.ceil(f.newLeft/f.newPer):0;f.ex=ex;
    if(ex>0){f.onTrack=f.days<=ex;f.need=Math.ceil(Math.ceil(f.newLeft/ex)*5*cycle/60);}return f;
  }
};
function human(days){if(days<1/24)return Math.max(1,Math.round(days*1440))+" min";if(days<1)return Math.round(days*24)+" h";const d=Math.round(days);return d+(d===1?" day":" days");}
/* a cloze question as it's read aloud: "[…]" becomes "blank", "[hint]" becomes "blank (hint)" */
function spokenGaps(q){ return q.replace(/\[([^\]]{0,40})\]/g, (m, h) => { h = h.trim(); return !h || h === "…" || h === "..." ? "blank" : "blank (" + h + ")"; }); }
function makeCard(id,deck,f,b,x){return{id,deck,f,b,x:x||"",img:null,aimg:null,io:null,cloze:false,snd:null,asnd:null,state:NEW,step:0,s:0,d:0,due:0,last:0,reps:0,lapses:0,skips:0,bounces:0,buriedUntil:-1,grade:null,layout:null,saved:false};}

return{MIN,DAY,NEW,LEARNING,REVIEW,RELEARNING,STATE,DIMS,PIECES,VIBES,LABEL,DIMLABEL,INT,MULT,Engine,Fsrs,makeCard,human,write,hint,gradeOf,fastSkip,engaged,reward,beta,fkey,
  analyse,splitPoints,splitQuestion,plainText,layoutsFor,readingMs,starts,distractor,likeness,kin,inline,caseMatters,deckLabel};
})();
