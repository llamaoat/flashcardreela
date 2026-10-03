# Roadmap

What's left to build, most important first. As of 1.2.0 (October 2026), the engine and web demo
are tested. The Android app compiles and has been reviewed line by line, but hasn't run on a real
phone yet.

## Now: before students use it

1. **Test on real phones.**
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
   fallback. `CopyWriter` is the plug-in point.
6. **Play Store readiness:**
   - the prominent disclosure and declaration Google requires for non-accessibility uses of the
     AccessibilityService API;
   - a private signing key;
   - a hosted privacy policy;
   - store listing screenshots from the design lab.

## Later

7. **iPhone.** iOS doesn't let apps detect individual reels.
   - **Options:** cards timed with Screen Time (every few minutes of TikTok), home-screen and
     lock-screen widgets, and a Shortcuts automation that opens a card when TikTok opens.
   - **Today:** the web demo's feed works on iPhone.
8. **Audio.** Cards whose Anki fields are only audio are left out today. Play Anki's sound files, or
    use text-to-speech where the field also has text.
9. **Real footage** for the looping backgrounds, instead of the ones drawn in code. This has to stay
    small, offline and free to use.
10. **A pilot with real students** to replace the simulation's modelled numbers with measured ones:
    cards answered per reel, skips, and recall at the exam.
