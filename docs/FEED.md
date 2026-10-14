# Reelcards Feed: setup guide

The Feed is a web page that plays YouTube Shorts and TikTok links you paste in, and slips your
flashcards between them. It works in any browser, including Chrome on an iPhone, with nothing to install.

Open it: **https://llamaoat.github.io/flashcardreela/feed.html**

## Set it up (about a minute)

1. **Read the notice and tick the box.** The Feed is different from the rest of Reelcards: to play a
   video, your browser loads it from YouTube or TikTok, so they can see your internet address. Your cards,
   answers and pasted links stay in your browser. For people aged 13 or over.
2. **Choose what to revise.** Tick one or more ready-made decks, or paste your own notes, one fact per
   line, like `Osmosis - diffusion of water`.
3. **Paste your videos.** One link per line. These work:
   - `https://www.youtube.com/shorts/…`
   - `https://www.youtube.com/watch?v=…`
   - `https://youtu.be/…`
   - `https://www.tiktok.com/@name/video/…`

   Short TikTok links (`vm.tiktok.com`) don't work. Open the video, copy its full link and paste that.
   Anything else is ignored, and you can paste up to 40 links.
4. **Pick how often.** Relaxed, Normal, Cram, or Demo (a card every 2 videos).
5. **Tap Start scrolling.** Swipe up through the videos. A card appears after a video.

## Using the cards

- Tap to see the answer.
- Say how it went (like, or tick) so Reelcards knows when to bring the card back.
- Your progress is kept in this browser on this device.

## Things to know

- **Autoplay.** iPhones often won't autoplay a video with sound. Tap play on the video itself.
- **Swiping over a video.** The video player can swallow a swipe. Swipe on the edge of the screen or
  below the video if it sticks.
- **TikTok.** It may show its own prompts, such as "open the app". Those come from TikTok.
- **Safari and a Home Screen icon keep separate storage.** Set it up again in whichever you use.

## Privacy and deleting your data

- The page keeps your choices, your links and your card progress in this browser only.
- To remove it all, tap **Delete everything this page keeps on this device**, then tap again to confirm.
- The full details are in the [privacy policy](../PRIVACY.md#the-reelcards-feed).

## For developers

`web-demo/src/feed.html` and `feed.js` build into `web-demo/feed.html` with
`python3 tools/build-web-demo.py`. The page's Content Security Policy pins its one inline script by hash,
so never edit the built file by hand. Links are checked strictly in `parseLink`.

**Status:** tested with stand-in YouTube and TikTok embeds, not yet on the real sites or on an iPhone.
