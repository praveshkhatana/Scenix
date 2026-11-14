#!/usr/bin/env python3
# Fold live sweep receipts into the catalogue: every playtested title becomes
# "tested", and ones whose end-state screenshots actually show a running game
# become "verified" (green dot + VERIFIED shelf on the hub). Safe to re-run.
import json, os, subprocess, sys
from PIL import Image

DATA = os.path.join(os.path.dirname(__file__), "..", "hub", "data", "games")
RECEIPTS = "/tmp/sweep-results.jsonl"
SHOTS = "/tmp/sweep-shots"
HUB = "nagraj@192.168.0.24"
KEY = os.path.expanduser("~/.ssh/pegify_ci_ed25519")
REMOTE_DIR = "scenix/hub/data/games"

def shot_stats(pid):
    """Brightness and colour of the final frame, on a small sample."""
    p = os.path.join(SHOTS, pid + "-b.png")
    if not os.path.exists(p):
        return None
    im = Image.open(p).convert("RGB").resize((96, 54))
    px = list(im.getdata())
    lum = [0.2 * r + 0.7 * g + 0.1 * b for (r, g, b) in px]
    mean = sum(lum) / len(lum)
    bright = sum(1 for v in lum if v > 40) / len(lum)
    color = sum(abs(r - g) + abs(g - b) for (r, g, b) in px) / len(px)
    return dict(mean=mean, bright=bright, color=color)

def judge(rec):
    s = shot_stats(rec["id"])
    verdict, changing = rec.get("verdict", ""), bool(rec.get("changing", False))
    try:
        frames = int(rec.get("frames") or 0)
    except (TypeError, ValueError):
        frames = 0
    if s is None:
        return False, "no-shot"
    if s["bright"] < 0.06 or s["mean"] < 18:
        return False, "black-screen"
    alive = frames > 0 or changing
    # A flat, near-monochrome frame that never moves is a lander or an error page.
    if not alive and s["color"] < 14:
        return False, "static-flat"
    if verdict.startswith("loaderror") or verdict == "never-started":
        return False, verdict
    if s["color"] < 10 and verdict in ("carved+domnav", "no-canvas") and not changing:
        return False, "dull-static"
    return True, "live-frame" if s["color"] >= 10 else "live-static"

def main():
    latest = {}
    for line in open(RECEIPTS):
        try:
            r = json.loads(line)
            latest[r["id"]] = r
        except Exception:
            pass
    by_src = {}
    for rec in latest.values():
        by_src.setdefault(rec.get("src", "curated.json"), []).append(rec)
    changed_files = []
    report = {"tested": 0, "verified_now": 0, "demoted": 0}
    for src, recs in by_src.items():
        path = os.path.join(DATA, src)
        if not os.path.exists(path):
            continue
        entries = json.load(open(path))
        touched = False
        for rec in recs:
            for e in entries:
                if e.get("id") != rec["id"]:
                    continue
                ok, why = judge(rec)
                if not e.get("tested"):
                    e["tested"] = True
                    touched = True
                if ok and not e.get("verified"):
                    e["verified"] = True
                    touched = True
                    report["verified_now"] += 1
                if (not ok and e.get("verified") and why in ("black-screen", "loaderror err", "never-started")):
                    # Only a hard-black or engine-refused verdict is strong enough to revoke a keeper.
                    e["verified"] = False
                    touched = True
                    report["demoted"] += 1
                if ok:
                    report["tested"] += 1
                break
        if touched:
            json.dump(entries, open(path, "w"), indent=1, ensure_ascii=False)
            changed_files.append(src)
    print(f"{len(latest)} receipts -> updated {changed_files}; "
          f"verified now +{report['verified_now']}, demoted {report['demoted']}, passing {report['tested']}")
    for src in changed_files:
        subprocess.run(["scp", "-i", KEY, "-o", "StrictHostKeyChecking=accept-new",
                        os.path.join(DATA, src), f"{HUB}:{REMOTE_DIR}/{src}"], check=True)
        print("pushed", src, "to prod hub")

if __name__ == "__main__":
    main()
