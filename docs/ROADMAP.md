# Roadmap

What's left to build, most important first. As of October 2026, the engine and web demo are
tested, and the Android app builds in CI and runs under Robolectric on Android 14 and 16, but it
hasn't run on a real phone yet.

## Now: before students use it

1. **Test on real phones:** [PHONE-TESTS.md](PHONE-TESTS.md) is the full checklist.
   - **Which phones:** two or three, ideally Android 8–10, Android 13+ (restricted settings) and a
     Samsung.
   - **Setup:** the accessibility switch (and "Allow restricted settings" on 13+).
   - **Detection:** reels detected in TikTok, Instagram and YouTube Shorts (Dashboard → Reel detection
     log), and the overlay appearing and going away cleanly.
   - **Cards:** every look and layout, tap, double-tap, swipe, hint, save and share, voiceover, and
     night mode after 10pm.
   - **Import:** opening the sample `.apkg` and the three in `engine/src/test/resources/anki/real/`;
     turning the phone while one is still reading; *Copy the AI prompt* and pasting a chatbot's reply.
   - **Report back:** fix what breaks, and record the phones tested in the README.
2. **Try the Anki exports that are still untested.** Decks exported by Anki's own code (the default
   `.apkg`, "Support older Anki versions", a `.colpkg`, and Anki's image occlusion) are now in
   `engine/src/test/resources/anki/real/`, and checks compare every card with how Anki shows it.
   - **Still to try:** a shared deck from AnkiWeb, and a deck made with the old Image Occlusion Enhanced
     add-on (its note type comes from the add-on, so Anki's library can't make one).
   - **How:** run `tools/crosscheck/run.sh deck.apkg` on each, open each in the web demo and on a
     phone, and fix any differences.
3. **Check the AnkiDroid connection against the real AnkiDroid.** Cloze from the note's fields, the
   picture tip, and the double-answer guard are built and tested against a stand-in written from the
   [API docs](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API). On a phone, check:
   - the `notes`, `models` and `templates` columns are what AnkiDroid really returns (if not, cards
     fall back to AnkiDroid's rendered text, so look for cloze cards showing `[...]`);
   - answering a card in AnkiDroid, then going back to TikTok, doesn't bring it up again.

## Next

4. **Pictures after moving phones.** Imported pictures are kept out of Android's backup (which stops
   altogether past 25 MB), so a restored phone has the cards but not their pictures, and image
   occlusion cards show "Picture not found". Decide whether to warn and offer to re-open the file, or
   to back up small decks' pictures.
5. **On-device AI** for the copy (hooks, hints, captions) and for messy notes → cards. This would use
   Gemini Nano through ML Kit's GenAI APIs, on phones that have it, with today's templates as the
   fallback. `CopyWriter` is the plug-in point. **Needs a decision first:** ML Kit is a Google library
   (from Google's Maven repository), which the "no third-party libraries" rule doesn't allow. Until
   then, *Make cards with AI* uses a chatbot the student already has, outside the app.
6. **Publish on Google Play.** The app side is done (API 36, the disclosure and consent, upload-key
   signing, a privacy policy page, listing graphics). What's left needs a Play Console account:
   the steps, listing text, Accessibility API declaration and video script are in
   [PLAY-STORE.md](PLAY-STORE.md).

## Later

7. **iPhone.** iOS doesn't let apps detect individual reels.
   - **Options:** cards timed with Screen Time (every few minutes of TikTok), home-screen and
     lock-screen widgets, and a Shortcuts automation that opens a card when TikTok opens.
   - **Today:** the web demo can be added to an iPhone's home screen, where it opens full screen and
     works offline (`manifest.webmanifest`, `sw.js`, the `appmode` view). It keeps progress in the
     browser (localStorage, plus IndexedDB for Anki files with pictures).
   - **Needs:** a Mac with Xcode (none in this project's cloud sessions), and Apple's Family Controls
     entitlement for the Screen Time APIs, which Apple has to approve.
8. **Audio, the rest.** Anki's sound files now come across from `.apkg` files and play (a question
   that's only a sound asks "Listen: what's this?"). Still missing: Anki's `{{tts:…}}` fields
   (text-to-speech in the deck's language), and sounds on AnkiDroid cards (other apps can't read
   AnkiDroid's media, as with pictures).
9. **Real footage** for the looping backgrounds, instead of the ones drawn in code. This has to stay
    small, offline and free to use.
10. **A pilot with real students** to replace the simulation's modelled numbers with measured ones:
    cards answered per reel, skips, and recall. The app side is ready: *Share my numbers* (counts only,
    shared when the student taps it), `tools/pilot-summary.py`, and a plan in [PILOT.md](PILOT.md).
    What's left is a school, consent, and two to four weeks.
