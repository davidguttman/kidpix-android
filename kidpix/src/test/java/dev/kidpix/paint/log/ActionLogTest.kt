package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile

/**
 * The action log's guarantees, headless: a recorded session replays to the same pixels (from
 * checkpoint zero and from every later checkpoint), a torn log restores the last whole action,
 * the undo history survives a restart, a newer versionCode never replays an older tail, the
 * storage cap holds. Sessions are the reference scenario files driven the app's way (pointer
 * events of mice, pens with pressure and coalesced samples, fingers), see harness/LogSessions.kt.
 *
 * KP_LOGPROP=all runs every scenario of every group (default: every 5th).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ActionLogTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-actionlog-test-" + System.nanoTime())
    private val refDir = File(File(System.getProperty("kidpix.defaultScenarios")!!).parentFile.parentFile.parentFile, "ref")

    @Before fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KCanvas.trackDirty = true
        KLog.versionCode = 10
        KLog.checkpointActions = 4
        KLog.checkpointCostMs = 1e9
        KLog.frameCapBytes = 48L shl 20
    }

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    private fun mainCrc() = crc(KDisplay.main_canvas.bitmap)

    // ------------------------------------------------------------------ codec

    @Test fun recordsRoundTripExactly() {
        val rnd = java.util.Random(3)
        val o = Bytes()
        val enc = RecordCoder(); enc.reset(1000)
        val acts = ArrayList<Pair<Action, Long>>()
        var t = 1000L
        repeat(500) { i ->
            t += rnd.nextInt(40)
            fun d() = if (rnd.nextBoolean()) RecordCoder.quantXY(rnd.nextDouble() * 1000) else rnd.nextDouble() * 1000
            fun pe(c: List<PEvent>? = null) = PEvent(rnd.nextInt(5), listOf("pen", "mouse", "touch", "eraser?")[rnd.nextInt(4)], d(), d(),
                if (rnd.nextBoolean()) RecordCoder.quantPressure(rnd.nextDouble()) else rnd.nextDouble(), rnd.nextInt(40), rnd.nextBoolean(),
                rnd.nextInt(3), if (rnd.nextBoolean()) t.toDouble() else t + 0.25, c)
            val a: Action = when (i % 12) {
                0 -> Action.Pointer(Action.DOWN, pe())
                1 -> Action.Pointer(Action.MOVE, pe(listOf(pe(), pe())))
                2 -> Action.Pointer(Action.UP, pe())
                3 -> Action.Wheel(rnd.nextGaussian())
                4 -> Action.Tool("stamp", rnd.nextBoolean())
                5 -> Action.Sub(rnd.nextInt(30), rnd.nextBoolean(), rnd.nextBoolean())
                6 -> Action.Letter(rnd.nextInt(27))
                7 -> Action.Color(rnd.nextInt(32), 1 + rnd.nextInt(3))
                8 -> Action.Key(rnd.nextBoolean(), rnd.nextInt(200), rnd.nextBoolean(), "ä")
                9 -> Action.Open("in-0001.kpx")
                10 -> Action.Shift(rnd.nextInt(100000).toLong())
                else -> Action.Pointer(Action.MOVE, pe())
            }
            enc.put(o, a, t, i * 7919)
            acts.add(a to t)
        }
        val dec = RecordCoder(); dec.reset(1000)
        val r = Reader(o.toByteArray())
        for ((i, p) in acts.withIndex()) {
            val rec = dec.get(r)
            assertEquals(p.second, rec.time)
            assertEquals(describe(p.first), describe(rec.action))
            if (p.first.starts) assertEquals(i * 7919, rec.random)
        }
        assertTrue(!r.more())
    }

    private fun describe(e: PEvent): String = "${e.pointerId} ${e.pointerType} ${e.clientX.toRawBits()} ${e.clientY.toRawBits()} " +
        "${e.pressure.toRawBits()} ${e.buttons} ${e.isPrimary} ${e.button} ${e.timeStamp.toRawBits()} ${e.coalescedOrNull?.map { describe(it) }}"

    private fun describe(a: Action): String = when (a) {
        is Action.Pointer -> "P${a.kind} ${describe(a.e)}"
        is Action.Wheel -> "W ${a.delta.toRawBits()}"
        is Action.Tool -> "T ${a.id} ${a.ctrl}"
        is Action.Sub -> "S ${a.index} ${a.contextmenu} ${a.ctrl}"
        is Action.Letter -> "L ${a.index}"
        is Action.Color -> "C ${a.index} ${a.which}"
        is Action.Palette -> "N ${a.next}"
        is Action.Key -> "K ${a.down} ${a.keyCode} ${a.ctrl} ${a.key}"
        is Action.Open -> "O ${a.name}"
        is Action.Pause -> "PAUSE"
        is Action.Shift -> "SH ${a.d}"
        is Action.Opened -> "OPENED"
    }

    @Test fun aTwoThousandPointPenStrokeIsAFewKB() {
        val s = LogSession(File(tmp, "long"))
        s.start()
        val before = File(KLog.segmentDir(), "log.kpl").length()
        val rnd = java.util.Random(1)
        var x = 300.0; var y = 300.0; var vx = 1.3; var vy = 0.7
        var t = 0.0
        fun pe(buttons: Int) = PEvent(5, "pen", RecordCoder.quantXY(x), RecordCoder.quantXY(y),
            RecordCoder.quantPressure(0.4 + 0.1 * Math.sin(t / 50)), buttons, true, 0, t)
        KLog.entry(Action.Pointer(Action.DOWN, pe(1)))
        repeat(2000) {
            vx += (rnd.nextDouble() - 0.5) * 0.3; vy += (rnd.nextDouble() - 0.5) * 0.3
            x = (x + vx).coerceIn(10.0, 1000.0); y = (y + vy).coerceIn(10.0, 700.0); t += 4.0
            s.clock.advance(4)
            KLog.entry(Action.Pointer(Action.MOVE, pe(1)))
        }
        s.clock.advance(4)
        KLog.entry(Action.Pointer(Action.UP, pe(0)))
        val bytes = File(KLog.segmentDir(), "log.kpl").length() - before
        println("2000-point pen stroke: $bytes bytes in the log")
        assertTrue("2000-point stroke costs $bytes bytes", bytes < 8 * 1024)
    }

    // ------------------------------------------------------------------ the property: replay == live

    private fun scenarioFiles() = File(refDir, "scenarios").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }

    /** Restores the session in [dir] from checkpoint [cp] (a copy: nothing newer) and settles; the picture's crc. */
    private fun restoreFrom(dir: File, seg: Int, cp: Int): Long {
        val copy = File(tmp, "copy"); copy.deleteRecursively(); dir.copyRecursively(copy)
        copy.listFiles()!!.filter { it.name.startsWith("seg-") && it.name.removePrefix("seg-").toInt() > seg }.forEach { it.deleteRecursively() }
        val sd = File(copy, "seg-%06d".format(seg))
        sd.listFiles()!!.filter { it.name.startsWith("ckpt-") && it.name.removePrefix("ckpt-").removeSuffix(".json").toInt() > cp }.forEach { it.delete() }
        val s = LogSession(copy)
        s.restart()
        s.settle()
        return mainCrc()
    }

    @Test fun everyScenarioReplaysToTheSamePixels() {
        val all = System.getenv("KP_LOGPROP") == "all"
        var n = 0; var checked = 0
        val failures = ArrayList<String>()
        for (f in scenarioFiles()) {
            val cfg = JSONObject(f.readText())
            val scs = cfg.getJSONArray("scenarios")
            for (i in 0 until scs.length()) {
                if (!all && n++ % 5 != 0) continue
                val sc = scs.getJSONObject(i)
                val id = sc.getString("id")
                val dir = File(tmp, "s")
                val s = LogSession(dir, rnd = java.util.Random(id.hashCode().toLong()))
                try {
                    s.start(if (sc.optBoolean("base", false)) File(refDir, "base.png") else null, sc.optInt("seed", cfg.optInt("seed", 1234)))
                    val dt = sc.optLong("dt", cfg.optLong("dt", 16))
                    val steps = sc.getJSONArray("steps")
                    for (j in 0 until steps.length()) s.step(steps.getJSONObject(j), dt)
                    s.settle()
                } catch (t: Throwable) {
                    if (generateSequence(t) { it.cause }.any { it is dev.kidpix.paint.GlfxNeedsGL }) { KLog.close(); continue }
                    throw AssertionError("$id live run failed", t)
                }
                val live = mainCrc()
                KLog.close()
                // every checkpoint of every segment: restore + replay the rest of its segment
                val segs = dir.listFiles()!!.filter { it.name.startsWith("seg-") }.map { it.name.removePrefix("seg-").toInt() }.sorted()
                for (seg in segs) {
                    val sd = File(dir, "seg-%06d".format(seg))
                    val expect = if (seg == segs.last()) live else {
                        val z = KLog.checkpoints(File(dir, "seg-%06d".format(seg + 1))).last()
                        val fs = FrameStore(FrameStore.DirBackend(File(dir, "frames")), null)
                        crc(fs.get(z.o.getLong("main"))!!)
                    }
                    for (cp in KLog.checkpoints(sd).map { it.seq }.sorted()) {
                        val got = try { restoreFrom(dir, seg, cp) } catch (t: Throwable) {
                            if (generateSequence(t) { it.cause }.any { it is dev.kidpix.paint.GlfxNeedsGL }) continue
                            failures.add("$id seg $seg ckpt $cp: ${t}"); continue
                        }
                        checked++
                        if (got != expect) failures.add("$id seg $seg ckpt $cp: ${KLog.lastRestore}")
                        KLog.close()
                    }
                }
            }
        }
        println("replay property: $checked restores checked, ${failures.size} differ")
        failures.forEach { println("  DIFF $it") }
        assertTrue("replays that differ:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    // ------------------------------------------------------------------ kills

    /** A long session over many tools with undos, from the scenario files. */
    private fun longSession(s: LogSession, seed: Long, steps: Int) {
        val rnd = java.util.Random(seed)
        val pool = ArrayList<JSONObject>()
        for (f in scenarioFiles()) {
            val scs = JSONObject(f.readText()).getJSONArray("scenarios")
            for (i in 0 until scs.length()) {
                val sts = scs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until sts.length()) pool.add(sts.getJSONObject(j))
            }
        }
        // no glfx mixers headless (they need GL): skip the jumble tool's GL options by never pressing jumble
        val usable = pool.filter { !(it.optString("op") == "tool" && it.optString("id") == "jumble") }
        repeat(steps) {
            val st = usable[rnd.nextInt(usable.size)]
            s.step(st, 16)
            if (rnd.nextInt(8) == 0) s.step(JSONObject().put("op", "tool").put("id", "undo"), 16)
            if (rnd.nextInt(6) == 0) s.step(JSONObject().put("op", "wait").put("ms", 400), 16)
        }
        s.settle()
    }

    @Test fun aTornLogRestoresTheLastWholeAction() {
        val dir = File(tmp, "torn")
        val s = LogSession(dir)
        s.start()
        longSession(s, 11, 120)
        KLog.close()
        val seg = dir.listFiles()!!.filter { it.name.startsWith("seg-") }.maxBy { it.name }
        val segNo = seg.name.removePrefix("seg-").toInt()
        val log = File(seg, "log.kpl")
        val parsed = LogFormat.read(log)
        val rnd = java.util.Random(5)
        var compared = 0
        repeat(12) {
            val copy = File(tmp, "torncopy"); copy.deleteRecursively(); dir.copyRecursively(copy)
            val cseg = File(copy, seg.name); val clog = File(cseg, "log.kpl")
            val cut = 40 + rnd.nextInt((log.length() - 40).toInt())
            RandomAccessFile(clog, "rw").use { it.setLength(cut.toLong()) }
            if (rnd.nextBoolean()) RandomAccessFile(clog, "rw").use { it.seek(it.length()); it.write(ByteArray(rnd.nextInt(50)) { 0x5a }) } // garbage after the tear
            val kept = LogWriter.completeBlocks(LogFormat.read(clog)).size
            // checkpoints are written after the blocks they have seen: none can be newer than the tear
            KLog.checkpoints(cseg).filter { it.blocks > kept }.forEach { File(cseg, "ckpt-%06d.json".format(it.seq)).delete() }
            val r = LogSession(copy)
            val msg = r.restart()
            val want = s.afterBlock[segNo * 100_000 + kept]
            if (want != null && KLog.checkpoints(cseg).isNotEmpty()) {
                compared++
                assertEquals("cut at $cut ($kept of ${parsed.blocks.size} blocks): $msg", want, mainCrc())
            }
            KLog.close()
        }
        println("torn logs: $compared of 12 cuts compared")
        assertTrue(compared >= 4)
    }

    @Test fun undoHistorySurvivesARestart() {
        val dir = File(tmp, "undo")
        val s = LogSession(dir)
        s.forceType = "mouse" // every stroke draws (a finger right after a pen would be a palm)
        s.start()
        val before = ArrayList<Long>()
        repeat(45) { i ->
            before.add(mainCrc())
            s.step(JSONObject().put("op", "stroke").put("points", org.json.JSONArray("[[${10 + i * 20},20],[${10 + i * 20},${200 + i * 5}],[${30 + i * 20},400]]")), 16)
        }
        s.settle()
        val end = mainCrc()
        val msg = s.restart()
        assertEquals("restored picture ($msg)", end, mainCrc())
        for (i in 44 downTo 0) {
            s.step(JSONObject().put("op", "tool").put("id", "undo"), 16)
            s.step(JSONObject().put("op", "wait").put("ms", 400), 16)
            assertEquals("undo to before stroke $i", before[i], mainCrc())
        }
        // and the redo side survives too
        s.settle(); val msg2 = s.restart()
        assertEquals("after undos ($msg2)", before[0], mainCrc())
        s.step(JSONObject().put("op", "tool").put("id", "undo"), 16)
        s.step(JSONObject().put("op", "tool").put("id", "undo"), 16) // double tap: forward (redo)
        assertEquals(before[1], mainCrc())
    }

    @Test fun aNewerVersionKeepsTheCheckpointAndNeverReplaysAnOlderTail() {
        val dir = File(tmp, "ver")
        val s = LogSession(dir)
        KLog.checkpointActions = 1000 // one checkpoint (zero), then a tail
        s.start()
        val zero = mainCrc()
        repeat(5) { i -> s.step(JSONObject().put("op", "stroke").put("points", org.json.JSONArray("[[${50 + i * 40},50],[${60 + i * 40},300]]")), 16) }
        s.settle()
        val live = mainCrc()
        assertNotEquals(zero, live)
        KLog.close()
        KLog.versionCode = 11
        val msg = s.restart()
        assertTrue(msg, msg.contains("not replayed"))
        assertEquals("the checkpoint picture", zero, mainCrc())
        assertEquals("a new segment from here", 2, KLog.segment)
        // same version: the tail replays
        KLog.close(); KLog.versionCode = 10
        dir.listFiles()!!.filter { it.name == "seg-000002" }.forEach { it.deleteRecursively() }
        s.restart()
        assertEquals(live, mainCrc())
    }

    @Test fun theStorageCapDropsOldestUndoStepsNeverThePicture() {
        val dir = File(tmp, "cap")
        KLog.frameCapBytes = 600_000
        val s = LogSession(dir)
        s.start()
        s.step(JSONObject().put("op", "tool").put("id", "flood"), 16)
        repeat(30) { i ->
            s.step(JSONObject().put("op", "color").put("index", i % 32), 16)
            s.step(JSONObject().put("op", "tap").put("points", org.json.JSONArray("[[${100 + i},100]]")), 16)
            s.step(JSONObject().put("op", "tool").put("id", "pencil"), 16)
            s.step(JSONObject().put("op", "stroke").put("points", org.json.JSONArray("[[${i * 30},10],[${i * 30 + 200},700]]")), 16)
            s.step(JSONObject().put("op", "tool").put("id", "flood"), 16)
        }
        s.settle()
        val live = mainCrc()
        val fs = History.frames
        val reachable = fs.bytesOf(fs.closure(History.pastIds + History.futureIds))
        println("cap: history ${History.pastIds.size} steps, $reachable reachable bytes, ${fs.totalBytes} stored")
        assertTrue(History.pastIds.size < 60)
        assertTrue(reachable < 600_000 + 400_000)
        s.restart()
        assertEquals(live, mainCrc())
    }

    @Suppress("unused") private fun unused(b: Bitmap) = b
}
