/* Reelcards extension: the setup shared by the popup and the welcome page. Get started is one tap, as in the app:
   GCSE Biology and Chemistry, "Normal" cards and no exam date, and a card on the very first video so it's clear at
   once that it works. Needs lib/engine.js, lib/decks.js and state.js. */
"use strict";
const RCSetup = (function(){
const PRE = {"GCSE Biology":"bio","GCSE Chemistry":"chem","GCSE Spanish":"es","GCSE Science: longer answers":"sci"}; // same ids as the web demo
const deckCards = name => DECKS[name].map((r, i) => RC.makeCard(PRE[name] + "-" + (i + 1), name, r[0], r[1], r[2]));
function setExam(E, m, days){ m.exam = days; E.settings.examDate = days ? Date.now() + days*RC.DAY : 0; }
function quickStart(E, m){
  if (!E.cards.length) { E.addCards(deckCards("GCSE Biology")); E.addCards(deckCards("GCSE Chemistry")); }
  E.settings.intensity = "normal"; setExam(E, m, 0); m.off = false; m.onboarded = true; m.firstCard = true;
}
/* The sites Reelcards works on. Chrome and Edge allow them when it's installed; Safari asks first, so on Safari
   there's one more tap: askSites() shows Safari's own question. It has to run straight from a tap. */
const SITES = chrome.runtime.getManifest().host_permissions || [];
function hasSites(){
  return new Promise(ok => {
    try { if (!chrome.permissions || !chrome.permissions.contains) { ok(true); return; }
      chrome.permissions.contains({origins: SITES}, r => { void chrome.runtime.lastError; ok(r !== false); }); } catch (e) { ok(true); }
  });
}
function askSites(){
  return new Promise(ok => {
    try { chrome.permissions.request({origins: SITES}, r => { void chrome.runtime.lastError; ok(!!r); }); } catch (e) { ok(false); }
  });
}
/* where to go once it's set up: the pages that start straight on a feed of short videos (name, address, short name) */
const FEEDS = [["YouTube Shorts", "https://www.youtube.com/shorts", "YouTube Shorts"], ["TikTok", "https://www.tiktok.com/", "TikTok"],
  ["Instagram Reels", "https://www.instagram.com/reels/", "Instagram"], ["X", "https://x.com/", "X"]];
return {PRE, deckCards, setExam, quickStart, SITES, hasSites, askSites, FEEDS};
})();
