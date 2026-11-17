#!/bin/bash
# Builds the JS Kid Pix web app from upstream/ plus overlay/ into the APK assets.
# Mirrors upstream build.sh (same file order) without needing npm.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UP="$ROOT/upstream"
OUT="$ROOT/app/src/main/assets/kidpix"

rm -rf "$OUT"
mkdir -p "$OUT/js"
cp -r "$UP/css" "$UP/img" "$UP/sndmp3" "$UP/static" "$OUT/"
cp "$UP/LICENSE" "$UP/NOTICE" "$OUT/"

(
  cd "$UP"
  for f in js/init/* js/util/* js/tools/* js/textures/* js/submenus/* \
           js/brushes/* js/builders/* js/stamps/* js/sounds/*; do
    cat "$f"
    printf '\n;\n'
  done
) > "$OUT/js/app.js"

cp "$ROOT/overlay/android.js" "$OUT/js/android.js"
cp "$ROOT/overlay/android.css" "$OUT/css/android.css"

# Inject overlay after app.js / kidpix.css, swap the boot hook, lock the viewport.
python3 -I - "$UP/index.html" "$OUT/index.html" <<'PY'
import sys
src, dst = sys.argv[1], sys.argv[2]
html = open(src, encoding="utf-8").read()
def sub(old, new):
    global html
    assert old in html, old
    html = html.replace(old, new, 1)
sub('<script type="text/javascript" src="js/app.js"></script>',
    '<script type="text/javascript" src="js/app.js"></script>\n'
    '  <script type="text/javascript" src="js/android.js"></script>')
sub('<link rel="stylesheet" type="text/css" href="css/kidpix.css">',
    '<link rel="stylesheet" type="text/css" href="css/kidpix.css">\n'
    '  <link rel="stylesheet" type="text/css" href="css/android.css">')
sub('<meta name="viewport" content="width=device-width, initial-scale=1.0">',
    '<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">')
sub('<body onload="init_kiddo_paint();">', '<body onload="kidpix_android_boot();">')
open(dst, "w", encoding="utf-8").write(html)
PY

echo "built $(du -sh "$OUT" | cut -f1) into $OUT"
