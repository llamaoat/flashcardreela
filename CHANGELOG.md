# Changelog

## 1.2.0: Anki files, cloze and image occlusion

**Import**
- Open any Anki file (.apkg or .colpkg) from the setup screen, with no AnkiDroid, no account and no
  internet. Reads all three Anki formats: 2.0 (`collection.anki2`), 2.1 (`collection.anki21`) and
  2.1.50+ (`collection.anki21b`, zstd-compressed, protobuf media list). The SQLite, zstd, protobuf and
  zip readers are written from scratch, so the app still has no third-party libraries.
- Note types: Basic, reversed, optional reversed, type-in-the-answer, custom ones (the answer is the
  first field the back adds; further fields become the explanation), cloze and image occlusion.
- Cloze: one card per gap number. The gap shows as a blank, or as the hint if the deck has one; the same
  number used several times becomes a multi-point answer; the full sentence goes behind "more".
  Nested deletions work.
- Image occlusion: Anki's built-in note type (rectangles, ellipses, polygons, text labels; fractional
  and older pixel coordinates) and the older Image Occlusion Enhanced add-on (SVG masks).
- Pictures on normal cards come across (PNG, JPEG, GIF, WebP, BMP up to 8 MB).
- Review cards keep their Anki progress: FSRS memory state if the deck has it, otherwise interval and
  ease; due dates carry over. Suspended and audio-only cards are left out, and the result screen says
  how many of each.
- Sample deck built in, so the import can be tried without an Anki file.

**Design**
- Image occlusion cards lead with the picture, full width and clear of the rail. The part to name is
  masked in the look's accent colour; a tap fades the mask to an outline, a double-tap means "knew it",
  and the hint uncovers the other labels so you can rule them out.
- Cloze gaps are drawn in each look's style: an accent block, an underline on handwritten notes, italic on
  the clean serif, a glow on neon. They also work in captions, the quiz layout and the numbered list.
- Front pictures sit under the question; back pictures under the answer.
- Screen readers and the voiceover read a gap as "blank".
- Design lab: "Anki: cloze & image occlusion".

**Engine**
- Picture cards always use big text; cloze cards never use the reply-to-a-comment layout.
- 103 engine checks (was 80), including all three Anki formats read to identical cards.
- `tools/make-test-decks.py` writes the test decks.

## 1.1.0: long answers

**Engine**
- Answer shapes (`AnswerShape`): answers are short, one long sentence, or split into 2–6 points (lists,
  steps, paragraphs), including Anki HTML. Long questions are split into their set-up and the question.
- The shape picks the layouts: quiz buttons only for short answers (and short wrong answers),
  word-by-word captions only for short questions, split-screen only where the answer fits.
- New layout: numbered list, with one blank per point before the reveal (1,470 combinations now).
- Part marks: tick the points you got. All = Good, at least half = Hard, fewer = Again; a hint caps
  it at Hard. Recalling more points gets more time before "fast" stops counting.
- Hints for long answers name how many points and how they start.
- New ready-made deck: GCSE Science, longer answers (13 cards, 2 to 5 marks).
- Anki cards up to 900 characters (was 220), since long answers are now split rather than cut.
- 80 engine checks (was 48), plus a Java/JavaScript cross-check of the splitter.

**Design**
- Points drop in one after another; the question shrinks out of the way; tap a point to mark it.
- "3 MARKS" pill; a quoted box for a long question's set-up.
- Chat answers arrive one message per point, with ✓ reactions for the ones you got.
- Long explanations go in the caption behind "more", which opens over a dimmed card.
- Think ring (3–7 s) and progress line (10–30 s) scale with reading time.
- Fit pass: text shrinks step by step instead of spilling out of the safe zone.
- Design lab: long cards, part marks, the opened caption and the hint state.

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
