# Design critique: card reels (round 1)

Reviewed with the Design plugin's critique framework against screenshots of the design lab
(TikTok / Instagram Reels / YouTube Shorts skins; football, music and gaming vibes) and the live feed.

## Overall impression
The cards now read as reels: full-bleed "footage", the host app's rail and caption band, word-by-word
captions and creator formats (reply-to-comment, text chat, poll). The weakest point was the light looks,
which read as a slide on a pastel background (homework energy), plus a hint bubble that covered the question.

## Usability
| Finding | Severity | Recommendation |
|---|---|---|
| Hint bubble floated over the question in split-screen | 🔴 Critical | Show the hint inline, under the question (done) |
| First-time students can't know "double-tap = knew it" | 🟡 Moderate | One-time coach marks on the first two cards |
| Hashtags wrap to two lines and crowd the caption band | 🟢 Minor | Keep two tags |

## Visual hierarchy
- **Eye goes first to** the question headline: correct, but the hook ("Most people get this wrong") is
  the thing that stops the scroll in real reels and was only 14px. Make it bigger and bolder, in the
  creator "text background" style.
- **Reading flow:** hook → question → countdown/answer → rail → caption band. Works.

## Consistency
| Element | Issue | Recommendation |
|---|---|---|
| Light looks (clean, handwritten) | Flat pastel page, dark rail text: breaks the reel illusion | Draw them as a notes "sheet" over dark footage, like creators screenshotting their notes |
| Gameplay footage | Checkerboard + square reads as a placeholder | Perspective runner track with lanes and coins |
| Neon headline | Line-height too tight with Anton | 1.1 (done) |

## Accessibility
- **Contrast:** white text sits on a bottom/top scrim with an outline or text-box; neon and highlight boxes pass 4.5:1.
- **Touch targets:** rail buttons 50px, reveal 56px, poll options 50px.
- **Motion:** all animation stops under "reduce motion".

## Priority fixes (applied in round 2)
1. Light looks become a sheet over footage.
2. Bigger hook, fewer hashtags.
3. Coach marks on the first two cards.
4. Better gameplay footage stand-in.
