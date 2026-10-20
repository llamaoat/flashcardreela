# Testing on real phones

Reelcards has never run on a phone. Everything below is built and tested on a computer: the engine,
the web demo in Chrome, and the Android code under Robolectric on Android 14 and 16. Real phones can
still differ in the accessibility service, the overlay, audio focus, AnkiDroid, and what TikTok,
Instagram and YouTube report. This is the list to go through, in order. It takes about an hour per
phone.

## Before you start

- **Phones:** two or three, ideally one on Android 8–10, one on 13 or newer (restricted settings),
  one on 15 or 16 (edge to edge, new back gesture), and a Samsung.
- **Install:** the APK from the latest CI run (Actions → Build → *Reelcards-apk*), or
  `adb install -r Reelcards.apk`. Installing with `adb` skips Android 13's restricted settings, so
  install from a file on at least one phone to test that too.
- **Logs:** `adb logcat -s Reelcards ReelcardsAnki AndroidRuntime` while you test. Dashboard → *Reel
  detection log* shows what the watcher saw.
- **Record:** the phone, its Android version, and a pass or fail for each line below. For a failure,
  add a screen recording and the log.

## 1. Setup

| Check | Expect |
|---|---|
| First open | Welcome screen. Nothing hidden under the status bar or the navigation bar (Android 15+). |
| Step 1: pick GCSE Biology, paste the example notes, open the sample Anki deck | "Made 6 cards", "18 cards from sample.apkg" |
| *Copy the AI prompt*, paste into any chatbot with some notes, paste the reply back | "Copied…" shows; the reply becomes cards |
| Open the three decks in `engine/src/test/resources/anki/real/` (copy them to the phone) | 26 cards each |
| Turn the phone while a deck is reading | The result still appears, on the same screen |
| Step 3: *Switch it on* | The disclosure is shown first. *No thanks* goes back. *Agree and open settings* opens Accessibility. |
| Switch Reelcards on (Android 13+ from a file: *Open app info* → ⋮ → *Allow restricted settings* first) | Back in the app, it moves on to *Try it* by itself |
| Welcome screen: tap *Get started* | Goes straight to *Switch it on* ("One last step"), with the note about what it picked. The disclosure and *Agree* are still there, and *Agree and open settings* is right under the disclosure, before the steps. |
| After the switch is on (from *Get started*) | Back in the app: "All set", and a test card slides up by itself within a couple of seconds (once). *Done* opens the dashboard with Biology and Chemistry. |
| Welcome screen: *Choose my decks and exam date first* | The 4 steps as before (decks, exam date, switch, try) |
| Android 13+, installed from a file: open *Switch it on* | A line above the *Agree* button says Android may grey the switch out; "Installed from a file? Read this first" with the steps shows below the button, with no *Open Accessibility* button yet |
| Tap *Agree*, then come back from Settings without switching it on | The page shows "It didn't switch on" at the top, now with *Open app info* and *Open Accessibility* |
| Dashboard, Reelcards on: *Switch Reelcards off* | Within a second the status says *Cards are off*, and Accessibility settings show Reelcards off. *Switch on* leads back to the setup page. |
| Back gesture on every setup screen (Android 13+: watch for the back preview) | Goes one step back; on the welcome screen it leaves the app |

## 2. Reels

| Check | Expect |
|---|---|
| TikTok: swipe 10 reels | The detection log shows *REEL SWIPE* for each; a card appears after the warm-up |
| Instagram Reels, YouTube Shorts | Same |
| Scrolling a normal list (Instagram home feed, YouTube home) | No swipes counted, no cards |
| A card appears | The reel's sound pauses, then comes back when the card goes |
| Home button or another app while a card is up | The card goes away |
| Leave a card alone for 90 seconds | It goes away by itself |
| Night mode (dashboard: *Always*) | Light looks switch to their night versions |

## 3. A card

Do each on at least two layouts (Dashboard → *How often* → Demo shows cards quickly).

| Check | Expect |
|---|---|
| Tap | The answer shows |
| Double-tap | "Knew it", graded, the card goes |
| Swipe up | The next reel, with no grade (back in 3 reels) |
| Hint | A hint appears and the answer stays hidden |
| Save | The card shows under *Saved* on the dashboard |
| Share, pick an app, send, come back | The card is still there; swiping on then ends it |
| Voiceover on (dashboard) | The hook and question are read out |
| Image occlusion card (sample deck) | The mask fades in; tap uncovers it; the picture lines up with the masks |
| TalkBack on | The card's buttons and the gap are read sensibly ("blank") |
| A sound card (open `anki-latest.apkg` from `anki/real/`; Spanish deck) | "Listen: what's this?" plays a short tone as it appears, and *▶ Listen* plays it again. "How do you say “hello”…" plays it when the answer shows. The reel stays quiet meanwhile. |

## 4. AnkiDroid

Needs AnkiDroid installed, with a deck that has a Basic card, a cloze card and a card with a picture.

| Check | Expect |
|---|---|
| Step 1 → *Your Anki decks* → allow the permission → pick the deck | Due cards arrive (dashboard counts) |
| The cloze card | Shows a blank, and the answer is just the missing word, not `[...]` and the whole sentence |
| A card with line breaks or a list | Separate points |
| The picture card | Not shown; the dashboard says those stay in AnkiDroid and suggests a .apkg |
| Answer a card in Reelcards | It's answered in AnkiDroid (check AnkiDroid's browser) |
| Answer a due card in AnkiDroid, then go to TikTok | That card doesn't come up in Reelcards |
| Remove the deck on the dashboard | It doesn't come back on the next swipes |

## 5. Keeping things

| Check | Expect |
|---|---|
| Skip a card, then force-stop the app (Settings → Apps → Reelcards → Force stop), then swipe on | The skipped card still comes back in about 3 reels |
| Pictures after a phone move: `adb shell run-as com.reelcards.app rm -r no_backup/anki-media`, then reopen the app | The dashboard says pictures are missing for the sample deck's cards, and those cards don't appear in reels. *Open the Anki file*, pick `sample.apkg` again: "Pictures are back for … cards", and they appear again |
| Remove the sample Anki deck on the dashboard | Its pictures are deleted (`adb shell run-as com.reelcards.app ls no_backup/anki-media`) |
| *Reset everything* | Back to the welcome screen, pictures gone |
| Dashboard → *Share my numbers (for a study)* | The whole report shows (counts only); *Share* opens the share sheet |

## Reporting back

Add the phones and results to the README's "Honest limits" section, and fix what breaks. If TikTok,
Instagram or YouTube aren't detected, the log shows the view name to add to `REEL_IDS` in
`ReelDetector.java`.

## Home-screen widget and the newer apps

1. Long-press the home screen → Widgets → Reelcards. Add it. It shows a due card (or "Nothing due right now").
2. Tap *Show answer*, then *Knew it*. A new card appears; the dashboard's count for today goes up by one.
3. With the service on, scroll videos in X, Reddit and Pinterest. A card should appear every few videos, as in TikTok.

## Pictures full screen

1. Open a deck with pictures (the sample Anki deck has some). On a card with a picture, tap to reveal the answer.
2. The picture shows a small corner mark. Tap it: the picture fills the screen with a **Close** button.
3. Pinch to zoom in, drag to move, double-tap to zoom in and out. Nothing behind it reacts (no reveal, heart or swipe).
4. Tap **Close**: the card is back as it was. Double-tap the card elsewhere: "I knew it" still works.
5. Try an image occlusion card too, and a card whose answer is a picture.
