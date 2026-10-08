# Reelcards browser extension

Your flashcards, slipped in between the short videos you watch on a computer: **TikTok**, **Instagram
Reels**, **YouTube Shorts** and **X**. Every few videos, the extension pauses the one playing and shows
a card on top, drawn like that site's own reels. It uses the same engine and card designs as the
Android app and the web demo.

## Install it (Chrome, Edge, Brave, Opera, Vivaldi)

1. Get the folder:
   - from GitHub Actions: open the latest *Build* run and download **Reelcards-extension**, then unzip it;
   - or use the `extension/` folder from a clone of this repository.
2. Open `chrome://extensions` (in Edge, `edge://extensions`).
3. Switch on **Developer mode** (top right).
4. Click **Load unpacked** and pick the folder (the one with `manifest.json` in it).
5. Pin Reelcards from the puzzle-piece menu, then click its icon.

## Use it

1. In the popup, tick a ready-made deck, paste your notes, or open an Anki file. Set your exam date,
   and how often you want cards (*Demo* shows one every 2 videos, which is handy for trying it).
2. Open TikTok, Instagram Reels, YouTube Shorts or X and scroll as normal.
3. When a card appears:
   - **click** to see the answer;
   - **double-click** (or the heart) if you knew it;
   - **scroll on**, press **↓** or **Esc**, or click **Next ↓** to move on;
   - **Pause 1 hour** stops cards for a while.
4. To try it straight away, open one of the sites and click **Show a test card on this tab** in the popup.

The popup shows what you've done today and what's due, and *Reset everything* clears it all.

## How it works

- `content.js` runs on the four sites. It finds the video being watched (the largest one mostly on
  screen). On YouTube Shorts and Instagram Reels the page address changes with each video. On TikTok and
  X a new video element counts, and on X only a video that's actually playing. When the video changes,
  it asks the engine whether to show a card, exactly as the Android app does when a reel is swiped.
- The card is drawn in a shadow root, so the site's styles can't touch it, scaled from the app's
  360 × 740 design to fit the window. While it's up, the page's videos stay paused.
- `state.js` keeps everything in `chrome.storage.local`, shared by every tab and the popup.
- `lib/` is copied from `web-demo/src/` by `tools/build-extension.py`: the engine, the card renderer,
  the card styles, the ready-made decks, the notes parser and the Anki reader. Never edit it by hand;
  CI checks it's up to date. `fonts/` are the app's own fonts.

## Limits

- **Pictures and sounds** from Anki files are kept in the extension's storage. A site with a strict
  content policy could block them; it hasn't happened on the stand-in pages, but report it if a picture
  shows blank.
- **Sites change their pages.** Detection relies on how each site lays out its videos, which they can
  change at any time. It has been tested on stand-in pages that behave like each feed, not on the live
  sites (this project's test machines can't reach them), so try it and report what breaks.
- Not in the Chrome Web Store yet: install it as above. Firefox and Safari need small changes.
- Tabs share one save: each change re-reads the newest save first, so two tabs don't undo each other (only changes in the same few milliseconds could still clash).

## Privacy

It has no network access of its own and sends nothing anywhere. On the four sites it looks only at the
size and position of video elements and, on YouTube and Instagram, the page address, to tell one
video from the next. It doesn't read text on the page or keep a record of what you watch. See
`PRIVACY.md`.
