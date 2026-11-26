# Runs scenario files (ref/scenarios/*.json, format in docs/SCENARIOS.md + ref/README.md) against the
# WebView Kid Pix over the devtools socket and saves the reference pictures.
#
# usage: uv run --with websockets scripts/ref_capture.py <devtools-port> [--out DIR] [--only ID_PREFIX] [files...]
#   default files: ref/scenarios/*.json, default out: ref/out
#
# The page is reloaded before every scenario (true boot state, overlay memory included), then
# a hook injected at document start provides: seeded Math.random (mulberry32), a virtual clock
# (Date.now / performance.now / setTimeout / setInterval / requestAnimationFrame) and tracking of
# pending image loads and errors. Nothing in app/ or overlay/ is changed.
import argparse, asyncio, base64, glob, json, os, sys, time, urllib.request
import websockets

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

HOOK = r"""
(function(){
  if (window.__ref) return;
  // The overlay saves the canvas when the old page hides, so clear it here, before boot reads it.
  try { localStorage.removeItem('kiddopaint'); } catch (e) {}
  var R = window.__ref = {errors: [], virtual: false, vnow: 0, T0: 1700000000000, seq: 0, q: [], pending: 0,
    realSetTimeout: window.setTimeout.bind(window), realClearTimeout: window.clearTimeout.bind(window),
    realSetInterval: window.setInterval.bind(window), realClearInterval: window.clearInterval.bind(window),
    realRaf: window.requestAnimationFrame.bind(window), realCaf: window.cancelAnimationFrame.bind(window),
    realDateNow: Date.now, realPerfNow: performance.now.bind(performance), realRandom: Math.random};
  function rec(kind, msg) { R.errors.push(kind + ': ' + String(msg).slice(0, 300)); }
  window.addEventListener('error', function(e) { rec('error', (e.message || e) + ' @' + (e.filename||'').split('/').pop() + ':' + e.lineno); });
  window.addEventListener('unhandledrejection', function(e) { rec('rejection', e.reason && (e.reason.stack || e.reason)); });
  var ce = console.error.bind(console);
  console.error = function() { rec('console.error', Array.prototype.join.call(arguments, ' ')); return ce.apply(null, arguments); };

  // mulberry32, the exact function the native side implements (see ref/README.md)
  R.mulberry32 = function(a) {
    return function() {
      a |= 0; a = a + 0x6D2B79F5 | 0;
      var t = Math.imul(a ^ a >>> 15, 1 | a);
      t = t + Math.imul(t ^ t >>> 7, 61 | t) ^ t;
      return ((t ^ t >>> 14) >>> 0) / 4294967296;
    };
  };
  R.seed = function(s) { Math.random = R.mulberry32(s); };

  // ---- virtual clock: when R.virtual, time only moves in R.advance(ms) ----
  Date.now = function() { return R.virtual ? R.T0 + R.vnow : R.realDateNow.call(Date); };
  performance.now = function() { return R.virtual ? R.vnow : R.realPerfNow(); };
  function add(fn, delay, args, interval) {
    var id = 1000000 + (++R.seq);
    R.q.push({id: id, due: R.vnow + delay, seq: R.seq, fn: fn, args: args, interval: interval});
    return id;
  }
  function remove(id) { for (var i = 0; i < R.q.length; i++) if (R.q[i].id === id) { R.q.splice(i, 1); return; } }
  window.setTimeout = function(fn, delay) {
    if (!R.virtual) return R.realSetTimeout.apply(null, arguments);
    return add(fn, Math.max(0, +delay || 0), Array.prototype.slice.call(arguments, 2), 0);
  };
  window.setInterval = function(fn, delay) {
    if (!R.virtual) return R.realSetInterval.apply(null, arguments);
    var d = Math.max(1, +delay || 0);
    return add(fn, d, Array.prototype.slice.call(arguments, 2), d);
  };
  window.clearTimeout = function(id) { if (id > 1000000) remove(id); else R.realClearTimeout(id); };
  window.clearInterval = function(id) { if (id > 1000000) remove(id); else R.realClearInterval(id); };
  window.requestAnimationFrame = function(cb) {
    if (!R.virtual) return R.realRaf(cb);
    return add(function() { cb(R.vnow); }, 16, [], 0);
  };
  window.cancelAnimationFrame = function(id) { if (id > 1000000) remove(id); else R.realCaf(id); };
  R.advance = function(ms) {
    var target = R.vnow + ms, runs = 0;
    for (;;) {
      var best = -1;
      for (var i = 0; i < R.q.length; i++) {
        var t = R.q[i];
        if (t.due <= target && (best < 0 || t.due < R.q[best].due || (t.due === R.q[best].due && t.seq < R.q[best].seq))) best = i;
      }
      if (best < 0 || ++runs > 100000) break;
      var t = R.q[best];
      R.vnow = Math.max(R.vnow, t.due);
      if (t.interval) { t.due += t.interval; t.seq = ++R.seq; } else R.q.splice(best, 1);
      try { (typeof t.fn === 'function' ? t.fn : new Function(t.fn)).apply(window, t.args); }
      catch (e) { rec('timer', e && (e.stack || e)); }
    }
    R.vnow = target;
  };

  // ---- pending image loads ----
  var desc = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src');
  Object.defineProperty(HTMLImageElement.prototype, 'src', {
    configurable: true, enumerable: desc.enumerable, get: desc.get,
    set: function(v) {
      var img = this;
      if (!img.__refPending) {
        img.__refPending = true; R.pending++;
        var done = function() { if (img.__refPending) { img.__refPending = false; R.pending--; } };
        img.addEventListener('load', done); img.addEventListener('error', done);
      }
      desc.set.call(this, v);
    }
  });
  R.busy = function() {
    var h = window.__kidpixAndroid && window.__kidpixAndroid.history;
    return R.pending > 0 || !!(h && h.restoring);
  };
})();
"""

# Helpers evaluated in the page after each reload (they rely on the hook above).
HELPERS = r"""
(function(){
  var R = window.__ref;
  R.press = function(el, ctrl, ctx) {
    if (!el) throw new Error('no such button');
    // a finger lands on the innermost element (letters read the <h1> text from the event target)
    while (el.firstElementChild) el = el.firstElementChild;
    var r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    // long press on a touch screen: Chrome sends only 'contextmenu' (no mousedown/click)
    if (ctx) { el.dispatchEvent(new MouseEvent('contextmenu', {bubbles: true, cancelable: true, view: window,
      button: 2, buttons: 0, clientX: x, clientY: y, detail: 0})); return; }
    ['mousedown', 'mouseup', 'click'].forEach(function(t) {
      el.dispatchEvent(new MouseEvent(t, {bubbles: true, cancelable: true, view: window, button: 0,
        buttons: t === 'mousedown' ? 1 : 0, clientX: x, clientY: y, detail: 1, ctrlKey: !!ctrl}));
    });
  };
  R.ev = function(type, x, y, down) {
    var c = KiddoPaint.Display.canvas, r = c.getBoundingClientRect();
    var cx = r.left + 1 + x * (r.width - 2) / c.width, cy = r.top + 1 + y * (r.height - 2) / c.height;
    var e = {type: type, offsetX: x, offsetY: y, clientX: cx, clientY: cy, pageX: cx, pageY: cy,
      which: 1, button: 0, buttons: down ? 1 : 0, pointerType: 'mouse',
      shiftKey: false, altKey: false, ctrlKey: false, metaKey: false,
      target: c, srcElement: c, timeStamp: R.vnow,
      preventDefault: function() {}, stopPropagation: function() {}};
    try { ev_canvas(e); } catch (err) { R.errors.push('ev_canvas ' + type + ': ' + (err && (err.stack || err)).toString().slice(0, 300)); }
  };
  R.points = function(type, pts, down, dt) {
    for (var i = 0; i < pts.length; i++) { R.advance(dt); R.ev(type, pts[i][0], pts[i][1], down); }
  };
  R.stroke = function(pts, dt, noUp) {
    R.advance(dt); R.ev('mousedown', pts[0][0], pts[0][1], true);
    R.points('mousemove', pts.slice(1), true, dt);
    if (noUp) return;
    var l = pts[pts.length - 1];
    R.advance(dt); R.ev('mouseup', l[0], l[1], false);
  };
  var KEYS = {shift: 16, alt: 18, ctrl: 17, cmd: 91, tilde: 192, space: 32};
  R.key = function(name, down) {
    var code = KEYS[name] || (name.length === 1 ? name.toUpperCase().charCodeAt(0) : 0);
    if (!code) throw new Error('unknown key ' + name);
    var e = {keyCode: code, key: name.length === 1 ? name : '', ctrlKey: false,
      preventDefault: function() {}, stopPropagation: function() {}};
    try { (down ? document.onkeydown : document.onkeyup)(e); }
    catch (err) { R.errors.push('key: ' + err); }
  };
  R.clearAll = function(keepMain) {
    var D = KiddoPaint.Display;
    if (!keepMain) D.clearMain();
    D.context.clearRect(0, 0, D.canvas.width, D.canvas.height);
    D.clearPreview(); D.clearAnim(); D.clearBnim();
    var h = window.__kidpixAndroid.history; h.past.length = 0; h.future.length = 0; h.restoring = null; h.token++;
    D.undoData = null;
  };
  R.drawBase = function(url) {
    return new Promise(function(res, rej) {
      var img = new Image();
      img.onload = function() { var c = KiddoPaint.Display.main_context; c.imageSmoothingEnabled = false; c.drawImage(img, 0, 0); res(true); };
      img.onerror = rej; img.src = url;
    });
  };
  R.flat = function(withOverlays) {
    var D = KiddoPaint.Display, m = D.main_canvas;
    var c = document.createElement('canvas'); c.width = m.width; c.height = m.height;
    var x = c.getContext('2d'); x.fillStyle = '#fff'; x.fillRect(0, 0, c.width, c.height);
    x.drawImage(m, 0, 0);
    if (withOverlays) [D.bnimCanvas, D.animCanvas, D.previewCanvas, D.canvas].forEach(function(l) { x.drawImage(l, 0, 0); });
    var u = c.toDataURL('image/png'); return u.slice(u.indexOf(',') + 1);
  };
})();
"""


class Page:
    def __init__(self, ws):
        self.ws, self.n = ws, 0

    async def call(self, method, **params):
        self.n += 1
        await self.ws.send(json.dumps({"id": self.n, "method": method, "params": params}))
        while True:
            m = json.loads(await asyncio.wait_for(self.ws.recv(), 120))
            if m.get("id") == self.n:
                if "error" in m:
                    raise RuntimeError(f"{method}: {m['error']}")
                return m.get("result", {})

    async def js(self, expr, await_promise=False):
        r = await self.call("Runtime.evaluate", expression=expr, returnByValue=True, awaitPromise=await_promise)
        if "exceptionDetails" in r:
            d = r["exceptionDetails"]
            raise RuntimeError(f"JS: {d.get('exception', {}).get('description') or d.get('text')}")
        return r["result"].get("value")

    async def until(self, expr, timeout=20.0):
        t = time.time()
        while time.time() - t < timeout:
            if await self.js(expr):
                return True
            await asyncio.sleep(0.03)
        return False

    async def idle(self):
        if not await self.until("!window.__ref.busy()", 20):
            raise RuntimeError("page stayed busy (image load / undo restore)")
        await self.js("new Promise(function(r){__ref.realRaf(function(){__ref.realRaf(function(){r(1)})})})", True)


def base_url(cfg, files_dir):
    p = os.path.join(files_dir, cfg.get("base", "base.png"))
    if not os.path.exists(p):
        return None
    return "data:image/png;base64," + base64.b64encode(open(p, "rb").read()).decode()


async def reset(page, cfg, sc, base):
    await page.js("localStorage.clear();true")
    await page.call("Page.reload", ignoreCache=False)
    await asyncio.sleep(0.2)
    if not await page.until("!!(window.__ref && window.__kidpixAndroid && window.KiddoPaint && KiddoPaint.Display.main_canvas && document.readyState==='complete')", 30):
        raise RuntimeError("page did not boot")
    await page.js(HELPERS + ";true")
    await page.idle()  # the opening page image draws itself asynchronously; let it, then wipe it
    # "keep_opening" (only ref/base.json uses it): keep the opening page as the starting picture
    await page.js("__ref.clearAll(true);true" if sc.get("keep_opening") else "__ref.clearAll();true")
    if sc.get("base"):
        if not base:
            raise RuntimeError("scenario wants the base picture but ref/base.png is missing")
        await page.js(f"__ref.drawBase({json.dumps(base)})", True)
    seed = sc.get("seed", cfg.get("seed", 1234))
    await page.js(f"__ref.vnow=0;__ref.q.length=0;__ref.virtual=true;__ref.seed({int(seed)});"
                  "init_kiddo_defaults();__ref.errors.length=0;true")


async def run_step(page, st, dt):
    op = st["op"]
    if op == "tool":
        await page.js(f"__ref.press(document.getElementById({json.dumps(st['id'])}));true")
    elif op == "sub":
        ctrl = "true" if st.get("ctrl") else "false"
        ctx = "true" if st.get("contextmenu") else "false"
        await page.js(f"__ref.press(document.getElementById('genericsubmenu').children[{int(st['index'])}],{ctrl},{ctx});true")
    elif op == "letter":
        await page.js(f"__ref.press(document.getElementById('alphabettoolbar').querySelectorAll('button')[{int(st['index'])}]);true")
    elif op == "color":
        await page.js(f"__ref.press(document.getElementById('colorselector').children[{int(st['index'])}]);true")
    elif op == "palette":
        await page.js(f"__ref.press(document.getElementById('color{'next' if st.get('dir', 'next') == 'next' else 'prev'}'));true")
    elif op in ("stroke", "tap"):
        pts = st["points"] if op == "stroke" else [st["point"]] if "point" in st else st["points"][:1]
        no_up = "true" if st.get("up", True) is False else "false"
        await page.js(f"__ref.stroke({json.dumps([[int(x), int(y)] for x, y in pts])},{dt},{no_up});true")
    elif op == "hover":
        await page.js(f"__ref.points('mousemove',{json.dumps([[int(x), int(y)] for x, y in st['points']])},false,{dt});true")
    elif op == "key":
        await page.js(f"__ref.key({json.dumps(st['name'])},{'true' if st.get('down', True) else 'false'});true")
    elif op == "wait":
        await page.js(f"__ref.advance({int(st['ms'])});true")
    else:
        raise ValueError(f"unknown op {op}")
    await page.idle()


async def settle(page, cap_ms=60000):
    """Run virtual time until no timer is left (or the cap); image loads resolve in between."""
    ran = 0
    while ran < cap_ms:
        await page.idle()
        if not await page.js("__ref.q.length"):
            return ran, True
        await page.js("__ref.advance(100);true")
        ran += 100
    return ran, False


def write_png(path, b64):
    with open(path, "wb") as f:
        f.write(base64.b64decode(b64))


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("port")
    ap.add_argument("files", nargs="*")
    ap.add_argument("--out", default=os.path.join(ROOT, "ref", "out"))
    ap.add_argument("--only", default=None, help="comma-separated id prefixes")
    a = ap.parse_args()
    files = a.files or sorted(glob.glob(os.path.join(ROOT, "ref", "scenarios", "*.json")))
    os.makedirs(a.out, exist_ok=True)
    man_path = os.path.join(a.out, "manifest.json")
    manifest = json.load(open(man_path)) if os.path.exists(man_path) else {}

    ws_url = json.load(urllib.request.urlopen(f"http://localhost:{a.port}/json"))[0]["webSocketDebuggerUrl"]
    async with websockets.connect(ws_url, max_size=2**28) as ws:
        page = Page(ws)
        await page.call("Page.enable")
        await page.call("Page.addScriptToEvaluateOnNewDocument", source=HOOK)
        for fpath in files:
            cfg = json.load(open(fpath))
            base = base_url(cfg, os.path.join(ROOT, "ref"))
            for sc in cfg["scenarios"]:
                if a.only and not any(sc["id"].startswith(p) for p in a.only.split(",")):
                    continue
                t0 = time.time()
                entry = {"group": sc.get("group"), "file": os.path.basename(fpath)}
                try:
                    await reset(page, cfg, sc, base)
                    dt = int(sc.get("dt", cfg.get("dt", 16)))
                    for st in sc["steps"]:
                        await run_step(page, st, dt)
                    settled_ms, settled = await settle(page) if sc.get("settle", True) else (0, True)
                    entry.update(await page.js(
                        "({canvas:{w:KiddoPaint.Display.main_canvas.width,h:KiddoPaint.Display.main_canvas.height},"
                        "tool:__kidpixAndroid.toolName(),errors:__ref.errors.slice(0,20),vclock:__ref.vnow})"))
                    entry["settle_ms"] = settled_ms
                    if not settled:
                        entry["unsettled"] = True
                    write_png(os.path.join(a.out, sc["id"] + ".png"), await page.js("__ref.flat(false)"))
                    if sc.get("overlays"):
                        write_png(os.path.join(a.out, sc["id"] + ".overlay.png"), await page.js("__ref.flat(true)"))
                except Exception as e:
                    entry["failed"] = str(e)[:400]
                entry["secs"] = round(time.time() - t0, 2)
                manifest[sc["id"]] = entry
                flag = "FAIL " + entry["failed"] if "failed" in entry else ("ERR " + "; ".join(entry["errors"])[:200] if entry.get("errors") else "ok")
                print(f"{sc['id']:40s} {entry.get('tool', ''):22s} {entry['secs']:5.1f}s {flag}", flush=True)
                json.dump(manifest, open(man_path, "w"), indent=1, sort_keys=True)


asyncio.run(main())
