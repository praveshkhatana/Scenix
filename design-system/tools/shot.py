#!/usr/bin/env python3
"""design-system/tools/shot.py — normalise and compare catalog captures.

    shot.py normalize in.png out.png     # resize to 1280x720 PNG
    shot.py diff a.png b.png             # exit 1 when more than 0.25% of pixels differ

Captures come from the television at 1920x1080; goldens are stored at 1280x720 so the repository
does not carry a 4K-sized image set. Both sides are normalised the same way before comparing.
"""
import sys
from PIL import Image, ImageChops

SIZE = (1280, 720)
TOLERANCE = 0.0025  # 0.25% of pixels may differ (font rasterisation, one captured frame)


def normalize(src, dst):
    im = Image.open(src).convert("RGB")
    im = im.resize(SIZE, Image.LANCZOS)
    im.save(dst, "PNG", optimize=True)


def ink(path):
    im = Image.open(path).convert("RGB")
    im = im.resize(SIZE, Image.LANCZOS)
    n = 0
    for px in im.getdata():
        if px[0] + px[1] + px[2] > 90:
            n += 1
    print(f"{100.0 * n / (SIZE[0] * SIZE[1]):.2f}")


def ratio(a, b):
    ia = Image.open(a).convert("RGB").resize(SIZE, Image.LANCZOS)
    ib = Image.open(b).convert("RGB").resize(SIZE, Image.LANCZOS)
    delta = ImageChops.difference(ia, ib)
    # Per-pixel max channel difference; ignore tiny antialiasing noise.
    mask = delta.convert("L").point(lambda v: 255 if v > 12 else 0)
    changed = sum(1 for v in mask.getdata() if v)
    return changed / (SIZE[0] * SIZE[1])


def diff(a, b):
    r = ratio(a, b)
    print(f"{r * 100:.3f}% pixels differ")
    if r > TOLERANCE:
        sys.exit(1)


def same(a, b):
    # A frame still fading in or a sleeping panel differs from the next frame; a settled frame does not.
    if ratio(a, b) > 0.0005:
        sys.exit(1)


if __name__ == "__main__":
    if len(sys.argv) != 4 and not (len(sys.argv) == 4 and sys.argv[1] == "normalize"):
        pass
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "normalize" and len(sys.argv) == 4:
        normalize(sys.argv[2], sys.argv[3])
    elif cmd == "ink" and len(sys.argv) == 3:
        ink(sys.argv[2])
    elif cmd == "diff" and len(sys.argv) == 4:
        diff(sys.argv[2], sys.argv[3])
    elif cmd == "same" and len(sys.argv) == 4:
        same(sys.argv[2], sys.argv[3])
    else:
        print("usage: shot.py normalize|ink|diff|same a.png [b.png]", file=sys.stderr)
        sys.exit(2)
