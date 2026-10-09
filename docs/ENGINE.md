# The Reelcards engine

The engine is plain Java 8 in [`engine/`](../engine) with no Android code and no dependencies, so the
same code runs in the app, in the tests and in the simulator (the [web demo](../web-demo) runs a
JavaScript port of it). The Android app calls it on every detected reel swipe and again when a card
reel leaves the screen.

Each swipe answers three questions: **when** to show a card, **which** card, and **how it looks**.
Then it **grades** what the student did and updates everything it has learned.

---

## 1. When: a learned rhythm that waits for natural breaks

Interruptions cost attention. Switching between short videos and another task measurably hurts
people's ability to hold intentions ([Chiossi et al., CHI 2023](https://github.com/mimuc/media-prospective-memory)),
so cards try to arrive at moments that interrupt least, and only as often as each student tolerates.

- **Warm-up:** no cards in the first 3 reels of a session (a session ends after 10 minutes without a swipe).
- **Rhythm** ([`Rhythm.java`](../engine/src/main/java/com/reelcards/engine/Rhythm.java)): the student's
  setting (Chill 8, Normal 5, Cram 3 reels) is only the starting point. Six arms scale it by
  ×0.6, ×0.8, ×1, ×1.3, ×1.7 and ×2.2. Each arm keeps a Beta belief about how likely a card is to be
  engaged with at that spacing, and Thompson sampling picks the arm that maximises
  *engaged cards per reel scrolled* = p / (gap + 1). Too often and students swipe cards away;
  too rarely and they barely revise, and this finds the balance per student. Old evidence fades
  (×0.98 per update), so it keeps adapting.
- **Natural breaks:** if the reel just watched held attention for 25 s or more, the card waits (up to
  twice) rather than cutting in. If the last three reels were each skimmed in under 3 s, the card
  comes one reel early.
- **Pressure:** a bit more often when 20+ cards are due, ×0.75 in the last week before the exam and
  ×0.6 in the last two days.
- **Guard rails:** daily cap (60 / 150 / 300), a one-tap pause, and **quiet mode**: after 5 fast skips
  (under 1.2 s) in a row, no cards for 15 minutes.

## 2. Which card

In priority order ([`Engine.pickCard`](../engine/src/main/java/com/reelcards/engine/Engine.java)):

1. **Skipped cards** come back 3 reels later, in a different look. A card skipped twice in a session
   is kept for the next session instead (no nagging).
2. **Learning steps** (1 and 10 minutes; 10 minutes after a lapse), earliest first.
3. **Due reviews with the lowest predicted recall.** Within two weeks of the exam, it's recall *on
   exam day* that counts. It also **interleaves**: if the weakest card is from the subject just shown,
   and a card from another subject is almost as weak (within 10 points), it switches, because mixed
   practice beats blocked practice.
4. **New cards**, spread across decks and **paced to the exam**: the daily new-card limit rises (up to
   3× the setting) so every card is first seen by about 80% of the way to the exam.
5. **Final sweep:** in the last 3 days, the weakest card at exam time even if it isn't due.

Cards forgotten 6+ times are flagged as **leeches** on the dashboard so the student can rewrite them.
**Saved** cards (bookmark button) are pulled forward to within a day.

## 3. How it looks: a recovering contextual bandit

A card reel is a combination of four pieces ([`Format.java`](../engine/src/main/java/com/reelcards/engine/Format.java)):

| Dimension | Pieces |
|---|---|
| Hook | "POV…", "Wait for it…", "Most people get this wrong", "text at 2am", "Rate my answer", "Storytime" |
| Layout | big text, word-by-word captions, group chat, reply-to-a-comment, split-screen, quiz poll, numbered list |
| Look | bold captions, highlight boxes, clean notes page, neon, handwritten notes |
| Background | satisfying clip, gameplay, city walk, football pitch, soft glow, music visualiser, plain |

That's 6 × 7 × 5 × 7 = 1,470 combinations. [`FormatBandit.java`](../engine/src/main/java/com/reelcards/engine/FormatBandit.java) picks one per card,
from the layouts the card's **answer shape** allows (below):

- **Thompson sampling** on a Beta belief per piece. The reward is fractional: a like or share = 1,
  a save 0.85, a quiz answer 0.9, a reveal read for 2 s+ 0.7, down to 0 for a sub-second swipe-away.
- **Recovering novelty**, after Duolingo's
  [sleeping, recovering bandit for notifications](https://research.duolingo.com/papers/yancey.kdd20.pdf)
  (Yancey & Settles, KDD 2020): a piece used recently gets a penalty that fades with a half-life of
  1.5 cards, so looks rotate naturally while favourites still come back often. On top of that: a hook
  never twice in a row, a layout at most twice, never the same full combination twice.
- **Context:** beliefs are kept overall and per host app (TikTok vs Instagram vs YouTube), pooled
  so a new app starts from the overall belief and drifts towards its own evidence.
- **Interests:** picking "football" at setup seeds a head start for the pitch background, "gaming" for
  gameplay and split-screen, "music" for the visualiser, and so on. They're priors, not rules; the
  student's behaviour takes over.
- Old evidence fades (×0.98), and about 1 card in 10 explores a random piece in one dimension.

**Answer shapes** ([`AnswerShape.java`](../engine/src/main/java/com/reelcards/engine/AnswerShape.java)).
Before picking a look, the engine reads the shape of the card. Answers are *short* (up to 48
characters), *medium* (one long sentence), or split into 2–6 points: a *list*, *steps* or *prose*.
Points come from line breaks (including Anki's `<br>` and `<li>`), numbered steps that run 1, 2, 3 in
order, semicolons, arrows (3+ parts, so a word equation stays whole), sentences (not after "e.g."),
"A, B, C and D" lists of short items, or one long sentence at ", so" or "because". Questions over 90
characters with a set-up are split into the set-up and the question (the trailing question or
command sentences: "Explain…", "Name…", "…?"). The shape decides the layouts: quiz buttons only for
short answers with short wrong answers, word-by-word captions only for questions up to 140 characters
with no set-up, split-screen only for short answers, and the numbered list only for 2+ points. This
follows SuperMemo's [minimum information principle](https://www.supermemo.com/en/archives1990-2015/articles/20rules):
long answers are recalled more reliably as their parts.

[`CopyWriter.java`](../engine/src/main/java/com/reelcards/engine/CopyWriter.java) writes the words for
the chosen hook and layout (hook line, chat messages, comment, caption, hashtags, hint, voiceover). It's
templated today, and it's the plug-in point for on-device AI.

## Grading

Normal reel gestures become Anki grades ([`Grade.java`](../engine/src/main/java/com/reelcards/engine/Grade.java)).
Speed matters: a fast correct answer means a stronger memory than a slow one, the idea behind
response-time-based scheduling such as ARTS ([Mettler, Massey & Kellman, 2016](https://www.apa.org/pubs/journals/features/xge-xge0000170.pdf)).
"Fast" is relative to reading time: 1.5 s + 45 ms per character of the question, plus 1.2 s for each
extra point the answer has.

| What happened | Grade |
|---|---|
| Liked before the answer showed, within reading time + 2.5 s | Easy |
| Liked before the answer showed, slower | Good |
| Revealed, then liked | Good |
| Quiz: right option within reading time of question and options + 4 s / slower | Good / Hard |
| Long answer (2+ points), ticked point by point: all / at least half / fewer | Good / Hard / Again |
| Used the hint, then liked, answered or ticked every point | Hard |
| Revealed with no like, or picked the wrong option | Again |
| Swiped past without revealing | not graded; comes back later |

## Memory model: FSRS-6, exam-aware and personalised

[`Fsrs.java`](../engine/src/main/java/com/reelcards/engine/Fsrs.java) implements
[FSRS-6](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm) with the 21 default
parameters of [`py-fsrs`](https://github.com/open-spaced-repetition/py-fsrs), the reference
implementation: stability, difficulty, the power forgetting curve
R = (1 + f·t/S)^(−w₂₀), short-term stability for same-day steps, and interval fuzz. The checks replay a
27-step review sequence and match `py-fsrs` to within 5e-11.

Two additions for exams:

- **Target recall rises as the exam approaches:** 90% normally, 92% within three weeks, 95% in the
  final week. Higher targets mean shorter intervals.
- **No interval jumps past the exam:** intervals are capped at the day before it.

**Personal calibration** ([`Calibration.java`](../engine/src/main/java/com/reelcards/engine/Calibration.java)):
full FSRS optimisation fits 21 parameters and needs hundreds of reviews. A student in their first weeks
has few, so this fits one number, a scale on every stability ("remembers 30% longer than average"),
by minimising log-loss on real 1+ day reviews with a pull towards 1.0. It waits for 100 long-term
reviews. In the simulation it neither helps nor hurts yet (log-loss 0.393 both ways), which is the
safe outcome: FSRS-6's flat forgetting curve makes one-parameter fits weakly identified, so it's
deliberately conservative.

**Anki decks** are different: AnkiDroid owns their schedule. The engine shows the cards Anki says are
due and sends each grade back as a normal review through
[AnkiDroid's content provider API](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API)
(`answer_ease` 1–4, plus time taken). Each card is built from its note and note type by the same rules
as an Anki file (below), so cloze works; AnkiDroid doesn't let other apps read its pictures, so cards
whose question needs one stay in AnkiDroid. Just before a grade is sent, the `schedule` query confirms
the card is still due, so a card answered in AnkiDroid meanwhile isn't answered twice.

---

## Anki files

Reelcards reads exported Anki decks itself
([`anki/`](../engine/src/main/java/com/reelcards/engine/anki/)), with no SQLite, zstd or protobuf
library, so the app stays dependency-free and works offline. All three file formats are supported:
`collection.anki2` (Anki 2.0), `collection.anki21` (2.1) and `collection.anki21b` (2.1.50+, zstd-compressed,
with a protobuf media list). The same rules are in `web-demo/src/apkg.js`, and a check reads all three
test decks to identical cards. A second set of decks is exported by Anki's own code
(`tools/real-anki-decks.py`, including a `.colpkg`), and the checks compare every card with how Anki
itself shows it: question, answer, cloze gaps, image occlusion targets and the FSRS memory state.

- **Which field is the answer:** the card's own templates are rendered (fields, `{{#…}}` sections,
  `cloze:`, `type:`, `text:`, `furigana:` and the special fields). The answer is the first field the back
  template adds that the front doesn't show; a type-in field counts as hidden. Any further back fields
  become the explanation. With no such field, the back after `<hr id=answer>` is used.
- **Cloze:** one card per gap number. Gaps for this number become "[…]" (or "[hint]"); the others show
  their text. Several gaps with the same number make a multi-point answer.
- **Image occlusion:** Anki's built-in note type (`{{c1::image-occlusion:rect:left=…}}`, plus ellipses,
  polygons and text; older versions used pixels, which are scaled by the picture's size read from its
  file header) and the Image Occlusion Enhanced add-on (its SVG question mask: the shape with class
  `qshape`, or the red fill, is the one to name).
- **Progress carries over:** a review card keeps its memory state. If the deck has FSRS data, its
  stability and difficulty are used as they are; otherwise stability is the interval, and difficulty
  comes from ease (250% → 5, each 25% less → +1). The due date is kept. New and learning cards start as
  new. Suspended cards are left out, and so are cards with nothing to read on either side (only
  sounds). Pictures over 8 MB or in vector formats are dropped.
- **Sounds:** the first `[sound:…]` on each side comes along (MP3, Ogg, M4A, WAV or FLAC, found from
  the file's first bytes, up to 4 MB). A question that's only a sound becomes "Listen: what's this?";
  a sound on the answer needs a written or pictured question to go with it. Sound cards use big text.
- **One way:** answers stay in Reelcards. Two-way sync with Anki is what the AnkiDroid connection is for.

## Evaluation

[`EngineChecks`](../engine/src/test/java/EngineChecks.java) runs 128 checks:
[`results/checks.txt`](../engine/results/checks.txt).

[`Simulate`](../engine/src/test/java/Simulate.java) runs simulated students on the real engine for 30
days: [`results/simulation.txt`](../engine/results/simulation.txt). Each student scrolls a set number of
minutes a day in 3 sessions (most reels ~11 s, 1 in 10 absorbing, 30–60 s), has a taste in card looks,
has a tolerance for how often cards come (every 3–12 reels), and has a hidden memory of every card
(FSRS-6 with a personal forgetting rate of ×0.7–×1.4). They "know" a card with that hidden recall
probability, and answer faster when they know it well. Three engine versions face the same students:

| Engine version | Skipped | Engaged cards / day | Modelled exam-day recall |
|---|---|---|---|
| Baseline: random looks, fixed rhythm | 66% | 24 | 17% |
| + adaptive looks | 57% | 35 | 25% |
| Full: + learned rhythm, natural breaks | 51% | 38 | 27% |

Recall is averaged over the whole 600-card deck, including cards never reached at 1 hour a day.
For students with no format taste, adaptive looks change little (63% → 59% skipped), which is the
expected result and a sanity check on the model.

**What the simulation can't tell you:** it's a model with assumptions built in (for example, that
students engage more with looks they like). It shows the engine behaves sensibly and compares
versions fairly; real effect sizes need a pilot with real students.

## Ideas not yet built

- On-device AI for the copy and for turning messy notes into cards (Gemini Nano through ML Kit's GenAI
  APIs where available), with the templates as fallback.
- Full FSRS parameter optimisation once a student has 1,000+ reviews.
- Using the learned per-app tastes to pick *which app's* sessions get more cards.

## Sources

- Open Spaced Repetition: [FSRS algorithm](https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm), [py-fsrs](https://github.com/open-spaced-repetition/py-fsrs), [SRS benchmark](https://github.com/open-spaced-repetition/srs-benchmark)
- Yancey, K. P. & Settles, B. (2020). [A Sleeping, Recovering Bandit Algorithm for Optimizing Recurring Notifications](https://research.duolingo.com/papers/yancey.kdd20.pdf). KDD 2020 ([ACM](https://dl.acm.org/doi/10.1145/3394486.3403351))
- Wozniak, P. (1999). [Effective learning: Twenty rules of formulating knowledge](https://www.supermemo.com/en/archives1990-2015/articles/20rules) (the minimum information principle)
- Mettler, E., Massey, C. M. & Kellman, P. J. (2016). [A Comparison of Adaptive and Fixed Schedules of Practice](https://www.apa.org/pubs/journals/features/xge-xge0000170.pdf). *Journal of Experimental Psychology: General*
- Chiossi, F. et al. (2023). Short-Form Videos Degrade Our Capacity to Retain Intentions: Effect of Context Switching On Prospective Memory. CHI 2023 ([code and data](https://github.com/mimuc/media-prospective-memory))
- AnkiDroid: [AnkiDroid API wiki](https://github.com/ankidroid/Anki-Android/wiki/AnkiDroid-API)
