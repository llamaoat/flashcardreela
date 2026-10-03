# A pilot with real students

The numbers in the README come from a simulation: a model of students, not students. A pilot replaces
them with measured ones. The app now has what a small pilot needs. It has no internet access, so
nothing is collected automatically. Each student sends their own counts when they choose to.

## What the app measures

Dashboard → *Share my numbers (for a study)* shows a report, then shares it only when the student
taps *Share*. It contains counts only, no card text, deck or file names, or apps:

- **Per day:** reels swiped, cards shown, answered, skipped (and fast skips), grades (again, hard,
  good, easy), new cards seen, minutes.
- **Recall:** reviews of cards last seen a day or more before, how many were remembered, and what
  FSRS predicted for them. This is real recall, measured on spaced reviews.
- **Card styles:** each layout's shown and skipped counts.
- **Set-up:** how often cards come, days to the exam, how many interests, and how many cards of
  each kind (ready-made, notes, Anki file, AnkiDroid).
- **A random code** per install, so reports can be told apart without names.

`tools/pilot-summary.py reports/` adds up a folder of reports. It shows each student and the whole
class, next to the simulation's numbers (skipped 51%, about 38 cards answered a day).

## A plan that fits a school

1. **Who:** 20–40 GCSE students who already use TikTok, Instagram or YouTube Shorts, with Android
   phones. Two classes is enough to see whether the simulation's numbers hold up.
2. **Consent:** the school's agreement, an information sheet for students and parents, and written
   consent from a parent or guardian as well as the student (they're under 16). Follow the school's
   safeguarding and data protection rules. The data is small and has no names in it, but a report
   arrives from someone, so treat it as personal data until the names are stripped.
3. **Set-up session (20 minutes):** install from Play's internal or closed testing, go through setup
   together, and pick the class's subject deck (or their own Anki decks).
4. **Two to four weeks** of normal use. No reminders beyond the app itself: the point is to see what
   happens when nobody is pushing.
5. **Mid-way and at the end:** students tap *Share my numbers* and send the report to the teacher
   (email or the school's learning platform). Save each one as a file in one folder, with nothing else
   in the file name.
6. **End-of-pilot quiz (optional, recommended):** ten questions from the class deck, half that the app
   showed a student and half it didn't. This is the closest thing to "recall at the exam", the third
   number the simulation models. The app can't measure it alone.
7. **Afterwards:** run `tools/pilot-summary.py`, delete the reports once the numbers are written up,
   and put the measured numbers in the README next to the modelled ones, saying how many students
   and days they come from.

## What it can and can't tell you

- **Can:** whether students keep it switched on, how many cards they actually answer per reel and per
  day, how often they skip, which card styles get skipped, and whether FSRS's predicted recall
  matches what they remember after a day or more.
- **Can't:** cause and effect. Students who keep using it may just be keener. For that, compare with
  a class revising another way over the same weeks, using the same end-of-pilot quiz.
