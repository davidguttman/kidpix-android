# Checks ref/scenarios/*.json (and ref/base.json) against the scenario format in docs/SCENARIOS.md plus the
# extensions listed in ref/README.md. usage: uv run python scripts/ref_validate.py
import glob, json, os, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS = {"pencil", "line", "square", "circle", "brush", "jumble", "flood", "eraser", "alphabet", "stamp", "truck", "undo"}
KEYS = {"shift", "alt", "ctrl", "cmd", "tilde", "space"}
STEP_FIELDS = {
    "tool": ({"id"}, set()), "sub": ({"index"}, {"ctrl"}), "letter": ({"index"}, set()),
    "color": ({"index"}, set()), "palette": ({"dir"}, set()), "stroke": ({"points"}, {"up"}),
    "tap": ({"points"}, set()), "hover": ({"points"}, set()), "key": ({"name"}, {"down"}), "wait": ({"ms"}, set()),
}
SC_FIELDS = {"id", "group", "base", "steps", "note", "overlays", "dt", "settle", "seed", "keep_opening"}
errs = []


def err(where, msg):
    errs.append(f"{where}: {msg}")


ids = set()
files = sorted(glob.glob(os.path.join(ROOT, "ref", "scenarios", "*.json"))) + [os.path.join(ROOT, "ref", "base.json")]
for f in files:
    name = os.path.relpath(f, ROOT)
    doc = json.load(open(f))
    for k in doc:
        if k not in {"canvas", "seed", "base", "dt", "note", "scenarios"}:
            err(name, f"unknown top-level field {k}")
    if doc.get("canvas") != {"w": 1074, "h": 798}:
        err(name, "canvas must be 1074x798")
    W, H = 1074, 798
    for sc in doc["scenarios"]:
        w = f"{name}:{sc.get('id')}"
        if sc.get("id") in ids:
            err(w, "duplicate id")
        ids.add(sc.get("id"))
        for k in sc:
            if k not in SC_FIELDS:
                err(w, f"unknown field {k}")
        if f.endswith("base.json"):
            pass
        elif sc.get("group") not in TOOLS or not os.path.basename(f).startswith(sc["group"]):
            err(w, f"group {sc.get('group')} does not match file")
        if not isinstance(sc.get("steps"), list):
            err(w, "steps missing")
            continue
        for i, st in enumerate(sc["steps"]):
            op = st.get("op")
            if op not in STEP_FIELDS:
                err(w, f"step {i}: unknown op {op}")
                continue
            req, opt = STEP_FIELDS[op]
            if not req <= set(st):
                err(w, f"step {i}: {op} needs {req}")
            extra = set(st) - req - opt - {"op"}
            if extra:
                err(w, f"step {i}: unknown fields {extra}")
            if op == "tool" and st["id"] not in TOOLS:
                err(w, f"step {i}: unknown tool {st['id']}")
            if op == "palette" and st["dir"] not in ("next", "prev"):
                err(w, f"step {i}: dir")
            if op == "key" and st["name"] not in KEYS and len(st["name"]) != 1:
                err(w, f"step {i}: key {st['name']}")
            if op in ("stroke", "tap", "hover"):
                pts = st["points"]
                if not pts or (op == "tap" and len(pts) != 1):
                    err(w, f"step {i}: points")
                for p in pts:
                    if not (len(p) == 2 and all(isinstance(v, int) for v in p) and 0 <= p[0] < W and 0 <= p[1] < H):
                        err(w, f"step {i}: bad point {p}")
for e in errs:
    print(e)
print(f"{len(files)} files, {len(ids)} scenarios, {len(errs)} problems")
sys.exit(1 if errs else 0)
