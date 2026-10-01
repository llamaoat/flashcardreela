# Changelog

## 1.0.0

**Engine**
- FSRS-6 scheduler (21 parameters), checked step by step against the reference `py-fsrs`; exam-aware
  target recall (90% → 92% → 95%) and no intervals past the exam.
- Learned rhythm: Thompson sampling over six spacings to maximise answered cards per reel, with
  natural-break timing (waits after an absorbing reel, comes early when skimming).
- Format engine rebuilt as a recovering contextual bandit (per-app beliefs, fading recency penalty,
  run limits), now over 1,260 combinations: new layouts (word-by-word captions, reply-to-a-comment),
  hooks ("Storytime"), looks (highlight boxes) and backgrounds (music visualiser).
- Response-time grading (Easy / Good / Hard by speed), hints cap the grade at Hard.
- Personal forgetting-rate calibration after 100 long-term reviews.
- Interleaving across subjects, exam-paced new cards, final pre-exam sweep, leech flags.
- Interest themes (football, gaming, music, fitness, beauty, food, comedy) seed the format engine.
- Saved cards come back within a day.
- 48 engine checks; a 30-day simulation of 30 students per condition with saved results.

**Design**
- Cards follow each host app: safe zones, right-hand rail (like, hint, save, share), caption band,
  progress bar and typeface (TikTok Sans, bundled under the OFL).
- New looks and layouts drawn like creator formats; light looks float as a notes sheet over footage.
- Interest badges (scorebug, game HUD, visualiser pill…), coach tips on the first two cards, night
  versions of light looks, and opt-in "Match my feed's colours" (Android 11+).
- New backgrounds: endless-runner track and music visualiser.
- Web demo v2 with a design lab showing every layout for every app, interest and state.

**App**
- Hint, save and share buttons on every card; saved cards on the dashboard.
- Dashboard settings for colour matching and night mode.
- Card overlay now covers the navigation bar on Android 11+.

**Project**
- Engine moved into its own Gradle module (`engine/`), with checks and simulation runnable from Gradle.
- GPLv3 licence, privacy policy, contributing guide, engine and design docs.
- CI runs the engine checks, builds the APK, publishes a release on version tags, and deploys the web
  demo to GitHub Pages.

## 0.1.0: hackathon prototype

- Accessibility-service reel detection for TikTok, Instagram, YouTube Shorts, Snapchat and Facebook,
  with a card drawn as an accessibility overlay.
- FSRS-4.5 scheduling, gesture grading (double-tap = knew it), skipping that never punishes.
- Format bandit over 320 combinations; ready-made GCSE decks; notes → cards; AnkiDroid read/write.
- Setup wizard, dashboard with forecast, practice feed, web demo.
