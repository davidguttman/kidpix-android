package dev.kidpix.paint.harness

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import dev.kidpix.paint.Ev
import dev.kidpix.paint.GlfxNeedsGL
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.KUi
import dev.kidpix.paint.color_next
import dev.kidpix.paint.color_prev
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.ev_canvas
import dev.kidpix.paint.init_color_selector
import dev.kidpix.paint.init_kiddo_defaults
import dev.kidpix.paint.init_subtool_bars
import dev.kidpix.paint.key_down
import dev.kidpix.paint.key_up
import dev.kidpix.paint.kidpix_android_boot
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs scenario files (docs/SCENARIOS.md format) against the engine with virtual time, the same way
 * scripts/ref_capture.py drives the WebView: dt ms of virtual time before every pointer event,
 * settle timers afterwards, then save the main canvas over white.
 */
class ScenarioRunner(private val outDir: File, private val log: (String) -> Unit = { println(it) }) {

    /** [skipped]: why no picture was saved (a glfx effect without a GL context), else null. */
    class Result(val id: String, val ok: Boolean, val error: String?, val settledMs: Long, val ms: Long, val skipped: String? = null)

    private lateinit var clock: VirtualClock

    fun runFile(file: File, only: String? = null): List<Result> {
        val cfg = JSONObject(file.readText())
        val canvas = cfg.optJSONObject("canvas")
        val w = canvas?.optInt("w", 1136) ?: 1136
        val h = canvas?.optInt("h", 798) ?: 798
        // "base.png" next to the scenario file; the reference keeps it one level up (ref/base.png
        // for ref/scenarios/*.json), so fall back to the parent directory when it is not here.
        val base = cfg.optString("base", "base.png").let { name ->
            val here = File(file.absoluteFile.parentFile, name)
            val up = File(file.absoluteFile.parentFile.parentFile, name)
            if (here.length() == 0L && up.length() > 0L) up else here
        }
        val scs = cfg.getJSONArray("scenarios")
        outDir.mkdirs()
        val results = ArrayList<Result>()
        val onlyRe = only?.let { globRegex(it) }
        for (i in 0 until scs.length()) {
            val sc = scs.getJSONObject(i)
            val id = sc.getString("id")
            if (onlyRe != null && !onlyRe.matches(id)) continue
            val t0 = System.nanoTime()
            var err: String? = null
            var settled = 0L
            var skipped: String? = null
            try {
                reset(w, h, if (sc.optBoolean("base", false)) base else null, sc.optInt("seed", cfg.optInt("seed", 1234)))
                val dt = sc.optLong("dt", cfg.optLong("dt", 16))
                val steps = sc.getJSONArray("steps")
                for (j in 0 until steps.length()) step(steps.getJSONObject(j), dt)
                // ref extension: "settle": false takes the picture with the animation part way
                if (sc.optBoolean("settle", true)) settled = settle()
            } catch (t: Throwable) {
                if (generateSequence(t) { it.cause }.any { it is GlfxNeedsGL }) skipped = "needs GL"
                else err = t.toString() + "\n" + t.stackTrace.take(12).joinToString("\n") { "  at $it" }
            }
            if (skipped != null) {
                File(outDir, "$id.png").delete(); File(outDir, "$id.overlay.png").delete()
            } else try {
                save(File(outDir, "$id.png"), false)
                if (sc.optBoolean("overlays", false)) save(File(outDir, "$id.overlay.png"), true)
            } catch (t: Throwable) {
                err = (err ?: "") + "\nsave: $t"
            }
            val ms = (System.nanoTime() - t0) / 1_000_000
            results.add(Result(id, err == null, err, settled, ms, skipped))
            log((if (err != null) "FAIL " else if (skipped != null) "SKIP " else "ok   ") + "$id (${ms}ms)" +
                (skipped?.let { " skipped: $it" } ?: "") + (err?.let { "\n$it" } ?: ""))
        }
        writeManifest(results)
        return results
    }

    private fun reset(w: Int, h: Int, base: File?, seed: Int) {
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KiddoPaint.resetWorld()
        KDisplay.store = null
        KDisplay.init_layers(w, h)
        // page load: init_kiddo_paint() minus storage
        init_kiddo_defaults()
        init_subtool_bars()
        init_color_selector()
        kidpix_android_boot() // the WebView app always runs the overlay
        clock.runDue()
        if (base != null) {
            val bmp = BitmapFactory.decodeFile(base.absolutePath) ?: error("base picture missing: $base")
            val c = KDisplay.main_context
            c.imageSmoothingEnabled = false
            c.drawImage(bmp, 0, 0)
        }
        History.clear(); KDisplay.undoData = null // the reference empties the overlay's history
        KRandom.seed(seed)
        init_kiddo_defaults()
    }

    private fun ev(type: String, x: Int, y: Int, down: Boolean) {
        val e = Ev(x.toDouble(), y.toDouble(), type)
        e.buttons = if (down) 1 else 0
        e.timeStamp = (clock.time - VirtualClock.T0).toDouble()
        ev_canvas(e)
    }

    private fun points(arr: JSONArray): List<IntArray> =
        (0 until arr.length()).map { val p = arr.getJSONArray(it); intArrayOf(p.getDouble(0).toInt(), p.getDouble(1).toInt()) }

    private fun step(st: JSONObject, dt: Long) {
        when (val op = st.getString("op")) {
            "tool" -> check(KAndroid.pressMain(st.getString("id"), st.optBoolean("ctrl", false))) { "no tool ${st.getString("id")}" }
            "sub" -> check(KAndroid.pressSubmenu(st.getInt("index"), st.optBoolean("contextmenu", false), st.optBoolean("ctrl", false))) {
                "no submenu button ${st.getInt("index")} in '${KUi.currentSubmenu}'"
            }
            "letter" -> KAndroid.pressAlphabet(st.getInt("index"))
            "color" -> colorSelect(st.getInt("index"), st.optInt("which", 1))
            "palette" -> if (st.optString("dir", "next") == "next") color_next() else color_prev()
            "stroke", "tap" -> {
                val pts = if (op == "tap" && st.has("point")) listOf(points(JSONArray().put(st.getJSONArray("point")))[0])
                else points(st.getJSONArray("points")).let { if (op == "tap") it.take(1) else it }
                clock.advance(dt); ev("mousedown", pts[0][0], pts[0][1], true)
                for (p in pts.drop(1)) { clock.advance(dt); ev("mousemove", p[0], p[1], true) }
                val l = pts.last()
                // ref extension: "up": false keeps the button held (eraser-08-letters-mid)
                if (st.optBoolean("up", true)) { clock.advance(dt); ev("mouseup", l[0], l[1], false) }
            }
            "hover" -> for (p in points(st.getJSONArray("points"))) { clock.advance(dt); ev("mousemove", p[0], p[1], false) }
            "key" -> {
                val name = st.getString("name")
                val code = KEYS[name] ?: if (name.length == 1) name.uppercase()[0].code else error("unknown key $name")
                if (st.optBoolean("down", true)) key_down(code, false, if (name.length == 1) name else "") else key_up(code)
            }
            "wait" -> clock.advance(st.getLong("ms"))
            else -> error("unknown op $op")
        }
        clock.runDue()
    }

    /** Run virtual time until no timer is left (cap 60 s), in 100 ms steps like the reference. */
    private fun settle(capMs: Long = 60_000): Long {
        var ran = 0L
        while (ran < capMs) {
            clock.runDue()
            if (clock.pending == 0) return ran
            clock.advance(100); ran += 100
        }
        return ran
    }

    /** Main canvas over white (+ bnim, anim, preview, tmp when [overlays]). */
    fun flatten(overlays: Boolean): Bitmap {
        val m = KDisplay.main_canvas
        val out = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.WHITE)
        c.drawBitmap(m.bitmap, 0f, 0f, null)
        if (overlays) for (l in listOf(KDisplay.bnimCanvas, KDisplay.animCanvas, KDisplay.previewCanvas, KDisplay.canvas)) {
            c.drawBitmap(l.bitmap, 0f, 0f, null)
        }
        return out
    }

    private fun save(f: File, overlays: Boolean) {
        f.outputStream().use { flatten(overlays).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun writeManifest(results: List<Result>) {
        val m = File(outDir, "manifest.json")
        val j = if (m.exists()) runCatching { JSONObject(m.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        for (r in results) j.put(r.id, JSONObject().put("ok", r.ok).put("error", r.error ?: JSONObject.NULL)
            .put("settled_ms", r.settledMs).put("ms", r.ms).put("skipped", r.skipped ?: JSONObject.NULL))
        m.writeText(j.toString(1))
    }

    companion object {
        val KEYS = mapOf("shift" to 16, "alt" to 18, "ctrl" to 17, "cmd" to 91, "tilde" to 192, "space" to 32)

        fun globRegex(glob: String): Regex = Regex(glob.split(",").joinToString("|") { g ->
            "^" + g.trim().map { ch ->
                when (ch) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(ch.toString()) }
            }.joinToString("") + "$"
        })
    }
}
