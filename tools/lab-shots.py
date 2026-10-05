#!/usr/bin/env python3
"""Screenshots of the web demo's design lab: every card style for one deck, in one state.

The design lab draws the same cards the Android app draws (render.js mirrors CardView.java), so
this is the quickest way to see a design change. Rebuild the demo first:

    python3 tools/build-web-demo.py
    python3 tools/lab-shots.py                         # long answers, TikTok, question + answer
    python3 tools/lab-shots.py --cards anki --host instagram --states q a hint
    python3 tools/lab-shots.py --cards short --night --each   # one image per card as well
    python3 tools/lab-shots.py --deck engine/src/test/resources/anki/real/usmle.apkg --pages 5   # any Anki file

Writes PNGs to build/shots/ and prints any page errors. Needs Playwright:
    pip install playwright && python3 -m playwright install chromium

Fonts come from Google Fonts. Offline (or in a sandbox that blocks them), point FONTS_DIR at a
folder of .ttf files (e.g. a clone of github.com/google/fonts) and they're served from there.

Options (the lab's own menus): --cards short|long|anki · --host tiktok|instagram|youtube ·
--vibe "" (none) or a theme · --states q a part liked more hint · --night. --deck opens an .apkg or
.colpkg in the lab (8 cards a page, each in a layout its shape allows); --pages says how many pages.
If Playwright's own Chromium isn't installed, set CHROMIUM to a Chromium executable.
"""
import argparse, asyncio, glob, os, pathlib, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
FAMILIES = ["TikTok Sans", "Anton", "Caveat", "Fraunces", "Bricolage Grotesque", "Roboto", "JetBrains Mono"]


def local_fonts(folder):
    """family -> .ttf path, found by file name under FONTS_DIR (e.g. ofl/caveat/Caveat[wght].ttf)."""
    found = {}
    for fam in FAMILIES:
        key = fam.replace(" ", "").lower()
        hits = [p for p in glob.glob(os.path.join(folder, "**", "*.ttf"), recursive=True)
                if os.path.basename(p).lower().startswith(key) and "italic" not in os.path.basename(p).lower()]
        if hits:
            found[fam] = sorted(hits, key=len)[0]
    return found


async def main(a):
    from playwright.async_api import async_playwright
    out = ROOT / "build" / "shots"
    out.mkdir(parents=True, exist_ok=True)
    page_file = ROOT / "web-demo" / "index.html"
    if not page_file.exists():
        sys.exit("web-demo/index.html is missing: run python3 tools/build-web-demo.py first")
    fonts = local_fonts(os.environ["FONTS_DIR"]) if os.environ.get("FONTS_DIR") else {}
    async with async_playwright() as p:
        browser = await p.chromium.launch(executable_path=os.environ.get("CHROMIUM") or None)
        ctx = await browser.new_context(viewport={"width": 1100, "height": 1000}, device_scale_factor=a.scale)
        if fonts:
            css = "".join("@font-face{font-family:'%s';src:url('https://fonts.local/%d.ttf');font-weight:100 900;}\n" % (f, i)
                          for i, f in enumerate(fonts))
            files = {"https://fonts.local/%d.ttf" % i: path for i, path in enumerate(fonts.values())}

            async def route(r):
                u = r.request.url
                if u.startswith("https://fonts.googleapis.com"):
                    await r.fulfill(status=200, content_type="text/css", body=css)
                elif u in files:
                    await r.fulfill(status=200, content_type="font/ttf", body=pathlib.Path(files[u]).read_bytes())
                else:
                    await r.abort()
            await ctx.route("https://**/*", route)
        page = await ctx.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        await page.goto(page_file.as_uri())
        await page.wait_for_timeout(900)
        if a.deck:
            await page.set_input_files("#labFile", a.deck)
            await page.wait_for_function("document.getElementById('labCards').value === 'file'", timeout=30000)
        cards = pathlib.Path(a.deck).stem if a.deck else a.cards
        for state, pg in [(s, k) for s in a.states for k in range(a.pages if a.deck else 1)]:
            if a.deck:
                await page.evaluate("n => { for (let i = 0; i < 99 && document.querySelector('#lab figcaption') && !document.querySelector('#lab figcaption').textContent.includes('Card ' + (n*8+1) + ' of'); i++) document.getElementById('labMore').click(); }", pg)
            else:
                await page.select_option("#labCards", a.cards)
            await page.select_option("#labHost", a.host)
            await page.select_option("#labVibe", a.vibe)
            await page.select_option("#labState", state)
            if await page.is_checked("#labNight") != a.night:
                await page.click("#labNight")
            await page.wait_for_timeout(700)  # let cards settle (points drop in, captions advance)
            name = "lab-%s-%s-%s%s%s" % (cards, a.host, state, "-night" if a.night else "", "-p%d" % (pg + 1) if a.deck else "")
            await page.locator("#lab").screenshot(path=str(out / (name + ".png")))
            print("wrote", (out / (name + ".png")).relative_to(ROOT))
            if a.each:
                n = await page.locator("#lab .mini").count()
                for i in range(n):
                    await page.locator("#lab .mini").nth(i).screenshot(path=str(out / ("%s-%d.png" % (name, i))))
                print("  and %d single cards" % n)
            fits = await page.evaluate("[...document.querySelectorAll('#lab .card')].map(c=>c.getAttribute('data-fit')||'-').join(' ')")
            print("  fit steps per card (- = full size):", fits)
        await browser.close()
        print("page errors:", errors or "none")
        return 1 if errors else 0


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cards", default="long", choices=["short", "long", "anki"])
    ap.add_argument("--host", default="tiktok", choices=["tiktok", "instagram", "youtube"])
    ap.add_argument("--vibe", default="")
    ap.add_argument("--states", nargs="+", default=["q", "a"], choices=["q", "a", "part", "liked", "more", "hint"])
    ap.add_argument("--night", action="store_true")
    ap.add_argument("--deck", help="an .apkg or .colpkg to open in the lab instead of --cards")
    ap.add_argument("--pages", type=int, default=1, help="with --deck: how many pages of 8 cards (default 1)")
    ap.add_argument("--each", action="store_true", help="also save each card on its own")
    ap.add_argument("--scale", type=float, default=2, help="device pixel ratio (default 2)")
    sys.exit(asyncio.run(main(ap.parse_args())))
