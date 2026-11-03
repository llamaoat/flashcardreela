/* Reelcards extension: loaded before the page's own scripts (document_start), so Reelcards gets the first say over
   every touch, click, scroll and key. While a card (or the Get started bar) is on screen, the ones that land on it
   are handled by content.js (window.__rcGuard) and go no further: a site's own listeners, even ones on the whole
   window, never see a swipe meant for the card, so the site can't move on to its next video underneath. With
   nothing of ours on screen this does nothing. content.js adds the same listeners itself if this didn't load. */
"use strict";
(function(){
if (window.__rcGuarded) return; window.__rcGuarded = true;
["touchstart","touchmove","touchend","touchcancel","pointerdown","pointermove","pointerup","pointercancel",
 "mousedown","mouseup","click","dblclick","contextmenu","wheel","keydown","keyup","keypress"].forEach(t =>
  addEventListener(t, e => { const g = window.__rcGuard; if (g && g(e)) e.stopImmediatePropagation(); }, {capture:true, passive:false}));
})();
