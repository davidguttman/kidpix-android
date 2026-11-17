# Tiny CDP client for the debug WebView: `uv run --with websockets scripts/cdp.py <port> <js>`
import asyncio, json, sys, urllib.request
import websockets

async def main(port, exprs):
    ws_url = json.load(urllib.request.urlopen(f"http://localhost:{port}/json"))[0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=2**26) as ws:
        n = 0
        async def call(method, **params):
            nonlocal n
            n += 1
            await ws.send(json.dumps({"id": n, "method": method, "params": params}))
            while True:
                m = json.loads(await ws.recv())
                if m.get("id") == n:
                    return m
        for e in exprs:
            if e.startswith("@"):  # @method {json}
                method, _, js = e[1:].partition(" ")
                r = await call(method, **(json.loads(js) if js else {}))
                if "error" in r: print("ERR", r["error"])
                continue
            r = await call("Runtime.evaluate", expression=e, returnByValue=True, awaitPromise=True)
            print(json.dumps(r.get("result", {}).get("result", {}).get("value", r)))

asyncio.run(main(sys.argv[1], sys.argv[2:]))
