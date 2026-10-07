# Privacy

*Last updated: 7 October 2026. Applies to the Reelcards Android app, the web demo and the browser extension.*

Reelcards is built for school students, so it's built to know as little as possible.

**In short:** no account, no ads, no analytics, no tracking. The app doesn't even request Android's
Internet permission, so it can't send anything anywhere. Everything stays in one file on your phone.

## What the accessibility permission is used for

Reelcards needs Android's accessibility permission to notice when you swipe to a new reel and to draw
a card on top of the app. It is set up to receive only two kinds of event:

- **scroll events**, to tell a new reel from other scrolling, using the size and position of the
  scrolling view and Android's internal view names (like `reel_viewer`);
- **window changes**, to take the card down when you leave the app.

It does **not** read messages, comments, captions or any other text on screen, and it doesn't record
what you watch, who posted it or how long you watched any particular account. It uses the time between
swipes (a number of seconds) to avoid interrupting a reel you were absorbed in, and keeps counts (how
many reels you swiped, and the seconds between swipes, per app) in the file described below, so it
learns when to show a card.

Before you switch it on, the app shows this on its *Switch it on* screen, and only opens Android's
settings if you tap *Agree*.

The **Reel detection log** in the app shows exactly what the watcher saw (app, view name and whether it
counted as a swipe), so you can check this yourself. It keeps the last 40 entries, on your phone only.

## Match my feed's colours (optional, off by default)

If you switch this on (Android 11+), after each reel settles the app takes one screenshot,
shrinks it to 24 × 48 pixels in memory, works out a single colour (a hue from 0 to 360) and
deletes the image straight away. Only that one number is kept, and it's used to tint the next card.
Screenshots are never saved or sent anywhere. Switch it off any time on the dashboard.

## What's stored on your phone

One file in the app's private storage holds your cards, their review history and schedule, your
settings (exam date, interests, how often), daily counts, and what the engine learned about which card
styles and rhythm suit you. Uninstalling the app or tapping *Reset everything* deletes it. If your
phone's system backup is turned on, Android may include this file in your own backup, as it does for
other apps.

If you open an Anki file, the pictures from its cards are saved in the app's private storage too, so the
cards can show them. They're left out of system backups (to keep backups small), and *Reset everything*
or uninstalling deletes them. The Anki file itself is read from the copy you picked and isn't kept.

## The web demo

The web demo runs in your browser, with the same rules: no account, no analytics, and your cards and
answers aren't sent anywhere. Once you finish setup, it keeps your cards, their schedule, your settings
and the same daily counts in your browser's own storage on that device, so it carries on where you left
off (including when it's added to your home screen). If you open an Anki file with pictures or sounds,
that file is kept there too, so the pictures can show next time. *Reset everything* deletes all of it,
and so does clearing the site's data in your browser.

Loading the page itself does go over the internet: the site is hosted by GitHub Pages, and the page asks
Google Fonts for its fonts, so like any website, they see your device's IP address when it loads. Neither
sees your cards. After the first visit, the page can also open from a copy kept on your device, with no
signal.

## The browser extension

The extension runs on TikTok, Instagram, YouTube and X (twitter.com) in your browser. To tell one
short video from the next, it looks at the size and position of the video players on the page and, on
YouTube and Instagram, at the page address. It doesn't read text, comments or names on the page, and
doesn't keep a record of the videos or addresses. It pauses the playing video while a card is up and
plays it again afterwards.

Your cards, their schedule, your settings and daily counts are kept in the browser's storage for
extensions, on that computer. The extension doesn't send anything anywhere: it has no server and no
analytics. *Reset everything* in its popup, or removing the extension, deletes it all.

## Other apps

- **AnkiDroid** (only if you choose Anki decks): Reelcards reads the cards AnkiDroid says are due in the
  decks you picked, with each card's note and note type (its fields and card templates), and sends back
  your answers, using AnkiDroid's official API and permission, which Android asks you to approve.
- **Anki files** (only if you open one): Android's file picker gives Reelcards that one file, nothing else
  in your storage. It's read on the phone; nothing is uploaded.
- **Share button:** only when you tap it, the question (not the answer) goes to the app you pick in
  Android's share sheet.
- **Voiceover:** reads the hook and question with your phone's own text-to-speech engine.
- **Share my numbers (for a study):** only when you tap *Share*, a summary of counts (reels swiped,
  cards shown, answered and skipped each day, how many you remembered, which card styles you skipped)
  goes wherever you send it, with a random code instead of your name. It has no cards, deck or file
  names, or apps in it, and the app shows you all of it first.
- **Make cards with AI:** *Copy the AI prompt* puts a ready-made prompt on your clipboard. If you paste
  it, with your notes, into a chatbot, that's between you and that chatbot's maker, under their
  privacy policy (and their age rules). Reelcards only sees the reply if you paste it back in.

## Children

Reelcards is made for students aged 13 and over, and collects no personal data from anyone, of any
age: there's no account, and nothing leaves the phone.

## Contact

Questions or concerns: open an issue at [github.com/llamaoat/flashcardreela](https://github.com/llamaoat/flashcardreela/issues).
If this policy changes, the new version is published at the same address, with a new date at the top.
