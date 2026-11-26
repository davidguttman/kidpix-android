# Writes ref/scenarios/<group>.json (format: docs/SCENARIOS.md + ref/README.md). Re-run after editing;
# the JSON files are the contract, this script only saves typing.
# usage: uv run python scripts/ref_scenarios.py
import json, math, os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "ref", "scenarios")
CANVAS = {"w": 1074, "h": 798}
SEED = 1234

# palette indices (first palette): red, blue, green, magenta, orange, violet, pink, sky, maroon,
# dark green, black, purple
COLORS = [7, 17, 13, 23, 29, 20, 25, 19, 6, 11, 0, 22]


def color(i):
    return {"op": "color", "index": COLORS[i % len(COLORS)]}


def curve(x0=140, y0=170, x1=900, y1=640, n=28, amp=90):
    """Diagonal with a bend: what a brush stroke looks like."""
    pts = []
    for k in range(n):
        t = k / (n - 1)
        x = x0 + (x1 - x0) * t
        y = y0 + (y1 - y0) * t + amp * math.sin(t * 2 * math.pi)
        pts.append([round(x), round(y)])
    return pts


def drag(x0, y0, x1, y1, n=5):
    return [[round(x0 + (x1 - x0) * k / (n - 1)), round(y0 + (y1 - y0) * k / (n - 1))] for k in range(n)]


DRAG = drag(230, 210, 760, 590)
TIMED = "Draws on a timer while the button is held: points are fixed and dt is virtual, so the number of timer ticks is part of the scenario."


def sc(id, group, steps, base=False, note=None, overlays=False, **kw):
    d = {"id": id, "group": group, "base": base, "steps": steps}
    if note:
        d["note"] = note
    if overlays:
        d["overlays"] = True
    d.update(kw)
    return d


def write(group, scenarios, note=None):
    doc = {"canvas": CANVAS, "seed": SEED, "base": "base.png", "dt": 16}
    if note:
        doc["note"] = note
    doc["scenarios"] = scenarios
    with open(os.path.join(OUT, group + ".json"), "w") as f:
        json.dump(doc, f, indent=1)
        f.write("\n")
    print(f"{group}: {len(scenarios)}")


def T(id):
    return {"op": "tool", "id": id}


def S(i, **kw):
    d = {"op": "sub", "index": i}
    d.update(kw)
    return d


def stroke(pts):
    return {"op": "stroke", "points": pts}


def tap(x, y):
    return {"op": "tap", "points": [[x, y]]}


PENCIL_TEX = ["solid", "partial1", "partial2", "partial3", "partial-artifact", "speckles", "stripes", "thatch",
              "shingles", "bubbles", "diamond", "ribbon", "sand", "brick", "chevron", "stairs", "cross",
              "diag-brick", "corner-stair", "houndstooth", "rainbow"]
SIZES = [1, 5, 9, 13, 17, 25]


def pencil():
    out = [sc("pencil-boot", "pencil", [stroke(curve())], note="No presses: the pencil as the app boots (size 9, solid, black).")]
    for i, s in enumerate(SIZES):
        out.append(sc(f"pencil-size{s}", "pencil", [T("pencil"), S(i), color(i), stroke(curve())]))
    for j, name in enumerate(PENCIL_TEX):
        out.append(sc(f"pencil-tex-{name}", "pencil", [T("pencil"), S(5), S(7 + j), color(j), stroke(curve())]))
    out.append(sc("pencil-tex-rainbow-thin", "pencil", [T("pencil"), S(1), S(27), stroke(curve())],
                  note="Rainbow hue advances per segment."))
    out.append(sc("pencil-hover", "pencil", [T("pencil"), S(5), color(3), {"op": "hover", "points": [[500, 400], [520, 410]]}],
                  overlays=True, note="Nothing pressed: main stays blank; the overlay picture shows any preview."))
    out.append(sc("pencil-palette-next", "pencil", [T("pencil"), S(4), {"op": "palette", "dir": "next"}, {"op": "color", "index": 3},
                                                    stroke(curve(n=10)), {"op": "color", "index": 20}, stroke(curve(y0=300, y1=760, n=10))],
                  note="Palette paging: colors 3 and 20 of the second palette."))
    out.append(sc("pencil-palette-prev", "pencil", [T("pencil"), S(4), {"op": "palette", "dir": "prev"}, {"op": "color", "index": 5},
                                                    stroke(curve(n=10)), {"op": "palette", "dir": "prev"}, {"op": "color", "index": 9},
                                                    stroke(curve(y0=300, y1=760, n=10))],
                  note="Palette paging backwards (wraps to the last palette), then once more."))
    out.append(sc("pencil-all-colors", "pencil", [T("pencil"), S(3)] + sum(
        [[{"op": "color", "index": c}, stroke([[40 + c * 31, 120], [40 + c * 31, 680]])] for c in range(32)], []),
        note="Every button of the first palette, one vertical line each."))
    out.append(sc("pencil-shift", "pencil", [T("pencil"), S(2), color(1), {"op": "key", "name": "shift", "down": True},
                                             stroke(curve()), {"op": "key", "name": "shift", "down": False}],
                  note="Shift held: upstream doubles the scaling."))
    return out


def line():
    out = []
    for i, s in enumerate(SIZES):
        out.append(sc(f"line-size{s}", "line", [T("line"), S(i), color(i), stroke(DRAG)]))
    for j, name in enumerate(PENCIL_TEX):
        out.append(sc(f"line-tex-{name}", "line", [T("line"), S(5), S(7 + j), color(j), stroke(DRAG)]))
    out.append(sc("line-boot-size", "line", [T("line"), color(2), stroke(DRAG), stroke(drag(200, 600, 900, 150))],
                  note="Line without option presses; two lines."))
    return out


SHAPE_TEX = ["none", "solid", "partial1", "partial2", "partial3", "partial-artifact", "speckles", "stripes", "thatch",
             "shingles", "bubbles", "diamond", "ribbon", "sand", "brick", "chevron", "stairs", "cross", "diag-brick",
             "corner-stair", "houndstooth", "rainbow-grad"]


def shape(group):
    out = []
    for j, name in enumerate(SHAPE_TEX):
        out.append(sc(f"{group}-tex-{name}", group, [T(group), S(j), color(j), stroke(DRAG)]))
    out.append(sc(f"{group}-boot", group, [T(group), color(4), stroke(DRAG), stroke(drag(600, 150, 300, 700))],
                  note="No option press (upstream default texture); second drag goes up-left."))
    out.append(sc(f"{group}-shift", group, [T(group), S(1), color(5), {"op": "key", "name": "shift", "down": True},
                                            stroke(DRAG), {"op": "key", "name": "shift", "down": False}],
                  note="Shift held while dragging."))
    out.append(sc(f"{group}-on-base", group, [T(group), S(3), color(10), stroke(DRAG)], base=True))
    return out


BRUSH_NAMES = ["leaky-pen", "zig-zag", "dots", "bubbly", "pies", "echoes", "northern-lights", "fuzzer", "magnifier",
               "spray-paint", "pine-needles", "3d", "kaleidoscope", "connect-dots", "swirl", "rotating-dots",
               "inverter", "geometry", "xy-to-xy", "tree", "splatter", "starburst", "looper", "galaxy",
               "hugs-xs", "cards", "shapes", "paw-prints"]
BRUSH_ON_BASE = {7, 8, 16}  # these move/transform what is already there
BRUSH_TIMED = {0, 3, 7, 20}  # AnimBrush and Fuzzer tick on setTimeout while held


def brush():
    out = []
    for i, name in enumerate(BRUSH_NAMES):
        steps = [T("brush"), color(i), S(i), stroke(curve())]
        if name == "tree":  # grows up from the press point
            steps[-1] = tap(537, 700)
        elif name == "xy-to-xy":  # three presses: two corners, then the third point dragged
            steps[-1:] = [tap(200, 650), tap(537, 140), stroke(drag(860, 640, 880, 600, 4))]
        elif name == "magnifier":  # magnifies where the stroke ends
            steps[-1] = stroke(curve(x1=400, y1=330, n=14))
        note = TIMED if i in BRUSH_TIMED else None
        out.append(sc(f"brush-{i:02d}-{name}", "brush", steps, base=i in BRUSH_ON_BASE, note=note))
        if i in BRUSH_ON_BASE:
            pass
    # spray paint sizes: upstream only opens the spray submenu when the option is clicked with ctrl
    for j in range(6):
        out.append(sc(f"brush-09-spray{j + 2}", "brush", [T("brush"), color(j), S(9, ctrl=True), S(j), stroke(curve(n=40))],
                      note="Spray submenu: reached by clicking Spray Paint with ctrl held (sub op with \"ctrl\": true)."))
    out.append(sc("brush-boot", "brush", [T("brush"), color(3), stroke(curve())], note=TIMED + " Tool press alone picks the first brush."))
    out.append(sc("brush-02-dots-slow", "brush", [T("brush"), color(1), S(2), stroke(curve(n=8))], dt=120,
                  note="Same brush, 120 ms between points (spacing reacts to time via velocity)."))
    out.append(sc("brush-hover", "brush", [T("brush"), S(12), color(2), {"op": "hover", "points": [[500, 400], [530, 420]]}],
                  overlays=True))
    return out


JUMBLE_NAMES = ["invert", "raindrops", "checkerboard", "wallpaper", "venetian-blinds", "outliner", "shadow-boxes",
                "zoom-in", "broken-glass", "picture-in-picture", "highlighter", "pattern-maker", "wrap-around",
                "swirl", "pancake-stack"]
JUMBLE_UNIMPL = {5, 7, 8, 10, 12}


def jumble():
    out = []
    for i, name in enumerate(JUMBLE_NAMES):
        note = None
        if i in JUMBLE_UNIMPL:
            note = "Upstream gap: the option only plays the 'unimplemented' sound; the tool stays what the jumble button set (whole-canvas invert)."
        out.append(sc(f"jumble-{i:02d}-{name}", "jumble", [T("jumble"), S(i), stroke(drag(380, 300, 640, 520, 8))], base=True, note=note))
    out.append(sc("jumble-boot", "jumble", [T("jumble"), tap(537, 399)], base=True, note="Jumble button alone: invert."))
    out.append(sc("jumble-01-raindrops-hold", "jumble", [T("jumble"), S(1), color(1), stroke([[537, 399]] * 30)],
                  base=True, note=TIMED))
    out.append(sc("jumble-00-invert-twice", "jumble", [T("jumble"), S(0), tap(300, 300), tap(700, 500)], base=True))
    return out


FLOOD_NAMES = SHAPE_TEX[1:-1] + ["rainbow-grad"]


def flood():
    out = []
    for j, name in enumerate(FLOOD_NAMES):
        out.append(sc(f"flood-tex-{name}", "flood", [T("flood"), S(j), color(j), tap(470, 470), color(j + 3), tap(537, 230)], base=True,
                      note="Two fills on the base: inside the blue square, then the white middle."))
    out.append(sc("flood-blank", "flood", [T("flood"), color(1), tap(500, 400)], note="Fill an empty canvas."))
    out.append(sc("flood-boot", "flood", [T("flood"), color(6), tap(780, 520)], base=True, note="No option press: default texture."))
    return out


ERASER_NAMES = ["square20", "circle10", "square10", "square2", "firecracker", "hidden-pictures", "white-circles",
                "slip-sliding", "letters", "fade-away", "black-hole", "count-down"]


def eraser():
    out = []
    for i, name in enumerate(ERASER_NAMES):
        if i in (4, 7):  # one press starts an animation
            steps = [T("eraser"), S(i), tap(537, 420)]
        else:
            steps = [T("eraser"), S(i), stroke(curve())]
        note = None
        if i in (4, 7, 8, 9):
            note = "Animated: the picture is taken after every timer it started has finished."
        out.append(sc(f"eraser-{i:02d}-{name}", "eraser", steps, base=True, note=note))
    # these end on a cleared canvas; also take the picture part way through ("settle": false)
    for i, ms in ((4, 700), (7, 800), (8, 0), (9, 1300)):
        name = ERASER_NAMES[i]
        if i == 8:  # letters fly while the button is held; lifting clears everything
            steps = [T("eraser"), S(i), {"op": "stroke", "points": curve()[:14], "up": False}]
        else:
            steps = [T("eraser"), S(i), tap(537, 420), {"op": "wait", "ms": ms}]
        out.append(sc(f"eraser-{i:02d}-{name}-mid", "eraser", steps, base=True, overlays=True, settle=False,
                      note="Picture taken without running the remaining timers (settle false): the animation part way."))
    out.append(sc("eraser-boot", "eraser", [T("eraser"), stroke(curve())], base=True, note="Tool press alone: upstream's plain eraser (or the remembered option)."))
    out.append(sc("eraser-00-blank", "eraser", [T("eraser"), S(0), stroke(curve())], note="Erasing nothing."))
    out.append(sc("eraser-07-slip-sliding-tap2", "eraser", [T("eraser"), S(7), tap(300, 300), tap(800, 600)], base=True))
    out.append(sc("eraser-remembered", "eraser", [T("eraser"), S(1), T("pencil"), T("eraser"), stroke(curve())], base=True,
                  note="Android overlay: coming back to the eraser re-applies the remembered option (circle 10)."))
    return out


def alphabet():
    out = []
    for page in (1, 2):
        steps = [T("alphabet")] + [{"op": "letter", "index": 26}] * (page - 1)
        for i in range(26):
            steps += [color(i), {"op": "letter", "index": i}, tap(70 + (i % 9) * 115, 130 + (i // 9) * 230)]
        out.append(sc(f"alphabet-page{page}", "alphabet", steps, note=f"Every letter of page {page}, each in another color; index 26 is the next-page arrow."))
    out.append(sc("alphabet-boot", "alphabet", [T("alphabet"), tap(537, 399)], note="Tool press alone: first letter of page 1, current color (black)."))
    out.append(sc("alphabet-wrap", "alphabet", [T("alphabet"), {"op": "letter", "index": 26}, {"op": "letter", "index": 26},
                                                {"op": "letter", "index": 3}, color(1), tap(537, 399)],
                  note="Next page twice wraps back to page 1."))
    out.append(sc("alphabet-hover", "alphabet", [T("alphabet"), {"op": "letter", "index": 10}, color(2),
                                                 {"op": "hover", "points": [[500, 400], [540, 420]]}], overlays=True))
    out.append(sc("alphabet-on-base", "alphabet", [T("alphabet"), {"op": "letter", "index": 7}, color(9), tap(470, 450), tap(780, 520)], base=True))
    return out


def stamp():
    out = []
    # 10 stamp packs x 8 rows; every sprite of a page in a 7x2 grid
    for pack in range(10):
        for row in range(8):
            steps = [T("stamp")] + [S(15)] * pack + [S(14)] * row
            for j in range(14):
                steps += [S(j), tap(90 + (j % 7) * 150, 260 + (j // 7) * 300)]
            out.append(sc(f"stamp-pack{pack}-row{row}", "stamp", steps,
                          note="Index 14 = next page (row), 15 = next stamp pack; every sprite on the page stamped once."))
    out.append(sc("stamp-boot", "stamp", [T("stamp"), tap(537, 399)], note="Tool press alone: first stamp."))
    out.append(sc("stamp-row-wrap", "stamp", [T("stamp")] + [S(14)] * 8 + [S(2), tap(537, 399)], note="Next page 8 times wraps to row 0."))
    out.append(sc("stamp-pack-wrap", "stamp", [T("stamp")] + [S(15)] * 10 + [S(4), tap(537, 399)], note="Next pack 10 times wraps to pack 0."))
    out.append(sc("stamp-drag", "stamp", [T("stamp"), S(5), stroke(curve(n=12))], note="Dragging a stamp."))
    out.append(sc("stamp-hover", "stamp", [T("stamp"), S(6), {"op": "hover", "points": [[500, 400], [530, 410]]}], overlays=True))
    out.append(sc("stamp-on-base", "stamp", [T("stamp"), S(8), tap(470, 450), tap(780, 520)], base=True))
    stk = [{"op": "key", "name": "cmd", "down": True}, T("stamp"), {"op": "key", "name": "cmd", "down": False}]
    for j in range(6):
        stk += [S(j), tap(150 + (j % 3) * 330, 230 + (j // 3) * 330)]
    out.append(sc("stamp-stickers", "stamp", stk, note="Stickers: stamp button pressed with cmd held (upstream modifiedCtrl); all six stickers."))
    return out


TRUCK = [(50, 50), (25, 25), (10, 10), (5, 5), (1, 1), (50, 25), (25, 10), (10, 5), (5, 2), (25, 50), (10, 25), (5, 10), (2, 5)]


def truck():
    out = []
    for i, (l, w) in enumerate(TRUCK):
        out.append(sc(f"truck-{i:02d}-{l}x{w}", "truck", [T("truck"), S(i), stroke(drag(470, 430, 640, 260, 6))], base=True,
                      note="Drag from the blue square up-right."))
    out.append(sc("truck-13-unimpl", "truck", [T("truck"), S(13), stroke(drag(470, 430, 640, 260, 6))], base=True,
                  note="Upstream gap: option 13 only plays the 'unimplemented' sound; the van keeps its previous size (50x50)."))
    out.append(sc("truck-boot", "truck", [T("truck"), stroke(drag(780, 520, 300, 300, 6))], base=True))
    out.append(sc("truck-twice", "truck", [T("truck"), S(1), stroke(drag(340, 320, 700, 300, 6)), stroke(drag(700, 300, 600, 650, 6))], base=True))
    return out


def undo():
    U = T("undo")
    W = {"op": "wait", "ms": 400}
    p = [T("pencil"), S(5)]
    a, b, c = stroke(curve(n=10)), stroke(curve(y0=320, y1=760, n=10)), stroke(curve(y0=60, y1=500, n=10))
    out = [
        sc("undo-one", "undo", p + [color(0), a, U, W], note="Undo Guy: a single tap acts after 350 ms (double-tap window); undo back to blank."),
        sc("undo-two-strokes", "undo", p + [color(0), a, color(1), b, U, W]),
        sc("undo-twice", "undo", p + [color(0), a, color(1), b, color(2), c, U, W, U, W]),
        sc("undo-redo", "undo", p + [color(0), a, color(1), b, U, W, U, U],
           note="Undo, then a double tap (two presses inside 350 ms) redoes."),
        sc("undo-nothing", "undo", [U, W], base=True, note="Nothing in the history: the base picture stays."),
        sc("undo-base-stroke", "undo", p + [color(3), a, U, W], base=True, note="Base is not in the history; undo returns to it."),
        sc("undo-after-new-stroke", "undo", p + [color(0), a, U, W, color(4), b, U, U],
           note="New stroke after undo clears redo: the double tap has nothing to redo."),
        sc("undo-eraser", "undo", [T("eraser"), S(0), stroke(curve()), U, W], base=True),
        sc("undo-tnt", "undo", [T("eraser"), S(4), tap(537, 420), {"op": "wait", "ms": 3000}, U, W], base=True, note="Undo after the firecracker finished."),
        sc("undo-flood", "undo", [T("flood"), S(0), color(1), tap(470, 470), color(2), tap(537, 230), U, W], base=True),
        sc("undo-mixer", "undo", [T("jumble"), S(0), tap(537, 399), U, W], base=True),
    ]
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    write("pencil", pencil())
    write("line", line())
    write("square", shape("square"))
    write("circle", shape("circle"))
    write("brush", brush())
    write("jumble", jumble())
    write("flood", flood())
    write("eraser", eraser())
    write("alphabet", alphabet())
    write("stamp", stamp())
    write("truck", truck())
    write("undo", undo())


main()
