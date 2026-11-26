#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["pillow", "numpy", "scipy"]
# ///
"""Stronger parity comparison than compare.py.

usage: uv run scripts/audit.py <refdir> <outdir> [--only glob,glob] [--overlays] [--md out.md]
                               [--crops <dir>] [--json out.json]

Per scenario present in both dirs:
  bbox   ink bounding boxes (non-white, any channel < 250) equal? max edge offset in px
  d0     pixels differing at all (tolerance 0)
  d24    pixels differing by more than 24 in some channel (compare.py's diff)
  blob   largest 8-connected blob of d24 pixels (pixels) and its bbox
  census exact colours used by at least --census-min pixels (default 30) in either picture:
         'major' colours in the ref with no exact match in native (missing) and vice versa
         (extra), "no match" meaning no colour within 3 per channel (the WebView leaves +-1..2
         noise on edges and resampled stamps). Kid Pix draws with few flat colours; a wrong palette entry or texture shows here
         even when it covers 0.1% of the picture.
With --overlays, compares <id>.overlay.png instead (scenarios with a reference overlay only).
--crops writes ref|native|diff crops around the largest blob (and the whole sheet, scaled).
"""
import argparse
import fnmatch
import json
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage


def load(p):
    im = Image.open(p).convert("RGBA")
    bg = Image.new("RGBA", im.size, (255, 255, 255, 255))
    return np.asarray(Image.alpha_composite(bg, im).convert("RGB")).astype(np.int16)


def ink_bbox(a):
    ink = np.any(a < 250, axis=2)
    ys, xs = np.nonzero(ink)
    if len(xs) == 0:
        return None
    return (int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max()))


def census(a, minpx):
    flat = (a[..., 0].astype(np.int64) << 16) | (a[..., 1].astype(np.int64) << 8) | a[..., 2].astype(np.int64)
    vals, counts = np.unique(flat, return_counts=True)
    return dict(zip(vals.tolist(), counts.tolist())), {v for v, c in zip(vals.tolist(), counts.tolist()) if c >= minpx}


CTOL = 3  # the WebView's antialiasing and resampling leave +-1..2 per channel; not a palette error


def near(v, counts, minpx=3):
    """True when the other picture has a colour within CTOL per channel of v (>= minpx pixels)."""
    r, g, b = v >> 16, (v >> 8) & 255, v & 255
    for dr in range(-CTOL, CTOL + 1):
        for dg in range(-CTOL, CTOL + 1):
            for db in range(-CTOL, CTOL + 1):
                rr, gg, bb = r + dr, g + dg, b + db
                if 0 <= rr < 256 and 0 <= gg < 256 and 0 <= bb < 256 and counts.get((rr << 16) | (gg << 8) | bb, 0) >= minpx:
                    return True
    return False


def hexc(v):
    return "#%06x" % v


def bbox_off(a, b):
    if a is None and b is None:
        return 0
    if a is None or b is None:
        return 9999
    return max(abs(x - y) for x, y in zip(a, b))


def compare(r, n, minpx):
    d = np.abs(r - n).max(axis=2)
    d0 = int((d > 0).sum())
    over = d > 24
    d24 = int(over.sum())
    lab, k = ndimage.label(over, structure=np.ones((3, 3)))
    blob, blob_box = 0, None
    if k:
        sizes = ndimage.sum(over, lab, range(1, k + 1))
        bi = int(np.argmax(sizes))
        blob = int(sizes[bi])
        sl = ndimage.find_objects(lab)[bi]
        blob_box = (sl[1].start, sl[0].start, sl[1].stop - 1, sl[0].stop - 1)
    rc, rmaj = census(r, minpx)
    nc, nmaj = census(n, minpx)
    missing = sorted((v for v in rmaj if not near(v, nc)), key=lambda v: -rc[v])
    extra = sorted((v for v in nmaj if not near(v, rc)), key=lambda v: -nc[v])
    rb, nb = ink_bbox(r), ink_bbox(n)
    return dict(d0=d0, d24=d24, pct24=100.0 * d24 / d.size, blob=blob, blob_box=blob_box,
                ref_bbox=rb, nat_bbox=nb, bbox_off=bbox_off(rb, nb),
                ref_major=len(rmaj), nat_major=len(nmaj),
                missing=[(hexc(v), rc[v]) for v in missing], extra=[(hexc(v), nc[v]) for v in extra]), over


def crop_sheet(r, n, over, box, path, pad=24, scale=3):
    h, w = over.shape
    x0, y0, x1, y1 = box
    x0, y0 = max(0, x0 - pad), max(0, y0 - pad)
    x1, y1 = min(w, x1 + pad + 1), min(h, y1 + pad + 1)
    if (x1 - x0) * scale > 900:
        scale = max(1, 900 // (x1 - x0))
    diff = np.full(r.shape, 255, np.uint8)
    diff[over] = (255, 0, 0)
    parts = [a[y0:y1, x0:x1].astype(np.uint8) for a in (r, n, diff)]
    sep = np.zeros((y1 - y0, 2, 3), np.uint8)
    sheet = np.concatenate([parts[0], sep, parts[1], sep, parts[2]], axis=1)
    im = Image.fromarray(sheet)
    im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    im.save(path)


def flags(m):
    f = []
    if m["bbox_off"] > 2:
        f.append("bbox")
    if m["missing"] or m["extra"]:
        f.append("census")
    if m["blob"] >= 200:
        f.append("blob")
    if m["pct24"] >= 0.5:
        f.append("diff")
    return f


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("refdir")
    ap.add_argument("outdir")
    ap.add_argument("--only", default=None)
    ap.add_argument("--overlays", action="store_true")
    ap.add_argument("--census-min", type=int, default=30)
    ap.add_argument("--md", default=None)
    ap.add_argument("--json", default=None)
    ap.add_argument("--crops", default=None)
    a = ap.parse_args()
    suf = ".overlay.png" if a.overlays else ".png"
    ids = sorted(f[: -len(suf)] for f in os.listdir(a.refdir) if f.endswith(suf) and (a.overlays or not f.endswith(".overlay.png")))
    if a.only:
        ids = [i for i in ids if any(fnmatch.fnmatch(i, g) for g in a.only.split(","))]
    if a.crops:
        os.makedirs(a.crops, exist_ok=True)
    rows = []
    for i in ids:
        np_ = os.path.join(a.outdir, i + suf)
        if not os.path.exists(np_):
            rows.append(dict(id=i, missing_file=True))
            continue
        r, n = load(os.path.join(a.refdir, i + suf)), load(np_)
        if r.shape != n.shape:
            hh, ww = min(r.shape[0], n.shape[0]), min(r.shape[1], n.shape[1])
            r, n = r[:hh, :ww], n[:hh, :ww]
        m, over = compare(r, n, a.census_min)
        m["id"] = i
        m["flags"] = flags(m)
        rows.append(m)
        if a.crops:
            box = m["blob_box"] or m["ref_bbox"] or (0, 0, r.shape[1] - 1, r.shape[0] - 1)
            crop_sheet(r, n, over, box, os.path.join(a.crops, i + (".overlay" if a.overlays else "") + ".blob.png"))
            ib = m["ref_bbox"] or m["nat_bbox"]
            if ib:
                ub = m["nat_bbox"] or ib
                u = (min(ib[0], ub[0]), min(ib[1], ub[1]), max(ib[2], ub[2]), max(ib[3], ub[3]))
                crop_sheet(r, n, over, u, os.path.join(a.crops, i + (".overlay" if a.overlays else "") + ".ink.png"), pad=8, scale=1)
    if a.json:
        json.dump(rows, open(a.json, "w"), indent=1)
    lines = ["| id | bbox ref | bbox native | off | d0 px | d24 px | d24 % | blob | colours ref/nat | missing (ref only) | extra (native only) | flags |",
             "|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for m in rows:
        if m.get("missing_file"):
            lines.append(f"| {m['id']} | | | | | | | | | | | not rendered |")
            continue
        fmt = lambda b: "-" if b is None else f"{b[0]},{b[1]},{b[2]},{b[3]}"
        cl = lambda xs: " ".join(f"{c}x{k}" for c, k in xs[:4]) + (f" +{len(xs) - 4}" if len(xs) > 4 else "")
        lines.append(f"| {m['id']} | {fmt(m['ref_bbox'])} | {fmt(m['nat_bbox'])} | {m['bbox_off']} | {m['d0']} | {m['d24']} | "
                     f"{m['pct24']:.3f} | {m['blob']} | {m['ref_major']}/{m['nat_major']} | {cl(m['missing'])} | {cl(m['extra'])} | {' '.join(m['flags'])} |")
    text = "\n".join(lines) + "\n"
    if a.md:
        open(a.md, "w").write(text)
    else:
        print(text)
    fl = [m for m in rows if m.get("flags") or m.get("missing_file")]
    print(f"{len(rows)} compared, {len(fl)} flagged", file=sys.stderr)
    for m in fl:
        print(f"  {m['id']}: {m.get('flags', 'not rendered')} blob={m.get('blob')} off={m.get('bbox_off')} "
              f"miss={m.get('missing', [])[:3]} extra={m.get('extra', [])[:3]}", file=sys.stderr)


if __name__ == "__main__":
    sys.exit(main())
