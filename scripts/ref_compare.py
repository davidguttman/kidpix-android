# Compares two capture runs picture by picture and records the result in the first run's
# manifest: "deterministic": true/false, plus "diff_pixels" when they differ.
# usage: uv run --with pillow --with numpy python scripts/ref_compare.py ref/out /tmp/run2
import json, os, sys
import numpy as np
from PIL import Image

a, b = sys.argv[1], sys.argv[2]
man_path = os.path.join(a, "manifest.json")
man = json.load(open(man_path))
other = json.load(open(os.path.join(b, "manifest.json")))
bad = []
for sid, e in sorted(man.items()):
    diffs = 0
    for suffix in (".png", ".overlay.png"):
        pa, pb = os.path.join(a, sid + suffix), os.path.join(b, sid + suffix)
        if not os.path.exists(pa):
            continue
        if not os.path.exists(pb):
            diffs = -1
            break
        ia = np.asarray(Image.open(pa).convert("RGBA")).astype(int)
        ib = np.asarray(Image.open(pb).convert("RGBA")).astype(int)
        diffs += int((np.abs(ia - ib).sum(2) > 0).sum()) if ia.shape == ib.shape else ia.shape[0] * ia.shape[1]
    same_errors = e.get("errors") == other.get(sid, {}).get("errors")
    e["deterministic"] = diffs == 0 and same_errors
    e.pop("diff_pixels", None)
    if diffs:
        e["diff_pixels"] = diffs
    if not e["deterministic"]:
        bad.append((sid, diffs, same_errors))
json.dump(man, open(man_path, "w"), indent=1, sort_keys=True)
print(f"{len(man)} scenarios, {len(bad)} differ between runs")
for sid, d, se in bad:
    print(f"  {sid}: {d} pixels{'' if se else ', errors differ'}")
