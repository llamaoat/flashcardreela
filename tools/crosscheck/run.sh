#!/usr/bin/env bash
# Checks that the Java engine (the Android app) and its JavaScript port (the web demo) agree:
#  1. how answers are split into points (tools/crosscheck/corpus.json), and notes made into cards
#     (tools/crosscheck/notes.txt), with the "Make cards with AI" prompt
#  2. every card read from each test Anki file (engine/src/test/resources/anki/*.apkg, and real/: decks
#     exported by Anki itself, from tools/real-anki-decks.py)
# Needs a JDK (8+) and Node (18+). Exits non-zero, with a diff, if they disagree.
#   tools/crosscheck/run.sh                      the test decks
#   tools/crosscheck/run.sh ~/Downloads/*.apkg   also some real Anki exports
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/crosscheck
rm -rf "$OUT" && mkdir -p "$OUT/classes"
javac -nowarn -encoding UTF-8 -d "$OUT/classes" $(find engine/src/main/java -name '*.java') tools/crosscheck/*.java

fail=0
compare() { # id, label, java output, js output
  if diff -u "$3" "$4" > "$OUT/$1.diff"; then echo "  same    $2"; else echo "  DIFFER  $2 (full diff: $OUT/$1.diff)"; head -20 "$OUT/$1.diff"; fail=1; fi
}

echo "Java vs JavaScript"
java -cp "$OUT/classes" Splitter tools/crosscheck/corpus.json > "$OUT/splitter.java.txt"
node tools/crosscheck/splitter.js tools/crosscheck/corpus.json > "$OUT/splitter.js.txt"
compare splitter "answer splitting, $(wc -l < "$OUT/splitter.java.txt") cards" "$OUT/splitter.java.txt" "$OUT/splitter.js.txt"
java -cp "$OUT/classes" Notes tools/crosscheck/notes.txt > "$OUT/notes.java.txt"
node tools/crosscheck/notes.js tools/crosscheck/notes.txt > "$OUT/notes.js.txt"
compare notes "notes to cards and the AI prompt, $(($(wc -l < "$OUT/notes.java.txt") - 2)) cards" "$OUT/notes.java.txt" "$OUT/notes.js.txt"

for deck in engine/src/test/resources/anki/*.apkg engine/src/test/resources/anki/real/*.*pkg "$@"; do
  f=$(basename "$deck"); n=${f%.*}
  java -cp "$OUT/classes" AnkiFiles "$deck" > "$OUT/$n.java.txt"
  node tools/crosscheck/anki-files.js "$deck" > "$OUT/$n.js.txt"
  compare "$n" "$f, $(($(wc -l < "$OUT/$n.java.txt") - 1)) cards" "$OUT/$n.java.txt" "$OUT/$n.js.txt"
done
exit $fail
