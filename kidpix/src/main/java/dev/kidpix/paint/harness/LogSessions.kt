package dev.kidpix.paint.harness

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.Ui
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.init_kiddo_defaults
import dev.kidpix.paint.log.Action
import dev.kidpix.paint.log.KLog
import dev.kidpix.paint.log.RecordCoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32

/**
 * Drives the engine through the action log (KLog.entry) the way the app does: scenario steps
 * become the app's entries (toolbar presses, Pointer Events of a mouse, pen or finger with
 * pressure, coalesced samples, hover), on virtual time. Used by the action log's property tests
 * and by `:kidpix:replayLog`.
 */
class LogSession(val dir: File, val w: Int = 1074, val h: Int = 798, private val rnd: java.util.Random = java.util.Random(7)) {
    lateinit var clock: VirtualClock
    /** Main picture CRC after every action that closed a log block: block count -> crc. */
    val afterBlock = HashMap<Int, Long>()
    /** The log's writer (null: writes at once, on the caller's thread). */
    var io: java.util.concurrent.Executor? = null

    /** A fresh engine with an empty log under [dir] (cleared first if [wipe]); [base] drawn on main. */
    fun start(base: File? = null, seed: Int = 1234, wipe: Boolean = true): String {
        if (wipe) dir.deleteRecursively()
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        KUi.ui = object : Ui {}
        return KLog.start(dir, w, h, io) {
            if (base != null) {
                val bmp = BitmapFactory.decodeFile(base.absolutePath) ?: error("base picture missing: $base")
                val c = KDisplay.main_context
                c.imageSmoothingEnabled = false
                c.drawImage(bmp, 0, 0)
            }
            KRandom.seed(seed)
            init_kiddo_defaults()
        }
    }

    /** Restart in the same process after a kill: the log and frames as they are on disk. */
    fun restart(): String {
        KLog.close()
        clock = VirtualClock()
        KTime.backend = clock
        KTime.origin = VirtualClock.T0
        return KLog.start(dir, w, h, io) { error("nothing to restore") }
    }

    private var nextId = 2
    private var penHoverId = 1

    private fun entry(a: Action) {
        val before = KLog.segment * 100_000 + KLog.blockCount
        KLog.entry(a)
        // the picture right after the action that closed a block (what a restore of that many blocks shows)
        val after = KLog.segment * 100_000 + KLog.blockCount
        if (KLog.recording && after != before) afterBlock[after] = crc(KDisplay.main_canvas.bitmap)
        clock.runDue()
        KLog.pump(clock.time)
    }

    /** Every stroke by this pointer type (null: mouse, pen or finger at random). */
    var forceType: String? = null

    private fun type(): String = forceType ?: when (rnd.nextInt(3)) { 0 -> "mouse"; 1 -> "pen"; else -> "touch" }

    /** One scenario step, the app's way. */
    fun step(st: JSONObject, dt: Long) {
        when (val op = st.getString("op")) {
            "tool" -> entry(Action.Tool(st.getString("id"), st.optBoolean("ctrl", false)))
            "sub" -> entry(Action.Sub(st.getInt("index"), st.optBoolean("contextmenu", false), st.optBoolean("ctrl", false)))
            "letter" -> entry(Action.Letter(st.getInt("index")))
            "color" -> entry(Action.Color(st.getInt("index"), st.optInt("which", 1)))
            "palette" -> entry(Action.Palette(st.optString("dir", "next") == "next"))
            "stroke", "tap" -> {
                val pts = if (op == "tap" && st.has("point")) listOf(st.getJSONArray("point").let { doubleArrayOf(it.getDouble(0), it.getDouble(1)) })
                else points(st.getJSONArray("points")).let { if (op == "tap") it.take(1) else it }
                val t = st.optString("type", "").ifEmpty { type() }
                val btn = st.optInt("buttons", 1) // a pen's eraser end: 32
                val id = nextId++
                fun pe(p: DoubleArray, buttons: Int, coalesced: List<PEvent>? = null): PEvent {
                    val pressure = if (t == "pen") RecordCoder.quantPressure(0.1 + rnd.nextDouble() * 0.8) else if (t == "touch") RecordCoder.quantPressure(rnd.nextDouble()) else 0.0
                    return PEvent(id, t, p[0] + 1, p[1] + 1, if (buttons == 0 && t == "pen") 0.0 else pressure, buttons, true, 0,
                        (clock.time - VirtualClock.T0).toDouble(), coalesced)
                }
                clock.advance(dt); entry(Action.Pointer(Action.DOWN, pe(pts[0], btn)))
                for (p in pts.drop(1)) {
                    clock.advance(dt)
                    // pens: sometimes a coalesced sample half way (as Android batches them)
                    val c = if (t == "pen" && rnd.nextInt(3) == 0) {
                        val mid = pe(doubleArrayOf(RecordCoder.quantXY(p[0] - 0.5), RecordCoder.quantXY(p[1] + 0.25)), btn)
                        listOf(mid, pe(p, btn))
                    } else null
                    entry(Action.Pointer(Action.MOVE, if (c != null) pe(p, btn, c).let { m -> PEvent(m.pointerId, m.pointerType, c[1].clientX, c[1].clientY, c[1].pressure, btn, true, 0, c[1].timeStamp, c) } else pe(p, btn)))
                }
                if (st.optBoolean("up", true)) { clock.advance(dt); entry(Action.Pointer(Action.UP, pe(pts.last(), 0))) }
            }
            "hover" -> for (p in points(st.getJSONArray("points"))) {
                clock.advance(dt)
                val t = if (rnd.nextBoolean()) "pen" else "mouse"
                entry(Action.Pointer(Action.MOVE, PEvent(penHoverId, t, p[0] + 1, p[1] + 1, 0.0, 0, true, 0, (clock.time - VirtualClock.T0).toDouble())))
            }
            "key" -> {
                val name = st.getString("name")
                val code = ScenarioRunner.KEYS[name] ?: if (name.length == 1) name.uppercase()[0].code else error("unknown key $name")
                entry(Action.Key(st.optBoolean("down", true), code, false, if (name.length == 1) name else ""))
            }
            "wait" -> { clock.advance(st.getLong("ms")); KLog.pump(clock.time) }
            else -> error("unknown op $op")
        }
        clock.runDue()
    }

    private fun points(arr: JSONArray): List<DoubleArray> =
        (0 until arr.length()).map { val p = arr.getJSONArray(it); doubleArrayOf(p.getDouble(0), p.getDouble(1)) }

    /** Runs timers until none is left (cap 60 s), checkpointing when it can, as the app's pump does. */
    fun settle(capMs: Long = 60_000): Long {
        var ran = 0L
        while (ran < capMs) {
            clock.runDue(); KLog.pump(clock.time)
            if (clock.active == 0) return ran
            clock.advance(100); ran += 100
        }
        return ran
    }

    companion object {
        fun crc(b: Bitmap): Long {
            val buf = java.nio.ByteBuffer.allocate(b.byteCount)
            b.copyPixelsToBuffer(buf)
            return CRC32().apply { update(buf.array()) }.value
        }

        /**
         * `:kidpix:replayLog`: the exported log in [exportDir] (KLog.export; adb pull) replayed
         * headless from its first checkpoint; the picture over white to [out].
         */
        fun replayExport(exportDir: File, out: File, log: (String) -> Unit = { println(it) }) {
            val work = File(System.getProperty("java.io.tmpdir"), "kp-replaylog-" + System.nanoTime())
            exportDir.copyRecursively(work, true)
            // a save point's export (saves/sp-*.json): its chain replayed from the start
            File(work, "saves").listFiles { f -> f.name.startsWith("sp-") && f.name.endsWith(".json") }?.firstOrNull()?.let { f ->
                val sp = dev.kidpix.paint.log.SavePoints.SavePoint.fromJson(dev.kidpix.paint.log.SavePoints.readCrcJson(f)!!)
                KLog.versionCode = sp.version
                val s = LogSession(work, sp.w, sp.h)
                log(s.restart())
                s.settle()
                var why: String? = "not played"; var pic: Bitmap? = null
                KLog.playSavePoint(sp.chain, sp.time, Double.POSITIVE_INFINITY, { _, g -> g() }) { r, b -> why = r; pic = b }
                log("save point ${sp.id} (chain ${sp.chain.map { it.toList() }}): ${why ?: "replayed"}")
                pic?.let { b ->
                    val flat = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(b, 0f, 0f, null) }
                    out.outputStream().use { flat.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    log("wrote $out")
                }
                work.deleteRecursively()
                return
            }
            val seg = work.listFiles()!!.filter { it.name.startsWith("seg-") }.maxBy { it.name }
            // only checkpoint zero (or the oldest kept): the whole log is replayed
            val cps = KLog.checkpoints(seg)
            val first = cps.last()
            cps.dropLast(1).forEach { File(seg, "ckpt-%06d.json".format(it.seq)).delete() }
            val w = first.o.getInt("w"); val h = first.o.getInt("h")
            KLog.versionCode = first.version
            val s = LogSession(work, w, h)
            log(s.restart())
            s.settle()
            ScenarioRunner(out.parentFile ?: File(".")).flatten(false).let { bmp ->
                out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            log("wrote $out")
            work.deleteRecursively()
        }
    }
}
