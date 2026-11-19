# The lit tool option must be the tool you actually get, also after switching tools and back.
# usage: uv run --with websockets scripts/selection_test.py <devtools-port> [pen|touch]
import asyncio, json, sys, urllib.request
import websockets

PORT = sys.argv[1]
MODE = sys.argv[2] if len(sys.argv) > 2 else "pen"
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

        async def tap(x, y):
            if MODE == "touch":
                await call("Input.dispatchTouchEvent", type="touchStart", touchPoints=[{"x": x, "y": y}])
                await asyncio.sleep(0.05)
                await call("Input.dispatchTouchEvent", type="touchEnd", touchPoints=[])
            else:
                await call("Input.dispatchMouseEvent", type="mousePressed", x=x, y=y, pointerType="pen",
                           force=0.5, buttons=1, button="left", clickCount=1)
                await call("Input.dispatchMouseEvent", type="mouseReleased", x=x, y=y, pointerType="pen",
                           force=0, buttons=0, button="left", clickCount=1)
            await asyncio.sleep(0.3)

        async def tapsel(sel):
            c = await js(f"""(function(){{var b=document.querySelector({json.dumps(sel)});
              var r=b.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2];}})()""")
            await tap(*c)

        async def tool(name):
            await tapsel("#" + name)

        async def option(title, nth=1):
            await js(f"""window.__opt=Array.prototype.filter.call(document.querySelectorAll('#genericsubmenu > button'),
              function(b){{return b.title==={json.dumps(title)}}})[{nth - 1}]""")
            c = await js("(function(){var r=__opt.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()")
            await tap(*c)

        state = """(function(){var t=KiddoPaint.Current.tool,T=KiddoPaint.Tools,name='?';
          for(var k in T){if(T[k]===t){name=k;break;}}
          var lit=Array.prototype.filter.call(document.querySelectorAll('#genericsubmenu > button.kp-selected'),function(b){return true})
            .map(function(b){return b.title});
          return {tool:name, lit:lit};})()"""

        await tool("eraser")
        await option("Firecracker")
        s = await js(state)
        check("dynamite picked", s["tool"] == "Tnt" and s["lit"] == ["Firecracker"], s)
        await tap(640, 450)  # boom
        await asyncio.sleep(3.2)
        await tool("brush")
        await option("Pies")
        s = await js(state)
        check("brush picked", s["lit"] == ["Pies"], s)
        await tool("eraser")
        s = await js(state)
        check("back to eraser keeps dynamite", s["tool"] == "Tnt" and s["lit"] == ["Firecracker"], s)
        await option("Eraser Circle 15")
        s = await js(state)
        check("other eraser option works", s["tool"] == "Eraser" and s["lit"] == ["Eraser Circle 15"], s)
        await option("Hidden Pictures")
        await tool("pencil")
        await tool("eraser")
        s = await js(state)
        check("hidden pictures survives a tool switch", s["tool"] == "EraserHiddenPicture" and s["lit"] == ["Hidden Pictures"], s)
        await tool("brush")
        s = await js(state)
        check("back to brush keeps pies", s["lit"] == ["Pies"], s)

        await tool("stamp")
        await asyncio.sleep(0.5)
        await js("window.__opt=document.querySelectorAll('#genericsubmenu > button')[4]")
        c = await js("(function(){var r=__opt.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()")
        await tap(*c)
        await tool("pencil")
        await tool("stamp")
        await asyncio.sleep(0.5)
        lit = await js("Array.prototype.map.call(document.querySelectorAll('#genericsubmenu > button'),function(b){return b.classList.contains('kp-selected')}).indexOf(true)")
        check("stamp re-entry lights the stamp upstream picks (first)", lit == 0, lit)
        await option("Next Page")
        lit = await js("document.querySelectorAll('#genericsubmenu > button.kp-selected').length")
        check("next page lights nothing", lit == 0, lit)

        await tool("pencil")
        await option("Size 10")

    print(f"{sum(results)}/{len(results)} passed")
    sys.exit(0 if all(results) else 1)


asyncio.run(main())
