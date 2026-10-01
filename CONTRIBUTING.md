# Contributing

Thanks for helping. Reelcards is small on purpose: no third-party libraries in the app, and an engine
in plain Java that runs anywhere.

## Getting set up

- **App:** open the folder in Android Studio, or `./gradlew assembleDebug`.
- **Engine:** `./gradlew :engine:engineChecks` (48 checks) and `./gradlew :engine:simulate`.
- **Web demo:** edit `web-demo/src/`, then `python3 tools/build-web-demo.py`.

## Where things go

| Change | Edit | Then |
|---|---|---|
| Scheduling, grading, rhythm, formats | `engine/src/main/java/…/engine/` | Add or update a check in `engine/src/test/java/EngineChecks.java`; re-run the simulation if behaviour changes, and save its output to `engine/results/` |
| Card design | `web-demo/src/head.html` + `render.js` first (check every app, interest and state in the design lab), then `app/…/CardView.java` | Keep both in step; [docs/DESIGN.md](docs/DESIGN.md) is the spec |
| Engine logic used by the web demo | also `web-demo/src/engine.js` | The JS port must match the Java engine |
| Reel detection for an app update | `REEL_IDS` in `app/…/ReelDetector.java` | Include the detection-log lines that show the new view name |
| Ready-made decks | `engine/…/SampleDecks.java` | Check facts against the exam board's specification |

## Rules of thumb

- **Java 8, no lambdas** in the app (the Gradle-free build's dexer can't desugar them); anonymous
  classes are fine. Minimum Android 8.0 (API 26). Guard newer APIs with `Build.VERSION.SDK_INT`, ideally
  in a small nested class (see `ReelWatcherService.Api30`).
- **Privacy is a feature.** No network access, no analytics, nothing read from the screen beyond what
  [PRIVACY.md](PRIVACY.md) describes. Anything that changes that needs discussion first.
- **It must not feel like homework.** Changes that show cards more often, or make them harder to skip,
  need a reason grounded in student behaviour.
- **Student-facing text:** short, plain, friendly, UK spelling.

## Pull requests

Describe what changed and why, how you tested it (checks, simulation, a real phone if you have one),
and include before/after screenshots for anything visual. By contributing you agree your work is
licensed under the [GPL v3](LICENSE).
