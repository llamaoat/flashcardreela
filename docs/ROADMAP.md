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
   - **Import:** opening the sample `.apkg`.
   - **Report back:** fix what breaks, and record the phones tested in the README.
2. **Try real Anki exports.**
   - **Which decks:**
     - an Anki desktop 2.1.50+ export (the default format);
     - one with "Support older Anki versions" ticked;
     - a `.colpkg`;
     - a shared deck from AnkiWeb;
     - a deck using Anki's image occlusion;
     - a deck using the old Image Occlusion Enhanced add-on.
   - **How:** run `tools/crosscheck/run.sh deck.apkg` on each, open each in the web demo and on a
     phone, and fix any differences.
   - **Afterwards:** consider adding small real exports to `engine/src/test/resources/anki/` with
     checks.
3. **AnkiDroid connection: cloze and pictures.** `AnkiBridge` only takes the text of each card.
   - **Cloze:** read the note's fields and note type through the provider's notes endpoint. Then reuse
     `AnkiPackage.clozeParse`/`clozeText` so the gap draws as a blank and the answer is just the
     deletion, not the whole sentence. Set `card.cloze`.
   - **Pictures and image occlusion:** find out whether AnkiDroid's API lets other apps read media
     files. If not, show a one-line tip on those decks suggesting an `.apkg` export.
4. **AnkiDroid double answers.** If a student answers a card in AnkiDroid while Reelcards still holds
   it, Reelcards may send a second answer. Check the card is still due (the `schedule` query) just
   before showing or grading it.

## Next

5. **Import polish** (from the 1.2.0 code review):
   - delete pictures when a deck is removed or an import is abandoned;
   - store picture paths relative to the media folder;
   - decode pictures off the main thread and at display width;
   - add the target mask's fade-in that the web demo has (`maskin`);
   - keep an import's result when the screen is rotated mid-import (it's dropped today).
6. **"Make cards with AI" button** on the paste-notes screen:
   - It copies a ready-made prompt the student pastes into any chatbot along with their notes. The
     chatbot replies in `Q:`/`A:` lines, which the notes parser already understands.
   - The app gets AI-made cards with no AI inside it and no internet permission.
7. **On-device AI** for the copy (hooks, hints, captions) and for messy notes → cards. This would use
   Gemini Nano through ML Kit's GenAI APIs, on phones that have it, with today's templates as the
   fallback. `CopyWriter` is the plug-in point.
8. **Play Store readiness:**
   - the prominent disclosure and declaration Google requires for non-accessibility uses of the
     AccessibilityService API;
   - a private signing key;
   - a hosted privacy policy;
   - store listing screenshots from the design lab.

## Later

9. **iPhone.** iOS doesn't let apps detect individual reels.
   - **Options:** cards timed with Screen Time (every few minutes of TikTok), home-screen and
     lock-screen widgets, and a Shortcuts automation that opens a card when TikTok opens.
   - **Today:** the web demo's feed works on iPhone.
10. **Audio.** Cards whose Anki fields are only audio are left out today. Play Anki's sound files, or
    use text-to-speech where the field also has text.
11. **Real footage** for the looping backgrounds, instead of the ones drawn in code. This has to stay
    small, offline and free to use.
12. **A pilot with real students** to replace the simulation's modelled numbers with measured ones:
    cards answered per reel, skips, and recall at the exam.
