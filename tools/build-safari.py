#!/usr/bin/env python3
"""Packs the browser extension for Safari on iPhone, iPad and Mac: build/safari/Reelcards-safari.zip.

    python3 tools/build-extension.py    # first, if web-demo/src/ changed
    python3 tools/build-safari.py

Upload the zip to Apple's Safari Web Extension Packager (App Store Connect, Users and Access, Xcode Cloud). It turns
the extension into an app for TestFlight and the App Store, with no Mac or Xcode: docs/SAFARI.md has every step.
CI builds the zip on every push (the Reelcards-safari artifact).

It's the same extension as Chrome's, so what tools/extension-test.py checks is what Safari gets. The manifest only
gains the 1024 x 1024 icon the App Store asks for (docs/play-store/icon-1024.png, from tools/store-assets.py) and
the oldest Safari it's meant for: 16.4 (iOS 16.4, March 2023), the first with everything it uses.
"""
import json, pathlib, sys, zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
EXT = ROOT / "extension"
OUT = ROOT / "build" / "safari"
SKIP = {"README.md"}   # notes for people, not part of the extension
ICON = ROOT / "docs" / "play-store" / "icon-1024.png"


def main():
    manifest = json.loads((EXT / "manifest.json").read_text(encoding="utf-8"))
    if not ICON.exists():
        sys.exit("missing %s: run python3 tools/store-assets.py" % ICON.relative_to(ROOT))
    manifest["icons"]["1024"] = "icons/icon-1024.png"
    manifest["browser_specific_settings"] = {"safari": {"strict_min_version": "16.4"}}
    OUT.mkdir(parents=True, exist_ok=True)
    zpath = OUT / "Reelcards-safari.zip"
    files = sorted(p for p in EXT.rglob("*") if p.is_file() and p.name not in SKIP and p.name != "manifest.json" and not p.name.startswith("."))
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
        for p in files:
            z.write(p, p.relative_to(EXT).as_posix())
        z.write(ICON, "icons/icon-1024.png")
    size = zpath.stat().st_size
    print("wrote %s (%d files, %.0f KB), version %s" % (zpath.relative_to(ROOT), len(files) + 2, size / 1024, manifest["version"]))


if __name__ == "__main__":
    main()
