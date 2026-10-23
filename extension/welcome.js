/* Reelcards extension: the welcome page. It opens by itself once, when the extension is installed (background.js),
   and from the popup. One tap to get started; on Safari, one more to let it on the video sites (Chrome and Edge
   allow them at install); then a site to open, where the first video brings a card. */
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
// Safari's own question comes up straight from the tap; if it doesn't, say where the switch is
$("allow").onclick = () => { RCSetup.askSites().then(ok => { if (ok) show("ready"); else $("allowHelp").hidden = false; }); };
$("recheck").onclick = e => { e.preventDefault(); refresh(); };
RCState.onChange(refresh); // Get started pressed in the popup instead
addEventListener("focus", refresh); // back from Safari's settings
refresh();
})();
