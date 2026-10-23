# Reelcards on iPhone and iPad: the Safari extension

iPhones and iPads don't let any app draw over TikTok, Instagram or YouTube, so the Android way can't work there.
What does work is a **Safari extension**: in Safari, on the YouTube, TikTok, Instagram and X websites, it slips
cards in between videos, the same as the browser extension on a computer. It *is* that extension
(`extension/`), packed for Safari by `tools/build-safari.py`.

It works on the websites, in Safari, not in the apps. YouTube Shorts works best: the YouTube website on a phone
is a good feed. TikTok's and Instagram's phone websites work but keep nudging people towards their apps, and
Instagram needs a login.

## What a student does, once

1. Tap the link on the website (or in a message from you) and install Reelcards. While it's in testing, that's
   a TestFlight link: TestFlight installs first (Apple's app for test versions), then Reelcards.
2. Switch the extension on: **Settings, Apps, Safari, Extensions, Reelcards**, on (on iOS 17 and earlier:
   **Settings, Safari, Extensions**). The Reelcards app that comes with it says the same.
3. The welcome page opens in Safari. If it doesn't, open YouTube in Safari, tap the **⋯** More button, then
   **Reelcards**. On it:
   - **Get started** (one tap: GCSE Biology and Chemistry, "Normal" cards, no exam date);
   - **Allow on the video sites**: Safari asks once whether Reelcards may work on those sites, and the answer is
     *Always Allow*;
   - **YouTube Shorts**: the first video brings a card straight away.

After that it's tap to see the answer, double-tap if you knew it, swipe up to move on, swipe back to see a card
again. The card fills the screen like a reel. Reelcards' entry in Safari's **⋯** More menu opens its settings:
pause, switch off, decks, exam date.

## What you do, once (no Mac needed)

You need an **Apple Developer Program** membership (US$99 a year). Apple's rules say who can hold one (an adult,
in most countries), so if you're under 18 ask a parent, carer or school to hold it.

1. **Get the file.** On GitHub, open **Actions**, the newest **Build** run, and download **Reelcards-safari**
   (GitHub wraps it in a zip; inside is `Reelcards-safari.zip`). Or run `python3 tools/build-safari.py`, which
   writes `build/safari/Reelcards-safari.zip`.
2. **Make an app record.** In [App Store Connect](https://appstoreconnect.apple.com/), **Apps, +, New App**:
   platform iOS (add macOS too if you like: the same extension works in Safari on a Mac), a name such as
   *Reelcards: GCSE flashcards*, language English (UK), a bundle ID such as `com.yourname.reelcards`, and any SKU.
3. **Pack it.** **Users and Access, Xcode Cloud, Safari Web Extension Packager, Upload**, and upload
   `Reelcards-safari.zip`. Apple builds the app that holds the extension. You can watch it in the Xcode Cloud
   tab; it uses the 25 hours of Xcode Cloud a month that come with the membership.
4. **Test it yourself first.** In **TestFlight**, add yourself as an internal tester (no review needed) and
   install it on your iPhone. Go through the student steps above and [What to check](#what-to-check).
5. **Let students in.** Make an external testing group with a **public link** (up to 10,000 people). The first
   build of a group goes to Apple's Beta App Review, which can take a day or two. Then share the link.
6. **Put the link on the website**: set `SAFARI_LINK` near the top of `web-demo/src/app.js`, run
   `python3 tools/build-web-demo.py`, and merge. The iPhone card under "Get Reelcards" then shows an
   *Add to Safari* button.
7. **Each new version:** raise `version` in `extension/manifest.json`, let CI build it, and upload the new zip
   the same way. TestFlight builds last 90 days.
8. **The App Store, later:** fill in the listing, *Data Not Collected* for privacy (nothing leaves the phone),
   and the age rating questions, then submit. In the review notes, say what it does and on which sites, and
   that it reads nothing on the page but the size of the video players and the page address.

If the packager reports a problem it can't solve, the fallback is a Mac with Xcode: Apple's converter
(`xcrun safari-web-extension-converter extension/`) makes an Xcode project from the same folder.

## How it's set up for Safari

- **The same code.** `build-safari.py` copies `extension/` and adds two things to the manifest: the 1024 x 1024
  icon the App Store wants (`docs/play-store/icon-1024.png`, from `tools/store-assets.py`), and Safari 16.4 as
  the oldest it's meant for.
- **Phones.** On a phone held upright the card fills the screen, with no bar under it. A finger drags it, as the
  Android app's card does: up to move on, down as well on a card you came back to; it springs back if the drag
  is short. Double-tap is detected by the extension itself, because not every phone browser sends double-clicks.
  Touches on the card never reach the site, so its own swipe can't change the video underneath. The site's
  phone addresses (`m.youtube.com`, `m.tiktok.com`) are covered.
- **Safari's permission.** Safari asks before an extension works on a site, and doesn't run it there until it's
  allowed. The welcome page and the popup have an *Allow on the video sites* button that asks Safari's question
  (`permissions.request`, the way Apple engineers have suggested on Apple's developer forums), and say where the
  switch is if no question appears. Chrome allows the sites at install, so there the step never shows.
- **The welcome page** (`welcome.html`) opens once, when the extension is installed, in the website's deep-sea
  look. *Get started* is shared with the popup (`setup.js`), and the first video after it brings a card.
- **Saves** wait at most 2 seconds for the background script's lock, so a slow wake on a phone can't stall them.
- **Storage** asks for `unlimitedStorage`, which Safari 16 and later honour (otherwise it allows 5 MB).

## What's tested, and what isn't

**Tested** (`python3 tools/extension-test.py phone`): the extension in a Chromium the size of an iPhone, with
touch, on stand-in YouTube and TikTok pages. The welcome page opens by itself and one tap sets it up; the first
video brings a card; it fills the screen; tap, double-tap, short drag, swipe away (without the video underneath
moving, or TikTok's feed scrolling), swiping back to a card you did and to one you skipped. 24 checks.

**Not tested: anything in Safari or on an iPhone.** This machine can't run WebKit or iOS. In particular:
- whether the welcome page opens by itself when the extension is switched on in Safari;
- whether *Allow on the video sites* brings up Safari's question, or the student needs the ⋯ menu;
- the real YouTube, TikTok and Instagram phone websites (they change their pages, and this machine can't reach
  them);
- whether the video plays again after a card, with sound (iPhones only allow that from a tap or swipe; the
  extension restarts it during the swipe, but YouTube may want a tap);
- the app the packager makes, and whether App Review asks for more in it.

## What to check on your iPhone

1. Install from TestFlight, switch it on, and see whether the welcome page opens by itself.
2. Get started, then Allow: does Safari's question appear? Which choices does it give?
3. YouTube Shorts in Safari: a card on the first video? Does it fill the screen? Tap, double-tap, swipe up.
4. Does the video play again after the card, with sound?
5. Swipe on to the next card, then back: does the card come back with its note?
6. Rotate the phone with a card up.
7. TikTok and Instagram websites: does a card appear between videos at all?
8. The ⋯ menu: does Reelcards' entry open its settings? Pause and Switch off.

Write down what happened at each step (a screen recording helps most) and report it, as in
[TESTERS.md](TESTERS.md).

## Apple's pages

Checked on 23 October 2026:
[Packaging and distributing Safari web extensions with App Store Connect](https://developer.apple.com/documentation/safariservices/packaging-and-distributing-safari-web-extensions-with-app-store-connect),
[Assessing your Safari web extension's browser compatibility](https://developer.apple.com/documentation/safariservices/assessing-your-safari-web-extension-s-browser-compatibility),
[Managing Safari web extension permissions](https://developer.apple.com/documentation/safariservices/managing-safari-web-extension-permissions).
The *permissions.request* advice and the reports of Safari waiting for permission come from search summaries of
Apple's developer forums, not pages read directly.
