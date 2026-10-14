#!/usr/bin/env python3
"""Assembles web-demo/index.html (one self-contained file) from web-demo/src/.

    python3 tools/build-web-demo.py            -> web-demo/index.html
    python3 tools/build-web-demo.py --fragment OUT.html
        -> the same page without <html>/<head>/<body> wrappers (for hosts that add their own)
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "web-demo" / "src"

def read(name):
    return (SRC / name).read_text(encoding="utf-8")

import base64
sample = base64.b64encode((ROOT / "engine" / "src" / "test" / "resources" / "anki" / "sample.apkg").read_bytes()).decode()
app = (read("app.js").replace("/*__DECKS__*/", read("decks.js")).replace("/*__NOTES__*/", read("notes.js"))
       .replace("/*__SAMPLE_APKG__*/", 'const SAMPLE_APKG = "' + sample + '"; // tools/make-test-decks.py'))
scripts = "<script>\n" + read("engine.js") + "\n" + read("apkg.js") + "\n" + read("render.js") + "\n" + app + "\n</script>\n"
head = read("head.html")
body = read("body.html")
page = head + body + scripts + "</body>\n</html>\n"

# the Reelcards Feed: a separate page (web-demo/feed.html) that loads videos from YouTube or TikTok, so it is
# kept apart from the rest and has its own Content Security Policy, pinned to this page's one script by hash
import hashlib
def build_feed():
    feed_page = read("feed.html")
    start = head.index("/* =================================================================================\n   CARD REEL DESIGN SYSTEM")
    end = head.index("/* Add to Home Screen")
    root_vars = re.search(r":root\{(.*?)\n\}", head, re.S).group(1)
    css = ":root{" + root_vars + "}\n" + head[start:end]
    font_link = re.search(r'<link rel="stylesheet" href="https://fonts.googleapis.com/css2[^>]*>', head).group(0)
    js = ("\"use strict\";\n" + read("engine.js") + "\n" + read("render.js") + "\nconst makeCard = RC.makeCard;\n" + read("decks.js") + "\n" + read("notes.js") + "\n" + read("feed.js") + "\n")
    digest = base64.b64encode(hashlib.sha256(js.encode("utf-8")).digest()).decode()
    out = (feed_page.replace("/*__CARD_CSS__*/", css).replace("<!--__FONT_LINK__-->", font_link)
           .replace("__SCRIPT_HASH__", digest).replace("/*__SCRIPTS__*/", js))
    (ROOT / "web-demo" / "feed.html").write_text(out, encoding="utf-8")
    print("wrote", ROOT / "web-demo" / "feed.html", len(out), "bytes (script sha256-" + digest[:12] + "…)")

if len(sys.argv) > 2 and sys.argv[1] == "--fragment":
    inner_head = re.search(r"<head>(.*?)</head>", head, re.S).group(1)
    inner_head = re.sub(r'<meta charset="utf-8">\s*|<meta name="viewport"[^>]*>\s*', "", inner_head)
    pathlib.Path(sys.argv[2]).write_text(inner_head.strip() + "\n" + body + scripts, encoding="utf-8")
    print("wrote fragment", sys.argv[2])
else:
    out = ROOT / "web-demo" / "index.html"
    out.write_text(page, encoding="utf-8")
    print("wrote", out, len(page), "bytes")
    build_feed()
