#!/usr/bin/env bash
# Refuses an app bundle that isn't signed, or is signed with the shared demo key in tools/ (which
# anyone can use, so it must never reach Google Play). CI runs it before keeping a Play bundle.
#   tools/check-play-bundle.sh app/build/outputs/bundle/release/app-release.aab
set -euo pipefail
f="${1:?usage: tools/check-play-bundle.sh app-release.aab}"
f="$(cd "$(dirname "$f")" && pwd)/$(basename "$f")"
cd "$(dirname "$0")/.."
demo=$(keytool -list -v -keystore tools/reelcards-debug.jks -storepass reelcards -alias reelcards | awk '/SHA256:/ {print $2; exit}')
got=$(keytool -printcert -jarfile "$f" 2>/dev/null | awk '/SHA256:/ {print $2; exit}' || true)
if [ -z "$got" ]; then echo "Not signed: $f" >&2; exit 1; fi
if [ "$got" = "$demo" ]; then echo "Signed with the shared demo key: don't upload this. Set up your upload key (tools/make-upload-key.sh)." >&2; exit 1; fi
echo "Signed with $got (not the demo key)."
