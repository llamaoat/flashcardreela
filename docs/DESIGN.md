# Card design

**Goal: a card should pass as a reel at a glance.** If it looks like a quiz app interrupting TikTok,
students swipe it away before reading it. So every card is built from what's already on their screen:
the host app's frame, the formats creators use, and the things that student watches.

The design is implemented twice from the same spec: in the web demo
([`web-demo/src/render.js`](../web-demo/src/render.js) and the `.card` rules in
[`head.html`](../web-demo/src/head.html)) and in the Android app
([`CardView.java`](../app/src/main/java/com/reelcards/app/CardView.java)). The web demo's **design lab**
shows every layout for any app, interest, state and time of day side by side.

![Cards in Instagram style with the football theme](img/cards-instagram-football.png)
![Cards in YouTube style with the music theme, answers revealed](img/cards-youtube-music.png)
![Cards in TikTok style with the gaming theme, after a like](img/cards-tiktok-gaming.png)

---

## 1. The host app's frame

Each app reserves different edges of the screen for its own buttons and captions. Cards keep their
text inside each app's **safe zone**, and draw that app's furniture in the same places, so the card
and the app's real overlay line up.

| App | Safe zone on 1080 × 1920 (top / bottom / left / right) | Rail | Caption band | Typeface |
|---|---|---|---|---|
| TikTok | 108 / 320 / 60 / 120 px | avatar with "+", ♥, comment, bookmark, share | @account, caption + 2 hashtags, ♫ original sound | [TikTok Sans](https://developers.tiktok.com/blog/tiktok-sans-open-source) (OFL) |
| Instagram Reels | 210 / 310 / 40 / 84 px | ♥, comment, bookmark, paper plane | avatar, account, outlined subject pill, caption, ♫ Original audio | system sans |
| YouTube Shorts | 120 / 300 / 48 / 96 px | 👍, comment, bookmark, share | title first, then avatar, @account, filled pill, sound | Roboto |

The safe-zone figures are consolidated from creator safe-zone guides
([1](https://postplanify.com/blog/social-media-safe-zones-2026-complete-guide),
[2](https://creamate.ai/en/blog/tiktok-safe-zone-guide),
[3](https://www.upload-post.com/tools/safe-zone-checker/)). They change when the apps redesign,
so they live in one function (`safeZone()` in both implementations).

The rail buttons do real things: ♥ = "knew it", the speech bubble gives a **hint** (first letter and
length), the bookmark **saves** the card (back within a day), and share sends the question to a
friend. Icons are generic outline shapes drawn for Reelcards, with no platform logos. A thin progress line
runs along the bottom (red on YouTube, white elsewhere), like a real reel.

## 2. Creator formats

**Looks:** text treatments borrowed from creator editing tools.

| Look | Treatment | Key word |
|---|---|---|
| Bold captions | heavy uppercase, black outline (reads on any footage) | yellow |
| Highlight boxes | each line on its own white rounded box (the "text background" style) | yellow box |
| Clean notes | serif headline on a paper-white notes page (Fraunces) | italic, blue |
| Neon | condensed uppercase with a glow (Anton) | pink |
| Handwritten notes | handwriting on ruled paper with a red margin (Caveat) | highlighter stroke |

The key word is the longest word of five or more letters, the word auto-captions would highlight.
Questions shrink to fit, and never break a word across lines.

**Light looks float as a notes "sheet"** (slightly rotated, with a shadow) over dark footage,
like a creator screenshotting their notes. That keeps the card reading as a reel, not a slide.

**Layouts:** big text · word-by-word captions (the question arrives in beats, about 3 words a second,
like auto-captions) · a group-chat screenshot (the answer arrives as the reply) · "replying to
@user's comment" · split-screen over gameplay · a two-option poll sticker.

**Hooks** sit in a pill at the top, the first thing read: "POV: …", "Wait for it…", "Most people
get this wrong", "text at 2am", "Rate my answer", "Storytime".

**Backgrounds** are drawn in code as stand-ins for looping footage (keeps the app tiny and
offline): a satisfying soap-cutting clip, an endless-runner track, a city walk at night, a football
pitch, a soft bokeh glow, a music visualiser. They're dimmed under the text, with a scrim at the top
and bottom so the app's own white UI stays readable.

## 3. Themed to the student

At setup students pick what they watch. Cards then pick up a small, recognisable touch, and the
format engine starts with a lean towards matching backgrounds and formats:

| Interest | Badge (top left) | Leans towards |
|---|---|---|
| Football | broadcast scorebug "YOU 0–0 EXAM · 90+3′" (the score updates when you get it right) | pitch, "POV" hook |
| Gaming | HUD "LVL 7" with an XP bar, "+25 XP" pops on a right answer | runner gameplay, split-screen, "Wait for it" |
| Music | "revision mix" pill with a visualiser | music visualiser, neon, captions |
| Fitness | "SET 1 · REP 1" → "PR ✓" | city walk, "POV", bold captions |
| Beauty | "✦ GRWM · revision edition" | soft glow, notes pages |
| Food | "let it cook · 0:03" | satisfying clip, handwritten notes |
| Comedy | "no laughing" | "text at 2am", reply-to-a-comment, group chat |

## 4. Time and colour

- **Night:** from 10pm to 6am the light looks switch to dark versions: the clean page becomes
  warm dark paper, the handwritten notes become a chalkboard. Students can force it on or off.
- **Match my feed's colours** (opt-in, Android 11+, off by default): dark looks are tinted to the
  dominant colour of the reel the student just watched. The app takes one screenshot after each reel
  settles, shrinks it to 24 × 48 pixels in memory, keeps a single hue and discards the image
  ([`FeedColour.java`](../app/src/main/java/com/reelcards/app/FeedColour.java)). It uses Android's
  [`AccessibilityService.takeScreenshot`](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService).

## 5. States and first use

- **Question:** a countdown ring (3, 2, 1, ?) with the reveal prompt. Tap anywhere to reveal.
- **Hint:** a white chip under the question, a half-typed reply in the chat layout, or a note in the poll.
- **Answer:** an accent-coloured box that drops in, with a one-line explanation.
- **Knew it:** the heart fills, a big heart bursts where you tapped, and a badge says how it was
  graded ("Knew it · Easy", "With a hint · Hard").
- **First two cards ever:** a coach tip, "Tap to see the answer · Double-tap if you knew it", fades
  out after 4 seconds.

## 6. Accessibility

- White text on footage always has an outline, a box or a scrim behind it.
- Touch targets: rail buttons 50 dp, the reveal button 56 dp, poll options 50 dp, the chat reply 44 dp.
- Text respects the phone's font size, up to 130%, beyond which the reel layout would break.
- Background motion stops when "Remove animations" is on.
- Every control has a content description for TalkBack. The optional voiceover reads the hook and
  question aloud, like creators do.

---

## How the design was made, and how to iterate

1. **Reference:** creator safe-zone guides, the apps' own layouts, creator formats, and TikTok's
   open-source typeface.
2. **Build in the browser first.** The design lab in the web demo renders every layout for every app,
   interest, state and night mode at once, which makes inconsistencies easy to spot.
3. **Screenshot and critique.** Screens were rendered headless with Playwright and reviewed with a
   structured design critique (first impression, usability, hierarchy, consistency, accessibility).
   Round one found the light looks read as slides, the hint covered the question, the hook was too
   small and the gameplay stand-in looked like a placeholder. All were fixed in round two
   ([critique notes](design-critique.md)).
4. **Port to Android** and review the port for behaviour differences.

To change the design: edit `web-demo/src/head.html` and `render.js`, run
`python3 tools/build-web-demo.py`, check the design lab, then mirror the change in `CardView.java`.
The most valuable next input is **screenshots from real students' feeds** (and real phones running the
APK), to compare against.

## Fonts

All bundled under the SIL Open Font License, with licence files in
[`app/src/main/assets/fonts`](../app/src/main/assets/fonts): TikTok Sans
([announcement](https://developers.tiktok.com/blog/tiktok-sans-open-source)), Anton, Fraunces and Caveat.
They're cut down to Latin characters and the weights the cards use (about 430 KB in total).
