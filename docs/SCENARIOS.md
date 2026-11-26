# Scenario files

Scripted drawings used to compare the native app with the WebView app and to test replays.
Extensions and the capture procedure are in `ref/README.md`.

## Format

```json
{ "canvas": {"w": 1136, "h": 798}, "seed": 1234, "base": "base.png",
  "scenarios": [
    { "id": "pencil-size3-partial2", "group": "pencil", "base": false, "steps": [
      {"op": "tool", "id": "pencil"},
      {"op": "sub", "index": 2},
      {"op": "color", "index": 5},
      {"op": "stroke", "points": [[100,100],[160,140],[300,220]]},
      {"op": "hover", "points": [[400,300]]},
      {"op": "wait", "ms": 300}
    ]}
  ]}
```

- Every scenario starts from a cleared canvas (or the base picture when `"base": true`), default
  state (as after `init_kiddo_defaults`, pencil selected), and the random generator reseeded.
- `tool`: press the main toolbar button with that id from upstream `index.html`
  (`pencil line square circle brush jumble flood eraser alphabet stamp truck undo`).
- `sub`: press the button at that index of the generic submenu now showing (index into
  `KiddoPaint.Submenu[name]`, spacers counted). `letter`: same for the alphabet bar.
- `color`: press palette button `index`. `palette`: `{"op":"palette","dir":"next"}`.
- `stroke`: mousedown at the first point, mousemove through the rest, mouseup at the last. Whole
  pixels. `hover`: mousemove with nothing pressed. `tap` = stroke with one point.
- `key`: `{"op":"key","name":"shift","down":true}` for upstream's modifier keys (optional use).
- `wait`: let timers and animations run for that long (virtual time in the native harness).
- The result is the main canvas drawn over white, saved as `<id>.png`. A scenario may add
  `"overlays": true` to also save preview+anim layers composited (`<id>.overlay.png`).

