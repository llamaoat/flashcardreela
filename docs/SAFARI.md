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
3. Open **YouTube Shorts in Safari**. Safari asks before an extension works on a site: tap the **⋯** More button
   (it shows a dot when Reelcards is waiting), then **Reelcards**, and choose **Always Allow**.
4. A **Get started** bar appears at the top of the video. One tap picks GCSE Biology and Chemistry, "Normal" cards
   and no exam date, and a card comes up straight away.

If Safari opens the welcome page by itself, that works too: *Get started*, *Allow on the video sites*, then
*YouTube Shorts*. It's there for when it opens, and nothing depends on it.

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
   install it on your iPhone. Go through the student steps above and [What to check](#what-to-check-on-your-iphone).
5. **Let students in.** Make an external testing group with a **public link** (up to 10,000 people). The first
   build of a group goes to Apple's Beta App Review, which can take a day or two. Then share the link.
6. **Put the link on the website**: set `SAFARI_LINK` near the top of `web-demo/src/app.js`, run
   `python3 tools/build-web-demo.py`, and merge. The iPhone card under "Get Reelcards" then shows an
   *Add to Safari* button.
7. **Each new version:** raise `version` in `extension/manifest.json`, let CI build it, and upload the new zip
   the same way. TestFlight builds last 90 days.
8. **The App Store, later:** fill in the listing, *Data Not Collected* for privacy (nothing leaves the phone),
   and the age rating questions, then submit. In the review notes, say what it does and on which sites, and
   that it reads nothing on the page but the size of the video players, which way the page scrolls and the page
   address.

If the packager reports a problem it can't solve, the fallback is a Mac with Xcode: Apple's converter
(`xcrun safari-web-extension-converter extension/`) makes an Xcode project from the same folder.

## How it's set up for Safari

- **The same code.** `build-safari.py` copies `extension/` and adds two things to the manifest: the 1024 x 1024
  icon the App Store wants (`docs/play-store/icon-1024.png`, from `tools/store-assets.py`), and Safari 16.4 as
  the oldest it's meant for.
- **Getting started doesn't depend on the welcome page.** Safari's install event is unreliable on iPhones (it
  can wait until the extension has been allowed on a site, and doesn't always come at all, by developers'
  reports), so the welcome page may never open. The first time a feed is open with nothing set up, the page
  itself shows a **Get started** bar (`content.js`, `offerSetup`): one tap sets up and shows a card on that video.
  *Not now* keeps it away for a day. The popup, from Safari's ⋯ menu, has Get started too.
- **Safari's permission.** Safari asks before an extension works on a site, and doesn't run it there until it's
  allowed. The welcome page and the popup have an *Allow on the video sites* button that asks Safari's question
  (`permissions.request`, the way Apple engineers have suggested on Apple's developer forums). They check
  afterwards whether access really was given rather than trust the answer, notice when it's given from Safari's
  own menu instead, and if no question appears they say how Safari asks on the site and offer to go there. Chrome
  allows the sites at install, so there the step never shows.
- **Phones.** On a phone held upright the card fills the screen, with no bar under it. A finger drags it, as the
  Android app's card does: up to move on, down as well on a card you came back to; it springs back if the drag
  is short. Double-tap is detected by the extension itself, because not every phone browser sends double-clicks.
  The site's phone addresses (`m.youtube.com`, `m.tiktok.com`) are covered.
- **The real sites.** Three things they might do, and what stops each one:
  - *Listening for swipes on the whole page.* `guard.js` loads before the page's own scripts, so Reelcards gets
    the first say over every touch, click, scroll and key. Anything that lands on a card is handled there and
    goes no further, so no site code, however early it listens, can move to its next video underneath.
  - *Drawing its own layers on top* ("Open in the app" banners, pop-ups). The card, the Get started bar and
    "Tap for sound" go in the browser's top layer (a popover, Safari 17 and later), above anything with a
    z-index; older Safari gets the highest z-index.
  - *Rebuilding its video players as you scroll* (some feeds throw them away to save memory). On a page with one
    address per video (YouTube Shorts, Instagram Reels) the address tells videos apart. Elsewhere, when the player
    can't, the way the page last scrolled decides "back", as the Android app uses a swipe's direction.
- **Sound after a card.** iPhones only let a page start a video with sound from a touch, a click or a key (WebKit's
  policy names touchend, click, dblclick and keydown; the pointer events the swipe is tracked with aren't on that
  list). So a swipe
  restarts the video from its touchend, a tap from its click, a key from its keydown. If the phone still says
  no, the video carries on muted rather than stopping, and a **Tap for sound** button gives the sound back
  (`sound.js`).
- **Saves** wait at most 2 seconds for the background script's lock, so a slow wake on a phone can't stall them.
- **Storage** asks for `unlimitedStorage`, which Safari 16 and later honour (otherwise it allows 5 MB).

## What's tested, and what isn't

**Tested** (`python3 tools/extension-test.py`): the extension in Chromium, on a computer-sized screen and on one
the size of an iPhone with touch, on stand-in YouTube, Instagram and TikTok pages that behave badly on purpose.
They listen for swipes, scrolls and keys on the whole window before anything else; the phone one keeps a banner
in the top layer; one TikTok-style feed throws its players away as you scroll. Checked on the phone:
- the welcome page, the Get started bar (and *Not now*), and the first card;
- tap, double-tap, short drag, swipe away;
- the site never moving underneath, the card above the banner, going back to cards on the rebuilt feed;
- the video stopping under a card and playing again with its sound;
- the muted fallback with "Tap for sound", on stand-in players.

Each new check was also run against a copy of the extension with that fix taken out, and failed there.

**Not tested: anything in Safari or on an iPhone.** This machine can't run WebKit or iOS, and can't reach the
real sites. So these are handled, but not seen working:
- **The welcome page opening by itself.** It doesn't need to now (the Get started bar), but it's worth knowing
  whether it does.
- **The Allow button bringing up Safari's question.** If it doesn't, the student uses Safari's ⋯ menu, which the
  page explains, and the Get started bar takes over.
- **The real phone websites.** The fixes above cover what they're likely to do, not what they actually do: they
  change their pages, and could do something else.
- **Sound after a card.** Chromium only asks for one tap per page before it allows sound, where iPhones ask for
  each play, so the phone test can't show the touchend timing matters. The fallback (muted, then "Tap for sound")
  is tested on stand-in players.
- **The app the packager makes**, and whether App Review asks for more in it.

## What to check on your iPhone

1. Install from TestFlight and switch it on. Does the welcome page open by itself? (Fine either way.)
2. Open YouTube Shorts in Safari. Does ⋯ show a dot? Tap Reelcards: which choices does Safari give?
3. After *Always Allow*: does the Get started bar appear at the top? Tap it: does a card come up?
4. Does the card fill the screen? Tap, double-tap, swipe up. Does the video stay where it was?
5. After the card, does the video play again with sound? If "Tap for sound" appears instead, does it work?
6. Swipe on to the next card, then back: does the card come back with its note?
7. Rotate the phone with a card up.
8. TikTok and Instagram websites: does a card appear between videos at all? Does going back bring it again?
9. The ⋯ menu: does Reelcards' entry open its settings? Pause and Switch off.
10. If the welcome page did open: does *Allow on the video sites* bring up Safari's question?

Write down what happened at each step (a screen recording helps most) and report it, as in
[TESTERS.md](TESTERS.md).

## Apple's pages

Checked on 23 and 24 October 2026:
[Packaging and distributing Safari web extensions with App Store Connect](https://developer.apple.com/documentation/safariservices/packaging-and-distributing-safari-web-extensions-with-app-store-connect),
[Assessing your Safari web extension's browser compatibility](https://developer.apple.com/documentation/safariservices/assessing-your-safari-web-extension-s-browser-compatibility),
[Managing Safari web extension permissions](https://developer.apple.com/documentation/safariservices/managing-safari-web-extension-permissions),
[WebKit: new video policies for iOS](https://webkit.org/blog/6784/new-video-policies-for-ios/) (which events
may start a video with sound). The *permissions.request* advice and the reports about Safari's install event come
from search summaries of Apple's developer forums, not pages read directly.
