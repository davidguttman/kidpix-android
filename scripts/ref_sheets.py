# Contact sheets: ref/sheets/<group>.png, every picture of a group labelled by id.
# usage: uv run --with pillow python scripts/ref_sheets.py [out-dir] [sheets-dir]
import glob, json, os, sys
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "ref", "out")
SHEETS = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, "ref", "sheets")
TW, TH, LH, COLS, MAXN = 358, 266, 18, 4, 24

try:
    FONT = ImageFont.truetype("DejaVuSans.ttf", 13)
except OSError:
    FONT = ImageFont.load_default()

os.makedirs(SHEETS, exist_ok=True)
for f in sorted(glob.glob(os.path.join(ROOT, "ref", "scenarios", "*.json"))):
    group = os.path.basename(f)[:-5]
    ids = [s["id"] for s in json.load(open(f))["scenarios"]]
    items = []
    for i in ids:
        for suffix in (".png", ".overlay.png"):
            p = os.path.join(OUT, i + suffix)
            if os.path.exists(p):
                items.append((i + ("" if suffix == ".png" else " [overlay]"), p))
    chunks = [items[k:k + MAXN] for k in range(0, len(items), MAXN)] or [[]]
    for ci, chunk in enumerate(chunks):
        rows = (len(chunk) + COLS - 1) // COLS
        sheet = Image.new("RGB", (COLS * (TW + 6) + 6, rows * (TH + LH + 6) + 6), (90, 90, 90))
        d = ImageDraw.Draw(sheet)
        for k, (label, p) in enumerate(chunk):
            x, y = 6 + (k % COLS) * (TW + 6), 6 + (k // COLS) * (TH + LH + 6)
            im = Image.open(p).convert("RGB")
            im.thumbnail((TW, TH), Image.LANCZOS)
            sheet.paste(im, (x, y + LH))
            d.rectangle([x, y, x + TW - 1, y + LH - 1], fill=(30, 30, 30))
            d.text((x + 3, y + 2), label, fill=(255, 255, 255), font=FONT)
        name = group if len(chunks) == 1 else f"{group}-{ci + 1}"
        sheet.save(os.path.join(SHEETS, name + ".png"), optimize=True)
        print(name, len(chunk))
