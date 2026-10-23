#!/usr/bin/env python3
"""Tests the browser extension in a real Chromium, on stand-in pages that behave like each site's feed.

    python3 tools/build-extension.py
    python3 tools/extension-test.py

It loads extension/ unpacked, answers requests for tiktok.com, instagram.com, youtube.com and x.com itself (so
the content script runs as it would on the real sites, with nothing fetched from the internet), and drives the
popup and the feeds. The phone tests run the same extension in a Chromium the size of an iPhone, with touch
(Chrome's own touch input, so the pages get real touches): the closest this machine gets to Safari on a phone. It
isn't Safari: nothing here runs WebKit. The stand-ins copy how the extension tells one video from the next (the page address on
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
SHORTS = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0;background:#111;color:#fff;font:14px system-ui">
<video id="v" width="360" height="640" muted style="display:block;margin:10px auto;background:#222"></video><p id="t" style="text-align:center"></p>
<script>
const IDS = "abcdefghijklmnop".split(""), PREFIX = "%(prefix)s", SUFFIX = "%(suffix)s";
const idx = () => { const m = location.pathname.match(/\\/(?:shorts|reels?)\\/([^\\/]+)/); return m ? IDS.indexOf(m[1]) : 0; };
if (!/\\/(?:shorts|reels?)\\/[^\\/]+/.test(location.pathname)) history.replaceState({}, "", PREFIX + IDS[0] + SUFFIX); // like the real feeds: /shorts goes to its first video
function go(d){ const i = Math.max(0, Math.min(IDS.length - 1, idx() + d)); history.pushState({}, "", PREFIX + IDS[i] + SUFFIX); show(); }
function show(){ document.getElementById("t").textContent = "Short " + IDS[idx()]; }
addEventListener("keydown", e => { if (e.key === "ArrowDown") go(1); else if (e.key === "ArrowUp") go(-1); });
addEventListener("wheel", e => { if (e.defaultPrevented) return; if (e.deltaY > 30) go(1); else if (e.deltaY < -30) go(-1); }, {passive:true});
let ty = null; // a finger, as on a phone: swipe up for the next video, down for the one before
addEventListener("touchstart", e => { ty = e.touches[0].clientY; }, {passive:true});
addEventListener("touchend", e => { if (ty == null) return; const dy = e.changedTouches[0].clientY - ty; ty = null; if (dy < -50) go(1); else if (dy > 50) go(-1); }, {passive:true});
show();
</script></body></html>"""

TIKTOK = """<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"></head><body style="margin:0;background:#111;color:#fff;font:14px system-ui">
<div id="feed" style="height:100vh;overflow-y:scroll;scroll-snap-type:y mandatory">
""" + "".join('<section style="height:100vh;scroll-snap-align:start;display:flex;align-items:center;justify-content:center"><video width="360" height="640" muted style="background:#222"></video></section>' for _ in range(14)) + """
</div></body></html>"""

PAGES = {
    "youtube": ("https://www.youtube.com/**", "https://www.youtube.com/shorts/a", SHORTS % {"prefix": "/shorts/", "suffix": ""}),
    "instagram": ("https://www.instagram.com/**", "https://www.instagram.com/reels/a/", SHORTS % {"prefix": "/reels/", "suffix": "/"}),
    "tiktok": ("https://www.tiktok.com/**", "https://www.tiktok.com/", TIKTOK),
    "youtube-phone": ("https://m.youtube.com/**", "https://m.youtube.com/shorts/a", SHORTS % {"prefix": "/shorts/", "suffix": ""}),
}
IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"

# ---------------------------------------------------------------- helpers
def launch(p, phone=False):
    profile = tempfile.mkdtemp(prefix="reelcards-ext-")
    exe = {"executable_path": os.environ["CHROMIUM"]} if os.environ.get("CHROMIUM") else {}
    size = dict(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True, device_scale_factor=3, user_agent=IPHONE) if phone else dict(viewport={"width": 1000, "height": 800})
    ctx = p.chromium.launch_persistent_context(profile, headless=False, **size, **exe,
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

def wait_for(ok, secs, page):
    """Polls ok() for up to secs seconds, waiting on page (Playwright only hears from the browser while it waits)."""
    end = time.time() + secs
    while time.time() < end:
        if ok(): return True
        page.wait_for_timeout(200)
    return ok()

def swipe(page, cdp, dy, x=195, y=None, ms=160):
    """A finger dragged dy pixels up (negative) or down the screen, through Chrome's own touch input."""
    y = y if y is not None else (620 if dy < 0 else 220)
    cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": [{"x": x, "y": y}]})
    for i in range(1, 9):
        page.wait_for_timeout(ms / 8)
        cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": [{"x": x, "y": y + dy * i / 8}]})
    cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})

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
    check("the popup says what to do next, where you can see it", popup.is_visible("#goMsg") and "first video brings a card" in popup.inner_text("#goMsg"), popup.inner_text("#goMsg"))
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

def phone(ctx, sw, ext_id):
    print("On a phone (Chromium the size of an iPhone, with touch)")
    welcome = None
    if wait_for(lambda: any(pg.url.endswith("/welcome.html") for pg in ctx.pages), 6, ctx.pages[0]):
        welcome = next(pg for pg in ctx.pages if pg.url.endswith("/welcome.html"))
    check("installing it opens the welcome page by itself", welcome is not None)
    if not welcome: return
    # carry on in a copy the test opens itself: Playwright attaches late to a tab an extension opens, and can miss
    # the stand-in sites for it
    url = welcome.url; welcome.close(); welcome = ctx.new_page(); welcome.goto(url)
    welcome.wait_for_selector("#go", state="visible")
    check("  it starts with one button: Get started", welcome.is_visible("#go") and not welcome.is_visible("#s-allow"))
    welcome.tap("#go")
    welcome.wait_for_selector("#s-ready", state="visible", timeout=5000)
    st = storage(sw)
    decks = sorted({c["deck"] for c in st["engine"]["cards"]}) if st else []
    check("one tap sets it up: GCSE Biology and Chemistry, Normal, no exam date",
          decks == ["GCSE Biology", "GCSE Chemistry"] and st["engine"]["settings"]["intensity"] == "normal" and not st["engine"]["settings"]["examDate"], decks)
    check("  Chrome allows the video sites already, so it goes straight to them (Safari asks first)", not welcome.is_visible("#s-allow"))
    check("  with a button for each site", welcome.locator("#sites a").count() == 4)
    welcome.tap('#sites a[href*="youtube"]')
    page = welcome
    page.wait_for_url("**/shorts/a")
    shown = wait_for(lambda: card_up(page), 5, page)
    check("the first video brings a card straight away", shown)
    if not shown: return
    box, vw = overlay(page).locator(".frame").bounding_box(), page.viewport_size
    check("  it fills the phone's screen, like a reel", box and abs(box["width"] - vw["width"]) < 2 and abs(box["height"] - vw["height"]) < 2, box)
    check("  with no bar under it", not overlay(page).locator(".bar").is_visible())
    if overlay(page).locator(".card.l-quiz").count(): overlay(page).locator('[data-act="pick"]').first.tap()  # a quiz is answered by picking
    else: page.touchscreen.tap(100, 620)
    page.wait_for_timeout(600)
    check("a tap shows the answer", overlay(page).locator(".card.rv").count() == 1)
    cdp = ctx.new_cdp_session(page)
    first = where(page, "youtube")
    swipe(page, cdp, -60, ms=320); page.wait_for_timeout(400)
    check("a short drag springs back: the card stays", card_up(page))
    swipe(page, cdp, -420); page.wait_for_timeout(700)
    check("swiping the card up takes it away", not card_up(page))
    check("  and the video under it stays put: the site never saw that swipe", where(page, "youtube") == first, where(page, "youtube"))
    popup = open_popup(ctx, ext_id); set_intensity(popup, "demo"); popup.close()
    page.bring_to_front()
    shown = False
    for _ in range(8):
        swipe(page, cdp, -420); page.wait_for_timeout(1100)
        if card_up(page): shown = True; break
    check("swiping up the feed brings the next card", shown)
    if not shown: return
    at_card = where(page, "youtube")
    page.touchscreen.tap(100, 620); page.wait_for_timeout(90); page.touchscreen.tap(102, 622); page.wait_for_timeout(500)
    badge = overlay(page).locator(".badge")
    check("a double tap says you knew it", badge.count() == 1 and "Knew it" in badge.inner_text(), badge.inner_text() if badge.count() else "no badge")
    before = graded(sw)
    swipe(page, cdp, -420); page.wait_for_timeout(800)
    check("  and swiping on grades it, once", graded(sw) == before + 1 and not card_up(page), (before, graded(sw)))
    swipe(page, cdp, 420); page.wait_for_timeout(1300)
    check("swiping back to the video before brings the card again", card_up(page) and note(page).startswith("You did this card already"), note(page))
    check("  over the video before", where(page, "youtube") != at_card)
    under = where(page, "youtube")
    swipe(page, cdp, 420); page.wait_for_timeout(700)
    check("  and a swipe either way takes it away, leaving the video where it was", not card_up(page) and where(page, "youtube") == under, (card_up(page), under, where(page, "youtube")))
    check("  without grading it again", graded(sw) == before + 1, (before, graded(sw)))
    # one swiped past without answering, then come back to: it can be answered now, and counts
    shown = False
    for _ in range(8):
        swipe(page, cdp, -420); page.wait_for_timeout(1100)
        if card_up(page): shown = True; break
    if shown:
        swipe(page, cdp, -420); page.wait_for_timeout(800)   # past it, unanswered
        swipe(page, cdp, 420); page.wait_for_timeout(1300)   # and back
        check("one swiped past says so when you come back", card_up(page) and "skipped" in note(page), note(page))
        if overlay(page).locator(".card.l-quiz").count(): overlay(page).locator('[data-act="pick"]').first.tap()
        else: page.touchscreen.tap(100, 620)
        page.wait_for_timeout(600)
        check("  and it can be answered now", overlay(page).locator(".card.rv").count() == 1)
        before = graded(sw)
        swipe(page, cdp, -420); page.wait_for_timeout(800)
        check("  which counts", graded(sw) == before + 1 and not card_up(page), (before, graded(sw)))
    else:
        check("another card comes along", False)
    # TikTok's feed scrolls natively: the card must keep the finger from scrolling it underneath
    tk = open_site(ctx, "tiktok"); cdp2 = ctx.new_cdp_session(tk); shown = False
    for _ in range(8):
        swipe(tk, cdp2, -420); tk.wait_for_timeout(1300)
        if card_up(tk): shown = True; break
    check("[tiktok] swiping up the feed brings a card", shown)
    if shown:
        top = where(tk, "tiktok")
        swipe(tk, cdp2, -420); tk.wait_for_timeout(900)
        check("[tiktok]   swiping the card away doesn't scroll the feed under it", not card_up(tk) and where(tk, "tiktok") == top, (top, where(tk, "tiktok")))
    tk.close()

def run(only=None):
    with sync_playwright() as p:
        for is_phone, tests in ((False, TESTS), (True, PHONE_TESTS)):
            tests = [t for t in tests if not only or only in t[0]]
            if not tests: continue
            ctx, sw, ext_id, profile = launch(p, phone=is_phone)
            try:
                for name, fn in tests:
                    if not is_phone: reset(sw)  # every test starts from a new install (the phone test is one: it needs the install's welcome page)
                    fn(ctx, sw, ext_id)
            finally:
                ctx.close(); shutil.rmtree(profile, ignore_errors=True)
    print("\n%d of %d checks passed" % (sum(results), len(results)))
    return 0 if all(results) else 1

TESTS = [("basics", basics), ("get_started", get_started), ("revisit", revisit)]
PHONE_TESTS = [("phone", phone)]

if __name__ == "__main__":
    sys.exit(run(sys.argv[1] if len(sys.argv) > 1 else None))
