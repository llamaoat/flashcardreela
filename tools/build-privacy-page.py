#!/usr/bin/env python3
"""Turns PRIVACY.md into a web page, for the privacy policy Google Play asks for at a public address.

    python3 tools/build-privacy-page.py [OUT]      (default build/site/privacy.html)

GitHub Pages publishes it next to the web demo (.github/workflows/pages.yml), at
https://<owner>.github.io/<repo>/privacy.html, the address the app links to (MainActivity.PRIVACY_URL).
Handles the Markdown PRIVACY.md uses: headings, paragraphs, lists, bold, italics, code and links.
"""
import html, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "build", "site", "privacy.html")

def inline(t):
    t = html.escape(t, quote=False)
    t = re.sub(r"`([^`]+)`", r"<code>\1</code>", t)
    t = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", t)
    t = re.sub(r"(?<![*\w])\*([^*]+)\*(?![*\w])", r"<em>\1</em>", t)
    return re.sub(r"\[([^\]]+)\]\(([^)\s]+)\)", lambda m: '<a href="%s">%s</a>' % (html.escape(m.group(2)), m.group(1)), t)

def convert(md):
    out, para, items = [], [], []
    def flush():
        if para: out.append("<p>%s</p>" % inline(" ".join(para))); para.clear()
        if items: out.append("<ul>%s</ul>" % "".join("<li>%s</li>" % inline(i) for i in items)); items.clear()
    for line in md.splitlines():
        if not line.strip():
            flush(); continue
        h = re.match(r"^(#{1,3})\s+(.*)", line)
        if h:
            flush(); n = len(h.group(1)); out.append('<h%d id="%s">%s</h%d>' % (n, re.sub(r"[^a-z0-9]+", "-", h.group(2).lower()).strip("-"), inline(h.group(2)), n)); continue
        if line.startswith("- "):
            if para: flush()
            items.append(line[2:].strip()); continue
        if items and line.startswith("  "):
            items[-1] += " " + line.strip(); continue
        if items: flush()
        para.append(line.strip())
    flush()
    return "\n".join(out)

body = convert(open(os.path.join(ROOT, "PRIVACY.md"), encoding="utf-8").read())
page = """<!doctype html>
<html lang="en-GB">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Reelcards privacy policy</title>
<style>
:root{--bg:#ffffff;--text:#1d1d1f;--muted:#5f5f6b;--link:#0b57d0;--code:#f1f1f4}
@media (prefers-color-scheme:dark){:root{--bg:#0f1014;--text:#ecebe6;--muted:#9c9ca9;--link:#8ab4f8;--code:#1e1f27}}
body{margin:0;background:var(--bg);color:var(--text);font:16px/1.6 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:720px;margin:0 auto;padding:32px 16px 64px}
h1{font-size:30px;line-height:1.2;margin:0 0 12px}h2{font-size:21px;margin:32px 0 8px}
a{color:var(--link)}code{background:var(--code);padding:1px 5px;border-radius:4px;font-size:.92em}
em{color:var(--muted)}li{margin:4px 0}
</style>
</head>
<body><main>
%s
<p><em>Reelcards is free software (GPLv3). This page is built from <a href="https://github.com/llamaoat/flashcardreela/blob/main/PRIVACY.md">PRIVACY.md</a>.</em></p>
</main></body>
</html>
""" % body
os.makedirs(os.path.dirname(OUT), exist_ok=True)
open(OUT, "w", encoding="utf-8").write(page)
print("wrote", OUT, len(page), "bytes")
