#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow", "numpy"]
# ///
"""Compare native renders with reference pictures.

usage: uv run scripts/compare.py <refdir> <outdir> [--tol 24] [--sheets <dir>] [--only glob]

Per scenario id present in both dirs (<id>.png): percent of pixels whose max channel
difference exceeds --tol, bounding box of ink (non-white) in each, mean RGB distance over all
pixels, and a side-by-side sheet (ref | native | diff in red) in --sheets (default
<outdir>/compare).
"""
import argparse
import fnmatch
import os
import sys

import numpy as np
from PIL import Image


def ink_bbox(a):
    ink = np.any(a < 250, axis=2)
    ys, xs = np.nonzero(ink)
    if len(xs) == 0:
        return None
    return (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))


def load(p):
    im = Image.open(p).convert("RGBA")
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    return np.asarray(Image.alpha_composite(bg, im).convert("RGB")).astype(np.int16)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("refdir")
    ap.add_argument("outdir")
    ap.add_argument("--tol", type=int, default=24)
    ap.add_argument("--sheets", default=None)
    ap.add_argument("--only", default=None)
    a = ap.parse_args()
    sheets = a.sheets or os.path.join(a.outdir, "compare")
    os.makedirs(sheets, exist_ok=True)
    ids = sorted(f[:-4] for f in os.listdir(a.refdir) if f.endswith(".png") and not f.endswith(".overlay.png"))
    if a.only:
        ids = [i for i in ids if any(fnmatch.fnmatch(i, g) for g in a.only.split(","))]
    print(f"{'id':40s} {'diff%':>7s} {'meanDist':>8s}  ref-ink-bbox            native-ink-bbox")
    worst = []
    for i in ids:
        rp, np_ = os.path.join(a.refdir, i + ".png"), os.path.join(a.outdir, i + ".png")
        if not os.path.exists(np_):
            print(f"{i:40s} {'missing':>7s}")
            continue
        r, n = load(rp), load(np_)
        if r.shape != n.shape:
            h, w = min(r.shape[0], n.shape[0]), min(r.shape[1], n.shape[1])
            print(f"{i:40s} size differs ref {r.shape[1]}x{r.shape[0]} native {n.shape[1]}x{n.shape[0]}; comparing {w}x{h}")
            r, n = r[:h, :w], n[:h, :w]
        d = np.abs(r - n)
        over = np.max(d, axis=2) > a.tol
        pct = 100.0 * over.mean()
        mean = float(np.sqrt((d.astype(np.float64) ** 2).sum(axis=2)).mean())
        print(f"{i:40s} {pct:7.3f} {mean:8.3f}  {str(ink_bbox(r)):24s} {ink_bbox(n)}")
        worst.append((pct, i))
        diff = np.full(r.shape, 255, np.uint8)
        diff[over] = (255, 0, 0)
        sheet = np.concatenate([r.astype(np.uint8), n.astype(np.uint8), diff], axis=1)
        Image.fromarray(sheet).save(os.path.join(sheets, i + ".png"))
    if worst:
        worst.sort(reverse=True)
        print("\nworst:", ", ".join(f"{i} {p:.2f}%" for p, i in worst[:10]))
    print(f"sheets in {sheets}")


if __name__ == "__main__":
    sys.exit(main())
