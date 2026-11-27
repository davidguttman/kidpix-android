package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.MessageDigest

/**
 * The play button (log/Watch.kt), headless: the replay ends in the live picture, and afterwards
 * the app is exactly as it was (picture, layers, undo/redo history, engine state, clock, random
 * state, every byte under the log directory), for histories with undo/redo, opens, drafts,
 * branches, firecracker segments, tools with timers; stopped at random moments; across a
 * versionCode boundary and a pruned or damaged chain; the duration scaling.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class WatchTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-watch-test-" + System.nanoTime())
    private val gallery = LinkedHashMap<Long, Bitmap>()
    private var nextMedia = 100L
    private var lastMedia = -1L

    @Before fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KCanvas.trackDirty = true
        KLog.versionCode = 11
        KLog.checkpointActions = 4
        KLog.checkpointCostMs = 1e9
        KLog.frameCapBytes = 48L shl 20
        SavePoints.capBytes = 512L shl 20
    }

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    private fun installUi() {
        KUi.ui = object : Ui {
            override fun save() {
                val sp = SavePoints.saving()
                val id = nextMedia++
                gallery[id] = KDisplay.main_canvas.snapshot()
                SavePoints.commitSave(sp, id, "h$id")
                lastMedia = id
            }
        }
    }

    private fun session(name: String): LogSession {
        val s = LogSession(File(tmp, name))
        s.forceType = "mouse"
        s.start()
        installUi()
        return s
    }

    private fun restart(s: LogSession) { s.restart(); installUi() }

    private fun stroke(s: LogSession, i: Int, dt: Long = 16) =
        s.step(JSONObject().put("op", "stroke").put("points", JSONArray("[[${20 + (i * 37) % 900},${30 + (i * 53) % 600}],[${80 + (i * 41) % 900},${90 + (i * 29) % 600}],[${40 + (i * 13) % 900},${300 + (i * 7) % 400}]]")), dt)
    private fun tap(s: LogSession, x: Int, y: Int) = s.step(JSONObject().put("op", "tap").put("point", JSONArray("[$x,$y]")), 16)
    private fun tool(s: LogSession, id: String) = s.step(JSONObject().put("op", "tool").put("id", id), 16)
    private fun sub(s: LogSession, i: Int) = s.step(JSONObject().put("op", "sub").put("index", i), 16)
    private fun wait(s: LogSession, ms: Long) = s.step(JSONObject().put("op", "wait").put("ms", ms), 16)
    private fun undo(s: LogSession) { tool(s, "undo"); wait(s, 400) }
    private fun redo(s: LogSession) { tool(s, "undo"); s.step(JSONObject().put("op", "wait").put("ms", 100), 16); tool(s, "undo"); wait(s, 400) }
    /** Eraser > Firecracker, a tap: the picture blows up (clear-all: a new segment at the next quiet moment). */
    private fun firecracker(s: LogSession) {
        val seg = KLog.segment
        tool(s, "eraser")
        sub(s, dev.kidpix.paint.KiddoPaint.Submenu["eraser"]!!.indexOfFirst { it.name == "Firecracker" })
        tap(s, 500, 400)
        s.settle()
        assertTrue("firecracker: a new segment", KLog.segment > seg)
        tool(s, "pencil")
    }
    private fun save(s: LogSession): Long { tool(s, "save"); s.settle(); return lastMedia }

    /** Everything that must be the same after watching. */
    private fun state(s: LogSession): Map<String, String> {
        KLog.drainIo()
        val m = LinkedHashMap<String, String>()
        m["main"] = crc(KDisplay.main_canvas.bitmap).toString()
        for ((n, c) in listOf("tmp" to KDisplay.canvas, "preview" to KDisplay.previewCanvas, "anim" to KDisplay.animCanvas, "bnim" to KDisplay.bnimCanvas))
            m[n] = crc(c.bitmap).toString()
        m["past"] = History.pastIds.toString(); m["future"] = History.futureIds.toString(); m["next"] = History.nextId.toString()
        m["engine"] = EngineState.capture().toString()
        m["random"] = KRandom.state.toString()
        m["clock"] = KLog.clock.time.toString()
        m["active"] = KLog.clock.active.toString()
        m["log"] = KLog.describe()
        m["files"] = dirHash(s.dir)
        return m
    }

    private fun dirHash(d: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        d.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f -> md.update(f.relativeTo(d).path.toByteArray()); md.update(f.readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun same(what: String, a: Map<String, String>, b: Map<String, String>) {
        val diff = a.keys.filter { a[it] != b[it] }
        if (diff.isEmpty()) return
        val sb = StringBuilder("$what: differs in $diff")
        for (k in diff) {
            val x = a[k]!!; val y = b[k] ?: ""
            val i = x.indices.firstOrNull { it >= y.length || x[it] != y[it] } ?: x.length
            sb.append("\n  $k: before ..${x.substring(maxOf(0, i - 80), minOf(x.length, i + 120))}\n  $k: after  ..${y.substring(maxOf(0, i - 80), minOf(y.length, i + 120))}")
        }
        throw AssertionError(sb.toString())
    }

    /** Plays the replay on a fake wall clock (16 ms a frame); [stopAfterSteps]: a tap after that many frames. */
    private class Run(val watch: Watch?, val no: Any?, val wallMs: Long, val steps: Int)

    private fun watch(stopAfterSteps: Int = -1): Run {
        var now = 1_000_000L
        val q = ArrayDeque<Pair<Long, () -> Unit>>()
        var ended = 0
        val r = Watch.start({ d, f -> q.add(d to f) }, { now }, null, {}, { ended++ })
        if (r !is Watch) return Run(null, r, 0, 0)
        var steps = 0
        while (q.isNotEmpty()) {
            val (d, f) = q.removeFirst()
            now += d
            if (steps == stopAfterSteps) { r.stop(); break }
            f(); steps++
            assertTrue("never stuck: $steps steps ${r.describe()}", steps < 10_000)
        }
        assertTrue(r.finished)
        assertEquals("onEnd once", 1, ended)
        assertFalse(KLog.playing)
        return Run(r, null, now - 1_000_000L, steps)
    }

    /** Watches to the end: the replay ends in the live picture, and everything is as it was. */
    private fun watchAndCheck(s: LogSession, what: String, exactEnd: Boolean = true): Watch {
        s.settle()
        val before = state(s)
        val run = watch()
        val w = run.watch ?: error("$what: did not start (${run.no})")
        assertFalse(w.stopped)
        if (exactEnd) assertEquals("$what: the replay ends in the live picture", before["main"], crc(w.replayEnd!!).toString())
        same("$what: everything as it was", before, state(s))
        println("watch $what: ${w.recordsPlayed} records, engine ${w.engineMs} ms, planned ${w.plannedMs} ms, took ${run.wallMs} ms, pieces picture-only ${w.piecesPictureOnly}, cut ${w.piecesCut}")
        return w
    }

    /** Drawing goes on after watching, and a restart rebuilds exactly what is on the screen. */
    private fun continuesAndRestores(s: LogSession, i: Int) {
        stroke(s, i); undo(s); stroke(s, i + 1); s.settle()
        val live = crc(KDisplay.main_canvas.bitmap)
        val hist = History.pastIds.toString() + History.futureIds
        restart(s)
        assertEquals("restart after watching: picture", live, crc(KDisplay.main_canvas.bitmap))
        assertEquals("restart after watching: history", hist, History.pastIds.toString() + History.futureIds)
    }

    // ------------------------------------------------------------------ scenario groups

    @Test fun strokesWithUndoAndRedo() {
        val s = session("undo")
        repeat(7) { stroke(s, it) }
        undo(s); undo(s); undo(s); redo(s)
        s.settle()
        assertTrue(History.canRedo)
        watchAndCheck(s, "undo/redo")
        continuesAndRestores(s, 40)
        watchAndCheck(s, "after a restart")
    }

    @Test fun manyToolsWithTimersAndSounds() {
        val s = session("tools")
        tool(s, "brush"); for (k in 0 until 6) { sub(s, k); stroke(s, k) }
        tool(s, "stamp"); tap(s, 300, 300); tap(s, 500, 200)
        tool(s, "alphabet"); s.step(JSONObject().put("op", "letter").put("index", 3), 16); tap(s, 200, 500)
        tool(s, "circle"); stroke(s, 20); tool(s, "square"); stroke(s, 21); tool(s, "line"); stroke(s, 22)
        tool(s, "flood"); tap(s, 700, 600)
        tool(s, "eraser"); sub(s, 1); stroke(s, 23)
        tool(s, "jumble"); sub(s, 20); stroke(s, 24) // a CPU mixer
        s.settle()
        watchAndCheck(s, "tools")
        continuesAndRestores(s, 60)
    }

    @Test fun firecrackerSegments() {
        val s = session("fire")
        repeat(3) { stroke(s, it) }
        firecracker(s)
        repeat(3) { stroke(s, 10 + it) }
        undo(s) // undo across the clear
        s.settle()
        val w = watchAndCheck(s, "firecracker")
        assertTrue("several pieces", KLog.chainPrefix.isNotEmpty())
        assertEquals(0, w.piecesCut)
        continuesAndRestores(s, 70)
    }

    @Test fun opensDraftsAndBranches() {
        val s = session("open")
        repeat(4) { stroke(s, it) }
        val sp1 = SavePoints.forMedia(save(s))!!
        repeat(3) { stroke(s, 10 + it) }
        s.settle()
        // open the save point: the drawing becomes a draft, the canvas the save point with its history
        assertTrue(SavePoints.open(sp1, SavePoints.picture(sp1)!!)); s.settle()
        watchAndCheck(s, "opened save point (no new drawing)")
        repeat(2) { stroke(s, 20 + it) } // a branch
        s.settle()
        watchAndCheck(s, "branch")
        save(s)
        val d = SavePoints.drafts().single()
        assertTrue(SavePoints.open(d, SavePoints.picture(d)!!)); s.settle()
        watchAndCheck(s, "draft")
        stroke(s, 30); s.settle()
        watchAndCheck(s, "draft drawn on")
        // a picture opened flat (no history): its own picture is the start
        val flat = KDisplay.main_canvas.snapshot()
        assertTrue(SavePoints.openFlat(flat)); s.settle()
        assertEquals("nothing drawn on a flat picture: nothing to replay", Watch.Companion.No.NOTHING, watch().no)
        stroke(s, 31); s.settle()
        watchAndCheck(s, "flat picture drawn on")
        continuesAndRestores(s, 80)
    }

    @Test fun stoppedAtRandomMomentsLeavesEverythingAsItWas() {
        val s = session("stop")
        tool(s, "brush"); sub(s, 2)
        repeat(12) { stroke(s, it) }
        undo(s)
        firecracker(s)
        repeat(5) { stroke(s, 30 + it) }
        s.settle()
        val before = state(s)
        val full = watch().steps
        val rnd = java.util.Random(3)
        repeat(12) { n ->
            val at = if (n == 0) 0 else rnd.nextInt(full + 1)
            val run = watch(at)
            same("stopped after $at of $full frames: everything as it was", before, state(s))
            if (at < full) assertTrue(run.watch!!.stopped)
        }
        continuesAndRestores(s, 50)
    }

    @Test fun anOlderVersionsPiecesShowTheirPictureAndTheRestReplays() {
        val s = session("version")
        repeat(6) { stroke(s, it) } // a checkpoint after 4, then 2 more: a tail the next version will not replay
        s.settle()
        KLog.close()
        KLog.versionCode = 12
        restart(s) // a new segment from the checkpoint (the 2 strokes after it are gone, by the log's version rule)
        val segs = KLog.chainPrefix.size
        assertEquals("a new segment continuing the older one", 1, segs)
        repeat(3) { stroke(s, 10 + it) }
        s.settle()
        val w = watchAndCheck(s, "version boundary (new segment)")
        assertEquals("the older segment shows only its picture", 1, w.piecesPictureOnly)
        continuesAndRestores(s, 20)
        // an update with nothing after the last checkpoint goes on in the same segment: the replay
        // starts at that checkpoint (everything after it is this version's)
        val v = session("version2")
        repeat(4) { stroke(v, it) } // checkpoint at 4, no tail
        v.settle()
        KLog.close(); KLog.versionCode = 13; restart(v)
        assertTrue("same segment", KLog.chainPrefix.isEmpty())
        assertEquals("nothing drawn by this version: nothing to replay", Watch.Companion.No.NOTHING, watch().no)
        repeat(3) { stroke(v, 10 + it) }
        v.settle()
        val w2 = watchAndCheck(v, "version boundary (same segment)")
        assertEquals(0, w2.piecesPictureOnly)
        assertTrue("only this version's strokes replayed: ${w2.recordsPlayed}", w2.recordsPlayed in 1..15)
    }

    @Test fun aPrunedOrDamagedChainStillEndsInTheLivePicture() {
        val s = session("pruned")
        repeat(3) { stroke(s, it) }
        firecracker(s)
        repeat(3) { stroke(s, 10 + it) }
        firecracker(s)
        repeat(3) { stroke(s, 20 + it) }
        s.settle(); KLog.drainIo()
        val chain = KLog.chainPrefix.map { it[0] }
        assertTrue("two earlier segments: $chain", chain.size >= 2)
        // the first one damaged (its log cut in the middle of a block), the second one gone
        val log1 = File(s.dir, "seg-%06d/log.kpl".format(chain[0]))
        log1.writeBytes(log1.readBytes().let { it.copyOf(it.size * 2 / 3) })
        File(s.dir, "seg-%06d".format(chain[1])).deleteRecursively()
        val w = watchAndCheck(s, "pruned + damaged")
        assertEquals("the damaged piece shows its picture", 1, w.piecesPictureOnly)
        continuesAndRestores(s, 30)
    }

    @Test fun aRandomStateMismatchCutsThatPieceAndTheEndIsStillTheLivePicture() {
        val s = session("mismatch")
        tool(s, "brush"); sub(s, 3) // a brush that draws random numbers
        repeat(4) { stroke(s, it) }
        firecracker(s)
        repeat(3) { stroke(s, 10 + it) }
        s.settle(); KLog.drainIo()
        // checkpoint zero of the first piece with another random state: its replay no longer matches
        val seg = KLog.chainPrefix.first()[0]
        val f = File(s.dir, "seg-%06d/ckpt-000000.json".format(seg))
        val o = JSONObject(f.readText().lines()[0]).put("random", 12345)
        val text = o.toString()
        val crc = java.util.zip.CRC32().apply { update(text.toByteArray()) }.value
        f.writeText(text + "\n#crc=" + "%08x".format(crc) + "\n")
        val w = watchAndCheck(s, "random mismatch")
        assertEquals(1, w.piecesCut)
    }

    @Test fun blankPageHasNothingToReplayAndBusyDoesNotStart() {
        val s = session("blank")
        s.settle()
        val before = state(s)
        assertEquals(Watch.Companion.No.NOTHING, watch().no)
        same("blank", before, state(s))
        tool(s, "pencil") // a tool tap draws nothing
        s.settle()
        assertEquals(Watch.Companion.No.NOTHING, watch().no)
        // a stroke still down: busy, nothing changes
        s.step(JSONObject().put("op", "stroke").put("up", false).put("points", JSONArray("[[10,10],[50,50]]")), 16)
        assertEquals(Watch.Companion.No.BUSY, watch().no)
        assertTrue(KAndroid.state.activeId != null)
    }

    @Test fun durationScalesWithTheSession() {
        // a tiny picture: not over in a blink
        val s = session("short")
        stroke(s, 1); stroke(s, 2); stroke(s, 3)
        val short = watchAndCheck(s, "3 strokes")
        val shortRun = watch()
        assertTrue("3 strokes take at least ~2 s: ${shortRun.wallMs}", shortRun.wallMs in 1900..4000)
        KLog.close()
        // a long session with idle gaps: 220 actions over ~10 minutes
        val l = session("long")
        for (k in 0 until 110) {
            stroke(l, k, dt = 40)
            if (k % 10 == 0) wait(l, 30_000) // the child stops for a while
            if (k % 7 == 0) undo(l)
        }
        l.settle()
        val long = watchAndCheck(l, "long session")
        val longRun = watch()
        println("durations: short engine ${short.engineMs} ms -> ${shortRun.wallMs} ms; long engine ${long.engineMs} ms -> ${longRun.wallMs} ms")
        assertTrue("idle gaps are skipped: ${long.engineMs}", long.engineMs < 110 * 600L + 110 * 200)
        assertTrue("a long session takes 6-20 s (+ beats): ${longRun.wallMs}", longRun.wallMs in 6000..22_000)
        assertTrue(longRun.wallMs > shortRun.wallMs)
    }
}
