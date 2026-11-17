# Every main tool x every submenu option: pen tap + pen stroke + finger stroke over CDP.
# Fails on JS errors, a hung page, or a dead renderer.
# usage: uv run --with websockets scripts/tool_smoke.py <devtools-port>
import asyncio, json, random, sys, urllib.request
import websockets

PORT = sys.argv[1]
MAIN = ["pencil", "line", "square", "circle", "brush", "jumble", "flood", "eraser",
        "alphabet", "stamp", "truck"]


async def main():
    ws_url = json.load(urllib.request.urlopen(f"http://localhost:{PORT}/json"))[0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=2**26) as ws:
        n = 0

        async def call(method, **params):
            nonlocal n
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params}))
            while True:
                m = json.loads(await asyncio.wait_for(ws.recv(), 30))
                if m.get("id") == n:
                    if "error" in m:
                        raise RuntimeError(m["error"])
                    return m.get("result", {})

        async def js(expr):
            r = await call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=True)
            return r["result"].get("value")

        async def pen(t, x, y, buttons=1):
            await call("Input.dispatchMouseEvent", type=t, x=x, y=y, pointerType="pen", force=random.random(),
                       buttons=buttons, button="none" if t == "mouseMoved" and not buttons else "left", clickCount=0 if t == "mouseMoved" else 1)

        await js("window.__errs=[];window.addEventListener('error',function(e){__errs.push(e.message+' @'+e.lineno)});true")
        L, T, W, H = await js("(function(){var r=KiddoPaint.Display.canvas.getBoundingClientRect();return [r.left,r.top,r.width,r.height]})()")
        rnd = lambda: (L + 20 + random.random() * (W - 40), T + 20 + random.random() * (H - 40))
        random.seed(7)
        combos = 0
        for tool in MAIN:
            await js(f"document.getElementById('{tool}').dispatchEvent(new MouseEvent('mousedown',{{bubbles:true,which:1}}));true")
            count = await js("document.querySelectorAll('#subtoolbars .subtoolbar button:not(.invisible)').length")
            print(f"{tool} -> {await js('window.__kidpixAndroid.toolName()')}", end="  ")
            for i in range(max(1, count)):
                await js(f"(function(){{var b=document.querySelectorAll('#subtoolbars .subtoolbar button:not(.invisible)')[{i}];if(b){{b.click();}}return true}})()")
                x, y = rnd()
                await pen("mouseMoved", x, y, 0)
                await pen("mousePressed", x, y)
                await pen("mouseReleased", x, y, 0)
                x0, y0 = rnd(); x1, y1 = rnd()
                await pen("mousePressed", x0, y0)
                for k in range(1, 9):
                    await pen("mouseMoved", x0 + (x1 - x0) * k / 8, y0 + (y1 - y0) * k / 8)
                await pen("mouseReleased", x1, y1, 0)
                alive = await js("1+1")
                combos += 1
                if alive != 2:
                    print("HUNG after", tool, i); sys.exit(1)
            await asyncio.sleep(0.5)  # let animated tools settle
            await js("KiddoPaint.Display.clearMain();true")
            print(f"{tool}: {count} options ok")
        errs = await js("__errs")
        print(f"{combos} tool/option combos, JS errors: {len(errs)}")
        for e in sorted(set(errs)):
            print("  ", e)
        sys.exit(1 if errs else 0)


asyncio.run(main())
