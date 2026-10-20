#!/usr/bin/env python3
"""Tests the browser extension in a real Chromium, on stand-in pages that behave like each site's feed.

    python3 tools/build-extension.py
    python3 tools/extension-test.py

It loads extension/ unpacked, answers requests for tiktok.com, instagram.com, youtube.com and x.com itself (so
the content script runs as it would on the real sites, with nothing fetched from the internet), and drives the
popup and the feeds. The stand-ins copy how the extension tells one video from the next (the page address on
YouTube Shorts and Instagram Reels, a new <video> element on TikTok and X). They don't prove it works on the live
sites, which change their pages and which this machine can't reach.

Needs Python 3 with Playwright (pip install playwright). Set CHROMIUM=/path/to/chrome if Playwright's own browser
isn't installed. Exits 1 if anything fails.
"""
import json, os, pathlib, shutil, sys, tempfile, time
from playwright.sync_api import sync_playwright

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXT = os.environ.get("EXTENSION_DIR") or str(ROOT / "extension")  # EXTENSION_DIR: try another copy of the extension
results = []

def check(name, ok, detail=""):
    results.append(bool(ok))
    print(("  PASS  " if ok else "  FAIL  ") + name + (("   [" + str(detail) + "]") if detail and not ok else ""))

# ---------------------------------------------------------------- stand-in pages
SHORTS = """<!doctype html><html><body style="margin:0;background:#111;color:#fff;font:14px system-ui">
<video id="v" width="360" height="640" muted style="display:block;margin:10px auto;background:#222"></video><p id="t" style="text-align:center"></p>
<script>
const IDS = "abcdefghijklmnop".split(""), PREFIX = "%(prefix)s", SUFFIX = "%(suffix)s";
const idx = () => { const m = location.pathname.match(/\\/(?:shorts|reels?)\\/([^\\/]+)/); return m ? IDS.indexOf(m[1]) : 0; };
function go(d){ const i = Math.max(0, Math.min(IDS.length - 1, idx() + d)); history.pushState({}, "", PREFIX + IDS[i] + SUFFIX); show(); }
function show(){ document.getElementById("t").textContent = "Short " + IDS[idx()]; }
addEventListener("keydown", e => { if (e.key === "ArrowDown") go(1); else if (e.key === "ArrowUp") go(-1); });
addEventListener("wheel", e => { if (e.defaultPrevented) return; if (e.deltaY > 30) go(1); else if (e.deltaY < -30) go(-1); }, {passive:true});
show();
</script></body></html>"""

TIKTOK = """<!doctype html><html><body style="margin:0;background:#111;color:#fff;font:14px system-ui">
<div id="feed" style="height:100vh;overflow-y:scroll;scroll-snap-type:y mandatory">
""" + "".join('<section style="height:100vh;scroll-snap-align:start;display:flex;align-items:center;justify-content:center"><video width="360" height="640" muted style="background:#222"></video></section>' for _ in range(14)) + """
</div></body></html>"""

PAGES = {
    "youtube": ("https://www.youtube.com/**", "https://www.youtube.com/shorts/a", SHORTS % {"prefix": "/shorts/", "suffix": ""}),
    "instagram": ("https://www.instagram.com/**", "https://www.instagram.com/reels/a/", SHORTS % {"prefix": "/reels/", "suffix": "/"}),
    "tiktok": ("https://www.tiktok.com/**", "https://www.tiktok.com/", TIKTOK),
}

# ---------------------------------------------------------------- helpers
def launch(p):
    profile = tempfile.mkdtemp(prefix="reelcards-ext-")
    exe = {"executable_path": os.environ["CHROMIUM"]} if os.environ.get("CHROMIUM") else {}
    ctx = p.chromium.launch_persistent_context(profile, headless=False, viewport={"width": 1000, "height": 800}, **exe,
        args=["--headless=new", "--no-sandbox", "--disable-extensions-except=" + EXT, "--load-extension=" + EXT])
    for site, (pattern, _, html) in PAGES.items():
        ctx.route(pattern, lambda route, request, html=html: route.fulfill(body=html, content_type="text/html"))
    for _ in range(40):
        if ctx.service_workers: break
        time.sleep(.25)
    sw = ctx.service_workers[0]
    return ctx, sw, sw.url.split("/")[2], profile

def storage(sw):
    """The extension's saved state, as the content scripts and the popup see it."""
    return sw.evaluate("() => new Promise(ok => chrome.storage.local.get('reelcards', r => ok(r.reelcards || null)))")

def open_popup(ctx, ext_id):
    page = ctx.new_page(); page.goto("chrome-extension://%s/popup.html#tab" % ext_id); page.wait_for_selector("#stats b")
    return page

def open_site(ctx, site):
    page = ctx.new_page(); page.goto(PAGES[site][1]); page.wait_for_timeout(900)
    return page

def move(page, site, steps, back=False):
    """One step along the feed, the way a person does it: the arrow key on Shorts and Reels, a scroll on TikTok."""
    for _ in range(abs(steps)):
        if site == "tiktok":
            page.evaluate("(d) => { const f = document.getElementById('feed'); f.scrollTo({top: Math.round(f.scrollTop / innerHeight + d) * innerHeight}); }", -1 if back else 1)
        else:
            page.keyboard.press("ArrowUp" if back else "ArrowDown")
        page.wait_for_timeout(1100)  # the extension looks every 0.6 s

def overlay(page):
    return page.locator("#reelcards-overlay")

def card_up(page):
    return overlay(page).count() > 0

def where(page, site):
    """Which video the stand-in feed is showing."""
    if site == "tiktok": return page.evaluate("() => Math.round(document.getElementById('feed').scrollTop / innerHeight)")
    return page.inner_text("#t")

def dismiss(page, site):
    """Moves on from a card the way a person does: scroll on."""
    page.mouse.move(500, 400); page.mouse.wheel(0, 120); page.wait_for_timeout(500)

def set_intensity(popup, value):
    popup.select_option("#intensity", value); popup.wait_for_timeout(300)

def reset(sw):
    """Back to a new install: nothing saved."""
    sw.evaluate("() => new Promise(ok => chrome.storage.local.clear(ok))")

def tab_message(sw, message):
    """Sends a message to the content script in whichever tab has one, as the popup does, and returns its answer."""
    return sw.evaluate("""(msg) => new Promise(done => {
        chrome.tabs.query({}, tabs => {
            let left = tabs.length, answer = null;
            if (!left) { done(null); return; }
            tabs.forEach(t => chrome.tabs.sendMessage(t.id, msg, r => {
                void chrome.runtime.lastError;
                if (r && !answer) answer = r;
                if (--left === 0) done(answer);
            }));
        });
    })""", message)

def ready(ctx, sw, ext_id, intensity="demo"):
    """Cards in the extension (Get started, as a student does it) and the given how-often. Leaves the popup open."""
    popup = open_popup(ctx, ext_id)
    if popup.is_visible("#go"): popup.click("#go"); popup.wait_for_timeout(800)
    set_intensity(popup, intensity)
    return popup

def graded(sw):
    """How many cards have been graded so far (the engine's log keeps the last 40 lines)."""
    st = storage(sw)
    return sum(1 for line in st["engine"]["log"] if line.startswith("Graded")) if st else 0

def note(page):
    n = overlay(page).locator(".note")
    return n.inner_text() if n.count() and n.is_visible() else ""

# ---------------------------------------------------------------- tests
def basics(ctx, sw, ext_id):
    print("The popup and the first card")
    popup = open_popup(ctx, ext_id)
    check("a new install has no cards and the popup says so", "No cards yet" in popup.inner_text("#mine"))
    popup.check('input[data-deck="GCSE Biology"]'); popup.wait_for_timeout(400)
    set_intensity(popup, "demo")
    st = storage(sw)
    check("ticking a deck saves its cards", st and len(st["engine"]["cards"]) > 10, st and len(st["engine"]["cards"]))
    check("Demo is saved as how often", st and st["engine"]["settings"]["intensity"] == "demo")
    for site in ("youtube", "instagram", "tiktok"):
        page = open_site(ctx, site)
        shown = False
        for _ in range(6):
            move(page, site, 1)
            if card_up(page): shown = True; break
        check("a card slips in between videos on a %s-style feed" % site, shown)
        if shown:
            check("  the card is drawn in a shadow root over the page", page.locator("#reelcards-overlay .card").count() == 1)
            dismiss(page, site)
            check("  scrolling on dismisses it", not card_up(page))
        page.close()
    popup.close()

def get_started(ctx, sw, ext_id):
    print("Get started and Switch off")
    popup = open_popup(ctx, ext_id)
    check("the popup offers Get started on a new install", popup.is_visible("#go"))
    popup.click("#go"); popup.wait_for_timeout(800)
    st = storage(sw)
    decks = sorted({c["deck"] for c in st["engine"]["cards"]}) if st else []
    check("Get started adds GCSE Biology and Chemistry", decks == ["GCSE Biology", "GCSE Chemistry"], decks)
    check("  \"Normal\" cards, no exam date", st and st["engine"]["settings"]["intensity"] == "normal" and not st["engine"]["settings"]["examDate"])
    check("  and it's marked as set up", st and st["meta"]["onboarded"] is True)
    check("the Get started box goes away once there are cards", not popup.is_visible("#go"))
    check("the popup says what to do next", "slips in" in popup.inner_text("#goMsg"), popup.inner_text("#goMsg"))
    popup.click("#off"); popup.wait_for_timeout(500)
    st = storage(sw)
    check("Switch off saves that cards are off", st and st["meta"]["off"] is True)
    check("  the popup says so and offers Switch on", "is off" in popup.inner_text("#state") and popup.inner_text("#off") == "Switch on")
    set_intensity(popup, "demo")
    page = open_site(ctx, "youtube")
    move(page, "youtube", 6)
    check("no card appears while it's switched off", not card_up(page))
    # the popup's test card still works when it's off
    r = tab_message(sw, {"type": "testCard"})
    page.wait_for_timeout(500)
    check("a test card can still be shown while it's off", r and r.get("ok") and card_up(page), r)
    dismiss(page, "youtube"); page.close()
    popup.click("#off"); popup.wait_for_timeout(500)
    check("Switch on turns cards back on", storage(sw)["meta"]["off"] is False)
    page = open_site(ctx, "youtube"); shown = False
    for _ in range(6):
        move(page, "youtube", 1)
        if card_up(page): shown = True; break
    check("and cards slip in again", shown)
    page.close(); popup.close()

def revisit(ctx, sw, ext_id):
    print("Scrolling back to a card")
    popup = ready(ctx, sw, ext_id, "demo")
    for site in ("youtube", "tiktok"):
        page = open_site(ctx, site)
        page.wait_for_timeout(500)
        if card_up(page): dismiss(page, site)  # a card on the very first video has no video before it to come back to
        shown = False
        for _ in range(8):
            move(page, site, 1)
            if card_up(page): shown = True; break
        check("[%s] a card slips in" % site, shown)
        if not shown: page.close(); continue
        first = where(page, site)
        check("[%s]   the first time, it has no note on it" % site, note(page) == "", note(page))
        dismiss(page, site)           # scrolled past it without answering: skipped
        page.wait_for_timeout(300)
        sr = storage(sw)["engine"]["sessionReels"]
        move(page, site, 1, back=True)
        check("[%s] going back to the video before puts the card on screen again" % site, card_up(page))
        check("[%s]   it says it was skipped" % site, "skipped" in note(page), note(page))
        check("[%s]   the video underneath is the one before" % site, where(page, site) != first)
        check("[%s]   going back doesn't count as a new video" % site, storage(sw)["engine"]["sessionReels"] == sr)
        # answer it this time: show the answer, say you knew it, and move on
        page.keyboard.press("Space"); page.wait_for_timeout(500); page.keyboard.press("l"); page.wait_for_timeout(300)
        before = graded(sw)
        page.keyboard.press("ArrowDown"); page.wait_for_timeout(700)
        check("[%s] answering it now grades it, once" % site, graded(sw) == before + 1, (before, graded(sw)))
        check("[%s]   and takes the card down without moving the video" % site, not card_up(page) and where(page, site) != first)
        set_intensity(popup, "chill")                    # no new card on the way forward
        move(page, site, 1)                              # on to the video the card was shown on
        check("[%s]   going on again shows no new card" % site, not card_up(page))
        move(page, site, 1, back=True)                   # and back again
        check("[%s] back to a card they did says so" % site, card_up(page) and note(page).startswith("You did this card already"), note(page))
        check("[%s]   with the answer showing" % site, overlay(page).locator(".card.rv").count() == 1)
        sr, before = storage(sw)["engine"]["sessionReels"], graded(sw)
        page.keyboard.press("ArrowDown"); page.wait_for_timeout(500)
        check("[%s]   looking at it again doesn't grade it again" % site, graded(sw) == before and not card_up(page), (before, graded(sw)))
        set_intensity(popup, "demo")
        page.close()
    popup.close()

def run(only=None):
    with sync_playwright() as p:
        ctx, sw, ext_id, profile = launch(p)
        try:
            for name, fn in TESTS:
                if only and only not in name: continue
                reset(sw)  # every test starts from a new install
                fn(ctx, sw, ext_id)
        finally:
            ctx.close(); shutil.rmtree(profile, ignore_errors=True)
    print("\n%d of %d checks passed" % (sum(results), len(results)))
    return 0 if all(results) else 1

TESTS = [("basics", basics), ("get_started", get_started), ("revisit", revisit)]

if __name__ == "__main__":
    sys.exit(run(sys.argv[1] if len(sys.argv) > 1 else None))
