# Drives the debug WebView over CDP with synthetic *pen* and *touch* input and checks
# the Android pen layer: pressure, side-button eraser, undo button, palm rejection, hover.
# usage: uv run --with websockets scripts/pen_test.py <devtools-port>
import asyncio, json, sys, urllib.request
import websockets

PORT = sys.argv[1]
results = []


def check(name, ok, detail=""):
    results.append(ok)
    print(("PASS " if ok else "FAIL ") + name + (f"  ({detail})" if detail else ""))


async def main():
    ws_url = json.load(urllib.request.urlopen(f"http://localhost:{PORT}/json"))[0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=2**26) as ws:
        n = 0

        async def call(method, **params):
            nonlocal n
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params}))
            while True:
                m = json.loads(await ws.recv())
                if m.get("id") == n:
                    if "error" in m:
                        raise RuntimeError(m["error"])
                    return m.get("result", {})

        async def js(expr):
            r = await call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
            if "exceptionDetails" in r:
                raise RuntimeError(r["exceptionDetails"])
            return r["result"].get("value")

        async def pen(type_, x, y, force=0.5, buttons=1, button="left"):
            await call("Input.dispatchMouseEvent", type=type_, x=x, y=y, pointerType="pen",
                       force=force, buttons=buttons, button=button, clickCount=1 if type_ != "mouseMoved" else 0)

        async def pen_stroke(x0, y0, x1, y1, force=0.5, buttons=1, button="left", steps=12):
            await pen("mousePressed", x0, y0, force, buttons, button)
            for i in range(1, steps + 1):
                t = i / steps
                await pen("mouseMoved", x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, force, buttons, button)
            await pen("mouseReleased", x1, y1, force, 0, button if button != "none" else "left")

        async def touch_stroke(x0, y0, x1, y1, steps=10):
            await call("Input.dispatchTouchEvent", type="touchStart", touchPoints=[{"x": x0, "y": y0}])
            for i in range(1, steps + 1):
                t = i / steps
                await call("Input.dispatchTouchEvent", type="touchMove",
                           touchPoints=[{"x": x0 + (x1 - x0) * t, "y": y0 + (y1 - y0) * t}])
            await call("Input.dispatchTouchEvent", type="touchEnd", touchPoints=[])

        # inked pixels in a vertical column of the main canvas around canvas-x
        col = """(function(cssX, y0, y1){
          var c = KiddoPaint.Display.main_canvas, r = KiddoPaint.Display.canvas.getBoundingClientRect();
          var s = c.width / (r.width - 2), x = Math.round((cssX - r.left - 1) * s);
          var d = c.getContext('2d').getImageData(x, Math.round((y0 - r.top) * s), 1, Math.round((y1 - y0) * s)).data, k = 0;
          for (var i = 0; i < d.length; i += 4) if (d[i+3] > 0 && d[i] < 128) k++;
          return k; })"""

        async def reset():
            await js("KiddoPaint.Display.clearMain(); KiddoPaint.Display.clearPreview(); "
                     "document.getElementById('pencil').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,which:1})); "
                     "KiddoPaint.Tools.Pencil.size = 5; KiddoPaint.Current.color = 'rgb(0,0,0)'; true")
            await asyncio.sleep(1.0)  # let palm grace from earlier pen input lapse

        r = await js("(function(){var r=KiddoPaint.Display.canvas.getBoundingClientRect();return [r.left,r.top,r.width,r.height]})()")
        L, T = r[0], r[1]

        # 1. pressure changes pencil thickness
        await reset()
        await pen_stroke(L + 100, T + 200, L + 500, T + 200, force=0.1)
        await pen_stroke(L + 100, T + 400, L + 500, T + 400, force=1.0)
        thin = await js(f"{col}({L + 300}, {T + 150}, {T + 250})")
        thick = await js(f"{col}({L + 300}, {T + 350}, {T + 450})")
        check("pressure: hard pen line is thicker than light", thick > thin > 0, f"light={thin}px hard={thick}px")

        # 2. side button (barrel) erases, then the pencil comes back
        await pen_stroke(L + 300, T + 120, L + 300, T + 480, force=0.5, buttons=2, button="right")
        after = await js(f"{col}({L + 300}, {T + 350}, {T + 450})")
        tool = await js("window.__kidpixAndroid.toolName()")
        check("side button erases the stroke under it", after == 0, f"inked px after={after}")
        check("tool goes back to Pencil after side-button stroke", tool == "Pencil", tool)

        # 3. second side button = undo
        await reset()
        await pen_stroke(L + 100, T + 600, L + 500, T + 600, force=0.5)
        await asyncio.sleep(0.3)
        before = await js(f"{col}({L + 300}, {T + 560}, {T + 640})")
        await pen("mouseMoved", L + 600, T + 600, 0, buttons=4, button="middle")
        await pen("mouseMoved", L + 610, T + 600, 0, buttons=0, button="none")
        await asyncio.sleep(0.6)
        undone = await js(f"{col}({L + 300}, {T + 560}, {T + 640})")
        check("second side button undoes", before > 0 and undone == 0, f"before={before} after={undone}")

        # 4. palm rejection: finger while the pen hovers does nothing
        await reset()
        await pen("mouseMoved", L + 700, T + 300, 0, buttons=0, button="none")
        await touch_stroke(L + 100, T + 300, L + 500, T + 300)
        palm = await js(f"{col}({L + 300}, {T + 250}, {T + 350})")
        check("palm (finger) ignored while pen hovers", palm == 0, f"inked px={palm}")

        # 5. finger still draws when no pen is around
        await asyncio.sleep(1.2)
        await touch_stroke(L + 100, T + 500, L + 500, T + 500)
        finger = await js(f"{col}({L + 300}, {T + 450}, {T + 550})")
        check("finger draws when pen is away", finger > 0, f"inked px={finger}")

        # 6. hover preview: stamps show under a hovering pen without drawing
        await reset()
        await js("document.getElementById('alphabet').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,which:1})); true")
        await pen("mouseMoved", L + 400, T + 300, 0, buttons=0, button="none")
        prev = await js("(function(){var c=KiddoPaint.Display.previewCanvas,d=c.getContext('2d').getImageData(0,0,c.width,c.height).data,k=0;for(var i=3;i<d.length;i+=4)if(d[i])k++;return k})()")
        main = await js("(function(){var c=KiddoPaint.Display.main_canvas,d=c.getContext('2d').getImageData(0,0,c.width,c.height).data,k=0;for(var i=3;i<d.length;i+=4)if(d[i])k++;return k})()")
        check("hovering pen shows stamp preview, draws nothing", prev > 0 and main == 0, f"preview px={prev} main px={main}")
        await js("document.getElementById('pencil').dispatchEvent(new MouseEvent('mousedown',{bubbles:true,which:1})); KiddoPaint.Display.clearPreview(); true")

        # 7. a lost pen-up must not freeze drawing
        await reset()
        await pen("mousePressed", L + 100, T + 700, 0.5)
        await js("window.__kidpixAndroid.state.activeId = 999; true")  # simulate the missing pointerup
        await pen_stroke(L + 100, T + 300, L + 500, T + 300, force=0.5)
        healed = await js(f"{col}({L + 300}, {T + 250}, {T + 350})")
        check("drawing recovers after a lost pen-up", healed > 0, f"inked px={healed}")
        await pen("mouseReleased", L + 100, T + 700, 0, 0, "left")

        await reset()
        print(f"{sum(results)}/{len(results)} passed")
        sys.exit(0 if all(results) else 1)


asyncio.run(main())
