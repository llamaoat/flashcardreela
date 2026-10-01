#!/usr/bin/env bash
# Builds the Reelcards APK without Gradle, straight from the Android build tools.
# (Most people should just use Android Studio or `./gradlew assembleDebug`. This script is how the
#  APK in the release was made, in an environment where Google's Maven servers weren't reachable.)
#
# Needs: JDK 17+, and these from the Android SDK (or equivalents):
#   ANDROID_JAR  platforms/android-34/android.jar
#   AAPT2        build-tools/<ver>/aapt2
#   DX_JAR       a dexer jar: d8 (build-tools/<ver>/lib/d8.jar, main class com.android.tools.r8.D8)
#                or the old dx.jar (main class com.android.dx.command.Main)
#   APKSIGNER    build-tools/<ver>/lib/apksigner.jar
set -euo pipefail
cd "$(dirname "$0")/.."

: "${ANDROID_JAR:?set ANDROID_JAR}"
: "${AAPT2:?set AAPT2}"
: "${DX_JAR:?set DX_JAR}"
: "${APKSIGNER:?set APKSIGNER}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0}"
OUT=build/manual
rm -rf "$OUT" && mkdir -p "$OUT"/{res,gen,classes}

echo "1/6 resources"
"$AAPT2" compile --dir app/src/main/res -o "$OUT/res/compiled.zip"
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.reelcards.app">|' \
  app/src/main/AndroidManifest.xml > "$OUT/AndroidManifest.xml"
"$AAPT2" link -o "$OUT/base.apk" -I "$ANDROID_JAR" --manifest "$OUT/AndroidManifest.xml" \
  --min-sdk-version 26 --target-sdk-version 34 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  -A app/src/main/assets --java "$OUT/gen" "$OUT/res/compiled.zip"

echo "2/6 compile Java"
javac -nowarn -Xlint:-options -source 8 -target 8 -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$OUT/classes" \
  $(find engine/src/main/java app/src/main/java "$OUT/gen" -name '*.java')

echo "3/6 dex"
if unzip -l "$DX_JAR" | grep -q 'com/android/tools/r8/D8.class'; then
  (cd "$OUT/classes" && find . -name '*.class' > ../classlist.txt)
  java -cp "$DX_JAR" com.android.tools.r8.D8 --min-api 26 --lib "$ANDROID_JAR" --output "$OUT" $(cd "$OUT/classes" && find . -name '*.class' | sed "s|^\.|$OUT/classes|")
else
  java -jar "$DX_JAR" --dex --min-sdk-version=26 --output="$OUT/classes.dex" "$OUT/classes"
fi

echo "4/6 package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk classes.dex)

echo "5/6 sign"
KS=tools/reelcards-debug.jks
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass reelcards -keypass reelcards -alias reelcards \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Reelcards Debug" >/dev/null
fi
java -jar "$APKSIGNER" sign --ks "$KS" --ks-pass pass:reelcards --key-pass pass:reelcards \
  --out build/Reelcards.apk "$OUT/unsigned.apk"

echo "6/6 verify"
java -jar "$APKSIGNER" verify build/Reelcards.apk
ls -la build/Reelcards.apk
