# Reelcards: notes for Claude Code

Reelcards puts flashcards between TikTok, Instagram Reels and YouTube Shorts, drawn so they look
like just another reel. An adaptive engine picks *when* a card appears, *which* card and *how it
looks*. Audience: UK GCSE students, 14–16. Android first, open source (GPLv3).
`README.md` is the overview; `docs/ROADMAP.md` is what's left to build.

## Layout

- `engine/`: all the logic, in plain Java 8 with no Android and no dependencies. It covers scheduling
  (FSRS-6), rhythm, the format bandit, grading, answer shapes, copy, notes parsing and reading Anki
  files (`anki/`).
- `app/`: the Android app (minSdk 26). It holds the accessibility service and overlay, the setup and
  dashboard screens and the card renderer (`CardView.java`). All UI is built in code, with no layout XML.
- `web-demo/src/`: a JavaScript port of the engine (`engine.js`, `apkg.js`), the card renderer
  (`render.js` + CSS in `head.html`) and the whole app journey (`app.js`). It's built into one file,
  `web-demo/index.html`, which is generated, so never edit it by hand.
- `docs/`: `ENGINE.md` (how and why the engine works), `DESIGN.md` (card design), `ROADMAP.md`.
- `tools/`:
  - the APK and web-demo builds;
  - the test-deck maker;
  - Java/JS cross-checks;
  - design-lab screenshots;
  - the shared debug signing key.

## Commands

```
./gradlew :engine:engineChecks     # 104 checks, must all pass (no Gradle: see README "Run them yourself")
./gradlew :engine:simulate         # 30-day simulated students, ~2 min → compare with engine/results/simulation.txt
tools/crosscheck/run.sh            # Java engine vs JS port: answer splitting + every card from the test decks
python3 tools/build-web-demo.py    # rebuild web-demo/index.html after editing web-demo/src/
python3 tools/lab-shots.py --cards long|short|anki --states q a hint   # PNGs of the design lab in build/shots/
./gradlew assembleDebug            # the APK (needs the Android SDK); CI builds it on every push
python3 tools/make-test-decks.py   # regenerate engine/src/test/resources/anki/*.apkg
```

`tools/build-apk.sh` builds the APK with no Gradle, from `aapt2`, `javac`, a dexer and `apksigner`
(the paths go in environment variables). `release/Reelcards.apk` was made that way.

## Hard rules

- **Java 8, no lambdas and no method references**, in `engine/` and `app/`. Use anonymous classes.
  The Gradle-free build uses the old `dx` dexer, which can't handle them, and the code stays consistent.
- **No third-party libraries and no AndroidX.** The app is ~410 KB. New file formats get small readers
  written in-house, like `anki/SqliteFile.java` and `anki/Zstd.java`.
- **No INTERNET permission, no analytics, no accounts.** Privacy is a feature for an under-18 audience
  (`PRIVACY.md`). Data stays in `Store.java`'s JSON file, plus imported pictures in no-backup storage.
- **Keep Java and JavaScript in step.** The web demo mirrors the app:
  - `engine/…` ↔ `web-demo/src/engine.js`
  - `engine/…/anki/AnkiPackage.java` ↔ `web-demo/src/apkg.js`
  - `app/…/CardView.java` ↔ `web-demo/src/render.js` + `head.html`

  Change both sides in the same commit and run `tools/crosscheck/run.sh`. Card designs are checked
  visually in the web demo's design lab, because there's no emulator in most sessions.
- Minimum Android 8.0 (API 26); don't use newer APIs without a version check.

## How to work here

- **After engine changes:** run the engine checks and the cross-check. Run the simulation when
  scheduling, rhythm or format choice changes, and save the new outputs in `engine/results/` if they move.
- **After card or design changes:** rebuild the web demo and screenshot the design lab
  (`tools/lab-shots.py`). Look at every look (caption, subtitle, clean, neon, paper) and host
  (`--host instagram|youtube`), plus the hint and answer states. The fit pass shrinks text when it
  would spill out of the safe zone; `fit steps` in the tool's output shows where that happened.
- **Android code is mostly unverified on devices.** It compiles and has been reviewed line by line,
  but has never run on a phone or emulator. Don't claim device testing that didn't happen. If you have
  an emulator or a phone with `adb`, use it, and say what you checked.
- **New check:** add it to `engine/src/test/java/EngineChecks.java`. Update the count in README.md,
  CONTRIBUTING.md and this file, and save the output to `engine/results/checks.txt`.
- **Release:**
  1. Bump `versionCode`/`versionName` in `app/build.gradle`.
  2. Add a CHANGELOG entry.
  3. Copy the APK to `release/`.
  4. Tag `vX.Y.Z`. CI publishes a GitHub Release for tags.
- **Writing:** UK English, plain words, short sentences. In-app copy talks to teenagers: friendly,
  never babyish. Docs say what's measured and what's modelled, with no hype.

## Things that bite

- `CardView.renderBody()` redraws the whole card body up to 20 times in one pass (the fit pass), so
  views are created and thrown away quickly. Keep side effects out of view construction. Animations
  key off `justRevealed`/`justHinted`, and detached views must cancel their animators.
- Each host app has different safe zones (`CardView.safeZone`, `--st/--sb/--sl/--sr` in CSS). The
  right-hand rail and the caption band are reserved for the host's own buttons.
- Picture cards always use the big-text layout. An image occlusion card leads with its picture and
  top-aligns its body on Android. Masks are fractions of the picture, and the picture is sized from
  its stored dimensions so they line up.
- The web demo embeds `engine/src/test/resources/anki/sample.apkg` as its built-in sample deck when it
  is built.
- The test Anki decks are written by `tools/make-test-decks.py` to Anki's file format, not exported
  by Anki. Real exports are still needed (see the roadmap), so run the cross-check on any you get:
  `tools/crosscheck/run.sh path/to/deck.apkg`.
- AnkiDroid connection (`AnkiBridge.java`):
  - Reelcards reads due cards through AnkiDroid's content provider and writes grades back.
  - It's text only, so pictures and image occlusion aren't shown and cloze shows plain `[...]`.
  - `.apkg` import handles all three, but one way only.
  - The chosen decks live in preferences (`AnkiBridge.selected`). Removing a deck on the dashboard
    also unselects it, or the next refill would bring its cards back.
- The overlay is drawn above every app, including the share sheet. While sharing, the card steps
  aside (invisible, `FLAG_NOT_TOUCHABLE`) rather than closing, and comes back when the app it was
  shown over returns (`ReelWatcherService.stepAside`/`comeBack`).
- `Store` saves the session too (skipped cards waiting to come back, skips this session, the
  warm-up count), so a restart mid-session carries on. New engine state that matters within a
  session needs saving there as well.
- In cloud sessions, Google Fonts and Google's Maven repository may be blocked:
  - The engine checks, the cross-check and the web demo only need a JDK, Node and Python.
  - `FONTS_DIR` makes the screenshots use local fonts.
  - The APK still builds in GitHub Actions.
