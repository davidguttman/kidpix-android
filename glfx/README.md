# :glfx — glfx.js on OpenGL ES 2.0

The WebGL effects of `upstream/js/util/glfx.js` (Evan Wallace, MIT) for the native Kid Pix,
running the **same GLSL** (WebGL 1 shaders are GLSL ES 1.00) in an offscreen EGL context.
Bitmap in, Bitmap out. Kotlin, namespace `dev.kidpix.glfx`, minSdk 29.

```kotlin
val gfx = Fx.canvas()                       // owns an EGL context: create once, reuse
val tex = gfx.texture(bitmap)               // or gfx.texture(rgbaBytes, w, h) for getImageData crops
val out: Bitmap = gfx.draw(tex).bulgePinch(x, y, r, s).update().toBitmap()
tex.destroy()
```

Callers translate line for line from JS: `tool.gfx.draw(tool.textureGfx).swirl(x, y, r, a).update()`
becomes `gfx.draw(tex).swirl(x, y, r, a).update()`, and `ctx.drawImage(renderedGfx, ...)` becomes
drawing `gfx.toBitmap(reuse)` (pass the previous bitmap back to avoid a 3.6 MB allocation per move).

## API

Every glfx.js method, same names and argument order (numbers are `Double`):

| Canvas | Filters |
|---|---|
| `texture(Bitmap)`, `texture(ByteArray, w, h)`, `draw(tex, w?, h?)`, `update()`, `contents()`, `getPixelArray()`, `toBitmap(reuse?)`, `destroy()`; `FxTexture.loadContentsOf(...)`, `FxTexture.destroy()`; `Fx.splineInterpolate(points)` | `brightnessContrast`, `curves`, `denoise`, `hueSaturation`, `noise`, `sepia`, `unsharpMask`, `vibrance`, `vignette`, `lensBlur`, `tiltShift`, `triangleBlur`, `zoomBlur`, `colorHalftone`, `dotScreen`, `edgeWork`, `hexagonalPixelate`, `ink`, `bulgePinch`, `matrixWarp`, `perspective`, `swirl` |

`curves` takes `List<DoubleArray>` control points (`[x, y]`); `matrixWarp` takes a flat
`DoubleArray` (4 or 9) or nested `Array<DoubleArray>`; `perspective` takes two 8-number quads.
`replace(element)` is DOM-only and has no equivalent.

Kept from glfx.js: no flip on upload (texture row 0 = top row), straight (non-premultiplied)
alpha inside, the ping-pong `texture`/`spareTexture`/`extraTexture` with glfx's `swapWith`
(which does not swap `type`), the shared `random()` GLSL function, float intermediate textures
when the context can render to them (the OES_texture_float probe and the
OES_texture_float_linear polyfill test), else the 8-bit fallback. On a GLES 3 context the float
textures use the sized `RGBA32F` format (WebGL 1 gets the same from Chrome behind unsized
`RGBA`/`FLOAT`).

Pixels: `texture(Bitmap)` un-premultiplies (`Bitmap.getPixels`, Skia, the same as Chrome's
upload from a canvas). `texture(bytes)` takes straight RGBA like `ImageData`. `update()` draws
into a top-down RGBA8 "drawing buffer" texture, clamping to 8 bits and premultiplying in the
same pass (what drawing the WebGL canvas onto a 2D canvas does); `toBitmap()` reads it into a
premultiplied ARGB_8888 bitmap with no CPU conversion.

## Threads

One thread owns a canvas: the one that called `Fx.canvas()`. Every call from any other thread
throws `IllegalStateException` naming both threads. Several canvases may share an owner thread
(each call makes its own context current, like glfx's `a = this._.gl`). In the app, run the mixer
on one dedicated GL thread (a `HandlerThread`), create the canvas there, and hand bitmaps across.
`destroy()` on the owner thread frees the context.

## Tests

`./gradlew :glfx:connectedDebugAndroidTest` (set `ANDROID_SERIAL` to pick a device). The
comparison test runs every case below twice on the device: natively, and through the real
glfx.js (a copy of upstream's file in the test assets) in a WebView inside the test APK, on the
committed test picture `src/androidTest/assets/testpic.png` (1136x798: gradients, black outlines,
a checkerboard, a transparent hole, an alpha ramp, shapes on transparency). Both sides get the
same decoded pixels (the WebView receives them as RGBA bytes via `putImageData`). PNGs (`*.native.png`,
`*.ref.png`, `*.diff.png` = worst channel x4) and `results.tsv` land in `glfx/build/renders/`.
`byte_*` files are the 8-bit fallback (forced natively, and OES_texture_float hidden from glfx.js).
Other tests: transparency round trip, ImageData bytes, wrong-thread failure, two canvases on one
thread, the spline.

Parameters are the ones `wholefx.js` (drawDistance-driven, centres (568,399) and (300,250)) and
`partialfx.js` (edgeWork(18) on a 72x72 getImageData crop, one crop running off the picture) use,
at two or three values each, plus one setting for every filter Kid Pix does not call.

## Results

Emulator `emulator-5574` (API 35 x86_64, `-gpu swiftshader_indirect`; both the native context and
the WebView render on SwiftShader, a CPU rasteriser, so times are far above a real tablet GPU's;
use them to compare filters, not as frame budgets). 2026-11-25.

Distance: per channel, premultiplied RGBA, 0–255, over all 906,528 pixels. "% px >16" = pixels
whose worst channel is off by more than 16. Native ms = `draw(tex).filter(...).update()` + `glFinish`,
averaged over 8 runs (30 for the crops); readback = `toBitmap(reuse)`; glfx.js ms = the same chain
in the WebView with a 1-pixel `readPixels` to sync.

| Case | Call | Mean dist | Max | % px >16 | 8-bit fallback mean / % >16 | Native ms (draw+filter+update) | Readback ms | glfx.js ms (WebView) |
|---|---|---|---|---|---|---|---|---|
| bulgePinch_pinch | `bulgePinch(568,399,200,-0.6)` | 0.007 | 3 | 0.000 | 0.007 / 0.000 | 3.62 | 1.56 | 7.96 |
| bulgePinch_bulge | `bulgePinch(300,250,200,0.6)` | 0.007 | 3 | 0.000 | 0.007 / 0.000 | 4.13 | 1.56 | 7.14 |
| bulgePinch_full | `bulgePinch(568,399,200,1)` | 0.007 | 3 | 0.000 | 0.007 / 0.000 | 3.12 | 1.23 | 6.46 |
| swirl_half | `swirl(568,399,150,3.141592653589793)` | 0.007 | 3 | 0.000 | 0.007 / 0.000 | 3.61 | 1.75 | 8.22 |
| swirl_back | `swirl(300,250,300,-6.283185307179586)` | 0.008 | 3 | 0.000 | 0.007 / 0.000 | 3.23 | 1.32 | 6.40 |
| lensBlur_10 | `lensBlur(10,0.88,0.70841)` | 0.002 | 2 | 0.000 | 0.003 / 0.000 | 111.71 | 2.19 | 114.10 |
| lensBlur_30 | `lensBlur(30,0.88,0.70841)` | 0.002 | 2 | 0.000 | 0.002 / 0.000 | 115.66 | 1.49 | 112.48 |
| triangleBlur_10 | `triangleBlur(10)` | 0.001 | 1 | 0.000 | 0.000 / 0.000 | 100.67 | 1.52 | 103.66 |
| triangleBlur_40 | `triangleBlur(40)` | 0.002 | 2 | 0.000 | 0.000 / 0.000 | 102.34 | 1.52 | 105.72 |
| zoomBlur_03 | `zoomBlur(568,399,0.3)` | 0.002 | 2 | 0.000 | 0.000 / 0.000 | 45.17 | 1.51 | 50.82 |
| zoomBlur_08 | `zoomBlur(300,250,0.8)` | 0.002 | 2 | 0.000 | 0.000 / 0.000 | 41.56 | 1.28 | 51.72 |
| hexagonalPixelate_5 | `hexagonalPixelate(568,399,5)` | 0.010 | 4 | 0.000 | 0.009 / 0.000 | 3.30 | 1.38 | 6.26 |
| hexagonalPixelate_20 | `hexagonalPixelate(300,250,20)` | 0.006 | 3 | 0.000 | 0.006 / 0.000 | 3.08 | 2.41 | 6.50 |
| ink_neg | `ink(-0.5)` | 0.021 | 18 | 0.000 | 0.017 / 0.000 | 22.13 | 1.70 | 26.16 |
| ink_025 | `ink(0.25)` | 0.010 | 3 | 0.000 | 0.010 / 0.000 | 23.03 | 1.26 | 23.60 |
| ink_06 | `ink(0.6)` | 0.021 | 21 | 0.000 | 0.014 / 0.000 | 23.20 | 1.42 | 23.78 |
| hue_neg | `hueSaturation(-0.6,0)` | 0.009 | 3 | 0.000 | 0.007 / 0.000 | 4.31 | 1.83 | 8.00 |
| hue_pos | `hueSaturation(0.4,0)` | 0.008 | 3 | 0.000 | 0.007 / 0.000 | 3.17 | 1.31 | 5.66 |
| sat_neg | `hueSaturation(0,-0.6)` | 0.006 | 3 | 0.000 | 0.006 / 0.000 | 3.55 | 1.36 | 6.10 |
| sat_pos | `hueSaturation(0,0.6)` | 0.009 | 3 | 0.000 | 0.008 / 0.000 | 3.14 | 1.46 | 6.34 |
| edgeWork_5 | `edgeWork(5)` | 0.432 | 255 | 0.246 | 0.290 / 0.173 | 107.41 | 1.75 | 107.62 |
| edgeWork_20 | `edgeWork(20)` | 0.059 | 255 | 0.024 | 0.107 / 0.044 | 106.28 | 1.82 | 113.28 |
| pancake | `brightnessContrast(0,0)` | 0.007 | 3 | 0.000 | 0.007 / 0.000 | 2.59 | 1.33 | 6.50 |
| partial_edgeWork | `edgeWork(18)` | 0.000 | 0 | 0.000 | 0.000 / 0.000 | 2.39 | 0.08 | 5.18 |
| partial_edgeWork_edge | `edgeWork(18)` | 0.000 | 0 | 0.000 | 0.000 / 0.000 | 2.41 | 0.09 | 4.92 |
| brightnessContrast | `brightnessContrast(0.2,0.4)` | 0.008 | 3 | 0.000 | - | 7.63 | 3.45 | 10.84 |
| brightnessContrast_neg | `brightnessContrast(-0.3,-0.5)` | 0.057 | 3 | 0.000 | - | 5.00 | 3.95 | 11.38 |
| denoise | `denoise(20)` | 0.007 | 4 | 0.000 | - | 222.11 | 2.11 | 256.52 |
| noise | `noise(0.5)` | 0.051 | 3 | 0.000 | - | 7.08 | 2.01 | 12.56 |
| sepia | `sepia(1)` | 0.007 | 4 | 0.000 | - | 5.00 | 2.32 | 12.74 |
| unsharpMask | `unsharpMask(20,2)` | 0.011 | 4 | 0.000 | - | 146.21 | 1.46 | 142.16 |
| vibrance | `vibrance(0.7)` | 0.007 | 3 | 0.000 | - | 4.93 | 1.96 | 11.20 |
| vignette | `vignette(0.5,0.6)` | 0.007 | 3 | 0.000 | - | 4.87 | 2.81 | 9.98 |
| tiltShift | `tiltShift(0,400,1136,380,15,200)` | 0.002 | 2 | 0.000 | - | 148.44 | 2.03 | 156.42 |
| colorHalftone | `colorHalftone(568,399,0.25,4)` | 0.071 | 4 | 0.000 | - | 13.51 | 2.26 | 16.90 |
| dotScreen | `dotScreen(568,399,1.1,3)` | 0.174 | 4 | 0.000 | - | 6.32 | 4.94 | 14.40 |
| curves | `curves([[0,0],[0.25,0.15],[0.5,0.6],[1,1]],[[0,0.1],[1,0.9]],[[0,0],[0.5,0.3],[1,1]])` | 0.008 | 3 | 0.000 | - | 6.29 | 2.98 | 16.14 |
| curves_single | `curves([[0,1],[0.5,0.3],[1,0]])` | 0.007 | 3 | 0.000 | - | 8.13 | 4.61 | 12.84 |
| matrixWarp | `matrixWarp([[1,0.2],[-0.1,0.9]],false,true)` | 0.007 | 3 | 0.000 | - | 5.93 | 2.60 | 16.84 |
| matrixWarp_inv3 | `matrixWarp([0.9,0.1,30,0.05,1.1,-20,0,0.0002,1],true,false)` | 0.002 | 3 | 0.000 | - | 5.84 | 2.51 | 8.86 |
| perspective | `perspective([175,156,496,55,161,279,504,330],[175,156,496,55,121,329,524,290])` | 0.005 | 3 | 0.000 | - | 3.28 | 1.92 | 7.00 |

### What does not match exactly, and why

- **Everything except ink and edgeWork**: max channel error 1–4, mean ≤ 0.07 (0.17 for dotScreen,
  whose pattern is `sin()` of big arguments). What's left is float rounding: `sin()`/`pow()` on
  two different SwiftShader builds (the emulator's GLES translator and Chrome's), and 8-bit
  rounding of low-alpha pixels.
- **ink** (max 18–21 on a few pixels): the shader multiplies the edge term by `strength⁵·100000`,
  so a last-bit difference in a texture sample decides whether a pixel turns black.
- **edgeWork** (mean 0.06–0.43, ~0.02–0.25 % of pixels flipped): pass 2 is
  `clamp(10000·(a−b)+0.5)`, a hard threshold on the difference of two blurred averages. Pixels
  sitting on the threshold flip between black and white on the smallest float difference. Here they
  are the alpha-ramp stripes and the gradient's noise texture. The pattern is otherwise the same
  (see `edgeWork_5.*.png`).
- **8-bit fallback**: the port matches glfx.js's own 8-bit path just as closely (column 6). Against
  glfx.js *with* float textures, the 8-bit path is far off for `lensBlur` (mean 11: the
  `pow(x, 7.6)` pre-pass needs float) and `edgeWork(5)` (mean 33). That is a property of glfx,
  not of the port: a tablet whose GPU cannot render to float textures shows the same in the web
  Kid Pix. `usesFloatTextures` tells which path a device took.
- **Chrome's `drawImage(<img>)` is not exact**: in this emulator's WebView, drawing the PNG onto a
  canvas bleeds 2–3 % alpha into transparent pixels next to hard edges (`(0,0,0,0)` became
  `(0,255,85,6)`). Fed that way, warps were up to 67 off on half-transparent edge pixels. This
  is why the test hands glfx.js the decoded bytes. `AlphaProbeTest` logs what the WebView does.

### Time per frame

On SwiftShader, the 61-tap blurs (`triangleBlur`, `edgeWork`, `tiltShift`, `unsharpMask`,
`lensBlur`) cost 100–150 ms per 1136x798 frame, `zoomBlur` about 45 ms, `ink` about 23 ms and
`denoise` (2x81 taps) about 220 ms. The warps and colour filters cost 3–8 ms. Readback adds about
2 ms. The blurs run at the same speed as glfx.js in the WebView (within ±5 %). The cheap filters
take about half glfx.js's time, because glfx.js pays a fixed per-call cost in the browser. On a tablet GPU the
blurs should fit a pointer-move budget. If they don't, the mixer can skip moves while a frame is in
flight: draw the latest pointer position only.
