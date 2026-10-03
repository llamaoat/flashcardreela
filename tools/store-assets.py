#!/usr/bin/env python3
"""Google Play listing graphics, drawn from the web demo's design lab (which mirrors CardView.java):

    python3 tools/build-web-demo.py
    python3 tools/store-assets.py [OUT_DIR]          (default docs/play-store)

Writes:
  phone-1.png … phone-5.png   1080 x 1920 phone screenshots: a card reel under a one-line caption
  feature-graphic.png         1024 x 500
  icon-512.png                the launcher icon, 512 x 512 (Play adds the rounded corners)

All are opaque PNGs, as Play asks. The cards use the app's own fonts (app/src/main/assets/fonts);
nothing is fetched from the internet. Needs Python 3 with Playwright and Pillow:
    pip install playwright pillow && python3 -m playwright install chromium
Play's rules for these images: docs/PLAY-STORE.md.
"""
import asyncio, base64, io, os, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "docs" / "play-store"
FONTS = ROOT / "app" / "src" / "main" / "assets" / "fonts"
FACES = [("TikTok Sans", "TikTokSans-Regular.ttf", 400, "normal"), ("TikTok Sans", "TikTokSans-Bold.ttf", 700, "normal"),
         ("TikTok Sans", "TikTokSans-Black.ttf", 900, "normal"), ("Anton", "Anton-Regular.ttf", 400, "normal"),
         ("Caveat", "Caveat-Bold.ttf", 700, "normal"), ("Fraunces", "Fraunces-Bold.ttf", 700, "normal"),
         ("Fraunces", "Fraunces-BoldItalic.ttf", 700, "italic")]
FONT_CSS = "".join("@font-face{font-family:'%s';src:url('https://fonts.local/%s');font-weight:%d;font-style:%s}\n" % f for f in FACES)

# caption, then the design lab's settings: cards, app, state, which card (0-7), night
SHOTS = [
    ("Your flashcards, slipped in between your reels", "short", "tiktok", "q", 0, False),
    ("Double-tap if you knew it. Swipe on if you didn't.", "short", "tiktok", "liked", 0, False),
    ("Long answers, marked point by point", "long", "tiktok", "part", 0, False),
    ("Opens Anki decks, cloze and image occlusion too", "anki", "tiktok", "q", 0, False),
    ("Looks like the app you're in, and learns your style", "short", "instagram", "q", 2, True),
]

ICON_SVG = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="12 12 84 84" width="512" height="512">
<rect x="0" y="0" width="108" height="108" fill="#0F1014"/>
<g transform="rotate(-10 54 54)"><path fill="#3A3C4A" d="M40,30 L68,30 Q72,30 72,34 L72,76 Q72,80 68,80 L40,80 Q36,80 36,76 L36,34 Q36,30 40,30 Z"/></g>
<g transform="rotate(6 54 54)"><path fill="#FFE14D" d="M42,28 L70,28 Q74,28 74,32 L74,76 Q74,80 70,80 L42,80 Q38,80 38,76 L38,32 Q38,28 42,28 Z"/>
<path fill="#0F1014" d="M50,43 L66,54 L50,65 Z"/></g></svg>"""


def opaque_png(png_bytes, path):
    from PIL import Image
    im = Image.open(io.BytesIO(png_bytes)).convert("RGB")
    im.save(path, "PNG", optimize=True)
    print("wrote", path.relative_to(ROOT) if path.is_relative_to(ROOT) else path, "%dx%d" % im.size)


def data_url(png):
    return "data:image/png;base64," + base64.b64encode(png).decode()


async def main():
    from playwright.async_api import async_playwright
    page_file = ROOT / "web-demo" / "index.html"
    if not page_file.exists():
        sys.exit("web-demo/index.html is missing: run python3 tools/build-web-demo.py first")
    OUT.mkdir(parents=True, exist_ok=True)
    async with async_playwright() as p:
        browser = await p.chromium.launch()
        ctx = await browser.new_context(viewport={"width": 1100, "height": 1000}, device_scale_factor=3)

        async def route(r):
            u = r.request.url
            if u.startswith("https://fonts.googleapis.com"):
                await r.fulfill(status=200, content_type="text/css", body=FONT_CSS)
            elif u.startswith("https://fonts.local/"):
                await r.fulfill(status=200, content_type="font/ttf", body=(FONTS / u.rsplit("/", 1)[1]).read_bytes())
            else:
                await r.abort()
        await ctx.route("https://**/*", route)
        page = await ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        await page.goto(page_file.as_uri())
        await page.wait_for_timeout(800)

        # 1. one card reel per shot, at full size (360 x 740 CSS px, so 1080 x 2220 at 3x)
        cards = []
        for caption, deck, host, state, idx, night in SHOTS:
            await page.select_option("#labCards", deck)
            await page.select_option("#labHost", host)
            await page.select_option("#labState", state)
            if await page.is_checked("#labNight") != night:
                await page.click("#labNight")
            await page.wait_for_timeout(1200 if deck == "anki" else 400)
            await page.evaluate("""i => { document.querySelectorAll('#lab figure').forEach((f, k) => { if (k !== i) f.style.display = 'none'; });
                                          const m = document.querySelectorAll('#lab .mini')[i]; m.style.width = '370px'; m.style.boxSizing = 'border-box';
                                          m.closest('figure').style.width = '370px'; m.querySelector('.scaler').style.transform = 'none'; }""", idx)
            await page.wait_for_timeout(300)
            cards.append(await page.locator("#lab .mini .scaler").nth(idx).screenshot())

        # 2. phone screenshots: caption above the card
        shot = await browser.new_page(viewport={"width": 360, "height": 640}, device_scale_factor=3)
        await shot.route("https://**/*", route)
        for n, ((caption, *_), png) in enumerate(zip(SHOTS, cards), 1):
            await shot.set_content("""<!doctype html><html><head><style>%s
                html,body{margin:0;width:360px;height:640px;overflow:hidden;background:linear-gradient(170deg,#1b1c26,#0f1014 60%%)}
                h1{font:900 23px/1.15 'TikTok Sans',sans-serif;color:#ecebe6;text-align:center;margin:0;padding:26px 26px 0;letter-spacing:-.2px}
                img{display:block;margin:18px auto 0;height:530px;border-radius:22px;box-shadow:0 16px 40px rgba(0,0,0,.55),0 0 0 4px #25262e}
                </style></head><body><h1>%s</h1><img src="%s"></body></html>""" % (FONT_CSS, caption, data_url(png)))
            await shot.wait_for_timeout(300)
            opaque_png(await shot.screenshot(), OUT / ("phone-%d.png" % n))

        # 3. feature graphic: the icon, the name, the promise, and three cards
        feat = await browser.new_page(viewport={"width": 1024, "height": 500}, device_scale_factor=1)
        await feat.route("https://**/*", route)
        imgs = "".join('<img class="c%d" src="%s">' % (i, data_url(cards[k])) for i, k in enumerate((3, 0, 2)))
        await feat.set_content("""<!doctype html><html><head><style>%s
            html,body{margin:0;width:1024px;height:500px;overflow:hidden;background:radial-gradient(circle at 78%% 50%%,#2a2b38,#0f1014 62%%);font-family:'TikTok Sans',sans-serif;color:#ecebe6}
            .icon{position:absolute;left:64px;top:92px;width:120px;height:120px;border-radius:28px;overflow:hidden}
            h1{position:absolute;left:64px;top:228px;margin:0;font:900 72px/1 'TikTok Sans';letter-spacing:-1.5px}
            p{position:absolute;left:66px;top:318px;margin:0;width:470px;font:700 26px/1.25 'TikTok Sans';color:#ffe14d}
            img{position:absolute;height:420px;border-radius:16px;box-shadow:0 12px 30px rgba(0,0,0,.6),0 0 0 3px #25262e}
            .c0{left:600px;top:70px;transform:rotate(-7deg)} .c1{left:730px;top:40px;transform:rotate(2deg);z-index:2} .c2{left:858px;top:72px;transform:rotate(9deg)}
            </style></head><body><div class="icon">%s</div><h1>Reelcards</h1><p>Revise without trying: flashcards between your reels</p>%s</body></html>"""
            % (FONT_CSS, ICON_SVG.replace('width="512" height="512"', 'width="120" height="120"'), imgs))
        await feat.wait_for_timeout(400)
        opaque_png(await feat.screenshot(), OUT / "feature-graphic.png")

        # 4. the icon
        icon = await browser.new_page(viewport={"width": 512, "height": 512}, device_scale_factor=1)
        await icon.set_content("<html><body style='margin:0'>%s</body></html>" % ICON_SVG)
        opaque_png(await icon.screenshot(), OUT / "icon-512.png")

        await browser.close()
        if errors:
            sys.exit("page errors: " + "; ".join(errors))


if __name__ == "__main__":
    asyncio.run(main())
