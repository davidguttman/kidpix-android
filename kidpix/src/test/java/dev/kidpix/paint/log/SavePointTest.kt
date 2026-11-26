package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.harness.LogSession
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executor

/**
 * Saved pictures keep their history (log/SavePoints.kt), headless: save -> draw -> save -> open
 * the first gives back its picture and every undo step; branches; shared frames under random
 * save/open/draw/undo/prune/restart sequences against an oracle taken when each save point was
 * made; drafts; kills at every write of save/open/park; the cap's order; files changed outside
 * the app; another versionCode; replays of save points.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SavePointTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-savepoint-test-" + System.nanoTime())
    private val refDir = File(File(System.getProperty("kidpix.defaultScenarios")!!).parentFile.parentFile.parentFile, "ref")

    /** The gallery: media id -> (the saved picture, its file hash). */
    private val gallery = LinkedHashMap<Long, Pair<Bitmap, String>>()
    private var nextMedia = 100L
    private var lastMedia = -1L
    private var saveFails = false

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

    private fun mainCrc() = crc(KDisplay.main_canvas.bitmap)

    /** The app's Save: a save point, the PNG to the gallery, the commit (as PaintActivity.save does). */
    private fun installUi() {
        KUi.ui = object : Ui {
            override fun save() {
                val sp = SavePoints.saving()
                val pic = KDisplay.main_canvas.snapshot()
                if (saveFails) { SavePoints.discard(sp); return }
                val id = nextMedia++
                val hash = "h$id-${crc(pic)}"
                gallery[id] = pic to hash
                SavePoints.commitSave(sp, id, hash)
                lastMedia = id
            }
        }
    }

    private fun session(name: String, io: Executor? = null): LogSession {
        val s = LogSession(File(tmp, name))
        s.forceType = "mouse"
        s.io = io
        s.start()
        installUi()
        return s
    }

    private fun restart(s: LogSession): String { val m = s.restart(); installUi(); return m }

    private fun stroke(s: LogSession, i: Int) =
        s.step(JSONObject().put("op", "stroke").put("points", JSONArray("[[${20 + (i * 37) % 900},${30 + (i * 53) % 600}],[${80 + (i * 41) % 900},${90 + (i * 29) % 600}],[${40 + (i * 13) % 900},${300 + (i * 7) % 400}]]")), 16)
    private fun tool(s: LogSession, id: String) = s.step(JSONObject().put("op", "tool").put("id", id), 16)
    private fun undo(s: LogSession) { tool(s, "undo"); s.step(JSONObject().put("op", "wait").put("ms", 400), 16) }
    private fun save(s: LogSession): Long { tool(s, "save"); s.settle(); return lastMedia }

    /** What a save point should open to: its picture, then each undo step's picture, newest first. */
    private fun oracleNow(): List<Long> = listOf(mainCrc()) + History.pastIds.reversed().map { id -> crc(History.frames.get(id)!!) }

    private fun open(s: LogSession, sp: SavePoints.SavePoint) {
        s.settle()
        val pic = SavePoints.picture(sp) ?: error("save point ${sp.id}: picture missing")
        assertTrue("open ${sp.id}", SavePoints.open(sp, pic))
        s.settle()
    }

    /** Opens [sp] and checks the picture and the first [steps] undo steps (all: -1) against [want]. */
    private fun checkOpen(s: LogSession, sp: SavePoints.SavePoint, want: List<Long>, steps: Int = -1, what: String = "") {
        open(s, sp)
        assertEquals("$what save point ${sp.id}: picture", want[0], mainCrc())
        assertEquals("$what save point ${sp.id}: undo steps", want.size - 1, History.pastIds.size)
        val n = if (steps < 0) want.size - 1 else minOf(steps, want.size - 1)
        for (k in 1..n) {
            undo(s)
            assertEquals("$what save point ${sp.id}: undo step $k", want[k], mainCrc())
        }
    }

    // ------------------------------------------------------------------ the main flow

    @Test fun saveDrawSaveOpenFirstGivesBackItsWholeHistoryThenBranches() {
        val s = session("flow")
        repeat(6) { stroke(s, it) }
        s.settle()
        val o1 = oracleNow()
        val m1 = save(s)
        val sp1 = SavePoints.forMedia(m1)!!
        repeat(4) { stroke(s, 10 + it) }
        undo(s) // a redo side too
        s.settle()
        val o2 = oracleNow()
        val m2 = save(s)
        val sp2 = SavePoints.forMedia(m2)!!
        assertEquals(1, sp2.future.size)
        // open the first: picture and every undo step equal the first save's
        checkOpen(s, sp1, o1, what = "first")
        assertTrue("the second save was clean: no draft", SavePoints.drafts().isEmpty())
        // back to the save point's picture, then a branch from it
        open(s, SavePoints.get(sp1.id)!!) // the canvas has undone steps (dirty): parked as a draft
        assertEquals(1, SavePoints.drafts().size)
        assertEquals(o1[0], mainCrc())
        repeat(3) { stroke(s, 30 + it) }
        s.settle()
        val o3 = oracleNow()
        assertEquals(o1.size + 3, o3.size)
        assertEquals("the branch's history goes back through the first save's", o1, o3.drop(3))
        val m3 = save(s)
        val sp3 = SavePoints.forMedia(m3)!!
        // all three open correctly, after a restart too
        restart(s)
        checkOpen(s, SavePoints.get(sp2.id)!!, o2, what = "second")
        checkOpen(s, SavePoints.get(sp3.id)!!, o3, what = "branch")
        checkOpen(s, SavePoints.get(sp1.id)!!, o1, what = "first again")
        // and the first one is untouched by all that
        assertEquals(sp1.toJson().toString(), SavePoints.get(sp1.id)!!.toJson().toString())
    }

    @Test fun twoSavesOfOneDrawingCostOnlyWhatDiffers() {
        val s = session("share")
        repeat(20) { stroke(s, it) }
        s.settle(); KLog.drainIo()
        val b0 = History.frames.totalBytes
        save(s)
        val b1 = History.frames.totalBytes
        stroke(s, 99); s.settle()
        val b2 = History.frames.totalBytes
        save(s)
        val b3 = History.frames.totalBytes
        val key = FrameStore.deflate(ByteArray(KDisplay.main_canvas.width * KDisplay.main_canvas.height * 4)).size
        println("save point bytes: first save ${b1 - b0}, a stroke ${b2 - b1}, second save ${b3 - b2} (an empty key frame is $key)")
        assertTrue("a save point of an unchanged picture is nearly free: ${b1 - b0}", b1 - b0 < 2048)
        assertTrue("the second save costs about what changed (one stroke): ${b3 - b2}", b3 - b2 < 8192)
        File(tmp, "share-bytes.txt").writeText("${b1 - b0} ${b2 - b1} ${b3 - b2}")
    }

    // ------------------------------------------------------------------ drafts

    @Test fun draftsParkReopenNoDuplicatesCapOfEight() {
        val s = session("drafts")
        repeat(3) { stroke(s, it) }
        s.settle()
        val m = save(s)
        val sp = SavePoints.forMedia(m)!!
        // open the saved picture while unchanged: nothing to park
        open(s, sp)
        assertEquals(0, SavePoints.drafts().size)
        // draw, then open: parked
        stroke(s, 7); s.settle()
        val od = oracleNow()
        open(s, sp)
        val d1 = SavePoints.drafts().single()
        // reopen the draft: picture and history come back
        checkOpen(s, d1, od, what = "draft")
        // opened and left unchanged (after the undos it IS changed: walk forward again)
        open(s, sp) // parks the undone state, replacing d1 (one slot per drawing)
        val d2 = SavePoints.drafts().single()
        assertNotEquals(d1.id, d2.id)
        open(s, d2); open(s, sp)
        assertEquals("a draft opened and left unchanged does not pile up", 1, SavePoints.drafts().size)
        // a draft opened and saved goes away
        open(s, d2)
        save(s)
        assertEquals("a draft that is saved is not kept twice", 0, SavePoints.drafts().size)
        // at most 8 drafts: the oldest go
        val made = ArrayList<Long>()
        repeat(11) { i ->
            stroke(s, 40 + i); s.settle()
            open(s, sp)
            made.add(SavePoints.drafts().first().id)
        }
        val kept = SavePoints.drafts().map { it.id }
        assertEquals(8, kept.size)
        assertEquals("the newest 8, newest first", made.takeLast(8).reversed(), kept)
        restart(s)
        assertEquals(kept, SavePoints.drafts().map { it.id })
        for (d in SavePoints.drafts()) assertNotNull("draft ${d.id} picture", SavePoints.picture(d))
    }

    @Test fun aFailedSaveKeepsTheChangesUnsaved() {
        val s = session("fail")
        stroke(s, 1); s.settle()
        val m = save(s)
        stroke(s, 2); s.settle()
        saveFails = true
        tool(s, "save"); s.settle()
        saveFails = false
        assertTrue(SavePoints.dirty())
        open(s, SavePoints.forMedia(m)!!)
        assertEquals("the unsaved stroke was parked", 1, SavePoints.drafts().size)
    }

    // ------------------------------------------------------------------ property: random sequences vs oracle

    @Test fun randomSaveOpenDrawUndoPruneSequencesMatchTheOracle() {
        val rounds = (System.getenv("KP_SPROUNDS") ?: "6").toInt()
        val pool = ArrayList<JSONObject>()
        for (f in File(refDir, "scenarios").listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }) {
            if (f.name.startsWith("jumble")) continue // glfx needs GL
            val scs = JSONObject(f.readText()).getJSONArray("scenarios")
            for (i in 0 until scs.length()) {
                val sts = scs.getJSONObject(i).getJSONArray("steps")
                for (j in 0 until sts.length()) sts.getJSONObject(j).takeIf { it.optString("op") != "tool" || it.optString("id") !in setOf("jumble", "save", "undo") }?.let { pool.add(it) }
            }
        }
        var opens = 0; var steps = 0; var prunes = 0
        for (round in 0 until rounds) {
            val rnd = java.util.Random(1000L + round)
            val s = session("prop$round")
            val oracle = HashMap<Long, List<Long>>()
            val log = ArrayList<String>()
            fun drawSome() { repeat(1 + rnd.nextInt(4)) { s.step(pool[rnd.nextInt(pool.size)], 16) }; s.settle() }
            repeat(40) { op ->
                when (rnd.nextInt(10)) {
                    0, 1, 2 -> { drawSome(); log.add("draw") }
                    3 -> { undo(s); s.settle(); log.add("undo") }
                    4, 5 -> { s.settle(); val o = oracleNow(); val m = save(s); oracle[SavePoints.forMedia(m)!!.id] = o; log.add("save ${SavePoints.forMedia(m)!!.id}") }
                    6, 7 -> {
                        val all = SavePoints.list()
                        if (all.isNotEmpty()) {
                            val sp = all[rnd.nextInt(all.size)]
                            s.settle()
                            val dirty = SavePoints.dirty()
                            val parkedOracle = if (dirty) oracleNow() else null
                            val sameDraft = sp.kind == SavePoints.DRAFT && SavePoints.origin?.sp == sp.id
                            val before = SavePoints.drafts().map { it.id }.toSet()
                            log.add("open ${sp.id} (${sp.kind}) dirty=$dirty")
                            if (sameDraft) { open(s, sp); return@repeat }
                            val want = oracle[sp.id]!!
                            checkOpen(s, sp, want, steps = rnd.nextInt(4), what = "round $round op $op [${log.takeLast(6)}]")
                            opens++; steps++
                            val newDrafts = SavePoints.drafts().map { it.id }.filter { it !in before }
                            if (parkedOracle != null) {
                                assertEquals("round $round op $op: parked", 1, newDrafts.size)
                                oracle[newDrafts[0]] = parkedOracle
                            } else assertTrue(newDrafts.isEmpty())
                            // the drafts' cap
                            assertTrue(SavePoints.drafts().size <= SavePoints.MAX_DRAFTS)
                        }
                    }
                    8 -> { restart(s); log.add("restart") }
                    else -> {
                        // prune: a forced checkpoint (frames not named go), sometimes under a cap that drops old histories
                        val tight = rnd.nextBoolean()
                        if (tight) SavePoints.capBytes = (History.frames.totalBytes * 0.7).toLong()
                        s.settle(); KLog.maybeCheckpoint(force = true); stroke(s, op); s.settle()
                        SavePoints.capBytes = 512L shl 20
                        prunes++
                        log.add("prune tight=$tight")
                    }
                }
                // every save point still held opens to its oracle picture; its frames are all readable
                for (sp in SavePoints.list()) {
                    val want = oracle[sp.id] ?: continue
                    assertEquals("round $round op $op: save point ${sp.id} picture [${log.takeLast(6)}]", want[0], crc(SavePoints.picture(sp)!!))
                    val past = sp.past.reversed().map { crc(History.frames.get(it) ?: error("round $round op $op: frame $it of save point ${sp.id} gone [${log.takeLast(6)}]")) }
                    assertEquals("round $round op $op: save point ${sp.id} history", want.drop(1), past)
                    // and its history can be replayed from its start: every segment of its chain is kept
                    val (pieces, why) = KLog.loadChain(sp.chain)
                    assertNotNull("round $round op $op: save point ${sp.id} chain ${sp.chain.map { it.toList() }}: $why [${log.takeLast(6)}]", pieces)
                }
            }
            KLog.close()
        }
        println("save point property: $rounds rounds, $opens opens checked, $prunes prunes")
    }

    // ------------------------------------------------------------------ kills

    /** A writer that runs the first [limit] tasks and drops the rest (the process died there). */
    private class KillAt(val limit: Int) : Executor {
        var n = 0
        override fun execute(r: Runnable) { if (n++ < limit) r.run() }
    }

    @Test fun aKillAtEveryWriteOfSaveOpenParkNeverLosesAPicture() {
        /** The flow; returns, for the moment before each save/open, the writes so far and the picture on the canvas. */
        fun flow(s: LogSession, k: KillAt): List<Pair<Int, Long>> {
            val marks = ArrayList<Pair<Int, Long>>()
            repeat(3) { stroke(s, it) }; s.settle()
            marks += k.n to mainCrc()
            val m = save(s)
            stroke(s, 5); s.settle()
            marks += k.n to mainCrc()
            open(s, SavePoints.forMedia(m)!!) // parks the stroke, opens the save
            stroke(s, 6); s.settle()
            marks += k.n to mainCrc()
            open(s, SavePoints.drafts().first()) // parks this, opens the first draft
            stroke(s, 7); s.settle()
            marks += k.n to mainCrc()
            return marks
        }
        val counter = KillAt(Int.MAX_VALUE)
        val marks = flow(session("killcount", counter), counter)
        val total = counter.n
        KLog.close()
        assertEquals(4, marks.map { it.second }.toSet().size)
        var checked = 0
        for (limit in marks[0].first..total) {
            gallery.clear(); lastMedia = -1
            val k = KillAt(limit)
            val s = session("kill", k)
            flow(s, k)
            KLog.close()
            // a torn temporary file of each kind next to the real ones
            File(File(tmp, "kill"), "saves").let { d -> d.mkdirs(); File(d, "sp-00000099.json.tmp").writeText("{\"id\":9") }
            File(File(tmp, "kill"), "frames/f-00000077.tmp").writeBytes(ByteArray(10))
            s.io = null
            val msg = restart(s)
            val have = HashSet<Long>()
            have += mainCrc()
            for (sp in SavePoints.list()) {
                val pic = SavePoints.picture(sp)
                assertNotNull("kill at $limit: save point ${sp.id} picture ($msg)", pic)
                have += crc(pic!!)
                for (id in sp.past) assertNotNull("kill at $limit: save point ${sp.id} frame $id", History.frames.get(id))
            }
            // every picture that was on disk (all its writes done) before a save or an open is still there after a kill at any later write
            for ((i, mk) in marks.withIndex()) if (limit >= mk.first) {
                assertTrue("kill at $limit (of $total): picture $i (written by ${mk.first}) lost; restore: $msg; save points ${SavePoints.list().map { it.id to it.kind }}", mk.second in have)
            }
            checked++
            KLog.close()
        }
        println("kill test: $checked kill points (writes ${marks[0].first}..$total) restored")
        assertTrue(checked > 10)
    }

    // ------------------------------------------------------------------ cap, gallery, version

    @Test fun theCapDropsOldestSaveHistoriesFirstThenDraftsNeverTheLivePicture() {
        val s = session("cap")
        val saves = ArrayList<Long>()
        val sp0 = run { repeat(4) { stroke(s, it) }; s.settle(); SavePoints.forMedia(save(s))!! }
        saves.add(sp0.id)
        repeat(3) { i -> repeat(4) { stroke(s, 10 * i + it + 10) }; s.settle(); saves.add(SavePoints.forMedia(save(s))!!.id) }
        // two drafts: draw, open the first save, draw, open it again
        stroke(s, 60); s.settle(); open(s, sp0)
        stroke(s, 61); s.settle(); open(s, SavePoints.get(sp0.id)!!)
        val drafts = SavePoints.drafts().map { it.id }
        assertEquals(2, drafts.size)
        repeat(5) { stroke(s, 70 + it) }; s.settle()
        val live = mainCrc(); val liveSteps = History.pastIds.size
        // a cap that only fits part of it: the oldest save points go first, drafts only after all saves
        KLog.drainIo()
        var cap = History.frames.totalBytes
        val order = ArrayList<Long>()
        while (SavePoints.list().isNotEmpty() && cap > 0) {
            cap = (cap * 0.8).toLong()
            SavePoints.capBytes = cap
            val before = SavePoints.list().map { it.id }.toSet()
            SavePoints.enforceCap()
            order += before.filter { SavePoints.get(it) == null }.sorted()
            if (cap < 1000) break
        }
        println("cap: dropped in order $order (saves $saves, drafts $drafts)")
        val firstDraft = order.indexOfFirst { it in drafts }
        if (firstDraft >= 0) assertTrue("every save went before any draft: $order", order.take(firstDraft).toSet().containsAll(saves) || order.subList(0, firstDraft).all { it in saves })
        assertEquals("oldest first", order.filter { it in saves }, saves.filter { it in order })
        SavePoints.capBytes = 512L shl 20
        assertEquals(live, mainCrc())
        restart(s)
        assertEquals("the live picture survives the cap", live, mainCrc())
        assertEquals(liveSteps, History.pastIds.size)
        // a dropped save point's picture opens flat from the gallery
        val (pic, hash) = gallery.values.first()
        val media = gallery.keys.first()
        assertNull(SavePoints.forFile(media, hash))
        s.settle()
        assertTrue(SavePoints.openFlat(pic)); s.settle()
        assertEquals(crc(pic), mainCrc())
        assertFalse(History.canUndo)
    }

    @Test fun aFileChangedOutsideTheAppOpensFlatAndAGoneFileFreesItsSavePoint() {
        val s = session("changed")
        repeat(3) { stroke(s, it) }; s.settle()
        val m1 = save(s)
        stroke(s, 4); s.settle()
        val m2 = save(s)
        assertNotNull(SavePoints.forFile(m1, gallery[m1]!!.second))
        assertNull("another hash: flat", SavePoints.forFile(m1, "edited"))
        assertNull("and its save point is freed", SavePoints.forMedia(m1))
        SavePoints.retainMedia(setOf(999L))
        assertNull("deleted from the gallery: freed at start", SavePoints.forMedia(m2))
        restart(s)
        assertTrue(SavePoints.list().none { it.kind == SavePoints.SAVE })
    }

    @Test fun aSavePointFromAnotherVersionOpensFromItsFramesNeverByAReplay() {
        val s = session("version")
        repeat(5) { stroke(s, it) }; s.settle()
        val o = oracleNow()
        val m = save(s)
        val sp = SavePoints.forMedia(m)!!
        // same version: the replay from the start ends in the saved picture
        var res: Pair<String?, Bitmap?>? = null
        KLog.playSavePoint(sp.chain, sp.time, Double.POSITIVE_INFINITY, { _, f -> f() }) { r, b -> res = r to b }
        assertNull("replay: ${res!!.first}", res!!.first)
        assertEquals("the replay ends in the saved picture", o[0], crc(res!!.second!!))
        assertEquals("the live picture is back", o[0], mainCrc())
        KLog.close()
        KLog.versionCode = 12
        restart(s)
        checkOpen(s, SavePoints.get(sp.id)!!, o, what = "other version")
        res = null
        KLog.playSavePoint(sp.chain, sp.time, Double.POSITIVE_INFINITY, { _, f -> f() }) { r, b -> res = r to b }
        assertTrue("no replay across versions: ${res!!.first}", res!!.first!!.contains("versionCode"))
    }

    @Test fun everySavePointReplaysFromItsStartToItsPicture() {
        val s = session("replay")
        val sps = ArrayList<Pair<SavePoints.SavePoint, Long>>()
        repeat(3) { stroke(s, it) }; s.settle()
        sps.add(SavePoints.forMedia(save(s))!! to mainCrc())
        // a clear-all (new segment), more drawing
        tool(s, "eraser")
        for (i in 0 until 20) { s.step(JSONObject().put("op", "sub").put("index", i), 16); if (KLog.segment > 1) break }
        s.settle()
        repeat(2) { stroke(s, 10 + it) }; s.settle()
        tool(s, "pencil")
        sps.add(SavePoints.forMedia(save(s))!! to mainCrc())
        // open the first, branch, save
        open(s, sps[0].first)
        repeat(2) { stroke(s, 20 + it) }; s.settle()
        sps.add(SavePoints.forMedia(save(s))!! to mainCrc())
        restart(s)
        for ((sp, want) in sps) {
            var res: Pair<String?, Bitmap?>? = null
            val live = mainCrc()
            KLog.playSavePoint(sp.chain, sp.time, Double.POSITIVE_INFINITY, { _, f -> f() }) { r, b -> res = r to b }
            assertNull("save point ${sp.id} (${sp.chain.map { it.toList() }}): ${res!!.first}", res!!.first)
            assertEquals("save point ${sp.id} replays to its picture", want, crc(res!!.second!!))
            assertEquals(live, mainCrc())
            s.settle()
        }
        // drawing still records after a replay
        stroke(s, 50); s.settle()
        val after = mainCrc()
        restart(s)
        assertEquals(after, mainCrc())
        // finite speed, on a scheduler
        val q = ArrayDeque<() -> Unit>()
        var done: String? = "pending"
        KLog.playSavePoint(sps[2].first.chain, sps[2].first.time, 50.0, { _, f -> q.add(f) }) { r, _ -> done = r }
        while (q.isNotEmpty()) q.removeFirst()()
        assertNull(done)
    }

    @Test fun anExportedSavePointReplaysHeadless() {
        val s = session("export")
        repeat(4) { stroke(s, it) }; s.settle()
        val sp = SavePoints.forMedia(save(s))!!
        repeat(2) { stroke(s, 9 + it) }; s.settle() // more drawing after the save: not part of it
        val want = KDisplay.main_canvas.let { c -> SavePoints.picture(sp)!! }
        val dest = File(tmp, "exported")
        var ok = false
        SavePoints.export(sp, dest) { ok = it }
        assertTrue(ok)
        KLog.close()
        val out = File(tmp, "export.png")
        LogSession.replayExport(dest, out)
        val png = android.graphics.BitmapFactory.decodeFile(out.absolutePath)
        val flat = Bitmap.createBitmap(want.width, want.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(flat).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(want, 0f, 0f, null) }
        val a = IntArray(flat.width * flat.height); val b = IntArray(a.size)
        flat.getPixels(a, 0, flat.width, 0, 0, flat.width, flat.height); png.getPixels(b, 0, png.width, 0, 0, png.width, png.height)
        assertEquals("the export replays to the saved picture", 0, a.indices.count { a[it] != b[it] })
    }

    @Test fun segmentsASavePointWasMadeFromOutliveTheLogsOwnPruning() {
        val s = session("segments")
        repeat(3) { stroke(s, it) }; s.settle()
        val want = mainCrc()
        val sp = SavePoints.forMedia(save(s))!!
        repeat(KLog.KEEP_SEGMENTS + 6) { i -> stroke(s, 20 + i); s.settle(); open(s, SavePoints.get(sp.id)!!) }
        assertTrue("many segments since: ${KLog.segment}", KLog.segment > sp.chain.last()[0] + KLog.KEEP_SEGMENTS + 2)
        restart(s)
        assertTrue("segment ${sp.chain.map { it[0] }} kept", sp.chain.all { KLog.hasSegment(it[0]) })
        var res: Pair<String?, Bitmap?>? = null
        KLog.playSavePoint(sp.chain, sp.time, Double.POSITIVE_INFINITY, { _, f -> f() }) { r, b -> res = r to b }
        assertNull(res!!.first)
        assertEquals(want, crc(res!!.second!!))
        // a segment nothing holds is pruned as before
        println("segments kept: ${KLog.segments()} (save point chain ${sp.chain.map { it.toList() }})")
        assertTrue(KLog.segments().size <= KLog.KEEP_SEGMENTS + sp.chain.size + 1 + SavePoints.drafts().sumOf { it.chain.size })
    }

    @Test fun migrationFromTheActionLogKeepsTheCanvasAndHistory() {
        // a 1.1.0 log: no chains, no saves directory
        val s = session("migrate")
        repeat(4) { stroke(s, it) }; s.settle()
        val o = oracleNow()
        KLog.close()
        File(File(tmp, "migrate"), "saves").deleteRecursively()
        for (seg in File(tmp, "migrate").listFiles()!!.filter { it.name.startsWith("seg-") }) for (f in seg.listFiles()!!.filter { it.name.startsWith("ckpt-") }) {
            val cp = KLog.readCheckpoint(f)!!
            cp.o.remove("chain")
            val text = cp.o.toString()
            val crc = java.util.zip.CRC32().apply { update(text.toByteArray()) }.value
            f.writeText(text + "\n#crc=" + "%08x".format(crc) + "\n")
        }
        restart(s)
        assertEquals(o, oracleNow())
        assertTrue("a canvas with history and no save: unsaved", SavePoints.dirty())
    }
}
