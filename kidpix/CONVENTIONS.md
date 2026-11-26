# Porting conventions (read before touching a file)

Translation, not redesign: one upstream file becomes one Kotlin file, close to line for line, same
names. Upstream JS (read-only) lives in the main checkout at `upstream/js`.

## Files, package, ownership

- `js/x/y.js` becomes `kidpix/src/main/java/dev/kidpix/paint/x/Y.kt`; dashes become PascalCase
  (`tools/eraser-doorbell.js` becomes `tools/EraserDoorbell.kt`).
- Every ported file is in package `dev.kidpix.paint` (one package, like JS globals: no imports
  between ported files) and starts with `@file:JvmName("<dir>_<Name>")`, for example
  `@file:JvmName("tools_EraserDoorbell")`. Without it, `tools/Circle.kt` and `submenus/Circle.kt`
  clash on the JVM.
- You edit only your piece's files. Stubs marked `STUB for js/... (piece: X)` belong to piece X:
  replace the body but keep the public names and types the stub declares (other code calls them).
- Shared, do not edit (ask the supervisor instead): `canvas/`, `engine/`, `init/`, `harness/`,
  `app/`, `util/Display.kt`, `util/Utils.kt`, `util/Colors.kt`, `util/Cache.kt`, `util/Array.kt`,
  `textures/`, `tools/Pixelpencil.kt`, `submenus/Pencil.kt`, `sounds/Sounds.kt` (piece 7 adds a
  `SoundPlayer` implementation in a new file and may fill `KSounds`), `build.gradle.kts`.
- Util files whose owner is not obvious: `util/filters.js` (mixer; `gcoInvert`/`gcoOverlay` are
  already ported), `util/dither.js` and the glfx bridge (mixer), `util/trim-canvas.js`,
  `util/smooth.js`, `util/fit-curve.js`, `util/kdtree.js`, `util/smoke.js` (brushes).

## Registration (no central list to edit)

```kotlin
// tools/X.kt: a tool. JS: KiddoPaint.Tools.Toolbox.X = function() {...}; KiddoPaint.Tools.X = new ...
class ToolboxX : Tool() { val tool = this; var size = 7.0; override fun mousedown(ev: Ev) { ... } }
val KiddoPaint.Tools.X by world { ToolboxX() }

// brushes/bubbles.js: a function on the namespace object
fun KiddoPaint.Brushes.Bubbles(color1: String?, ...): BrushResult { ... }

// submenus/line.js: the 12 submenu names are fixed and already declared; fill in yours
var KiddoPaint.Submenu.line: MutableList<SubmenuItem> by world { mutableListOf(SubmenuItem(...)) }
```

`by world { }` makes a per-page-load singleton: `KiddoPaint.resetWorld()` (each scenario, each
app start) creates fresh tools, like reloading the page. Call sites read like the JS:
`KiddoPaint.Tools.Pencil.size = 9.0`, `KiddoPaint.Brushes.Bubbles(color)`,
`KiddoPaint.Submenu["line"]`. To keep extra state on a shared object (rare), declare
`var KCurrent.foo: T by world { init }` in your own file. `KiddoPaint.Display/Colors/Current/
Cache/Stamps/Alphabet/Sprite` are the objects `KDisplay`, `KColors`, `KCurrent`, `KCache`,
`KStamps`, `KAlphabet`, `KSprite`.

## Worked examples

The pencil is the reference port: compare `upstream/js/tools/pixelpencil.js` with
`tools/Pixelpencil.kt`, and `js/submenus/pencil.js` with `submenus/Pencil.kt`. A submenu entry:

```kotlin
SubmenuItem(
    name = "Size 10",
    imgSrc = "img/tool-submenu-pencil-size-3.png",   // or imgJs = { makeIcon(KiddoPaint.Textures::Solid) }
    handler = { KiddoPaint.Tools.Pencil.size = 9.0 }, // `e ->` when the JS reads the event
)
```

Spacers are `SubmenuItem(name = "spacer", invisible = true)`. `e.type` is `"click"` or
`"contextmenu"` (long press / right click), `e.which` 1 or 3. `imgJs` returns a canvas, not a
data URL (`makeIcon`, `makeCircleIcon` return a `KCanvas`).

A brush (JS returns `{brush: canvas, offset: n}` objects): declare a small class next to it, or
reuse the shape the first caller needs; brush and tool workers agree through the stub you leave.

## JS idioms

| JS | Kotlin |
|---|---|
| `var tool = this;` closures over tool state | `val tool = this` in the class; fields are `var` |
| `this.mousemove = function(ev)` | `override fun mousemove(ev: Ev)`; reassigned handlers become `var f: (Ev) -> Unit` |
| numbers | `Double` by default; `Int` only for indices/counters. `Int * Double` works in Kotlin, assignment does not convert |
| `Math.round(x)` | `jsRound(x)` (floor(x + 0.5); Kotlin's `round` is half-even, `roundToInt` differs on -0.5) |
| `%` | Kotlin `%` keeps the dividend's sign like JS; never `.mod()` |
| `x \| 0`, `~~x` | `x.toInt()` (truncates toward 0) |
| `a \|\| b` defaults | `a ?: b` (`color1 = color1 \|\| 'black'` becomes `val color1 = color1In ?: "black"`) |
| truthiness (`if (obj)`, `if (n)`) | explicit: `!= null`, `!= 0.0`, `isNotEmpty()` |
| optional args | default parameters; `undefined` becomes `null` |
| `{_x: 3, _y: 4}` events | `Ev(3, 4)` |
| `Math.random()` | `KRandom.random()` (seeded mulberry32; never kotlin.random) |
| `Date.now()` / `performance.now()` | `KTime.now()` / `KTime.performanceNow()` |
| `setTimeout(f, ms)` / `setInterval` / `requestAnimationFrame` | `KTime.setTimeout({ ... }, ms)` etc.; handles are `Int` |
| `document.createElement('canvas')` | `KCanvas()` (300x150 like the web); `KCanvas(w, h)` |
| `canvas.width = x` | clears and resets the context, as in the browser |
| `new Image(); img.src = ...; img.onload = ...` | `KImage()`; loads synchronously, `onload` fires on a 0 ms timer. Or `KAssets.loadBitmap(path)` |
| `canvas.toDataURL()` | keep the `KCanvas` (or `snapshot()`); there are no data URLs |
| `Array.prototype.random` | `list.random()`: util/Array.kt, uses KRandom (package members beat the stdlib `random()`) |
| `color2json(c)`, `hslToRgb`, `clamp`... | same names in util/Utils.kt |

## Ctx2D (canvas/Ctx2D.kt)

Same names and semantics as CanvasRenderingContext2D. Numeric arguments are `Number`, so
`ctx.fillRect(0, 0, 2.5, 1)` mixes freely. `fillStyle`/`strokeStyle` take a CSS color string, a
`CanvasPattern` or a `CanvasGradient` (invalid values and `null` are ignored, like the browser).
`drawImage` takes any `CanvasImageSource` (`KCanvas`, `KImage`, `BitmapSource`) or a `Bitmap`,
in the 3, 5 and 9 argument forms. `getImageData` returns straight-alpha RGBA `ImageData`;
`data[i]` reads an Int, writes clamp and round half to even like `Uint8ClampedArray`. All 26
composite operations exist; the unbounded ones (`source-in`, `source-out`, `destination-in`,
`destination-atop`, `copy`) clear outside the source like the browser. Fonts: `"<n>px <family>"`;
register a typeface with `Ctx2D.registerFont(name, typeface)`.

Known differences from Chrome: arcs are cubic Béziers, not conics (sub-pixel); anti-aliasing
coverage differs slightly at edges; `filter` supports only `blur(Npx)`; shadow blur is
approximate; non-concentric radial gradients need API 31+ (older devices fall back to concentric);
text metrics come from Android fonts.

## Sounds, UI, persistence

- Every `KiddoPaint.Sounds.x()` from sounds.js exists and calls `KSounds.player` (silent by default).
- Engine code never touches Views. The DOM side of upstream is the `Ui` interface (`KUi.ui`):
  `showSubmenu`, `showAlphabet`, `paletteChanged`, `currentColorChanged`, `cursorChanged`,
  `invalidate`, `save/open/exit`. Toolbar presses go through `press_main_tool(id)`,
  `press_submenu(i)`, `press_alphabet(i)`, `colorSelect(i)`, `color_next()/color_prev()`.
- `Display.saveUndo/undo/redo`: 30 steps with redo (`History`); `saveToLocalStorage` goes to
  the app's `PictureStore` (off the main thread); headless runs have no store.

## Headless harness

Env: `export JAVA_HOME=$HOME/.local/share/mise/installs/java/21 ANDROID_HOME=$HOME/Android/sdk`
(Gradle 8.13 does not run on the system JDK 27). Python only through `uv`.

```bash
./gradlew :kidpix:renderScenarios -Pscenarios=ref/scenarios/line.json -Pout=/tmp/out/line -Ponly='line-*'
uv run scripts/compare.py ref/out /tmp/out/line --only 'line-*'   # diff %, ink bboxes, sheets
./gradlew :kidpix:testDebugUnitTest                                 # unit tests + kidpix/scenarios/pencil.json
```

The runner (`harness/ScenarioRunner.kt`) follows `scripts/ref_capture.py`: fresh world per
scenario, seed, `dt` ms of virtual time before each pointer event (default 16), timers settled
for up to 60 s afterwards, main canvas over white saved as `<id>.png` (plus `<id>.overlay.png`
with `"overlays": true`), and a `manifest.json` with errors. A scenario that throws fails the
task after all PNGs are written. Put your own scenario files next to the reference ones
(`ref/scenarios/`) or in `kidpix/scenarios/<group>.json`.

## Before you say done

- `./gradlew :kidpix:assembleDebug :kidpix:testDebugUnitTest` pass.
- Your group renders headless and you looked at the PNGs; compare against `ref/` and explain any
  remaining differences.
- No edits outside your files; `git diff --stat` shows only them (plus your scenarios/notes).
