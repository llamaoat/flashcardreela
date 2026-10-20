# Getting Reelcards onto Google Play

For the order to do things in, and what to click, start with [PLAY-STEPS.md](PLAY-STEPS.md). This page has
the text to paste.

Most of what Google Play asks for is now in the repo. The rest needs your Play Console account. This page
lists both, with text you can paste. Play's rules change, so check each step against the Console
when you do it.

## Already in the repo

| Play asks for | Where it is |
|---|---|
| Target API 36 (new apps and updates, from 31 August 2026) | `app/build.gradle`. Edge-to-edge pages and back callbacks are handled (tested on Android 16 under Robolectric, not yet on a phone). |
| Prominent disclosure and consent for the Accessibility API | The *Switch it on* screen (`MainActivity.showPermission`, text in `MainActivity.DISCLOSURE`). Android's settings only open after *Agree and open settings*. *No thanks* goes back. |
| Privacy policy at a public address, and in the app | `PRIVACY.md`, published as `privacy.html` by the Pages workflow. The app links to it from the disclosure and the dashboard (`MainActivity.PRIVACY_URL`). |
| An app bundle (.aab) signed with your own key | `tools/make-upload-key.sh`, then the *Play bundle* job in CI. `tools/check-play-bundle.sh` refuses the shared demo key. |
| Icon, feature graphic, phone screenshots | `docs/play-store/`, made by `tools/store-assets.py` |

## Steps only you can do

1. **Developer account.** Sign up at [play.google.com/console](https://play.google.com/console). There's
   a one-off fee and identity checks. New *personal* accounts must run a closed test with at least 12
   testers for 14 days before they can publish to everyone, so start that early.
2. **Upload key.** Run `tools/make-upload-key.sh` on your own computer. It prints four repository
   secrets to add (GitHub → Settings → Secrets and variables → Actions). Then run *Actions → Build →
   Run workflow*, or push a version tag. The *Reelcards-play-bundle* artifact is the `.aab` to upload.
   Keep the key file and its password in a password manager, and turn on **Play App Signing** when
   the Console asks (Google keeps the key that signs the app itself).
3. **Privacy policy online.** The Pages workflow publishes it at
   `https://llamaoat.github.io/flashcardreela/privacy.html`. Open that address and check it loads. If the
   repo moves or is renamed, change `PRIVACY_URL` in `MainActivity.java` and the links in `PRIVACY.md`.
4. **Create the app** in the Console: name *Reelcards*, default language English (United Kingdom), App,
   Free.
5. **App content** (Console → Policy → App content):
   - Privacy policy: the address above.
   - Ads: no ads.
   - App access: all features work without logging in. Say that the reel feature needs the
     accessibility service switched on, and that the in-app *Practice feed* shows cards without it.
   - Content rating: category *Reference, news or educational*. Answer no to violence, sexual content,
     language, drugs, gambling and user-to-user communication. (The share button only hands text to
     another app the student picks.)
   - Target audience: 13–15, 16–17 and 18+. Don't tick under-13 ages: that brings in the Families
     policy, and the app is made for 14–16-year-olds.
   - Data safety: see [below](#data-safety).
   - Sensitive permissions and APIs: the [Accessibility API declaration](#accessibility-api-declaration).
6. **Store listing:** the [text below](#store-listing), and the graphics in `docs/play-store/`
   (`icon-512.png`, `feature-graphic.png`, `phone-1.png` to `phone-5.png`). Category: Education. Give a
   contact email you're happy to show publicly.
7. **Release:** upload the `.aab` to *Internal testing* first, install it on two or three phones from
   Play, and go through [docs/PHONE-TESTS.md](PHONE-TESTS.md). Then run the closed test, then
   production. Raise `versionCode` in `app/build.gradle` for every upload.

## Accessibility API declaration

Reelcards isn't an accessibility tool, so `isAccessibilityTool` is `false` in
`res/xml/reel_watcher.xml`. Play then wants a declaration form and a video. Suggested answers:

- **Is your app an accessibility tool?** No.
- **What does it use the AccessibilityService API for?** *Reelcards is a revision app for school
  students. It notices when the user swipes to a new reel in a short-video app, so that every few reels
  it can show one of the user's own flashcards on top, as an accessibility overlay. It listens only for
  scroll and window-change events. It uses the scrolling view's size, position and resource name to tell
  a new reel from other scrolling, and window changes to take the card down when the user leaves the app.
  It doesn't read text on screen, and doesn't record what the user watches.*
- **Does it collect or share personal or sensitive data through the API?** No. Counts (reels swiped
  and the seconds between swipes) are kept on the device so the app learns when to show a card. The
  app has no internet permission.
- **Optional screenshot use (Android 11+):** off by default. If the user turns on *Match my feed's
  colours*, the app takes one screenshot per reel, reduces it to a single hue in memory and deletes it
  at once. It's never stored or sent.

**Video** (an unlisted YouTube link, 30–90 seconds, screen recording with captions):
1. Open Reelcards and go through setup to the *Switch it on* screen. Hold on the disclosure so it can
   be read.
2. Tap *Agree and open settings*, switch Reelcards on in Accessibility, tap Allow, and go back.
3. Open a short-video app and swipe a few reels until a flashcard appears over the feed.
4. Double-tap the card (it's graded and goes away), swipe on, and show another card being skipped.
5. Back in Reelcards, open *Reel detection log* to show it only saw scroll events and view names.

## Data safety

Google counts data as *collected* only when it leaves the device, and *shared* when it goes to a
third party, on or off the device, unless the user started it and expects it.

- **Does the app collect or share any of the required user data types?** No. Nothing leaves the device:
  there's no internet permission, no analytics and no account.
- **Two things to think about when you answer:**
  - *Share button:* the student taps it and picks the app, so it's a user-initiated share. That isn't
    declared.
  - *Share my numbers (for a study):* counts only, shown in full, and shared only when the student
    taps *Share* and picks where it goes. That's also a user-initiated share.
  - *AnkiDroid:* answers go to AnkiDroid on the same phone only if the student connects their decks.
    That's also user-initiated and expected. If the Console's wording makes you unsure, declare *App
    activity → Other actions* as shared, for app functionality, optional.
- **Security practices:** data isn't sent, so nothing needs encrypting in transit. Users delete
  everything with *Reset everything* or by uninstalling.

## Store listing

**App name** (30 characters max): `Reelcards: GCSE flashcards`

**Short description** (80 max): `Revise without trying: your flashcards slip in between the reels you watch.`

**Full description:**

```
Revise without trying. Reelcards slips your flashcards in between the short videos you already
watch, styled so they look like just another reel. Double-tap if you know it, swipe on if you don't.

HOW IT WORKS
• Every few reels, one of your flashcards appears, made to look like the app you're in.
• Double-tap if you knew it, tap to see the answer, or swipe on. Skipping is never punished.
• It learns when to show a card (never in the middle of a reel you're into) and which card styles you
  actually answer.
• A proper spaced-repetition scheduler (FSRS) plans reviews around your exam date.

YOUR CARDS
• Ready-made GCSE decks: Biology, Chemistry, Spanish and Science (longer answers).
• Paste your notes and they become cards, or use "Make cards with AI" to have a chatbot you already
  use write them.
• Your Anki decks through AnkiDroid: answers go straight back into Anki.
• Anki files (.apkg and .colpkg) from any Anki version, including cloze and image occlusion cards.
• Long answers are split into points, so you can mark what you got.

PRIVATE BY DESIGN
• No account, no ads, no analytics. The app doesn't even have internet access.
• Everything stays on your phone.

ABOUT THE PERMISSION
Reelcards uses Android's accessibility service to notice when you swipe to a new reel and to show a
card on top. It only reacts to scrolling. It never reads your messages or anything else on screen,
and never records what you watch. You'll see exactly what it does before you switch it on.

Reelcards is free and open source (GPLv3). It isn't affiliated with any of the apps it works
alongside.
```

Keep other apps' names (TikTok, Instagram, YouTube) out of the title, short description and graphics.
Play's metadata policy is strict about other brands. The full description doesn't need them either.

## What could still get it rejected

- **The Accessibility API review.** Google checks non-accessibility uses closely. The disclosure, the
  declaration and the video are what it reviews. Keep the video showing the disclosure clearly.
- **Drawing over other apps.** The card covers the app being used, by the student's own choice. If a
  reviewer calls this interference, point to the consent screen and the fact that a swipe dismisses
  it.
- **Testing.** The app has never run on a phone. Do the internal test and the phone checklist first.
