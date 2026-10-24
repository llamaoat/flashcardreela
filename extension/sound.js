/* Reelcards extension: starting the site's videos again after a card. Phones only let a page start a video with
   sound from a touch, a click or a key (WebKit counts touchend, click, dblclick and keydown), so content.js calls
   resume() from one of those. If a phone still says no, the video carries on muted rather than stopping, and
   needSound(video) is told, so the student gets a "Tap for sound" button; unmute(video), from that tap, gives the
   sound back. A video that won't play at all is left as it was. */
"use strict";
const RCSound = (function(){
function resume(videos, needSound){
  videos.forEach(v => {
    let p; try { p = v.play(); } catch (e) { return; }
    if (!p || !p.catch) return;
    p.catch(err => {
      if (!err || err.name !== "NotAllowedError" || v.muted) return;
      v.muted = true;
      let q; try { q = v.play(); } catch (e) { v.muted = false; return; }
      if (q && q.then) q.then(() => { if (needSound) needSound(v); }, () => { v.muted = false; });
    });
  });
}
function unmute(v){ try { v.muted = false; const p = v.play(); if (p && p.catch) p.catch(() => {}); } catch (e) {} }
return {resume, unmute};
})();
