package dev.kidpix.paint.log

import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.zip.CRC32

/**
 * The safety nets, with a checkpoint whose engine state is made wrong on purpose (the current
 * tool changed from Spray Paint's SpritePlacer to AnimBrush, the old bug's state):
 * - its stored fingerprint no longer matches what the restore gives: the tail is not replayed,
 *   the checkpoint's picture stays;
 * - with the fingerprint forged to match, the per-action fingerprints in the log catch it at the
 *   first action: the replay stops, the picture goes back to the checkpoint's; nothing wrong is
 *   drawn either way.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SafetyNetTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-safety-test-" + System.nanoTime())

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    private fun stroke(s: LogSession, k: Int) {
        val pts = JSONArray()
        for (p in 0 until 4) pts.put(JSONArray().put(60 + (k * 131 + p * 197) % 900).put(50 + (k * 89 + p * 151) % 700))
        s.step(JSONObject().put("op", "stroke").put("points", pts), 16)
    }

    private fun rewrite(f: File, edit: (JSONObject) -> Unit) {
        val o = JSONObject(f.readText().split("\n")[0])
        edit(o)
        val text = o.toString()
        f.writeText(text + "\n#crc=" + "%08x".format(CRC32().apply { update(text.toByteArray()) }.value) + "\n")
    }

    @Test fun aWrongEngineStateNeverDrawsAWrongTail() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KLog.versionCode = 12
        KLog.checkpointActions = 1_000_000; KLog.checkpointCostMs = 1e12
        val dir = File(tmp, "s")
        val s = LogSession(dir); s.forceType = "mouse"; s.start()
        s.step(JSONObject().put("op", "tool").put("id", "brush"), 16)
        s.step(JSONObject().put("op", "sub").put("index", KiddoPaint.Submenu["wackybrush"]!!.indexOfFirst { it.name == "Spray Paint" }), 16)
        stroke(s, 1); s.settle()
        KLog.checkpoint()
        val atCheckpoint = crc(KDisplay.main_canvas.bitmap)
        stroke(s, 2); stroke(s, 3); s.settle()
        val live = crc(KDisplay.main_canvas.bitmap)
        assertNotEquals(atCheckpoint, live)
        KLog.drainIo(); KLog.close()

        val cp = File(dir, "seg-000001").listFiles()!!.filter { it.name.startsWith("ckpt-") && it.name.endsWith(".json") }.maxBy { it.name }
        val good = cp.readText()
        val toolKey = "2dev.kidpix.paint.KCurrent.tool"
        var animKey = ""
        rewrite(cp) { o ->
            val roots = o.getJSONObject("graph").getJSONObject("roots")
            val spray = roots.get(toolKey).toString()
            assertTrue("the tool is SpritePlacer's slot: $spray", spray.contains("SpritePlacer"))
            animKey = roots.keys().asSequence().firstOrNull { it.contains("#AnimBrush#") } ?: "1dev.kidpix.paint.tools_AnimBrush#AnimBrush#getAnimBrush(Ldev/kidpix/paint/KiddoPaint\$Tools;)Ldev/kidpix/paint/ToolboxAnimBrush;"
            roots.put(toolKey, JSONArray().put("W").put(animKey))
        }

        // 1. the stored fingerprint does not match: the checkpoint's picture, no tail
        val m1 = s.restart(); s.settle()
        println("SAFETY 1: $m1")
        assertTrue(m1, m1.contains("engine state differs, tail not replayed"))
        assertEquals("the checkpoint's picture", atCheckpoint, crc(KDisplay.main_canvas.bitmap))
        val forged = Regex("fingerprint ([0-9a-f]+) != ").find(KLog.lastApplyProblem!!)!!.groupValues[1]
        KLog.drainIo(); KLog.close()

        // 2. the fingerprint forged to the wrong state's: the per-action fingerprints stop the replay
        File(dir, "seg-000002").deleteRecursively() // the new segment the first restore started
        cp.writeText(good)
        rewrite(cp) { o ->
            o.getJSONObject("graph").getJSONObject("roots").put(toolKey, JSONArray().put("W").put(animKey))
            o.getJSONObject("graph").put("fp", forged)
        }
        val m2 = s.restart(); s.settle()
        println("SAFETY 2: $m2")
        assertEquals("the forged state passes the checkpoint's own check", null, KLog.lastApplyProblem)
        assertTrue(m2, m2.contains("engine fingerprint differs") && m2.contains("back to the checkpoint"))
        assertEquals("the checkpoint's picture, nothing drawn with the wrong tool", atCheckpoint, crc(KDisplay.main_canvas.bitmap))
        KLog.drainIo(); KLog.close()

        // 3. the untouched checkpoint: the tail replays to the live picture
        File(dir, "seg-000002").deleteRecursively()
        cp.writeText(good)
        val m3 = s.restart(); s.settle()
        println("SAFETY 3: $m3")
        assertEquals("restored exactly", live, crc(KDisplay.main_canvas.bitmap))
    }
}
