package dev.kidpix.paint.log

import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.harness.LogSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
/** What a checkpoint costs (main thread) and weighs, against the 1.1.0 description of the engine (gated: KP_PERF=1). */
class CheckpointCostTest {
    @Test fun cost() {
        org.junit.Assume.assumeTrue("KP_PERF not set", System.getenv("KP_PERF") != null)
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KLog.versionCode = 12; KLog.checkpointActions = 1_000_000; KLog.checkpointCostMs = 1e12
        val s = LogSession(File(System.getProperty("java.io.tmpdir"), "kp-cost")); s.start()
        val r = java.util.Random(3)
        val g = ArrayList<Double>(); val gs = ArrayList<Int>(); val e = ArrayList<Double>(); val es = ArrayList<Int>(); val c = ArrayList<Double>(); val enc = ArrayList<Double>(); val capOnly = ArrayList<Double>(); val str = ArrayList<Double>()
        for (round in 0 until 3) for (t in KAndroid.TOOL_IDS) {
            s.step(JSONObject().put("op", "tool").put("id", t), 16)
            val n = KiddoPaint.Submenu[KUi.currentSubmenu]?.size ?: 1
            for (k in 0 until 6) {
                if (KUi.currentSubmenu != "alphabet") s.step(JSONObject().put("op", "sub").put("index", r.nextInt(n)), 16)
                else s.step(JSONObject().put("op", "letter").put("index", r.nextInt(26)), 16)
                val pts = JSONArray(); for (p in 0 until 8) pts.put(JSONArray().put(r.nextInt(1000)).put(r.nextInt(780)))
                s.step(JSONObject().put("op", "stroke").put("points", pts), 16)
            }
            s.settle()
            if (!KLog.quiescent()) continue
            // warm both, then time
            repeat(3) { KLog.engineNow(); EngineState.capture() }
            var t0 = System.nanoTime(); val gj = KLog.engineNow().toString(); g.add((System.nanoTime() - t0) / 1e6); gs.add(gj.length)
            t0 = System.nanoTime(); EngineGraph.encode(); enc.add((System.nanoTime() - t0) / 1e6)
            t0 = System.nanoTime(); val cap = KLog.engineNow(); capOnly.add((System.nanoTime() - t0) / 1e6)
            t0 = System.nanoTime(); cap.toString(); str.add((System.nanoTime() - t0) / 1e6)
            t0 = System.nanoTime(); val ej = EngineState.capture().toString(); e.add((System.nanoTime() - t0) / 1e6); es.add(ej.length)
            val before = KLog.ckptNs; KLog.checkpoint(); c.add((KLog.ckptNs - before) / 1e6)
        }
        fun st(v: List<Double>) = "mean %.2f max %.2f".format(v.average(), v.max())
        println("COST n=${g.size} graph ${st(g)} ms, ${gs.average().toInt()} chars (max ${gs.max()}); engineState ${st(e)} ms, ${es.average().toInt()} chars (max ${es.max()}); whole checkpoint ${st(c)} ms")
        println("COST ${KLog.costs()}")
        println("COST parts: encode ${st(enc)}, capture ${st(capOnly)}, toString ${st(str)}")
        EngineGraph.encode().roots.entries.sortedByDescending { it.value.length }.take(8).forEach { (k, v) ->
            val big = Regex("\"(\\d+):([A-Za-z0-9+/=]{2000,})\"").findAll(v).map { it.groupValues[1] + "ints/" + it.groupValues[2].length }.toList()
            val px = Regex("\\[(\\d+),(\\d+),\"([A-Za-z0-9+/=]{2000,})\"\\]").findAll(v).map { it.groupValues[1] + "x" + it.groupValues[2] + "/" + it.groupValues[3].length }.toList()
            println("COSTROOT ${v.length} ${k.take(90)} msaa=$big pixels=$px") }
    }
}
