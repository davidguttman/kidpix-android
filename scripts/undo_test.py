# Multi-step undo + double-tap redo on the Undo Guy, and the selected-tool highlight.
# usage: uv run --with websockets scripts/undo_test.py <devtools-port>
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
            return r["result"].get("value")

        async def pen(t, x, y, buttons=1):
            await call("Input.dispatchMouseEvent", type=t, x=x, y=y, pointerType="pen", force=0.5,
                       buttons=buttons, button="left", clickCount=0 if t == "mouseMoved" else 1)

        async def tap(x, y):
            await pen("mousePressed", x, y)
            await pen("mouseReleased", x, y, 0)

        async def stroke(x0, y0, x1, y1):
            await pen("mousePressed", x0, y0)
            for k in range(1, 11):
                await pen("mouseMoved", x0 + (x1 - x0) * k / 10, y0 + (y1 - y0) * k / 10)
            await pen("mouseReleased", x1, y1, 0)

        count = """(function(){var c=KiddoPaint.Display.main_canvas,d=c.getContext('2d').getImageData(0,0,c.width,c.height).data,r=0,g=0,b=0;
          for(var i=0;i<d.length;i+=4){if(d[i]>200&&d[i+1]<60&&d[i+2]<60)r++;if(d[i+1]>130&&d[i]<60&&d[i+2]<60)g++;if(d[i+2]>200&&d[i]<60&&d[i+1]<60)b++;}
          return [r>0?1:0,g>0?1:0,b>0?1:0].join('')})()"""

        async def strokes_shown():
            await asyncio.sleep(0.8)
            return await js(count)

        def center(id_):
            return f"(function(){{var r=document.getElementById('{id_}').getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]}})()"

        L, T = await js("(function(){var r=KiddoPaint.Display.canvas.getBoundingClientRect();return [r.left,r.top]})()")
        ux, uy = await js(center("undo"))
        await js("window.__kidpixAndroid.history.past.length=0;window.__kidpixAndroid.history.future.length=0;true")
        await js("KiddoPaint.Display.clearMain();document.getElementById('pencil').dispatchEvent(new MouseEvent('mousedown',{bubbles:true}));KiddoPaint.Tools.Pencil.texture = function(c){return KiddoPaint.Textures.Solid(c)}; true")
        await asyncio.sleep(1.0)
        for i, color in enumerate(["rgb(255,0,0)", "rgb(0,170,0)", "rgb(0,0,255)"]):
            await js(f"KiddoPaint.Current.color='{color}';true")
            await stroke(L + 100, T + 150 + i * 150, L + 600, T + 150 + i * 150)
        got = await strokes_shown(); check("three strokes drawn", got == "111", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))

        await tap(ux, uy); await asyncio.sleep(0.6)
        got = await strokes_shown(); check("tap 1: blue undone", got == "110", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))
        await tap(ux, uy); await asyncio.sleep(0.6)
        got = await strokes_shown(); check("tap 2: green undone too (multi-step)", got == "100", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))

        await tap(ux, uy); await tap(ux, uy)
        got = await strokes_shown()
        check("double-tap: green comes back", got == "110", got)
        await tap(ux, uy); await tap(ux, uy)
        got = await strokes_shown(); check("double-tap again: blue comes back", got == "111", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))
        await tap(ux, uy); await tap(ux, uy)
        got = await strokes_shown(); check("double-tap with nothing ahead: no change", got == "111", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))

        await tap(ux, uy); await asyncio.sleep(0.6)
        await js("KiddoPaint.Current.color='rgb(255,0,0)';true")
        await stroke(L + 700, T + 500, L + 900, T + 500)
        await tap(ux, uy); await tap(ux, uy)
        got = await strokes_shown(); check("new stroke after undo clears the forward steps", got == "110", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))

        for _ in range(4):
            await tap(ux, uy); await asyncio.sleep(0.6)
        got = await strokes_shown(); check("undo all the way back to blank", got == "000", got + " past/fut " + str(await js("[window.__kidpixAndroid.history.past.length, window.__kidpixAndroid.history.future.length].join('/')")))

        # selection highlight
        bx, by = await js(center("brush"))
        await tap(bx, by); await asyncio.sleep(0.5)
        sel = await js("Array.from(document.querySelectorAll('#mainbar .kp-selected')).map(b=>b.id).join(',')")
        check("tapping Brush highlights only Brush", sel == "brush", sel)
        opt = await js("(function(){var b=document.querySelectorAll('#genericsubmenu button:not(.invisible)')[3];var r=b.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()")
        await tap(*opt); await asyncio.sleep(0.5)
        idx = await js("Array.from(document.querySelectorAll('#genericsubmenu button:not(.invisible)')).findIndex(b=>b.classList.contains('kp-selected'))")
        check("tapping a brush option highlights it", idx == 3, str(idx))
        px, py = await js(center("pencil"))
        await tap(px, py); await asyncio.sleep(0.5)
        await tap(bx, by); await asyncio.sleep(0.5)
        idx = await js("Array.from(document.querySelectorAll('#genericsubmenu button:not(.invisible)')).findIndex(b=>b.classList.contains('kp-selected'))")
        check("brush option is still highlighted after switching away and back", idx == 3, str(idx))
        await tap(px, py)

        print(f"{sum(results)}/{len(results)} passed")
        sys.exit(0 if all(results) else 1)


asyncio.run(main())
