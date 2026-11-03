/* An orca and her calf circling each other, drawn in code (no image files): black and white shapes over the sea,
   a ring of dots turning slowly round them, and a few bubbles from their tails. The shapes are drawn for Reelcards,
   not traced from anywhere. Used on the website's first screen, over the sea from sea.js. Draws one still frame
   when "reduce motion" is on, and stops when it leaves the screen. Needs paletteAt() and rgb() from sea.js. */
function orcaFamily(cv, opt){
  opt = opt || {};
  const g = cv.getContext("2d"), TAU = Math.PI*2, still = matchMedia("(prefers-reduced-motion: reduce)").matches;
  let W = 0, H = 0, S = 1, cx = 0, cy = 0, seed = 11;
  const rnd = () => (seed = (seed*16807) % 2147483647) / 2147483647;
  function size(){
    const r = cv.getBoundingClientRect(), dpr = Math.min(2, window.devicePixelRatio || 1);
    W = Math.max(2, r.width); H = Math.max(2, r.height); cv.width = Math.round(W*dpr); cv.height = Math.round(H*dpr);
    g.setTransform(dpr,0,0,dpr,0,0); S = Math.min(W, H); cx = W/2; cy = H/2;
  }
  size();
  if (!opt.manual) addEventListener("resize", size);

  // R: radius of the circle the body lies along, len: body length, both as fractions of the canvas
  const WHALES = [
    {R:.3, len:.74, hw:.112, fin:.18, amp:.05, phase:0, beat:0, light:false},
    {R:.185, len:.36, hw:.135, fin:.1, amp:.07, phase:Math.PI, beat:1.9, light:true}
  ];
  const DOTS = []; for (let i = 0; i < 340; i++) DOTS.push({a:rnd()*TAU, r:.1 + .44*Math.sqrt(rnd()), z:.7 + rnd()*1.3, tw:rnd()*TAU});
  const bubbles = []; let lastT = 0, nextBubble = 0;

  const smooth = (p, pts) => { // a closed curve through the points, rounded
    const n = pts.length, mid = (a, b) => [(a[0]+b[0])/2, (a[1]+b[1])/2], m0 = mid(pts[n-1], pts[0]);
    p.moveTo(m0[0], m0[1]);
    for (let i = 0; i < n; i++) { const a = pts[i], m = mid(a, pts[(i+1)%n]); p.quadraticCurveTo(a[0], a[1], m[0], m[1]); }
    p.closePath();
  };
  const prof = s => { // half the body's width: round at the nose, widest a quarter of the way back, a slim tail stock
    if (s < .26) return Math.sqrt(Math.max(0, 1 - Math.pow((.26 - s)/.26, 2)));
    return .2 + .8*Math.pow(1 - (s - .26)/.74, 1.35);
  };

  function whale(w, t, th){
    const R = w.R*S*(1 + .03*Math.sin(.9*t + w.phase)), L = w.len*S, span = L/R, h0 = w.hw*L;
    const P = s => { const f = th - s*span, r = R + w.amp*L*Math.sin(TAU*1.05*s - 4.4*t + w.beat)*(.1 + .9*Math.pow(s, 1.7));
      return [cx + r*Math.cos(f), cy + r*Math.sin(f)]; };
    const N = s => { // tail-ward direction, and the way to the whale's back (outwards from the circle)
      const a = P(Math.max(0, s - .012)), b = P(Math.min(1, s + .012)); let tx = b[0] - a[0], ty = b[1] - a[1];
      const m = Math.hypot(tx, ty) || 1; tx /= m; ty /= m;
      let nx = -ty, ny = tx; const f = th - s*span; if (nx*Math.cos(f) + ny*Math.sin(f) < 0) { nx = -nx; ny = -ny; }
      return [nx, ny, tx, ty];
    };
    const Q = (s, d) => { const p = P(s), n = N(s); return [p[0] + n[0]*d, p[1] + n[1]*d]; };          // d: distance towards the back
    const U = (s, u) => { s = Math.max(0, Math.min(1, s)); const p = P(s), n = N(s), h = h0*prof(s); return [p[0] + n[0]*h*u, p[1] + n[1]*h*u]; }; // u: -1 belly .. 1 back
    const ink = "#0A0B0F", pale = w.light ? "#F1EEE7" : "#FFFFFF";

    // flukes, behind the body
    const p1 = P(1), n1 = N(1), roll = Math.sin(-4.4*t + w.beat + .6);
    g.save(); g.translate(p1[0], p1[1]); g.rotate(Math.atan2(n1[3], n1[2]) + .22*roll); g.scale(1, .62 + .38*Math.abs(Math.cos(-2.2*t + w.beat)));
    const fl = L*.17; g.beginPath(); g.moveTo(-fl*.2, 0);
    g.bezierCurveTo(fl*.25, fl*.32, fl*.7, fl*.72, fl*1.05, fl*.98); g.bezierCurveTo(fl*1.0, fl*.55, fl*.88, fl*.25, fl*.62, 0);
    g.bezierCurveTo(fl*.88, -fl*.25, fl*1.0, -fl*.55, fl*1.05, -fl*.98); g.bezierCurveTo(fl*.7, -fl*.72, fl*.25, -fl*.32, -fl*.2, 0);
    g.closePath(); g.fillStyle = ink; g.fill(); g.restore();

    // the body
    const body = new Path2D(), M = 72, D = [], V = [];
    for (let i = 0; i <= M; i++) { const s = i < 14 ? .14*Math.pow(i/14, 2) : .14 + (i - 14)/(M - 14)*.86, p = P(s), n = N(s), h = h0*prof(s);
      D.push([p[0] + n[0]*h, p[1] + n[1]*h]); V.push([p[0] - n[0]*h, p[1] - n[1]*h]); }
    body.moveTo(D[0][0], D[0][1]); for (let i = 1; i <= M; i++) body.lineTo(D[i][0], D[i][1]);
    for (let i = M; i >= 0; i--) body.lineTo(V[i][0], V[i][1]); body.closePath();
    g.fillStyle = w.light ? pale : ink; g.fill(body);

    g.save(); g.clip(body);
    if (!w.light) { // white underside, with the pale flank patch behind the fin
      const edge = [[0, 0], [.04, -.4], [.12, -.22], [.22, -.12], [.34, -.2], [.46, -.3], [.55, -.14], [.62, .28], [.68, .44], [.73, .08], [.8, -.4], [.9, -.7], [1, -.9]];
      const pts = edge.map(e => U(e[0], e[1])); pts.push(U(1, -1.7), U(.5, -1.7), U(0, -1.7));
      const pp = new Path2D(); smooth(pp, pts); g.fillStyle = pale; g.fill(pp);
    } else { // the calf: a dark cape over a pale body
      const edge = [[0, .8], [.05, .1], [.15, -.25], [.3, -.2], [.44, -.32], [.58, -.15], [.72, -.28], [.88, -.35], [1, -.4]];
      const pts = edge.map(e => U(e[0], e[1])); pts.push(U(1, 1.8), U(.5, 1.8), U(0, 1.8));
      const pp = new Path2D(); smooth(pp, pts); g.fillStyle = ink; g.fill(pp);
    }
    // the oval patch behind the eye, slanting towards the back
    const e0 = U(.125, .5), en = N(.125), dx = en[2] + .42*en[0], dy = en[3] + .42*en[1];
    g.beginPath(); g.ellipse(e0[0], e0[1], .052*L, .021*L, Math.atan2(dy, dx), 0, TAU); g.fillStyle = pale; g.fill();
    if (!w.light) { // the grey saddle behind the fin
      const s0 = U(.58, .8), sn = N(.58);
      g.beginPath(); g.ellipse(s0[0], s0[1], .085*L, .034*L, Math.atan2(sn[3], sn[2]), 0, TAU); g.fillStyle = "#AEB2BA"; g.fill();
    }
    g.restore();

    // fins: the tall back fin, and the paddle on the underside
    const fin = new Path2D(), hh = w.fin*L;
    let a0 = Q(.345, h0*prof(.345)*.8), a1 = Q(.385, h0*prof(.385) + hh*.62), a2 = Q(.47, h0*prof(.47) + hh), a3 = Q(.5, h0*prof(.5) + hh*.12), a4 = Q(.515, h0*prof(.515)*.8);
    fin.moveTo(a0[0], a0[1]); fin.quadraticCurveTo(a1[0], a1[1], a2[0], a2[1]); fin.quadraticCurveTo(a3[0], a3[1], a4[0], a4[1]); fin.closePath();
    g.fillStyle = ink; g.fill(fin);
    const pec = new Path2D(), b0 = Q(.27, -h0*prof(.27)*.8), c0 = Q(.255, -(h0*prof(.255) + L*.13)), tp = Q(.41, -(h0*prof(.41) + L*.17)), c1 = Q(.44, -(h0*prof(.44) + L*.06)), b1 = Q(.385, -h0*prof(.385)*.8);
    pec.moveTo(b0[0], b0[1]); pec.quadraticCurveTo(c0[0], c0[1], tp[0], tp[1]); pec.quadraticCurveTo(c1[0], c1[1], b1[0], b1[1]); pec.closePath();
    g.fillStyle = ink; g.fill(pec);
    return p1;
  }

  function draw(tms){
    const t = tms/1000, dt = Math.min(.05, Math.max(0, t - lastT)); lastT = t;
    g.clearRect(0, 0, W, H);
    const pal = paletteAt(tms);
    // a soft pool of light behind the family, in the colour the sea is drifting through
    let gr = g.createRadialGradient(cx, cy, 0, cx, cy, S*.56);
    gr.addColorStop(0, rgb(pal.b[0], .55)); gr.addColorStop(.6, rgb(pal.b[1], .22)); gr.addColorStop(1, rgb(pal.b[1], 0));
    g.fillStyle = gr; g.fillRect(0, 0, W, H);
    // dots turning slowly round the middle, faster nearer it, twinkling
    for (const d of DOTS) {
      const r = d.r*S, a = d.a + t*.1*(.28*S/r), edge = Math.max(0, Math.min(1, (.5*S - r)/(.12*S)));
      const al = (.18 + .5*(.5 + .5*Math.sin(t*1.3 + d.tw)))*edge;
      g.fillStyle = "rgba(255,255,255," + al.toFixed(3) + ")"; g.beginPath(); g.arc(cx + r*Math.cos(a), cy + r*Math.sin(a), d.z*Math.max(.8, S/520), 0, TAU); g.fill();
    }
    // bubbles from the tails
    nextBubble -= dt;
    const th = .5*t; // the whole family turns clockwise
    const tails = WHALES.map(w => whale(w, t, th + w.phase));
    if (!still && nextBubble <= 0) { nextBubble = .09 + rnd()*.1; const q = tails[rnd() < .6 ? 0 : 1]; bubbles.push({x:q[0] + (rnd() - .5)*8, y:q[1] + (rnd() - .5)*8, vx:(rnd() - .5)*10, vy:-(8 + rnd()*16), life:1, r:1.2 + rnd()*2.4}); }
    for (let i = bubbles.length - 1; i >= 0; i--) {
      const b = bubbles[i]; b.x += b.vx*dt*S/560; b.y += b.vy*dt*S/560; b.life -= dt/2.6;
      if (b.life <= 0) { bubbles.splice(i, 1); continue; }
      g.strokeStyle = "rgba(255,255,255," + (b.life*.75).toFixed(3) + ")"; g.lineWidth = 1.1; g.beginPath(); g.arc(b.x, b.y, b.r*S/560 + 1, 0, TAU); g.stroke();
    }
  }

  if (opt.manual) return {draw:draw};
  let run = true, visible = true;
  if ("IntersectionObserver" in window) new IntersectionObserver(es => { visible = es[0].isIntersecting; }).observe(cv);
  function frame(t){
    if (!cv.isConnected || !run) return;
    if (visible && !document.hidden) draw(t);
    if (!still) requestAnimationFrame(frame);
  }
  if (still) draw(1700); else requestAnimationFrame(frame);
  return {draw:draw, stop:() => { run = false; }};
}
