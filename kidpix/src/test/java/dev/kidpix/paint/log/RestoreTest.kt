package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * A kill never changes anything. Every case runs twice from the same start: once straight
 * through, once killed (the log closed, the engine's memory scrambled by another session, then
 * a restart from what is on disk) after a forced checkpoint and more drawing. At the kill and at
 * every step after it, both runs must agree on: the picture and the overlay layers (pixels), the
 * whole engine state (every root of [EngineGraph]: every tool's fields, the options, the
 * contexts, KRandom), the option bar's highlights, the undo/redo depth, and the pictures of the
 * next strokes, undos and redos. The play button's replay of the killed run must end in its live
 * picture. Cases: every option of every tool's submenu, plain, ctrl- and long-pressed, every
 * option of a submenu a press opens (the spray sizes), every letter on every page, stamp and
 * sprite pages, palette pages and every colour, every modifier key held across the kill, the
 * pen's eraser end; then random sequences of all of it with checkpoints, kills, save points,
 * opens and drafts.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RestoreTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-restore-test-" + System.nanoTime())
    private val gallery = LinkedHashMap<Long, Bitmap>()
    private var nextMedia = 100L

    @Before fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KCanvas.trackDirty = System.getenv("KP_NODIRTY") == null
        if (System.getenv("KP_NOMSAAINC") != null) dev.kidpix.paint.canvas.Ctx2D.msaaIncremental = false
        KLog.versionCode = 12
        KLog.checkpointActions = 1_000_000 // only the checkpoints a case forces
        KLog.checkpointCostMs = 1e12
        KLog.frameCapBytes = 48L shl 20
        SavePoints.capBytes = 512L shl 20
        EngineGraph.keepLastCapture = true
        FrameStore.logError = { m, t -> System.err.println("$m: $t"); if (System.getenv("KP_STACKS") != null) t.printStackTrace() }
    }

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    private fun installUi() {
        KUi.ui = object : Ui {
            override fun save() {
                val sp = SavePoints.saving()
                val id = nextMedia++
                gallery[id] = KDisplay.main_canvas.snapshot()
                SavePoints.commitSave(sp, id, "h$id")
            }
        }
    }

    // ------------------------------------------------------------------ steps

    private fun j(vararg kv: Pair<String, Any>) = JSONObject().also { o -> kv.forEach { (k, v) -> o.put(k, v) } }
    private fun tool(id: String) = j("op" to "tool", "id" to id)
    private fun sub(i: Int, ctrl: Boolean = false, ctx: Boolean = false) = j("op" to "sub", "index" to i, "ctrl" to ctrl, "contextmenu" to ctx)
    private fun stroke(k: Int, type: String = "", buttons: Int = 1): JSONObject {
        val pts = JSONArray()
        for (p in 0 until 4) pts.put(JSONArray().put(60 + (k * 131 + p * 197) % 940).put(50 + (k * 89 + p * 151) % 700))
        return j("op" to "stroke", "points" to pts, "type" to type, "buttons" to buttons)
    }
    private fun wait(ms: Long) = j("op" to "wait", "ms" to ms)
    private val undo = listOf(tool("undo"), wait(400))
    private val redo = listOf(tool("undo"), wait(100), tool("undo"), wait(400))

    /** Special steps (not LogSession ops): "checkpoint", "kill", "save", "open" (the newest save point), "openDraft". */
    private fun special(op: String) = j("op" to "~$op")

    // ------------------------------------------------------------------ running a case

    class Obs(val label: String, val pictures: Map<String, Long>, val graph: Map<String, String>, val ui: String, val history: String, val time: Long)

    private var dumpDir: File? = null
    private fun observe(label: String): Obs {
        KLog.drainIo()
        dumpDir?.let { d -> d.mkdirs(); File(d, "%02d.png".format(d.list()!!.size)).outputStream().use { KDisplay.main_canvas.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        val p = LinkedHashMap<String, Long>()
        p["main"] = crc(KDisplay.main_canvas.bitmap)
        for ((n, c) in listOf("tmp" to KDisplay.canvas, "preview" to KDisplay.previewCanvas, "anim" to KDisplay.animCanvas, "bnim" to KDisplay.bnimCanvas)) p[n] = crc(c.bitmap)
        val g = EngineGraph.encode().roots.filterKeys { it != "3~clock" }
        val ui = "${KUi.currentSubmenu} bar=${KAndroid.barName} lit=${KAndroid.selectedOptions().sorted()} letter=${KAndroid.selectedLetter()} tool=${KAndroid.currentToolId}"
        val hist = "undo ${History.pastIds.size} redo ${History.futureIds.size} canUndo ${History.canUndo}"
        return Obs(label, p, g, ui, hist, KLog.clock.time)
    }

    private fun same(what: String, a: Obs, b: Obs) {
        val sb = StringBuilder()
        for ((k, v) in a.pictures) if (b.pictures[k] != v) sb.append("\n  picture $k differs")
        if (a.ui != b.ui) sb.append("\n  highlights: ${a.ui} | ${b.ui}")
        if (a.history != b.history) sb.append("\n  history: ${a.history} | ${b.history}")
        val keys = (a.graph.keys + b.graph.keys).toSortedSet()
        var n = 0
        for (k in keys) {
            val x = a.graph[k] ?: "(none)"; val y = b.graph[k] ?: "(none)"
            if (x == y) continue
            if (n++ >= 3) { sb.append("\n  ..."); break }
            val i = x.indices.firstOrNull { it >= y.length || x[it] != y[it] } ?: x.length
            sb.append("\n  engine $k:\n    without kill ..${x.substring(maxOf(0, i - 100), minOf(x.length, i + 100))}\n    killed       ..${y.substring(maxOf(0, i - 100), minOf(y.length, i + 100))}")
        }
        if (sb.isNotEmpty()) throw AssertionError("$what at ${b.label}:$sb")
    }

    private var caseNo = 0

    /**
     * Runs [steps] once with every "kill" ignored (the oracle) and once with them, observing at
     * each kill and after every step that follows one; the two must agree everywhere.
     */
    private fun runCase(name: String, steps: List<JSONObject>, watch: Boolean = true): Pair<Int, Int> {
        val n = caseNo++
        // both runs start from the same shared-object fields (a process starts them at their
        // initial values; in one test process the previous run left its own)
        val statics = EngineGraph.captureStatics()
        if (System.getenv("KP_DUMP") != null) dumpDir = File("/tmp/kp-dump/o$n")
        val oracle = run(File(tmp, "o$n"), steps, kill = false)
        if (System.getenv("KP_DUMP") != null) dumpDir = File("/tmp/kp-dump/k$n")
        EngineGraph.applyStatics(statics)
        val killed = run(File(tmp, "k$n"), steps, kill = System.getenv("KP_NOKILL") == null, oracleTimes = oracle.map { it.time })
        assertEquals("$name: observations", oracle.size, killed.size)
        for (i in oracle.indices) same(name, oracle[i], killed[i])
        if (watch) {
            // the play button replays the whole history: it ends in the live picture
            val live = crc(KDisplay.main_canvas.bitmap)
            val end = watchEnd()
            if (end != null) assertEquals("$name: the play button's replay ends in the live picture", live, end)
        }
        KLog.close()
        File(tmp, "o$n").deleteRecursively(); File(tmp, "k$n").deleteRecursively()
        return oracle.size to steps.count { it.optString("op") == "~kill" }
    }

    private fun run(dir: File, steps: List<JSONObject>, kill: Boolean, oracleTimes: List<Long>? = null): List<Obs> {
        val s = LogSession(dir)
        s.start()
        installUi()
        val out = ArrayList<Obs>()
        var after = false
        for (st in steps) {
            when (st.optString("op")) {
                "~checkpoint" -> { s.settle(); assertTrue("quiet for a checkpoint: ${KLog.describe()}", KLog.quiescent()); KLog.checkpoint() }
                "~kill" -> {
                    s.settle()
                    if (kill) {
                        KLog.drainIo()
                        scramble()
                        s.restart(); installUi()
                        s.settle()
                        // the live run's clock went on while nothing happened: so does this one's
                        oracleTimes?.getOrNull(out.size)?.let { t -> if (t > KLog.clock.time) KLog.clock.advanceTo(t) }
                        assertEquals("restore: the engine state came back exactly (${KLog.lastRestore})", null, KLog.lastApplyProblem)
                    }
                    out.add(observe("kill ${out.size}" + if (kill) " (restore: ${KLog.lastRestore})" else ""))
                    after = true
                }
                "~save" -> { s.step(tool("save"), 16); s.settle() }
                "~open" -> SavePoints.list().filter { it.kind == SavePoints.SAVE }.maxByOrNull { it.id }?.let { sp -> s.settle(); SavePoints.open(sp, SavePoints.picture(sp)!!); s.settle() }
                "~openDraft" -> SavePoints.drafts().firstOrNull()?.let { sp -> s.settle(); SavePoints.open(sp, SavePoints.picture(sp)!!); s.settle() }
                else -> s.step(st, 16)
            }
            if (System.getenv("KP_TRACE") != null) println("TRACE ${if (kill) "K" else "O"} ${st} letters=${KUi.alphabetLetters.size} sub=${KUi.currentSubmenu} tool=${dev.kidpix.paint.KAndroid.toolName(dev.kidpix.paint.KCurrent.tool)}")
            if (after && !st.optString("op").startsWith("~")) {
                s.settle()
                oracleTimes?.getOrNull(out.size)?.let { t -> if (t > KLog.clock.time) KLog.clock.advanceTo(t) }
                out.add(observe("after ${st.optString("op")} #${out.size}"))
            }
        }
        return out
    }

    private fun nid(): Int = dev.kidpix.paint.engine.KTime::class.java.getDeclaredField("nextId").let { it.isAccessible = true; it.getInt(null) }

    /** Another session's drawing in memory (other tools, options, colours, letters): what a restore must not keep. */
    private fun scramble() {
        KLog.close()
        val junk = LogSession(File(tmp, "junk"), rnd = java.util.Random(99))
        junk.start(seed = 4321)
        installUi()
        val r = java.util.Random(5)
        for (k in 0 until 14) {
            junk.step(tool(listOf("brush", "stamp", "alphabet", "circle", "jumble", "eraser", "truck").let { it[r.nextInt(it.size)] }.toString()), 16)
            val n = KiddoPaint.Submenu[KUi.currentSubmenu]?.size ?: 0
            if (n > 0) junk.step(sub(r.nextInt(n), ctrl = r.nextInt(4) == 0), 16)
            junk.step(j("op" to "color", "index" to r.nextInt(16), "which" to 1), 16)
            junk.step(stroke(r.nextInt(50)), 16)
        }
        junk.step(j("op" to "key", "name" to "shift"), 16)
        junk.settle()
        KLog.close()
        File(tmp, "junk").deleteRecursively()
        // a new process: no slot knows its property's name until it is used
        dev.kidpix.paint.engine.World.forgetNames()
    }

    /** The play button's replay to its end (instant fake clock); the picture it ended in, null if it could not start. */
    private fun watchEnd(): Long? {
        var now = 1_000_000L
        val q = ArrayDeque<Pair<Long, () -> Unit>>()
        val w = Watch.start({ d, f -> q.add(d to f) }, { now }, null, {}, {}) as? Watch ?: return null
        var steps = 0
        while (q.isNotEmpty() && steps < 20_000) { val (d, f) = q.removeFirst(); now += d; f(); steps++ }
        return w.replayEnd?.let { crc(it) }
    }

    /** A selection, then: draw, checkpoint, draw more, kill, then the next strokes, two undos and a redo. */
    private fun killCase(select: List<JSONObject>, k: Int, extra: List<JSONObject> = emptyList()): List<JSONObject> =
        select + listOf(stroke(k), stroke(k + 1, "pen"), special("checkpoint"), stroke(k + 2, "touch"), stroke(k + 3)) + extra +
            listOf(special("kill"), stroke(k + 4, "pen"), stroke(k + 5)) + undo + undo + redo + listOf(stroke(k + 6))

    // ------------------------------------------------------------------ the enumeration

    /** Every submenu option reachable from a tool button: (tool, option index, ctrl, long press, option name). */
    private fun options(): List<Pair<List<JSONObject>, String>> {
        val s = LogSession(File(tmp, "enum"))
        s.start(); installUi()
        val out = ArrayList<Pair<List<JSONObject>, String>>()
        for (t in KAndroid.TOOL_IDS) {
            if (t == "alphabet") continue // letters below
            s.step(tool(t), 16); s.settle()
            val bar = KUi.currentSubmenu
            val items = KiddoPaint.Submenu[bar] ?: continue
            for ((i, d) in items.withIndex()) {
                if (d.invisible) continue
                for (variant in 0 until 3) {
                    val press = sub(i, ctrl = variant == 1, ctx = variant == 2)
                    out.add(listOf(tool(t), press) to "$t/$bar[$i] ${d.name}${listOf("", " ctrl", " long")[variant]}")
                    if (variant == 1) {
                        // a ctrl-press may open another submenu (Spray Paint: the spray sizes): each of its options
                        s.step(tool(t), 16); s.step(press, 16); s.settle()
                        val other = KUi.currentSubmenu
                        if (other != bar && other != "alphabet") KiddoPaint.Submenu[other]?.forEachIndexed { oi, od ->
                            if (!od.invisible) out.add(listOf(tool(t), press, sub(oi)) to "$t/$bar[$i] ${d.name} ctrl > $other[$oi] ${od.name}")
                        }
                        s.step(tool(t), 16); s.settle()
                    }
                }
            }
        }
        // letters, on every page of the alphabet bar (the last button turns the page)
        s.step(tool("alphabet"), 16); s.settle()
        val letters = KUi.alphabetLetters.size
        val pages = ArrayList<List<JSONObject>>()
        var turn = emptyList<JSONObject>()
        val seen = HashSet<String>()
        while (seen.add(KUi.alphabetLetters.joinToString())) {
            pages.add(turn)
            turn = turn + j("op" to "letter", "index" to letters - 1)
            s.step(j("op" to "letter", "index" to letters - 1), 16); s.settle()
            if (pages.size > 12) break
        }
        for ((pi, pre) in pages.withIndex()) for (li in 0 until KUi.alphabetLetters.size)
            out.add((listOf(tool("alphabet")) + pre + j("op" to "letter", "index" to li)) to "alphabet page $pi letter $li")
        KLog.close()
        File(tmp, "enum").deleteRecursively()
        return out
    }

    @Test fun everyOptionOfEveryToolSurvivesAKill() {
        val all = options()
        val only = System.getenv("KP_RESTORE_ONLY")?.let { Regex(it) }
        // the GL mixers render on a GL thread that headless tests do not have: their drag never
        // ends here (tested on the emulator and the tablet instead)
        val gl = Regex("jumble/jumble\\[\\d+] (Swirl|Pancake Stack)( |$)")
        val cases = all.filter { (only == null || only.containsMatchIn(it.second)) && !gl.containsMatchIn(it.second) }
        println("RESTORE every option: ${all.count { gl.containsMatchIn(it.second) }} GL cases left to the device tests")
        var obs = 0
        val failures = ArrayList<String>()
        for ((k, c) in cases.withIndex()) {
            try { obs += runCase(c.second, killCase(c.first, k)).first }
            catch (e: AssertionError) { failures.add(c.second + ": " + (e.message ?: "").take(700)); KLog.close() }
        }
        println("RESTORE every option: ${cases.size} cases, $obs observations, ${failures.size} failures")
        File(System.getProperty("java.io.tmpdir"), "kp-restore-failures.txt").writeText(failures.joinToString("\n---\n"))
        failures.take(10).forEach { println("FAIL $it") }
        assertTrue("${failures.size} of ${cases.size} options change after a kill:\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    /**
     * The option bar frames the engine's options: after every press of every option (plain, ctrl,
     * long; the spray sizes; letters) what the views last painted is what the engine has selected.
     */
    @Test fun theOptionBarAlwaysFramesTheEnginesSelection() {
        val all = options()
        val s = LogSession(File(tmp, "bar")); s.start()
        var painted = ""
        fun engine() = "${KUi.currentSubmenu} ${KAndroid.selectedOptions().sorted()} ${KAndroid.selectedLetter()} ${KAndroid.currentToolId}"
        KUi.ui = object : Ui { override fun selectionChanged() { painted = engine() } }
        val bad = ArrayList<String>()
        for ((steps, name) in all) {
            for (st in steps) {
                s.step(st, 16)
                if (painted != engine()) { bad.add("$name: shows $painted, engine has ${engine()}"); painted = engine() }
            }
            s.step(stroke(1), 16); s.settle()
        }
        KLog.close()
        println("RESTORE highlights: ${all.size} cases, ${bad.size} stale")
        bad.take(10).forEach { println("STALE $it") }
        assertTrue("${bad.size} presses leave a stale highlight:\n" + bad.take(5).joinToString("\n"), bad.isEmpty())
    }

    @Test fun stampAndSpritePagesPalettesKeysAndThePensEraser() {
        val cases = ArrayList<Pair<List<JSONObject>, String>>()
        // stamp pages and sprite sheets: the page-turning options, several times, then a stamp of that page
        val s = LogSession(File(tmp, "enum2")); s.start(); installUi()
        s.step(tool("stamp"), 16); s.settle()
        val items = KiddoPaint.Submenu[KUi.currentSubmenu]!!
        val flips = items.withIndex().filter { (_, d) -> d.name in setOf("Next Page", "Next Stamp Pack", "Previous Page", "Previous Stamp Pack") }.map { it.index }
        val firstStamp = items.indexOfFirst { !it.invisible && it.name !in setOf("Next Page", "Next Stamp Pack") }
        KLog.close()
        for (f in flips) for (times in 1..3) for (pick in listOf(firstStamp, firstStamp + 3))
            cases.add((listOf(tool("stamp")) + List(times) { sub(f) } + sub(pick)) to "stamp flip $f x$times pick $pick")
        // palettes: every page, every colour, left and right button
        for (page in 0 until 6) for (ci in 0 until 16 step 3) for (which in listOf(1, 3))
            cases.add((List(page) { j("op" to "palette", "dir" to "next") } + j("op" to "color", "index" to ci, "which" to which)) to "palette $page colour $ci/$which")
        cases.add(listOf(j("op" to "palette", "dir" to "prev"), j("op" to "palette", "dir" to "prev")) to "palette back twice")
        // modifier keys held across the checkpoint and the kill, and toggles
        for (key in listOf("shift", "alt", "ctrl", "cmd", "tilde", "space", "n", "c", "r", "v", "3", "7"))
            for (t in listOf("pencil", "brush", "circle", "stamp")) cases.add(listOf(tool(t), j("op" to "key", "name" to key)) to "key $key held, $t")
        // the pen's eraser end (a temporary eraser), with several tools
        for (t in listOf("pencil", "brush", "stamp", "flood")) cases.add(listOf(tool(t)) to "pen eraser end with $t")
        var obs = 0
        val failures = ArrayList<String>()
        for ((k, c) in cases.withIndex()) {
            val extra = if (c.second.startsWith("pen eraser")) listOf(stroke(k + 40, "pen", 32)) else emptyList()
            try { obs += runCase(c.second, killCase(c.first, k, extra)).first }
            catch (e: AssertionError) { failures.add(c.second + ": " + (e.message ?: "").take(700)); KLog.close() }
        }
        println("RESTORE pages/palettes/keys/pen: ${cases.size} cases, $obs observations, ${failures.size} failures")
        failures.take(10).forEach { println("FAIL $it") }
        assertTrue("${failures.size} of ${cases.size} cases change after a kill:\n" + failures.take(5).joinToString("\n"), failures.isEmpty())
    }

    /** Random sequences of everything, with checkpoints and kills anywhere quiet, save points, opens and drafts. */
    @Test fun randomSequencesSurviveKills() {
        val rounds = System.getenv("KP_RESTORE_ROUNDS")?.toIntOrNull() ?: 24
        val onlyRound = System.getenv("KP_RESTORE_ROUND")?.toIntOrNull()
        var obs = 0; var kills = 0
        for (round in 0 until rounds) {
            if (onlyRound != null && round != onlyRound) continue
            val r = java.util.Random(1000L + round)
            val steps = ArrayList<JSONObject>()
            var subN = 30
            for (k in 0 until 60) {
                when (r.nextInt(20)) {
                    0, 1 -> steps.add(tool(KAndroid.TOOL_IDS[r.nextInt(KAndroid.TOOL_IDS.size)]))
                    2, 3, 4 -> steps.add(sub(r.nextInt(subN), ctrl = r.nextInt(5) == 0, ctx = r.nextInt(9) == 0))
                    5 -> steps.add(j("op" to "letter", "index" to r.nextInt(27)))
                    6 -> steps.add(j("op" to "color", "index" to r.nextInt(16), "which" to if (r.nextBoolean()) 1 else 3))
                    7 -> steps.add(j("op" to "palette", "dir" to if (r.nextBoolean()) "next" else "prev"))
                    8 -> steps.add(j("op" to "key", "name" to listOf("shift", "alt", "space", "v", "n", "2").let { it[r.nextInt(it.size)] }.toString(), "down" to r.nextBoolean()))
                    9 -> steps.addAll(if (r.nextBoolean()) undo else redo)
                    10 -> steps.add(special("checkpoint"))
                    11 -> { steps.add(special("kill")) }
                    12 -> steps.add(special(listOf("save", "open", "openDraft").let { it[r.nextInt(it.size)] }.toString()))
                    13 -> steps.add(stroke(r.nextInt(80), "pen", 32))
                    else -> steps.add(stroke(r.nextInt(80), listOf("mouse", "pen", "touch").let { it[r.nextInt(it.size)] }.toString()))
                }
                subN = 30
            }
            // a sub index past the end of the bar does nothing; the alphabet bar ignores sub presses
            steps.add(special("checkpoint")); steps.add(stroke(3)); steps.add(special("kill")); steps.add(stroke(4))
            if (System.getenv("KP_PRINT_STEPS") != null) steps.forEachIndexed { i, st -> println("STEP $i $st") }
            val (o, kk) = runCase("random round $round", steps, watch = round % 3 == 0)
            obs += o; kills += kk
        }
        println("RESTORE random: $rounds rounds, $kills kills, $obs observations")
    }
}
