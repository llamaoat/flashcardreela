# Changelog

## 1.3.2: going back to a card, smoother cards

- **Get started (Android).** The first screen's main button now picks GCSE Biology and Chemistry, "Normal" and
  no exam date, and goes straight to the switch (the old questions are one link away: "Choose my decks and
  exam date first"). The disclosure and the *Agree* tap are still there, and *Agree and open settings* now sits
  right under the disclosure instead of below the steps. When the switch is on, a test card slides up by
  itself. Android doesn't let an app switch its own accessibility service on, or allow its own restricted
  settings, and an accessibility service can't bring its app back to the front, so those steps stay with the
  student: in the app it's two taps, then Android's own switch.
- **Switch Reelcards off** on the dashboard: one tap, no trip to Settings. It's the only setting Reelcards uses.
- Clearer help when Android 13+ greys out the switch for an app installed from a file: the steps show before the
  student hits the wall (and say to tap the switch once first), and again if the switch didn't turn on.
- Docs: a Google Play step-by-step (`docs/PLAY-STEPS.md`).
- **The website has a new look** (it goes live when this is merged, with no new app version needed): a deep-sea
  landing page with an orca and her calf circling in the sea, "How it works", the install steps folded away,
  and the live demo below. The demo's welcome animation no longer runs while it's off-screen, and the sea draws
  in a few strokes instead of thousands, so the page is lighter on phones. New share picture (`tools/og-image.py`).
- **Going back to a card (Android).** Swipe back in TikTok, Instagram or YouTube and the card that sat
  there comes back, sliding down from the top. A card you did shows its answer with "You did this card
  already · Good" and isn't graded twice. One you swiped past says "You skipped this one. Have a go?" and
  counts when you answer it. Swiping back also no longer counts as a new reel, so it can't set off a fresh
  card. Not yet run on a phone. The browser extension does the same (next entry); the web demo doesn't.
- **The browser extension (0.2.0) catches up with the app.** *Get started* in the popup (one click: GCSE
  Biology and Chemistry, "Normal", no exam date, then a test card on the page you're on), *Switch off* and
  *Switch on*, and **going back to a card**: scroll or press ↑ to the video before and the card comes back
  with "You did this card already · Good" (answer showing, not graded twice) or "You skipped this one. Have a
  go?". Going back doesn't count as a new video. `tools/extension-test.py` is new: it loads the extension
  in a real Chromium and runs it on stand-in TikTok, Instagram and YouTube feeds (48 checks pass). It has not
  been tried on the live sites, which this machine can't reach. The extension keeps the last 30 videos of a
  visit in the page's memory so that back works (never saved or sent); `PRIVACY.md` says so.
- **In the web demo, TikTok, Instagram and YouTube now look different.** Each has its own top bar ("For
  You" with a search, a big "Reels" with a camera, "Shorts" with a search and a menu) and its own normal reels
  with that app's rail and counts, caption band and "Follow" or "Subscribe" button, in the safe zones the cards
  use. Pressing an app's button before setup now shows it: a normal reel, then a card slips in. The Android
  practice feed's filler reels are still the same for all three.
- **Get started in the web app too** (the home-screen app on an iPhone, and the demo on the website): one tap
  picks GCSE Biology and Chemistry, "Normal" and no exam date. On the home-screen app that's one tap to the
  first card, since there's no switch to turn on; the website's demo goes on to its pretend Android switch, as
  the app does. The iPhone Shortcut's link (`?card=1`) now works before setup as well, with the same choices.
  Not tried on a real iPhone.
- **The extension on phones, and packed for Safari (0.3.0),** so iPhones and iPads can have cards between
  videos in Safari: on the YouTube, TikTok, Instagram and X websites, not in their apps, which iOS doesn't allow
  for any app.
  - On a phone the card fills the screen like a reel. A finger drags it as on Android: up to move on, down as
    well on a card you came back to, springing back if the drag is short. Double-tap works without the browser's
    help. Touches on the card never reach the site, so its own swipe can't move the video underneath.
  - A **welcome page** opens by itself at install, in the website's sea-and-orca look: one tap on *Get
    started*, then (in Safari only) *Allow on the video sites*, then a site to open. The first video after Get
    started brings a card straight away.
  - `tools/build-safari.py` makes `Reelcards-safari.zip` (CI keeps it as an artifact) for Apple's Safari Web
    Extension Packager, which turns it into an app for TestFlight and the App Store with no Mac.
    [docs/SAFARI.md](docs/SAFARI.md) has every step, for you and for students. The website's iPhone card shows an
    *Add to Safari* button once its link is set.
  - Fixed on the way: *Pause 1 hour* on a card's bar now saves; saves no longer stall if the background
    script is slow to answer.
  - Tested in a Chromium the size of an iPhone, with touch, on stand-in pages (`tools/extension-test.py phone`,
    24 checks). **Not run in Safari or on an iPhone**: this machine can't run either, and needs an Apple
    Developer account to make the app.
- Smoother cards on Android (not yet run on a phone): the card window is now hardware-accelerated and
  the slide in and out animates a cached layer, so it no longer redraws every frame. A card on its way
  no longer triggers a save mid-slide, and the swipe detector stops querying the reel app for the rest of a
  swipe, which lightens the load on TikTok and the others.
- **Reelcards Feed** (`feed.html`): a separate page that plays pasted YouTube and TikTok links with cards
  between them, for browsers with no extension (Chrome on iPhone). Strict CSP, sandboxed embeds from
  youtube-nocookie.com and tiktok.com only, consent first, one-tap delete. Not yet tested on the real sites.
- `PRIVACY.md` covers the Feed; the security checklist is in `docs/SECURITY.md` and
  `docs/security-checklist.json`. Privacy page headings now have anchors.

## 1.3.1: pictures full screen, a seamless sea

- Behind the sea there's now a slowly shapeshifting colour field: four hues at once drifting and blending,
  easing every 18 seconds through eight palettes (ocean, dusk, lagoon, indigo, aurora, rose, deep sea,
  twilight), starting from one that follows the hour. It stays deep so the white words always read.
- The moving sea now fills the whole welcome screen instead of a band, and on the dashboard it melts into
  the page and scrolls away with it. Foam is softer behind the topic wheel so it stays readable.

- Once the answer's showing, tap a card's picture to open it full screen. Pinch or double-tap to zoom,
  drag to move, and tap **Close** to go back to the card. Pictures you can open have a small corner
  mark. A double-tap on the card still means "I knew it". Same in the web demo and the extension (there,
  scroll to zoom and Esc closes the picture).

## 1.3.0: browser extension, home-screen web app, dense decks

**Anki**
- Anki's text-to-speech fields (`{{tts es_ES:Front}}`) are spoken in their language: by the phone's own
  voice in the app, the browser's in the web demo and the extension. They used to show as plain text.
  This works for AnkiDroid cards too.

**Quizzes**
- A quiz's wrong answer now comes from a card asking the same kind of question ("Antidote for…" gets
  another antidote), so it's no longer obviously wrong.

**Getting it**
- The web page opens with a "Get Reelcards" box: the download and quick steps for Android, iPhone and
  computer, with your device's highlighted. The browser extension is now a plain download from the
  page (`Reelcards-extension.zip`), no GitHub account needed.

**iPhone**
- On an iPhone, the web page now explains how to make a Shortcut that opens straight onto a card
  (`?app=1&card=1`), and an optional automation that does it after you open TikTok. iPhones don't let
  any app draw over other apps, so this is a one-tap card, not one slipped into the feed.

**Look**
- A new look for the app's own screens, the extension popup and the web demo: Inter, light by day
  and dark when your phone is, ink pill buttons. The welcome screen and dashboard have a moving sea
  (a swimmer, waves of foam rolling past) with a wheel of your topics over it. Cards are unchanged.

**Android**
- A home-screen widget: one due card, *Show answer*, then *Knew it* or *Again*. Cards that need a
  picture or a sound stay out of it.
- Cards now also appear between videos in X, Reddit and Pinterest (as well as TikTok, Instagram,
  YouTube Shorts, Snapchat and Facebook). Detection in the new apps still needs checking on a phone.
- After moving to a new phone, Anki cards whose pictures or sounds didn't come across (Android doesn't back
  them up) are held back instead of showing "Picture not found". The dashboard says which decks, and
  opening the same Anki file again brings them back.

**Browser extension** (`extension/`)
- Cards between videos on TikTok, Instagram Reels, YouTube Shorts and X in Chrome and Edge: the same
  engine and card designs, drawn like each site's reels. Click for the answer, double-click if you knew
  it, scroll on to skip. A popup to pick decks, paste notes or open an Anki file, pictures, sounds and image occlusion included.

**Dense decks** (tried with a USMLE Step 1-style deck made by Anki itself: `tools/usmle-deck.py`)
- Tables in Anki cards are read row by row, each value labelled with its column ("Ferritin: Iron
  deficiency ↓, Chronic disease ↑"). They ran together before.
- A long cloze note keeps its gap in the big question; the set-up box never takes the sentence with
  the gap.
- Clinical vignettes aren't drawn as one long comment, so the question stays big and the answer
  readable.
- Quiz buttons: the wrong option is never one the question already shows (another gap of the same
  note), and is picked to look like the right answer (a species for a species).
- Na+, Ca2+, pH, NaCl and mEq/L keep their capitals in the bold looks. Hints skip "0:", "1." numbering.
  Extra fields no longer run together ("GOLDMARK; Glycols; Oxoproline…").
- Long deck names no longer push the Instagram and YouTube deck chip into the buttons: it shows the
  deck's last part ("Renal") and is cut short with "…" if needed.
- Web: a folded set-up box no longer shows half of its next line.

**Web demo**
- Add to Home Screen: a full-screen app view that works offline (iPhone and Android).
- Keeps progress in the browser once setup is done, Anki pictures included. *Reset everything* clears it.
- The design lab opens any Anki file, 8 cards a page in layouts their shape allows
  (`tools/lab-shots.py --deck`).

**Anki**
- AnkiDroid cards now follow the same rules as an Anki file: Reelcards reads each card's note and note
  type, so a cloze gap is a blank and the answer is just the deletion. Line breaks and lists in
  AnkiDroid cards stay separate points (they ran together before). Older AnkiDroid versions fall back
  to the card's rendered text.
- AnkiDroid doesn't let other apps read its pictures, so cards whose question needs a picture (and
  image occlusion) stay in AnkiDroid, and the dashboard suggests exporting that deck as a .apkg.
- A card answered in AnkiDroid while Reelcards held it isn't answered twice: grades are checked
  against AnkiDroid's queue before they're sent, and leaving AnkiDroid drops cards it no longer lists.
- Tested against decks exported by Anki's own code (`tools/real-anki-decks.py`): the default
  .apkg, "Support older Anki versions" and a .colpkg, compared card by card with how Anki shows them.
- Numeric HTML entities (`&#x27;`, as decks made by scripts have) are decoded. An image occlusion text
  label is never a card on its own.

**Import**
- Pictures are stored by name, decoded off the main thread at the size they're shown, and deleted
  when their deck is removed or an import isn't used.
- Turning the phone while a deck is still reading keeps the result (it was dropped).
- Image occlusion masks fade in on Android, as in the web demo.

**Sound**
- Anki's sound files come across from `.apkg` files (MP3, Ogg, M4A, WAV, FLAC up to 4 MB). A question
  that's only a sound asks "Listen: what's this?" and plays it as the card appears; a sound on the
  answer plays when it shows. *▶ Listen* and *▶ Hear it* play them again. Cards with nothing to read on
  either side are still left out. In the app and the web demo.

**Pilot**
- Dashboard → *Share my numbers (for a study)*: counts only (reels, cards answered and skipped per
  day, measured recall after a day or more, card styles), with a random code instead of a name,
  shown in full and shared only when the student taps *Share*. `tools/pilot-summary.py` adds up a
  class's reports; `docs/PILOT.md` is a plan for a school pilot.

**Notes**
- *Make cards with AI* on the paste-notes screen copies a ready-made prompt for any chatbot; its reply
  (Q:/A: lines) pastes straight back in. The app itself stays offline.
- The notes reader copes with how chatbots write: `**Q:**`, `Q1:`/`A1:`, and intro lines ending in a
  colon. 116 engine checks (was 104), and the Java/JS cross-check now covers notes too.

**Google Play**
- Targets Android 16 (API 36), as Play requires from 31 August 2026. Pages keep clear of the system
  bars and keyboard on Android 15+, and back works through Android 13+'s back callbacks (Android 16
  no longer calls the old method).
- The *Switch it on* screen is Play's prominent disclosure: what the accessibility service sees, does,
  keeps and never does, with *Agree and open settings* or *No thanks*.
- A privacy policy page (built from PRIVACY.md, published with the web demo), linked from the app.
- Release bundles sign with your own upload key (`tools/make-upload-key.sh`); CI builds the `.aab`
  and refuses one signed with the shared demo key.
- Listing graphics (`tools/store-assets.py`), and `docs/PLAY-STORE.md` for the Console steps.
- `docs/PHONE-TESTS.md`: the checklist for testing on real phones.

**Fixes**
- Handwritten (paper) cards: the ruled lines now sit under each line of writing instead of crossing
  it, in the app and the web demo.
- The first-cards tip fits the card: "Tap the answer you think is right" on a quiz, "Tap to uncover
  it" on image occlusion.
- A damaged save is kept (`engine.json.damaged`) instead of being overwritten, and the app goes back
  to the copy saved just before it (`engine.json.bak`), with a note on the dashboard.
- Share no longer loses the card. The card steps aside while the share sheet is open and comes back
  when you return to the app. A card shared without being answered isn't counted as a skip and isn't
  sent back in 3 reels.
- Removing an AnkiDroid deck on the dashboard now sticks (the deck is unselected, so the next refill
  doesn't bring its cards back).
- A restart mid-session carries on where it was: skipped cards keep their "back in 3 reels" place,
  cards skipped twice stay away until the next session, and the warm-up isn't repeated.
- A card's reading-progress bar stops when the card is closed, instead of running on for up to 30 s.
- The engine checks print UTF-8 whatever the console, so `engine/results/checks.txt` keeps its quotes
  and arrows. 104 checks (was 103).
- Docs: `docs/ENGINE.md` check count, and the APK size in `app/build.gradle`.

## 1.2.0: Anki files, cloze and image occlusion

**Import**
- Open any Anki file (.apkg or .colpkg) from the setup screen, with no AnkiDroid, no account and no
  internet. Reads all three Anki formats: 2.0 (`collection.anki2`), 2.1 (`collection.anki21`) and
  2.1.50+ (`collection.anki21b`, zstd-compressed, protobuf media list). The SQLite, zstd, protobuf and
  zip readers are written from scratch, so the app still has no third-party libraries.
- Note types: Basic, reversed, optional reversed, type-in-the-answer, custom ones (the answer is the
  first field the back adds; further fields become the explanation), cloze and image occlusion.
- Cloze: one card per gap number. The gap shows as a blank, or as the hint if the deck has one; the same
  number used several times becomes a multi-point answer; the full sentence goes behind "more".
  Nested deletions work.
- Image occlusion: Anki's built-in note type (rectangles, ellipses, polygons, text labels; fractional
  and older pixel coordinates) and the older Image Occlusion Enhanced add-on (SVG masks).
- Pictures on normal cards come across (PNG, JPEG, GIF, WebP, BMP up to 8 MB).
- Review cards keep their Anki progress: FSRS memory state if the deck has it, otherwise interval and
  ease; due dates carry over. Suspended and audio-only cards are left out, and the result screen says
  how many of each.
- Sample deck built in, so the import can be tried without an Anki file.

**Design**
- Image occlusion cards lead with the picture, full width and clear of the rail. The part to name is
  masked in the look's accent colour; a tap fades the mask to an outline, a double-tap means "knew it",
  and the hint uncovers the other labels so you can rule them out.
- Cloze gaps are drawn in each look's style: an accent block, an underline on handwritten notes, italic on
  the clean serif, a glow on neon. They also work in captions, the quiz layout and the numbered list.
- Front pictures sit under the question; back pictures under the answer.
- Screen readers and the voiceover read a gap as "blank".
- Design lab: "Anki: cloze & image occlusion".

**Engine**
- Picture cards always use big text; cloze cards never use the reply-to-a-comment layout.
- 103 engine checks (was 80), including all three Anki formats read to identical cards.
- `tools/make-test-decks.py` writes the test decks.

## 1.1.0: long answers

**Engine**
- Answer shapes (`AnswerShape`): answers are short, one long sentence, or split into 2–6 points (lists,
  steps, paragraphs), including Anki HTML. Long questions are split into their set-up and the question.
- The shape picks the layouts: quiz buttons only for short answers (and short wrong answers),
  word-by-word captions only for short questions, split-screen only where the answer fits.
- New layout: numbered list, with one blank per point before the reveal (1,470 combinations now).
- Part marks: tick the points you got. All = Good, at least half = Hard, fewer = Again; a hint caps
  it at Hard. Recalling more points gets more time before "fast" stops counting.
- Hints for long answers name how many points and how they start.
- New ready-made deck: GCSE Science, longer answers (13 cards, 2 to 5 marks).
- Anki cards up to 900 characters (was 220), since long answers are now split rather than cut.
- 80 engine checks (was 48), plus a Java/JavaScript cross-check of the splitter.

**Design**
- Points drop in one after another; the question shrinks out of the way; tap a point to mark it.
- "3 MARKS" pill; a quoted box for a long question's set-up.
- Chat answers arrive one message per point, with ✓ reactions for the ones you got.
- Long explanations go in the caption behind "more", which opens over a dimmed card.
- Think ring (3–7 s) and progress line (10–30 s) scale with reading time.
- Fit pass: text shrinks step by step instead of spilling out of the safe zone.
- Design lab: long cards, part marks, the opened caption and the hint state.

## 1.0.0

**Engine**
- FSRS-6 scheduler (21 parameters), checked step by step against the reference `py-fsrs`; exam-aware
  target recall (90% → 92% → 95%) and no intervals past the exam.
- Learned rhythm: Thompson sampling over six spacings to maximise answered cards per reel, with
  natural-break timing (waits after an absorbing reel, comes early when skimming).
- Format engine rebuilt as a recovering contextual bandit (per-app beliefs, fading recency penalty,
  run limits), now over 1,260 combinations: new layouts (word-by-word captions, reply-to-a-comment),
  hooks ("Storytime"), looks (highlight boxes) and backgrounds (music visualiser).
- Response-time grading (Easy / Good / Hard by speed), hints cap the grade at Hard.
- Personal forgetting-rate calibration after 100 long-term reviews.
- Interleaving across subjects, exam-paced new cards, final pre-exam sweep, leech flags.
- Interest themes (football, gaming, music, fitness, beauty, food, comedy) seed the format engine.
- Saved cards come back within a day.
- 48 engine checks; a 30-day simulation of 30 students per condition with saved results.

**Design**
- Cards follow each host app: safe zones, right-hand rail (like, hint, save, share), caption band,
  progress bar and typeface (TikTok Sans, bundled under the OFL).
- New looks and layouts drawn like creator formats; light looks float as a notes sheet over footage.
- Interest badges (scorebug, game HUD, visualiser pill…), coach tips on the first two cards, night
  versions of light looks, and opt-in "Match my feed's colours" (Android 11+).
- New backgrounds: endless-runner track and music visualiser.
- Web demo v2 with a design lab showing every layout for every app, interest and state.

**App**
- Hint, save and share buttons on every card; saved cards on the dashboard.
- Dashboard settings for colour matching and night mode.
- Card overlay now covers the navigation bar on Android 11+.

**Project**
- Engine moved into its own Gradle module (`engine/`), with checks and simulation runnable from Gradle.
- GPLv3 licence, privacy policy, contributing guide, engine and design docs.
- CI runs the engine checks, builds the APK, publishes a release on version tags, and deploys the web
  demo to GitHub Pages.

## 0.1.0: hackathon prototype

- Accessibility-service reel detection for TikTok, Instagram, YouTube Shorts, Snapchat and Facebook,
  with a card drawn as an accessibility overlay.
- FSRS-4.5 scheduling, gesture grading (double-tap = knew it), skipping that never punishes.
- Format bandit over 320 combinations; ready-made GCSE decks; notes → cards; AnkiDroid read/write.
- Setup wizard, dashboard with forecast, practice feed, web demo.
