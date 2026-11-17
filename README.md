# Kid Pix for Android tablets

[JS Kid Pix](https://github.com/vikrum/kidpix) (vikrum, GPL-3.0) packaged as an Android app with real pen support.
Upstream is a git submodule at `upstream/`, untouched. Everything Android lives in `overlay/` and `app/`.

## Pen support

Tested on the Lenovo TB336FU (`lenovo-stylus` digitizer: pressure, eraser tool, two side buttons).
Uses Pointer Events, so it works with any Android active pen (USI, MPP, Wacom AES/EMR, S Pen).

- **Pressure** changes line size for Pencil, the brushes and the eraser
- **Hover** shows tool previews (stamps, letters, eraser) under the pen tip without drawing
- **Side button 1 (held) or the eraser end**: erase, then the previous tool comes back
- **Side button 2**: undo
- **Palm rejection**: finger input on the canvas is ignored while the pen is touching, hovering, or for 0.8 s after it lifts. Fingers still draw when there's no pen.
- Pen samples are dispatched unbuffered and Pencil uses coalesced events for smooth lines
- If one pen-up event gets lost, the next stroke recovers instead of drawing freezing

Some Lenovo tablets bind side-button presses to system shortcuts in Settings → Pen. If a button opens notes or takes a screenshot, turn that shortcut off.

## Android bits

- Fullscreen landscape, tools on the left, tool options on the right, canvas sized to the screen
- **Save** writes a PNG to `Pictures/Kid Pix` (MediaStore, no permissions)
- The drawing autosaves (localStorage) and comes back after the app is closed
- External links are blocked so kids stay in the app
- If the WebView renderer dies, the app reloads instead of crashing

## Build

```bash
git submodule update --init
export JAVA_HOME=~/.local/share/mise/installs/java/21.0.2 ANDROID_HOME=~/Android/sdk
ANDROID_USER_HOME=~/dotfiles/config/.android ./gradlew assembleDebug   # same debug key as the other sideloaded apps
```

`scripts/build-web.sh` runs as part of `preBuild`: it concatenates upstream JS in upstream's
`build.sh` order (no npm) and injects `overlay/android.{js,css}` into the page.

## Tests (debug build, WebView debugging on)

```bash
adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof dev.kidpix.tablet)
uv run --with websockets scripts/pen_test.py 9333    # pressure, side buttons, eraser, palm, hover, stuck-stroke recovery
uv run --with websockets scripts/tool_smoke.py 9333  # every tool x every option, pen tap + stroke, no JS errors / hangs
```
