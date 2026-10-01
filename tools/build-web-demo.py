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

app = read("app.js").replace("/*__DECKS__*/", read("decks.js")).replace("/*__NOTES__*/", read("notes.js"))
scripts = "<script>\n" + read("engine.js") + "\n" + read("render.js") + "\n" + app + "\n</script>\n"
head = read("head.html")
body = read("body.html")
page = head + body + scripts + "</body>\n</html>\n"

if len(sys.argv) > 2 and sys.argv[1] == "--fragment":
    inner_head = re.search(r"<head>(.*?)</head>", head, re.S).group(1)
    inner_head = re.sub(r'<meta charset="utf-8">\s*|<meta name="viewport"[^>]*>\s*', "", inner_head)
    pathlib.Path(sys.argv[2]).write_text(inner_head.strip() + "\n" + body + scripts, encoding="utf-8")
    print("wrote fragment", sys.argv[2])
else:
    out = ROOT / "web-demo" / "index.html"
    out.write_text(page, encoding="utf-8")
    print("wrote", out, len(page), "bytes")
