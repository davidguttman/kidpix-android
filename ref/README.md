# Reference pictures from the WebView app

`ref/scenarios/<group>.json` are scenario files (format: docs/SCENARIOS.md, plus the extensions below).
`ref/out/<id>.png` is what the WebView Kid Pix (app/ + overlay/, unchanged) drew for each
scenario; `ref/out/manifest.json` records per scenario the canvas size, the tool object in use at
the end, JS/console errors, how long timers ran after the steps, and whether two runs gave
identical pictures. `scripts/ref_sheets.py` makes contact sheets (`ref/sheets/`, not committed) for looking over a group.
`ref/base.png` is the base picture (`"base": true`), built by `ref/base.json`.

Canvas: **1074 x 798** (the 1280x800 tablet viewport minus the tool and option columns). That
is what the WebView gives on a 1280x800 screen; the docs/SCENARIOS.md example's 1136 was a guess.

## Re-running

```bash
export JAVA_HOME=<jdk 17..24> ANDROID_HOME=$HOME/Android/sdk   # gradle 8.13 does not run on JDK 27
./gradlew :app:assembleDebug
emulator -avd <tablet avd> -port 5570 -no-window -no-audio &
adb -s emulator-5570 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5570 shell wm size 1200x1920; adb -s emulator-5570 shell wm density 240
adb -s emulator-5570 shell settings put system accelerometer_rotation 0
adb -s emulator-5570 shell settings put system user_rotation 1
adb -s emulator-5570 shell am start -n dev.kidpix.tablet/.MainActivity
adb -s emulator-5570 forward tcp:9570 localabstract:webview_devtools_remote_$(adb -s emulator-5570 shell pidof dev.kidpix.tablet)

uv run python scripts/ref_scenarios.py                                    # regenerate scenario JSON (optional)
uv run --with websockets scripts/ref_capture.py 9570                      # all files -> ref/out (~7 min)
uv run --with websockets scripts/ref_capture.py 9570 --only brush-0,undo  # id prefixes
uv run --with websockets scripts/ref_capture.py 9570 --out /tmp/run2      # second run
uv run --with pillow --with numpy python scripts/ref_compare.py ref/out /tmp/run2   # marks "deterministic"
uv run --with pillow python scripts/ref_sheets.py                         # contact sheets
# base picture: ref_capture.py 9570 --out /tmp/b ref/base.json && cp /tmp/b/_base.png ref/base.png
```

## Random numbers: mulberry32

`Math.random` is replaced by this function, seeded with the scenario's `"seed"` (or the file's):

```js
function mulberry32(a) {
  return function() {
    a |= 0; a = a + 0x6D2B79F5 | 0;
    var t = Math.imul(a ^ a >>> 15, 1 | a);
    t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
    return ((t ^ t >>> 14) >>> 0) / 4294967296;
  };
}
Math.random = mulberry32(seed);
```

Kotlin, same numbers (32-bit Int arithmetic wraps like `|0` and `Math.imul`):

```kotlin
class Mulberry32(seed: Int) {
    private var a = seed
    fun next(): Double {
        a += 0x6D2B79F5
        var t = (a xor (a ushr 15)) * (1 or a)
        t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}
```

Check: seed 1234 gives 0.07329497812315822, 0.7034119898453355, 0.9028560190927237.

Everything that calls `Math.random` consumes from the one generator, **including sounds**:
`KiddoPaint.Sounds.Library.playRand` (used by `Sounds.oops`, `Sounds.explosion`,
`Sounds.bubblepops`) draws one number per call even with no audio device. So the Undo Guy (oops),
the firecracker (explosion), White Circles and Raindrops (bubblepops) each take one number per
sound. The native side must draw the same numbers at the same moments, sound or no sound.

## Reset before every scenario (exact procedure)

1. A hook is registered with `Page.addScriptToEvaluateOnNewDocument`, so it runs at document
   start, before the app's scripts. It removes `localStorage['kiddopaint']` (the overlay saves the
   old canvas when the page hides, which boot would otherwise restore), installs the error/console
   capture, the image-load tracker and the switchable virtual clock (off at first: boot runs on
   real time).
2. `Page.reload`. The app boots exactly as on the tablet: `kidpix_android_boot()` sizes the
   canvas, runs `init_kiddo_paint()` (which calls `init_kiddo_defaults()` and shows the pencil
   submenu), and the overlay sets `Pencil.size = 9`, solid texture, and its option memory to
   nothing remembered except pencil size 9 / solid.
3. Wait until every pending image load has finished (the opening page draws itself
   asynchronously) and two animation frames have passed.
4. Clear main, tmp (`Display.canvas`), preview, anim and bnim; empty the overlay's undo history
   (`past`, `future`, `restoring`) and upstream's `Display.undoData`.
5. If `"base": true`: draw `ref/base.png` at (0,0) on main, `imageSmoothingEnabled = false`.
   (`ref/base.json` alone uses `"keep_opening": true`: steps 4 leaves main as the opening page.)
6. Virtual clock on at t = 0 (`Date.now()` = 1700000000000 + t, `performance.now()` = t), timer
   queue empty, `Math.random = mulberry32(seed)`, then `init_kiddo_defaults()` once more so
   `prevEvTs` and the velocity state start on the virtual clock. Errors collected so far are
   dropped.

## Steps (as docs/SCENARIOS.md, with the details the driver fixes)

- **Presses** (`tool`, `sub`, `letter`, `color`, `palette`) dispatch `mousedown`, `mouseup`,
  `click` (button 0, so `which` = 1) on the innermost element of the button (the `<img>` or the
  letter's `<h1>`, where a finger lands; upstream's letter handler reads the letter from the event
  target). So both upstream's handlers and the Android overlay's (option memory, Undo Guy) run.
  Presses take no virtual time.
  - `tool`: `#<id>` of the main toolbar, `undo` included (the overlay's Undo Guy: one press waits
    350 ms for a second one; two presses in a row are the double tap = redo).
  - `sub`: `#genericsubmenu.children[index]` (spacers counted).
  - `letter`: `#alphabettoolbar button[index]`: 0..25 the letters, 26 the next-page arrow.
  - `color`: `#colorselector.children[index]`. `palette`: `#colornext` / `#colorprev`.
- **Pointer steps** call `ev_canvas` directly with a plain object: `type`, `offsetX/offsetY` = the
  whole-pixel point, `clientX/Y`/`pageX/Y` mapped to the screen, `which: 1`, `button: 0`,
  `buttons` 1 while pressed / 0 on mouseup and hover, `pointerType: 'mouse'`, no `pressure`, no
  modifier keys. Before **every** event the virtual clock advances by `dt` ms (default 16; file or
  scenario `"dt"` overrides) and runs the timers that fall due, in due-time order.
  `stroke` = mousedown(p0), mousemove(p1..pn), mouseup(pn). `tap` = mousedown(p), mouseup(p).
  `hover` = mousemove per point with nothing pressed. This bypasses the overlay's pointer layer,
  so pen pressure scaling and its eraser gap filling are not in the references.
- **`key`**: calls upstream's `document.onkeydown/onkeyup` with `{keyCode}`. Names: `shift` 16,
  `alt` 18, `ctrl` 17 (upstream's "meta" flag), `cmd` 91 (upstream's `modifiedCtrl`), `tilde`
  192, `space` 32, or a single letter.
- **`wait`**: advance the virtual clock by `ms`.
- After each step the driver waits (real time) for pending image loads and undo restores, so
  async image loads count as instant: the native side should load images synchronously.
- After the last step: **settle** = advance the clock in 100 ms slices, waiting for image loads in
  between, until no timer is pending (cap 60 s; none hit the cap). `settle_ms` in the manifest is
  how much virtual time that took (at most 2.5 s, the Fade Away eraser and the firecracker).
- Picture: white, then main. With `"overlays": true` also `<id>.overlay.png` = white, main, bnim,
  anim, preview, tmp (the on-screen stack, bottom to top).

### Virtual clock semantics (the native harness must match)

`setTimeout(f, d)`: due = now + max(0, d). `setInterval(f, d)`: every max(1, d) ms, first at
now + d. `requestAnimationFrame(cb)`: due = now + 16, `cb(now)`. Ties run in scheduling order.
Advancing to T runs every timer due at or before T (including ones scheduled while advancing)
with `now` set to its due time, then sets `now = T`.

### Format extensions (beyond docs/SCENARIOS.md)

| Where | Field | Meaning |
|---|---|---|
| file or scenario | `"dt": 16` | virtual ms before each pointer event |
| scenario | `"settle": false` | take the picture right after the steps, timers still pending (mid-animation pictures, `*-mid`) |
| scenario | `"note"` | free text |
| `sub` step | `"ctrl": true` | the click carries `ctrlKey` (upstream opens the spray submenu only on ctrl-click) |
| `stroke` step | `"up": false` | no mouseup at the end: the button stays held (`eraser-08-letters-mid`) |
| `tap` step | `"points": [[x, y]]` | one point, as `stroke` |
| scenario (base.json only) | `"keep_opening": true` | start from the opening page instead of a cleared canvas |

## Coverage

Every option of every submenu, read from `KiddoPaint.Submenu` in the live page:

| Group | Scenarios | Covers |
|---|---|---|
| pencil | 34 | 6 sizes, 21 textures, boot state, palette next/prev, all 32 colors, shift, hover |
| line | 28 | 6 sizes, 21 textures, boot |
| square, circle | 25 each | 22 textures, boot, shift, on base |
| brush | 37 | 28 wacky brushes, 6 spray sizes, boot, slow dots (dt 120), hover |
| jumble | 18 | 15 mixer options on the base, boot, raindrops held, invert twice |
| flood | 23 | 21 textures (two fills each on the base), blank canvas, boot |
| eraser | 20 | 12 erasers on the base, 4 mid-animation (`settle: false`), boot, blank, two slide-aways, remembered option |
| alphabet | 6 | every letter of both pages, boot, page wrap, hover, on base |
| stamp | 87 | every sprite of all 10 packs x 8 rows, row/pack wrap, boot, drag, hover, on base, all 6 stickers |
| truck | 16 | 14 sizes (13 is a gap), boot, two moves |
| undo | 11 | undo, two undos, undo+redo (double tap), nothing to undo, base, redo cleared by a new stroke, after eraser/firecracker/flood/mixer |

Options reached only with a keyboard (no tablet path): the spray submenu (ctrl-click on Spray
Paint), the stickers (stamp button with cmd held), previous stamp page / pack (right click,
not covered).

## Non-deterministic scenarios

**None.** Two full runs (330 scenarios) gave pixel-identical pictures and identical error lists;
every entry in `manifest.json` has `"deterministic": true`. What made the time- and load-dependent
ones stable: the virtual clock (Fuzzer, Leaky Pen, Bubbly, Splatter, Raindrops tick on
`setTimeout`; the firecracker, Slip-Sliding Away, Fade Away, Venetian Blinds, Wallpaper, Shadow
Boxes, Inverter run on timers/intervals; velocity uses `Date.now`), seeded `Math.random`, and
waiting for every image load (stamps, stickers, Hidden Pictures, Slip-Sliding Away, Pattern
Maker, undo restores) before the next step.

Captured on the emulator's Android System WebView, Chrome/124.0.6367.219 (Android 15 x86_64
image, swiftshader GPU). Antialiasing and fonts may differ slightly on other WebView versions.

## Upstream gaps (match them, don't fix them)

- Electric mixer options **5 The Outliner, 7 Zoom In, 8 Broken Glass, 10 The Highlighter,
  12 Wrap Around** only call `Sounds.unimpl()`. The tool stays what the jumble button set
  (`WholeCanvasEffect` with `JumbleFx.INVERT`), so dragging gives that effect's grey wash.
- Moving van option **13** only calls `Sounds.unimpl()`; the van keeps its size (50x50 at boot).
- The spray submenu (`KiddoPaint.Submenu.spray`, 6 sizes) opens only on a ctrl-click of Spray
  Paint; the stickers submenu only when the stamp button is pressed with cmd held.
- The firecracker, Slip-Sliding Away, #$%!*!! and Fade Away erasers end by clearing the whole
  picture (`Display.clearAll`); their final references are blank by design (`*-mid` show the
  animation). Undo right after a firecracker but before it finishes restores the old picture,
  then the animation carries on and clears it (why `undo-tnt` waits 3 s first).
- No option threw a JS error or logged a console error in the WebView (manifest `errors` all empty).
- Text (alphabet, #$%!*!! eraser) is drawn with the WebView's fonts; expect font differences.
