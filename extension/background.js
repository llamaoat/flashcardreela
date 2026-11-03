/* Reelcards extension: one lock for every save. A tab or the popup asks for it before changing progress
   and hands it back after saving, so changes from different tabs never overlap, however close together.
   A holder that disappears (a closed tab) loses the lock after 3 seconds. */
"use strict";
let held = false, timer = null;
const waiting = [];
function grant(reply){ held = true; clearTimeout(timer); timer = setTimeout(release, 3000); reply(true); }
function release(){ clearTimeout(timer); const next = waiting.shift(); if (next) grant(next); else held = false; }
/* The welcome page, once, when Reelcards is first installed (or first switched on, in Safari): one tap to get started. */
chrome.runtime.onInstalled.addListener(d => { if (d.reason === "install") chrome.tabs.create({url: chrome.runtime.getURL("welcome.html")}); });
chrome.runtime.onMessage.addListener((msg, sender, reply) => {
  if (!msg) return;
  if (msg.type === "lock") { if (held) waiting.push(reply); else grant(reply); return true; }
  if (msg.type === "unlock") { release(); reply(true); }
});
