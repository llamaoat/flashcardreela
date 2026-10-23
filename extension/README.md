# Reelcards browser extension

Your flashcards, slipped in between the short videos you watch in a browser: **TikTok**, **Instagram
Reels**, **YouTube Shorts** and **X**. Every few videos, the extension pauses the one playing and shows
a card on top, drawn like that site's own reels. It uses the same engine and card designs as the
Android app and the web demo. On a computer it runs in Chrome, Edge and the like; on an iPhone or iPad it
runs in **Safari**, packed by `tools/build-safari.py` ([docs/SAFARI.md](../docs/SAFARI.md)).

## Install it (Chrome, Edge, Brave, Opera, Vivaldi)

1. Get the folder:
   - from GitHub Actions: open the latest *Build* run and download **Reelcards-extension**, then unzip it;
   - or use the `extension/` folder from a clone of this repository.
2. Open `chrome://extensions` (in Edge, `edge://extensions`).
3. Switch on **Developer mode** (top right).
4. Click **Load unpacked** and pick the folder (the one with `manifest.json` in it).
5. The welcome page opens by itself. Pin Reelcards from the puzzle-piece menu so its button is handy.

On an iPhone or iPad: [docs/SAFARI.md](../docs/SAFARI.md).

## Use it

1. On the welcome page (or in the popup), tap **Get started**. One tap picks GCSE Biology and Chemistry,
   "Normal" cards and no exam date. In Safari there's one more tap, **Allow on the video sites**, because
   Safari asks before an extension works on a website. Everything is still there to change in the popup:
   other ready-made decks, your own notes, an Anki file, your exam date, and how often you want cards
   (*Demo* shows one every 2 videos, which is handy for trying it).
2. Open TikTok, Instagram Reels, YouTube Shorts or X and scroll as normal. The first video after Get
   started brings a card straight away, so you can see it working.
3. When a card appears:
   - **tap** or **click** to see the answer;
   - **double-tap** (or the heart) if you knew it;
   - **swipe up**, **scroll on**, press **↓** or **Esc**, or click **Next ↓** to move on;
   - **Pause 1 hour** stops cards for a while.
4. **Going back to a card:** swipe, scroll or press **↑** back to the video before and the card that sat
   there comes back, sliding down from the top (as in the Android app). A card you did shows its answer,
   says "You did this card already · Good" and isn't graded twice. One you skipped says "You skipped this
   one. Have a go?" and counts when you answer it. Going back never counts as a new video.
5. **Switch off** in the popup stops all cards until you press **Switch on**. (Pause for an hour is the
   gentler version.) A test card still works while it's off.
6. To try it straight away, open one of the sites and click **Show a test card on this tab** in the popup.

The popup shows what you've done today and what's due, and *Reset everything* clears it all.

## How it works

- `content.js` runs on the four sites (and their phone addresses, `m.youtube.com` and `m.tiktok.com`). It
  finds the video being watched (the largest one mostly on screen). On YouTube Shorts and Instagram Reels
  the page address changes with each video. On TikTok and X a new video element counts, and on X only a
  video that's actually playing. When the video changes, it asks the engine whether to show a card,
  exactly as the Android app does when a reel is swiped.
- It keeps a short trail of the videos of this visit (the last 30, in the page's memory only). A card
  is shown on arriving at a video and sits just before it, so coming back to the video before it passes
  through the card's place and brings the card back. The same idea as the slots in the Android app's
  `ReelWatcherService`. A video change while a card is up is noted but doesn't count.
- The card is drawn in a shadow root, so the site's styles can't touch it. On a computer or tablet it's the
  app's 360 × 740 design in a frame, scaled to the window. On a phone held upright it fills the screen like a
  reel, and a finger drags it as the Android app's card does: up to move on (down as well on a card you came
  back to), springing back if the drag is short. Touches on the card never reach the site, so its own swipe
  can't change the video underneath. While a card is up, the page's videos stay paused.
- `welcome.html` opens once, at install, in the website's deep-sea look (`lib/sea.js`, `lib/orca.js`).
  `setup.js` is Get started and the Safari site question, shared by it and the popup.
- `state.js` keeps everything in `chrome.storage.local`, shared by every tab and the popup.
- `lib/` is copied from `web-demo/src/` by `tools/build-extension.py`: the engine, the card renderer,
  the card styles, the ready-made decks, the notes parser, the Anki reader and the sea. Never edit it by
  hand; CI checks it's up to date. `fonts/` are the app's own fonts.

## Limits

- **Pictures and sounds** from Anki files are kept in the extension's storage. A site with a strict
  content policy could block them; it hasn't happened on the stand-in pages, but report it if a picture
  shows blank.
- **Sites change their pages.** Detection relies on how each site lays out its videos, which they can
  change at any time. It has been tested on stand-in pages that behave like each feed, not on the live
  sites (this project's test machines can't reach them), so try it and report what breaks.
- **Going back leans on that detection too.** TikTok and X are told apart by their video elements, and if
  a site reuses one element for different videos, "back" looks like a new video: you just don't get the old
  card back, and nothing else goes wrong.
- **Safari hasn't run it yet.** Its phone layout and touch have been tested in a Chromium the size of an
  iPhone, not in Safari or on an iPhone: [docs/SAFARI.md](../docs/SAFARI.md) lists what to check.
- Not in the Chrome Web Store yet: install it as above. Firefox needs small changes.
- Tabs share one save. Every change takes a lock in `background.js`, re-reads the newest save, changes it and
  saves, so tabs never undo each other's progress. A save waits at most 2 seconds for the lock.

## Test it

`tools/extension-test.py` loads this folder in a real Chromium and drives the popup and stand-in pages that
behave like each site's feed: cards slipping in, Get started, Switch off, scrolling back to a card, and on a
phone-sized screen with touch, the welcome page, swipes and double-taps. It needs Python with Playwright
(`pip install playwright`); set `CHROMIUM=/path/to/chrome` if Playwright's own browser isn't installed. It
takes a few minutes and exits 1 if anything fails. `python3 tools/extension-test.py phone` runs one test.

## Privacy

It has no network access of its own and sends nothing anywhere. On the four sites it looks only at the
size and position of video elements and, on YouTube and Instagram, the page address, to tell one
video from the next. It doesn't read text on the page. It remembers the last 30 videos of a visit in the
page's memory so that "back" works, and that list is never saved or sent. See `PRIVACY.md`.
