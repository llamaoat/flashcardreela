/* Reelcards extension: the welcome page. It opens by itself when the extension is installed (background.js) and
   from the popup. One tap to get started; on Safari, one more to let it on the video sites (Chrome and Edge allow
   them at install); then a site to open, where the first video brings a card. Safari doesn't always open this page,
   so the video sites offer Get started themselves too (content.js), and nothing here is the only way through. */
"use strict";
(function(){
const $ = id => document.getElementById(id);
seaBackdrop({swimmer:false, maxDpr:1.5, veil:() => .3});
orcaFamily($("orca"));

$("sites").innerHTML = RCSetup.FEEDS.map((f, i) => '<a class="btn' + (i ? "" : " primary") + '" href="' + f[1] + '">' + f[2] + "</a>").join("");

let step = null;
function show(s){
  if (s === step) return; step = s;
  ["start", "allow", "ready"].forEach(n => { $("s-" + n).hidden = n !== s; });
}
function refresh(){
  RCState.load().then(r => {
    if (!r.E.cards.length) { show("start"); return; }
    RCSetup.hasSites().then(ok => show(ok ? "ready" : "allow"));
  });
}
$("go").onclick = () => {
  $("go").disabled = true;
  RCState.update((E, m) => RCSetup.quickStart(E, m)).then(() => { $("go").disabled = false; refresh(); });
};
/* Safari's own question comes up straight from the tap. Whatever it answers, look again rather than trust it; if
   access still isn't there, say how Safari asks on the site instead, and let them go there. */
$("allow").onclick = () => {
  RCSetup.askSites().then(() => RCSetup.hasSites()).then(ok => {
    if (ok) { show("ready"); return; }
    $("allowHelp").hidden = false; $("anyway").hidden = false;
  });
};
$("recheck").onclick = e => { e.preventDefault(); refresh(); };
RCState.onChange(refresh); // set up from the popup, or from a video page, instead
try { chrome.permissions.onAdded.addListener(refresh); } catch (e) {} // allowed from Safari's own menu meanwhile
addEventListener("focus", refresh);
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); }); // back from Safari's settings
refresh();
})();
