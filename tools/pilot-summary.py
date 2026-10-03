#!/usr/bin/env python3
"""Adds up the "Share my numbers" reports from a pilot (docs/PILOT.md) and sets them next to the
simulation's modelled numbers (engine/results/simulation.txt).

    python3 tools/pilot-summary.py reports/            (a folder of reports, one per student)
    python3 tools/pilot-summary.py a.json b.txt …       (or the files themselves)

Each report is the JSON the app shares; pasted into a .json or .txt file is fine (anything around
the JSON, like an email signature, is ignored). A student who sends twice counts once, with their
latest report. Standard library only.
"""
import glob, json, os, sys

def load(path):
    text = open(path, encoding="utf-8", errors="replace").read()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end < start:
        return None
    try:
        r = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return None
    return r if r.get("report") == "reelcards-pilot" else None

def main(args):
    paths = []
    for a in args or ["."]:
        paths += sorted(glob.glob(os.path.join(a, "*"))) if os.path.isdir(a) else [a]
    latest = {}
    for p in paths:
        r = load(p)
        if r is None:
            print("skipped (not a Reelcards report):", p, file=sys.stderr)
            continue
        if r["student"] not in latest or r["exported"] >= latest[r["student"]]["exported"]:
            latest[r["student"]] = r
    if not latest:
        sys.exit("No Reelcards reports found.")

    rows = []
    for code, r in sorted(latest.items()):
        days = [d for d in r["days"] if d["reels"] > 0]
        t, rec = r["totals"], r["recall"]
        rows.append({"student": code, "days": len(days), "reels/day": t["reels"] / max(1, len(days)),
                     "cards/day": t["cards"] / max(1, len(days)), "answered/day": t["answered"] / max(1, len(days)),
                     "answered/reel": t["answered"] / t["reels"] if t["reels"] else None,
                     "skipped": t["skipped"] / t["cards"] if t["cards"] else None,
                     "recall n": rec["reviewsAfterADay"], "recall": rec["rate"], "predicted": rec["predicted"]})

    def fmt(v, pct=False):
        if v is None:
            return "–"
        return "%d%%" % round(100 * v) if pct else ("%.2f" % v if isinstance(v, float) and v < 10 else "%d" % round(v))

    cols = [("student", False), ("days", False), ("reels/day", False), ("cards/day", False), ("answered/day", False),
            ("answered/reel", False), ("skipped", True), ("recall n", False), ("recall", True), ("predicted", True)]
    print("  ".join("%-13s" % c for c, _ in cols))
    for row in rows:
        print("  ".join("%-13s" % (row[c] if c == "student" else fmt(row[c], pct)) for c, pct in cols))

    # the whole class: totals, so heavy users count for more, as in the simulation's averages
    T = lambda k: sum(r["totals"][k] for r in latest.values())
    n = sum(r["recall"]["reviewsAfterADay"] for r in latest.values())
    remembered = sum(r["recall"]["remembered"] for r in latest.values())
    student_days = sum(row["days"] for row in rows)
    print()
    print("Class (%d students, %d student-days)" % (len(rows), student_days))
    print("  cards skipped            %s    (simulation, full engine: 51%%)" % fmt(T("skipped") / T("cards") if T("cards") else None, True))
    print("  cards answered per day   %s    (simulation: 38)" % fmt(T("answered") / max(1, student_days)))
    print("  answered per reel        %s" % fmt(T("answered") / T("reels") if T("reels") else None))
    print("  recall after 1+ days     %s of %d reviews    (FSRS predicted %s)" % (
        fmt(remembered / n if n else None, True), n,
        fmt(sum(r["recall"]["predicted"] * r["recall"]["reviewsAfterADay"] for r in latest.values() if r["recall"]["predicted"] is not None) / n if n else None, True)))

if __name__ == "__main__":
    main(sys.argv[1:])
