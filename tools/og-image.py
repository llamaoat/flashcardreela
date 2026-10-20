#!/usr/bin/env python3
"""The picture that shows when the website's link is shared (WhatsApp, Discord, iMessage...): the first screen of the
page, 1200 x 630, saved as web-demo/icons/og.jpg.

    python3 tools/build-web-demo.py
    python3 tools/og-image.py

Needs Python 3 with Playwright and Pillow (pip install playwright pillow && python3 -m playwright install chromium).
Set CHROMIUM=/path/to/chrome if Playwright's own browser isn't installed. The fonts come from app/src/main/assets/fonts,
so nothing is fetched from the internet. The orcas and the colours move, so every run gives a slightly different picture.
"""
import base64, functools, http.server, io, os, pathlib, threading
from playwright.sync_api import sync_playwright
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parent.parent
FONTS = ROOT / "app" / "src" / "main" / "assets" / "fonts"
OUT = ROOT / "web-demo" / "icons" / "og.jpg"

def face(weight, file):
    return "@font-face{font-family:'Inter';font-weight:%d;src:url(data:font/ttf;base64,%s)}" % (weight, base64.b64encode((FONTS / file).read_bytes()).decode())

handler = functools.partial(http.server.SimpleHTTPRequestHandler, directory=str(ROOT / "web-demo"))
handler.log_message = lambda *a, **k: None
server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
threading.Thread(target=server.serve_forever, daemon=True).start()

with sync_playwright() as p:
    opts = {"executable_path": os.environ["CHROMIUM"]} if os.environ.get("CHROMIUM") else {}
    browser = p.chromium.launch(**opts)
    page = browser.new_page(viewport={"width": 1200, "height": 630})
    page.goto("http://127.0.0.1:%d/index.html" % server.server_address[1])
    page.add_style_tag(content=face(400, "Inter-Regular.ttf") + face(500, "Inter-Medium.ttf") + face(600, "Inter-SemiBold.ttf")
                       + ".sitenav,.landing .install,.trust,.ctas{display:none!important}.landing{min-height:630px!important;padding:0 56px!important}.landing h1{font-size:64px!important}.lead{font-size:20px!important}")
    page.wait_for_timeout(3500)  # the sea and the orcas are moving; let them settle into a pose
    png = page.screenshot()
    browser.close()
OUT.parent.mkdir(parents=True, exist_ok=True)
Image.open(io.BytesIO(png)).convert("RGB").save(OUT, "JPEG", quality=88, optimize=True)
print("wrote", OUT, OUT.stat().st_size, "bytes")
