package dev.kidpix.paint.log

import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Action logs written by 1.1.0 (versionCode 10) and by 1.2.0 before this change (versionCode 11),
 * made by those builds (test resources `migration/v10`, `migration/v11`: pencil strokes, Brush >
 * Spray Paint strokes, two undos, an oval; then a pause checkpoint; `killed` also has a tail of
 * strokes after it). This build (versionCode 12) restores the checkpoint's picture and every undo
 * step from the frames exactly, never replays the older tail, takes a checkpoint of its own
 * format at once, and from then on a kill changes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class MigrationTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-migration-test-" + System.nanoTime())

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    private fun fixture(path: String): File {
        val u = javaClass.classLoader!!.getResource("migration/$path.json") ?: error("fixture $path missing")
        return File(u.toURI()).parentFile
    }

    private fun stroke(s: LogSession, k: Int) {
        val pts = JSONArray()
        for (p in 0 until 4) pts.put(JSONArray().put(10 + (k * 37 + p * 53) % 280).put(10 + (k * 29 + p * 41) % 180))
        s.step(JSONObject().put("op", "stroke").put("points", pts), 16)
    }

    @Test fun olderLogsRestoreTheirPictureAndEveryUndoStepExactly() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KLog.checkpointActions = 1_000_000; KLog.checkpointCostMs = 1e12
        KLog.versionCode = 12
        for (v in listOf("v10", "v11")) for (case in listOf("paused", "killed")) {
            val what = "$v/$case"
            val base = fixture("$v/$case")
            val want = JSONObject(File(base, "$case.json").readText())
            val dir = File(tmp, "$v-$case")
            File(base, case).copyRecursively(dir)
            val s = LogSession(dir, 300, 200)
            s.forceType = "mouse"
            val msg = s.restart()
            println("MIGRATION $what: $msg")
            s.settle()
            assertEquals("$what: the picture", want.getLong("main"), crc(KDisplay.main_canvas.bitmap))
            val past = want.getJSONArray("past").let { a -> (0 until a.length()).map { a.getLong(it) } }
            assertEquals("$what: the undo depth", past.size, History.pastIds.size)
            assertEquals("$what: the undo pictures", past, History.pastIds.map { crc(History.frames.get(it)!!) })
            if (case == "killed") assertTrue("$what: the older tail is not replayed ($msg)", msg.contains("not replayed") || msg.contains("versionCode"))
            // a checkpoint of this build's own format right away
            val cps = KLog.checkpoints(KLog.segmentDir()!!)
            assertTrue("$what: a checkpoint keeping the engine state ($msg)", cps.first().o.has("graph"))
            // every undo step gives back the stored picture
            for (k in past.indices.reversed()) {
                s.step(JSONObject().put("op", "tool").put("id", "undo"), 16); s.step(JSONObject().put("op", "wait").put("ms", 400), 16)
                assertEquals("$what: undo to step $k", past[k], crc(KDisplay.main_canvas.bitmap))
            }
            // redo all, draw on, kill: identical
            repeat(past.size) {
                s.step(JSONObject().put("op", "tool").put("id", "undo"), 16); s.step(JSONObject().put("op", "wait").put("ms", 100), 16)
                s.step(JSONObject().put("op", "tool").put("id", "undo"), 16); s.step(JSONObject().put("op", "wait").put("ms", 400), 16)
            }
            assertEquals("$what: redone to the picture", want.getLong("main"), crc(KDisplay.main_canvas.bitmap))
            stroke(s, 20); stroke(s, 21); s.settle()
            val live = crc(KDisplay.main_canvas.bitmap); val hist = History.pastIds.size
            KLog.drainIo()
            s.restart(); s.settle()
            assertEquals("$what: after drawing on, a kill changes nothing", live, crc(KDisplay.main_canvas.bitmap))
            assertEquals("$what: nor the undo depth", hist, History.pastIds.size)
            KLog.close()
        }
    }
}
