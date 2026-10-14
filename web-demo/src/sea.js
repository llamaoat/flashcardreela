/* The colour field behind the sea: a deep base with three soft blobs of colour drifting over it. Every 18
   seconds it eases into the next palette (ocean, dusk, lagoon, indigo, aurora, rose, deep sea, twilight), and it starts at a palette that
   follows the hour, so the app's colours move on as time goes on, like Reelcards itself changing shape
   from card to card. Kept dark enough that the white dashes and words always read. */
const PAL = [
  {base:[11,33,64], b:[[29,78,216],[34,184,207],[125,211,252],[99,102,241]]}, // ocean
  {base:[26,16,56], b:[[91,63,208],[190,60,170],[96,165,250],[236,72,153]]}, // dusk
  {base:[8,38,46], b:[[14,159,142],[45,212,191],[56,189,248],[16,185,129]]}, // lagoon
  {base:[13,28,70], b:[[67,56,202],[14,165,233],[167,139,250],[217,70,239]]}, // indigo
  {base:[6,26,30], b:[[16,185,129],[124,58,237],[34,211,238],[163,230,53]]}, // aurora
  {base:[32,10,38], b:[[219,39,119],[147,51,234],[59,130,246],[251,113,133]]}, // rose
  {base:[5,18,42], b:[[14,116,144],[30,64,175],[94,234,212],[168,85,247]]}, // deep sea
  {base:[20,18,52], b:[[59,130,246],[236,72,153],[45,212,191],[129,140,248]]} // twilight
];
const PAL_MS = 18000, PAL_START = new Date().getHours()/24 * PAL.length * PAL_MS;
const lerp = (a, b, f) => a + (b-a)*f, mix = (a, b, f) => a.map((v, i) => lerp(v, b[i], f)), rgb = (c, al) => "rgba("+Math.round(c[0])+","+Math.round(c[1])+","+Math.round(c[2])+","+(al==null?1:al)+")";
function paletteAt(t){
  const u = (t + PAL_START) / PAL_MS, i = Math.floor(u), f0 = u - i, f = f0*f0*(3-2*f0), A = PAL[i % PAL.length], B = PAL[(i+1) % PAL.length];
  return {base: mix(A.base, B.base, f), b: A.b.map((c, k) => mix(c, B.b[k], f))};
}
function field(g, W, H, t, pal, still){
  g.fillStyle = rgb(pal.base); g.fillRect(0,0,W,H);
  const R = Math.max(W, H)*.62, RS = [1,1,.7,.5], AS = [.8,.8,.6,.55];
  pal.b.forEach((c, i) => { const a = t/(9000+i*3700)+i*1.7, x = W*(.5+.4*Math.cos(a)), y = H*(.42+.32*Math.sin(a*1.3+i)), rg = g.createRadialGradient(x,y,0,x,y,R*RS[i]);
    rg.addColorStop(0, rgb(c, AS[i])); rg.addColorStop(1, rgb(c, 0)); g.fillStyle = rg; g.fillRect(0,0,W,H); });
}
function sea(g, pts, W, H, sx, sy, t, scrim){
  const pal = paletteAt(t); field(g, W, H, t, pal); g.lineCap = "round";
  const front = ((t*.06) % (W+520)) - 160;
  for (const p of pts) {
    let a = -.6 + Math.sin(p[1]*.009+t*.00035)*.22 + Math.cos(p[0]*.007-t*.00025)*.18;
    const dx = p[0]-sx, dy = p[1]-sy, d = Math.hypot(dx,dy), pull = Math.max(0, 1-d/120);
    if (pull > 0) a = a*(1-pull) + Math.atan2(dy,dx)*pull;
    if (d < 14) continue;
    const dr = Math.sin(t*.0016+p[4])*1.4, x = p[0]+Math.cos(a)*dr, y = p[1]+Math.sin(a)*dr;
    const k = x - (front + Math.sin(y*.02+t*.0009)*30 + Math.sin(y*.066)*10);
    let len = p[2]*(1-pull*.5), w = 1.05, col = "rgba(240,248,255,"+(.46+p[3]*.3).toFixed(2)+")";
    if (k < 0 && k > -22) { len = 1+p[3]*1.5; w = 1.7+p[3]; col = "rgba(255,255,255,.8)"; }
    else if (k <= -22 && k > -300) { if (p[3] < .3) continue; len = 2.5+p[3]*4; w = 2.5; col = "rgba(255,255,255,.55)"; a *= .4; }
    const ca = Math.cos(a)*len/2, sa = Math.sin(a)*len/2; g.strokeStyle = col; g.lineWidth = w;
    g.beginPath(); g.moveTo(x-ca,y-sa); g.lineTo(x+ca,y+sa); g.stroke();
  }
  if (scrim) { const sg = g.createLinearGradient(0,H,0,0); sg.addColorStop(0, rgb(pal.base)); sg.addColorStop(.3, rgb(pal.base,.9)); sg.addColorStop(.62, rgb(pal.base,0)); g.fillStyle = sg; g.fillRect(0,0,W,H); } // solid under the words
  // the swimmer
  const s = Math.sin(t*.006); g.save(); g.translate(sx, sy); g.rotate(-.35); g.fillStyle = g.strokeStyle = "#EE8A63"; g.lineWidth = 3;
  g.beginPath(); g.ellipse(0,0,8,4.5,0,0,7); g.fill(); g.beginPath(); g.arc(9.5,-.5,3.5,0,7); g.fill();
  g.beginPath(); g.moveTo(4,-3); g.quadraticCurveTo(10,-9,15+s*2.5,-6+s*4); g.moveTo(4,3); g.quadraticCurveTo(10,8,14-s*2.5,9-s*3);
  g.moveTo(-7,-2); g.lineTo(-16,-4+s*2); g.moveTo(-7,2); g.lineTo(-16,4.5-s*2); g.stroke(); g.restore();
}

/* The same sea as a full-screen backdrop for a page with no phone frame (the Reelcards Feed). It sits behind
   everything, stops when it leaves the page, and with "reduce motion" on draws one still frame. */
function seaBackdrop(){
  const cv = document.createElement("canvas"); cv.setAttribute("aria-hidden", "true");
  cv.style.cssText = "position:fixed;inset:0;width:100%;height:100%;z-index:0;pointer-events:none"; document.body.insertBefore(cv, document.body.firstChild);
  const g = cv.getContext("2d"), still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let W = 0, H = 0, pts = [], sx = 0, sy = 0;
  function size(){
    W = innerWidth; H = innerHeight; const dpr = Math.min(2, window.devicePixelRatio || 1); cv.width = W*dpr; cv.height = H*dpr; g.setTransform(dpr,0,0,dpr,0,0);
    const G = Math.max(9, Math.sqrt(W*H/4500)); let seed = 7; const rnd = () => (seed = (seed*16807) % 2147483647) / 2147483647;
    pts = []; for (let y=-G; y<H+G; y+=G) for (let x=-G; x<W+G; x+=G) pts.push([x+rnd()*G*.8, y+rnd()*G*.8, 4+rnd()*4, rnd(), rnd()*6.28]);
    sx = W - Math.min(60, W*.15); sy = 64;
  }
  size(); addEventListener("resize", size);
  function frame(t){ if (!cv.isConnected) return; sea(g, pts, W, H, sx, sy, t, false); g.fillStyle = rgb(paletteAt(t).base, .62); g.fillRect(0, 0, W, H); // a veil, so the words on top always read
    if (!still) requestAnimationFrame(frame); }
  requestAnimationFrame(frame);
}
