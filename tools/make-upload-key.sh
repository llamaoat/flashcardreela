#!/usr/bin/env bash
# Makes your private upload key for Google Play, and says what to put in GitHub's secrets so CI can
# sign the app bundle with it (.github/workflows/build.yml, "Play bundle").
#   tools/make-upload-key.sh [where to save it]      (default ~/reelcards-upload.jks)
# Keep the .jks file and its password somewhere private and backed up (a password manager). Never
# commit it. With Play App Signing, Google holds the key that signs the app itself; this one only
# proves an upload came from you, and Google can reset it if it's lost.
set -euo pipefail
out="${1:-$HOME/reelcards-upload.jks}"
if [ -e "$out" ]; then echo "$out already exists: not overwriting it." >&2; exit 1; fi
read -r -s -p "Choose a password for the key (6 or more characters): " pw; echo
if [ "${#pw}" -lt 6 ]; then echo "Too short." >&2; exit 1; fi
keytool -genkeypair -keystore "$out" -storetype PKCS12 -alias upload -keyalg RSA -keysize 4096 \
  -validity 10000 -storepass "$pw" -keypass "$pw" -dname "CN=Reelcards upload key" >/dev/null
chmod 600 "$out"
cat <<MSG

Made $out.
Add these repository secrets (GitHub: Settings > Secrets and variables > Actions > New repository secret):
  REELCARDS_KEYSTORE_BASE64     the output of:  base64 -w0 "$out"   (macOS: base64 -i "$out")
  REELCARDS_KEYSTORE_PASSWORD   the password you just chose
  REELCARDS_KEY_ALIAS           upload
  REELCARDS_KEY_PASSWORD        the same password
To build the bundle on your own computer instead:
  REELCARDS_KEYSTORE="$out" REELCARDS_KEYSTORE_PASSWORD=... ./gradlew :app:bundleRelease
MSG
