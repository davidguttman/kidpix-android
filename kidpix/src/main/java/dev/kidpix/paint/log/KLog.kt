package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KUi
import dev.kidpix.paint.SoundPlayer
import dev.kidpix.paint.Ui
import dev.kidpix.paint.canvas.BitmapSource
import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.kidpix_android_boot
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executor
import java.util.zip.CRC32

/**
 * The action log: every entry into the engine ([entry]) is recorded before it runs, so the
 * picture can be rebuilt from the log. Restoring = the newest valid checkpoint (pictures as
 * stored frames + engine state) + a replay of the log after it.
 *
 * Determinism: the engine's clock is a [VirtualClock] in the app too (the app pumps it to the
 * wall time, [pump]); every timer runs at its due time and every entry first runs the timers due
 * before it, so a replay that moves the clock to each record's time runs the same code in the
 * same order with the same `Date.now()`. KRandom is the one random source (sounds draw from it
 * too); its state is recorded per action and checked by replays. Pictures from outside (a picture
 * opened from the gallery) are stored next to the log.
 *
 * Layout under [root]: `frames/` (FrameStore: undo steps and checkpoint pictures),
 * `seg-NNNNNN/log.kpl` (the log of one segment: one picture from new/open/clear on),
 * `seg-NNNNNN/ckpt-NNNNNN.json`, `seg-NNNNNN/in-NNNN.kpx` (opened pictures).
 */
object KLog {
    // ---------------------------------------------------------------- configuration
    var versionCode = 0
    /** The live time for an entry (the app: the wall clock); null = the clock as it is (tests). */
    var liveNow: (() -> Long)? = null
    /** The sound player used while replaying (silent, but with the app's timing model). */
    var replaySounds: () -> SoundPlayer = { KSounds.silent }
    /** Asks the app to call [flushIdle] a little later (hover moves are batched). */
    var requestIdleFlush: (() -> Unit)? = null
    /** Asks the app to call [pump] a little later (a checkpoint is due but something is still going on). */
    var requestRetry: (() -> Unit)? = null
    var log: (String) -> Unit = { println("KidPixLog: $it") }

    /** Checkpoint after this many actions or this much engine time of actions (what a replay costs). */
    var checkpointActions = 40
    var checkpointCostMs = 50.0
    /** A pending timer later than this behind the wall clock: the process was frozen (Shift). */
    const val JUMP_MS = 1000L
    /** Storage cap: frames reachable from the history, and logs of kept segments. */
    var frameCapBytes = 48L shl 20
    var logCapBytes = 16L shl 20
    const val KEEP_SEGMENTS = 8

    // ---------------------------------------------------------------- state
    var root: File? = null
        private set
    private var io: Executor? = null
    var replaying = false
        private set
    private var writer: LogWriter? = null
    private var coder = RecordCoder()
    private val cur = Bytes(8192)
    private var curHasRecords = false
    var segment = 0
        private set
    private var segDir: File? = null
    private var blocks = 0
    private var ckptSeq = 0
    private var actionsSince = 0
    private var costSinceNs = 0L
    private var rollWanted = false
    private var inputSeq = 0
    private val inputs = HashMap<String, Bitmap>()
    private var w = 0
    private var h = 0
    /** Main and layer frames of checkpoint zero of each segment (kept for replays from the start). */
    private val zeroFrames = java.util.concurrent.ConcurrentHashMap<Int, Set<Long>>()
    /** Every frame checkpoint zero of each segment names (pictures and its undo history). */
    private val zeroAll = java.util.concurrent.ConcurrentHashMap<Int, Set<Long>>()
    /**
     * What came before the current segment's start, oldest first: (segment, blocks) pieces whose
     * replays, one after the other, end where this segment starts (a clear-all, a save point
     * opened, a restart that could not replay). Empty: the segment starts the picture.
     */
    @Volatile var chainPrefix: List<IntArray> = emptyList()
    /** Frames and segments that save points and drafts hold (log/SavePoints.kt): never pruned. */
    var pinned: () -> Pair<Set<Long>, Set<Int>> = { emptySet<Long>() to emptySet() }
    /** Called on the writer thread after each checkpoint write (the storage cap of save points). */
    var afterCheckpointWrite: (() -> Unit)? = null
    /** A save point's or a segment's replay is being played (input is dropped). */
    var playing = false
        private set
    /** The play button's replay: glfx mixers show their drag as it was drawn (tools/Wholefx.kt). */
    @Volatile var showDrags = false
    /** The newest checkpoint taken, in memory (playing a save point comes back to it). */
    private var lastCkpt: Checkpoint? = null
    /** History refs of the last two checkpoints (kept when pruning). */
    private var lastRefs: List<Pair<Set<Long>, Long>> = emptyList()
    /** The engine state of a freshly started engine (EngineGraph roots): checkpoints store what differs. */
    private var baseline: Map<String, String>? = null
    /** Why the last checkpoint applied did not reproduce its engine state exactly (null: it did). */
    var lastApplyProblem: String? = null
        private set
    /** Main-thread cost of the engine state in checkpoints (for the report and perfLog). */
    var graphNs = 0L; var graphCount = 0L; var graphMaxNs = 0L; var graphBytes = 0L
    /** The same for whole checkpoints (frames handed to the writer, the engine state, the JSON). */
    var ckptNs = 0L; var ckptMaxNs = 0L

    /** Debug: what checkpoints cost (main thread) and weigh. */
    fun costs(): String = "checkpoints $graphCount: %.2f ms mean, %.2f ms max (engine state %.2f ms mean, %.2f ms max), last %d bytes; moves %d logged %.4f ms mean, %.3f ms max".format(
        if (graphCount > 0) ckptNs / 1e6 / graphCount else 0.0, ckptMaxNs / 1e6, if (graphCount > 0) graphNs / 1e6 / graphCount else 0.0, graphMaxNs / 1e6,
        graphBytes, moves, if (moves > 0) moveNs / 1e6 / moves else 0.0, moveMaxNs / 1e6)

    /** Timing (for the report and perfLog): ns of main-thread logging work per record. */
    var recordNs = 0L; var records = 0L
    /** The same for pointer moves only (the per-event cost while drawing). */
    var moveNs = 0L; var moves = 0L; var moveMaxNs = 0L
    var lastRestore: String = ""

    val clock: VirtualClock get() = KTime.backend as VirtualClock
    fun segmentDir(): File? = segDir
    /** Log blocks handed to the writer in this segment. */
    val blockCount: Int get() = blocks
    val recording: Boolean get() = writer != null && !replaying

    // ---------------------------------------------------------------- setup

    /** Stops recording (tests between sessions; the app never calls it). */
    fun close() {
        writer?.close(); writer = null
        replaying = false
        cur.clear(); curHasRecords = false
        inputs.clear(); zeroFrames.clear(); zeroAll.clear(); lastRefs = emptyList()
        chainPrefix = emptyList(); lastCkpt = null; playing = false
        pinned = { emptySet<Long>() to emptySet() }; afterCheckpointWrite = null
        root = null
        SavePoints.close()
        History.frames = FrameStore(FrameStore.MemBackend(), null) // nothing points into a directory that may go
    }

    /**
     * Starts the engine for a [width] x [height] canvas from what is stored under [dir]: the newest
     * checkpoint and the log after it. With nothing stored, [fresh] sets up the first picture (the
     * app: an older build's autosaved picture, or the opening page) and checkpoint zero is taken.
     * Returns a line describing what happened.
     */
    fun start(dir: File, width: Int, height: Int, ioExecutor: Executor?, fresh: () -> Unit): String {
        close()
        root = dir; io = ioExecutor; w = width; h = height
        History.frames = FrameStore(FrameStore.DirBackend(File(dir, "frames")), ioExecutor)
        History.onPush = null
        val t0 = System.nanoTime()
        // what save points and old segments hold, before anything can prune
        for (s in existingSegments()) checkpoints(segFile(s)).lastOrNull()?.takeIf { it.seq == 0 }?.let { noteZero(s, it) }
        SavePoints.load(dir)
        val msg = try { restore() } catch (t: Throwable) { FrameStore.logError("restore failed", t); null }
        lastRestore = if (msg != null) msg else {
            freshEngine(width, height)
            fresh()
            clock.runDue()
            History.clear(); History.nextId = (History.frames.ids().filter { it < FrameStore.OOB_BASE }.maxOrNull() ?: 0L) + 1
            segment = existingSegments().maxOrNull() ?: 0
            startSegment(emptyList())
            "fresh start, segment $segment"
        }
        lastRestore += " in %.1f ms".format((System.nanoTime() - t0) / 1e6)
        log(lastRestore)
        return lastRestore
    }

    /** A freshly started engine (as on page load), and its state noted as the checkpoints' baseline. */
    private fun freshEngine(width: Int, height: Int) {
        Engine.start(width, height, null)
        kidpix_android_boot()
        baseline = EngineGraph.encode().roots
    }

    /** The engine state as a checkpoint keeps it (save points keep it too; reading it changes nothing). */
    fun engineNow(): JSONObject = EngineGraph.capture(baseline)

    private fun existingSegments(): List<Int> = (root?.list() ?: emptyArray()).mapNotNull {
        if (it.startsWith("seg-")) it.removePrefix("seg-").toIntOrNull() else null
    }.sorted()

    private fun segFile(seg: Int) = File(root, "seg-%06d".format(seg))

    // ---------------------------------------------------------------- live entries

    /** Runs [a] in the engine, recording it first. All app input goes through here. */
    fun entry(a: Action) {
        if (replaying || playing) return // input during a replay (the replay API) is dropped
        liveNow?.invoke()?.let { liveAdvance(it) }
        val rec = recording
        val t0 = if (rec) System.nanoTime() else 0L
        if (rec) { coder.put(cur, a, clock.time, KRandom.state, if (a.starts) ActionPrint.now() else 0); curHasRecords = true }
        val t1 = if (rec) System.nanoTime() else 0L
        try { a.apply() } catch (t: Throwable) { FrameStore.logError("action ${a.code} failed", t) }
        // the views show the engine's selection after every action (not only where a handler
        // remembered to say so: Brush > Spray Paint clears the brush options and said nothing)
        if (a !is Action.Pointer || a.kind != Action.MOVE) KUi.ui.selectionChanged()
        if (!rec) return
        val t2 = System.nanoTime()
        costSinceNs += t2 - t1
        if (a.ends) {
            val hover = a is Action.Pointer && a.kind == Action.MOVE
            if (!hover) endAction() else requestIdleFlush?.invoke()
        } else if (cur.n > 16 * 1024) flushBlock(false)
        val ns = (t1 - t0) + (System.nanoTime() - t2)
        recordNs += ns; records++
        if (a is Action.Pointer && a.kind == Action.MOVE) { moveNs += ns; moves++; if (ns > moveMaxNs) moveMaxNs = ns }
    }

    /** A picture opened from the gallery: stored next to the log, then opened as an action. */
    fun openPicture(bmp: Bitmap) {
        val name = "in-%04d.kpx".format(++inputSeq)
        inputs[name] = bmp
        val d = segDir
        if (recording && d != null) runIo { runCatching { writeRaw(bmp, File(d, name)) } }
        entry(Action.Open(name))
        rollWanted = true // an opened picture starts a new segment
    }

    fun inputPicture(name: String): Bitmap? = inputs[name] ?: segDir?.let { d ->
        runCatching { readRaw(File(d, name)) }.getOrNull()?.also { inputs[name] = it }
    }

    /** Erasers that end by clearing everything: a new picture, a new segment. */
    fun clearedAll() { if (recording) rollWanted = true }

    /** The live clock moves to [now] (timers due run first). A long gap shifts the timers (recorded). */
    fun liveAdvance(now: Long) {
        val c = clock
        val nd = c.nextDue()
        if (nd != null && now - nd > JUMP_MS) {
            val d = now - nd
            if (recording) { coder.put(cur, Action.Shift(d), c.time, KRandom.state); curHasRecords = true }
            c.shiftTasks(d)
        }
        c.advanceTo(now)
    }

    /** The app's timer pump: run what is due by the wall clock, then see if a checkpoint can be taken. */
    fun pump(now: Long) {
        if (replaying || playing) return
        liveAdvance(now)
        if (actionsSince > 0 || rollWanted) maybeCheckpoint()
    }

    private fun endAction() {
        flushBlock(true)
        actionsSince++
        maybeCheckpoint()
    }

    /** Writes batched hover moves (the app calls it a moment after the last one). */
    fun flushIdle() { if (recording && curHasRecords && KAndroid.state.activeId == null) flushBlock(true) }

    private fun flushBlock(complete: Boolean) {
        val wr = writer ?: return
        if (!curHasRecords) return
        val o = Bytes(cur.n + 12)
        o.u(versionCode); o.u(w); o.u(h)
        o.bytes(cur.a, 0, cur.n)
        wr.append(o.toByteArray(), complete, coder.prints)
        blocks++
        cur.clear(); curHasRecords = false
    }

    /** The app pauses: sounds stop (an action), everything is written and a checkpoint taken if possible. */
    fun pause() {
        if (!recording) return
        entry(Action.Pause())
        maybeCheckpoint(force = true)
        writer?.sync()
    }

    // ---------------------------------------------------------------- checkpoints

    fun quiescent(): Boolean = KAndroid.state.activeId == null && clock.active == 0 &&
        KSounds.player.quietAt() <= clock.time && !KAndroid.toolIsDown(KCurrent.tool)

    fun maybeCheckpoint(force: Boolean = false): Boolean {
        if (!recording) return false
        val due = rollWanted || actionsSince >= checkpointActions || costSinceNs >= checkpointCostMs * 1e6 || (force && actionsSince > 0)
        if (!due) return false
        if (!quiescent()) { requestRetry?.invoke(); return false }
        if (rollWanted) { flushBlock(true); startSegment(chainPrefix + intArrayOf(segment, blocks)) } else checkpoint()
        return true
    }

    /** The pieces whose replays end in the picture as it is now (the current segment up to now last). */
    fun chainNow(): List<IntArray> { flushBlock(true); return chainPrefix + intArrayOf(segment, blocks) }

    /**
     * The canvas becomes [picture] (the canvas's size) with the undo history [past] / [future]
     * (frame ids, oldest first), and a new segment starts from it, continuing [chain] (the pieces
     * that made it; empty for a picture without a history). Recorded as checkpoint zero of that
     * segment, so it needs a quiet moment: false (nothing done) when something is in progress.
     */
    fun openState(picture: Bitmap, past: List<Long>, future: List<Long>, chain: List<IntArray>): Boolean {
        if (replaying || playing) return false
        if (recording && !quiescent()) return false
        flushBlock(true)
        KDisplay.main_context.replaceWith(BitmapSource(picture))
        History.restore(past, future, History.nextId)
        KDisplay.undoData = if (History.canUndo) true else null
        if (recording) startSegment(chain) else chainPrefix = chain
        entry(Action.Opened())
        KUi.ui.invalidate()
        return true
    }

    /** Opens segment [segment] + 1 (continuing [prefix]) and takes its checkpoint zero (the state now). */
    private fun startSegment(prefix: List<IntArray>) {
        chainPrefix = prefix
        writer?.close()
        segment++
        val d = segFile(segment); d.mkdirs()
        segDir = d
        writer = LogWriter(File(d, "log.kpl"), io, LogFormat.Header(versionCode, w, h, segment, clock.time))
        coder = RecordCoder(); coder.reset(clock.time)
        blocks = 0; ckptSeq = 0; rollWanted = false
        inputs.clear(); inputSeq = 0
        checkpoint()
    }

    /** Takes a checkpoint now (the caller checked [quiescent]). */
    fun checkpoint() {
        val d = segDir ?: return
        val c0 = System.nanoTime()
        flushBlock(true)
        trimToCap()
        val (mainId, mainCopy) = History.store(KDisplay.main_canvas.bitmap)
        val layers = JSONObject()
        for ((name, c) in overlayLayers()) if (!c.knownBlank) layers.put(name, History.store(c.bitmap).first)
        History.remember(KDisplay.main_canvas.bitmap, mainId, mainCopy)
        val g0 = System.nanoTime()
        val graph = EngineGraph.capture(baseline)
        val gns = System.nanoTime() - g0
        graphNs += gns; graphCount++; if (gns > graphMaxNs) graphMaxNs = gns
        val o = JSONObject()
            .put("v", 2).put("seg", segment).put("seq", ckptSeq).put("blocks", blocks).put("recTime", coder.lastTime)
            .put("time", clock.time).put("random", KRandom.state).put("version", versionCode).put("w", w).put("h", h)
            .put("graph", graph)
            .put("past", JSONArray(History.pastIds)).put("future", JSONArray(History.futureIds)).put("next", History.nextId)
            .put("main", mainId).put("layers", layers).put("inputSeq", inputSeq)
            .put("chain", JSONArray().also { a -> chainPrefix.forEach { p -> a.put(JSONArray().put(p[0]).put(p[1])) } })
        val cpObj = Checkpoint(o, d)
        lastCkpt = cpObj
        if (ckptSeq == 0) noteZero(segment, cpObj)
        val text = o.toString()
        graphBytes = text.length.toLong()
        val crc = CRC32().apply { update(text.toByteArray()) }.value
        val f = File(d, "ckpt-%06d.json".format(ckptSeq))
        val seq = ckptSeq
        ckptSeq++
        actionsSince = 0; costSinceNs = 0
        val refs = (History.pastIds + History.futureIds + mainId + layers.keys().asSequence().map { layers.getLong(it) }).toSet()
        lastRefs = (lastRefs + (refs to History.nextId)).takeLast(2)
        val keep = HashSet<Long>()
        lastRefs.forEach { keep += it.first }
        zeroFrames.values.forEach { keep += it }
        for (p in chainPrefix) zeroAll[p[0]]?.let { keep += it }
        zeroAll[segment]?.let { keep += it }
        keep += pinned().first
        val below = lastRefs.minOf { it.second }
        val seg = segment
        History.frames.afterWrites {
            runCatching {
                val tmp = File(d, "ckpt.tmp")
                tmp.writeText(text + "\n#crc=" + "%08x".format(crc) + "\n")
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
                // keep checkpoint zero and the newest two of this segment
                d.listFiles { x -> x.name.startsWith("ckpt-") && x.name.endsWith(".json") }?.sortedBy { it.name }?.let { all ->
                    all.drop(1).dropLast(2).forEach { it.delete() }
                }
                pruneSegments(seg)
                afterCheckpointWrite?.invoke()
            }.onFailure { FrameStore.logError("checkpoint $seq write failed", it) }
        }
        History.frames.prune(keep, below)
        val cns = System.nanoTime() - c0
        ckptNs += cns; if (cns > ckptMaxNs) ckptMaxNs = cns
    }

    private fun overlayLayers() = listOf("bnim" to KDisplay.bnimCanvas, "anim" to KDisplay.animCanvas,
        "preview" to KDisplay.previewCanvas, "tmp" to KDisplay.canvas)

    /** Over the cap: the oldest undo steps go (never the current picture). */
    private fun trimToCap() {
        val fs = History.frames
        var n = 0
        while (History.canUndo && fs.bytesOf(fs.closure(History.pastIds + History.futureIds)) > frameCapBytes) {
            History.dropOldest(); n++
        }
        if (n > 0) log("storage cap: dropped the $n oldest undo steps")
    }

    /**
     * Writer thread: old segments' logs go (the newest [KEEP_SEGMENTS], within [logCapBytes]),
     * except the ones the current picture was made from ([chainPrefix]) and the ones save points
     * and drafts were made from ([pinned]): those count toward the save points' storage cap.
     */
    private fun pruneSegments(current: Int) {
        val held = chainPrefix.map { it[0] }.toSet() + pinned().second
        val segs = existingSegments().filter { it < current && it !in held }.sortedDescending()
        var bytes = File(segFile(current), "log.kpl").length()
        for ((i, s) in segs.withIndex()) {
            val dir = segFile(s)
            val size = dir.listFiles()?.sumOf { it.length() } ?: 0
            bytes += size
            if (i >= KEEP_SEGMENTS - 1 || bytes > logCapBytes) {
                dir.listFiles()?.forEach { it.delete() }; dir.delete()
                zeroFrames.remove(s); zeroAll.remove(s)
            }
        }
    }

    /** Bytes of segment [seg]'s files (0 if it is gone). Any thread. */
    fun segmentBytes(seg: Int): Long = segFile(seg).listFiles()?.sumOf { it.length() } ?: 0L

    fun hasSegment(seg: Int): Boolean = root != null && File(segFile(seg), "log.kpl").isFile
    fun segments(): List<Int> = existingSegments()
    /** Frames checkpoint zero of [seg] names (pictures, layers, undo history). */
    fun zeroRefs(seg: Int): Set<Long> = zeroAll[seg] ?: emptySet()

    private fun noteZero(seg: Int, z: Checkpoint) {
        val pics = HashSet<Long>(); pics += z.o.getLong("main")
        z.o.optJSONObject("layers")?.let { l -> l.keys().forEach { pics += l.getLong(it) } }
        zeroFrames[seg] = pics
        zeroAll[seg] = pics + z.ids("past") + z.ids("future")
    }

    // ---------------------------------------------------------------- restore

    class Checkpoint(val o: JSONObject, val dir: File) {
        val seg = o.getInt("seg"); val seq = o.getInt("seq"); val blocks = o.getInt("blocks")
        val version = o.getInt("version")
        fun ids(key: String): List<Long> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
        fun chain(): List<IntArray> = o.optJSONArray("chain")?.let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); intArrayOf(p.getInt(0), p.getInt(1)) } } ?: emptyList()
    }

    fun readCheckpoint(f: File): Checkpoint? = runCatching {
        val lines = f.readText().split("\n")
        val text = lines[0]
        val crc = lines.firstOrNull { it.startsWith("#crc=") }?.removePrefix("#crc=")?.toLong(16) ?: return null
        if (CRC32().apply { update(text.toByteArray()) }.value != crc) return null
        Checkpoint(JSONObject(text), f.parentFile!!)
    }.getOrNull()

    fun checkpoints(segDir: File): List<Checkpoint> =
        (segDir.listFiles { x -> x.name.startsWith("ckpt-") && x.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.name }.mapNotNull { readCheckpoint(it) }

    /** Restores from the newest usable checkpoint; null when there is none. */
    private fun restore(): String? {
        for (seg in existingSegments().sortedDescending()) {
            val d = segFile(seg)
            for (cp in checkpoints(d)) {
                if (cp.o.getInt("w") != w || cp.o.getInt("h") != h) continue // another canvas size: start over below
                val main = History.frames.get(cp.o.getLong("main")) ?: continue
                segment = seg; segDir = d
                val problem = applyCheckpoint(cp, main)
                chainPrefix = cp.chain()
                return replayAfter(cp, problem)
            }
        }
        return null
    }

    /**
     * The engine as checkpoint [cp] left it ([main]: its picture). Returns null when the engine
     * state came back exactly (its fingerprint is the one stored), else why not: then nothing may
     * be replayed after it (the tail would run with a wrong state). Checkpoints of 1.1.0 and of
     * 1.2.0 before versionCode 12 kept a description of the state instead ([EngineState]): they
     * are applied as they were then (their tails are never replayed by this app: version rule).
     */
    fun applyCheckpoint(cp: Checkpoint, main: Bitmap): String? {
        val o = cp.o
        if (o.has("graph")) return applyGraph(cp, main)
        val ui = KUi.ui
        KUi.ui = object : Ui {}
        try {
            Engine.start(o.getInt("w"), o.getInt("h"), null)
            kidpix_android_boot()
            clock.time = o.getLong("time")
            EngineState.apply(o.getJSONObject("engine"))
            clock.runDue() // what the presses started (a stamp's image loads on a 0 ms timer)
            KDisplay.main_context.replaceWith(BitmapSource(main))
            val layers = o.optJSONObject("layers")
            for ((name, c) in overlayLayers()) {
                c.getContext("2d").clearRect(0, 0, c.width, c.height)
                layers?.optLong(name, -1)?.takeIf { it >= 0 }?.let { id -> History.frames.get(id)?.let { c.getContext("2d").replaceWith(BitmapSource(it)) } }
            }
            EngineState.applyContexts(o.getJSONObject("engine"))
            History.restore(cp.ids("past"), cp.ids("future"), o.getLong("next"))
            History.remember(KDisplay.main_canvas.bitmap, o.getLong("main"), main)
            clock.time = o.getLong("time")
            KRandom.state = o.getInt("random")
            inputSeq = o.optInt("inputSeq")
        } finally { KUi.ui = ui }
        lastApplyProblem = null
        return null
    }

    /** [applyCheckpoint] of a checkpoint that stores the engine state itself ([EngineGraph]). */
    private fun applyGraph(cp: Checkpoint, main: Bitmap): String? {
        val o = cp.o
        val ui = KUi.ui
        KUi.ui = object : Ui {}
        val problem: String?
        try {
            freshEngine(o.getInt("w"), o.getInt("h"))
            clock.runDue() // what starting the engine queued (as on a fresh start)
            KDisplay.main_context.replaceWith(BitmapSource(main))
            val layers = o.optJSONObject("layers")
            for ((name, c) in overlayLayers()) {
                c.getContext("2d").clearRect(0, 0, c.width, c.height)
                layers?.optLong(name, -1)?.takeIf { it >= 0 }?.let { id -> History.frames.get(id)?.let { c.getContext("2d").replaceWith(BitmapSource(it)) } }
            }
            // everything else: the tools, the options, the layers' drawing state, the clock, KRandom
            problem = try { EngineGraph.apply(o.getJSONObject("graph"), baseline) } catch (t: Throwable) { "engine state unreadable: $t" }
            History.restore(cp.ids("past"), cp.ids("future"), o.getLong("next"))
            History.remember(KDisplay.main_canvas.bitmap, o.getLong("main"), main)
            clock.time = o.getLong("time")
            KRandom.state = o.getInt("random")
            inputSeq = o.optInt("inputSeq")
        } finally { KUi.ui = ui }
        lastApplyProblem = problem
        if (problem != null) log("checkpoint ${cp.seg}/${cp.seq}: $problem")
        return problem
    }

    /**
     * Replays the log of [cp]'s segment after it. The tail is replayed only if it was written by
     * this versionCode (a newer app never replays an older app's actions: the checkpoint picture
     * stays). Recording then goes on in the same segment, or in a new one when the tail could not
     * be replayed (so the log always ends in the state the app is in).
     */
    private fun replayAfter(cp: Checkpoint, problem: String? = null): String {
        val logFile = File(cp.dir, "log.kpl")
        val parsed = LogFormat.read(logFile)
        val header = parsed.header
        val complete = LogWriter.completeBlocks(parsed)
        var why = "segment $segment checkpoint ${cp.seq}"
        var forked = false
        var decoded: List<Pair<Int, List<Rec>>> = emptyList()
        val decoder = RecordCoder()
        if (header == null) { forked = true; why += ", log unreadable" }
        else {
            decoded = try {
                decoder.reset(header.t0)
                val out = ArrayList<Pair<Int, List<Rec>>>()
                for (b in complete) {
                    decoder.prints = b.prints
                    val r = Reader(b.records, b.recStart)
                    val recs = ArrayList<Rec>()
                    while (r.more()) recs.add(decoder.get(r))
                    out.add(b.index to recs)
                }
                out
            } catch (t: Throwable) { forked = true; why += ", log undecodable"; emptyList() }
        }
        val tail = decoded.filter { it.first >= cp.blocks }
        val tailVersions = complete.filter { it.index >= cp.blocks }.map { it.version }.toSet()
        if (!forked && tailVersions.any { it != versionCode }) { forked = true; why += ", tail from versionCode $tailVersions not replayed" }
        if (!forked && cp.version != versionCode && tail.isNotEmpty()) { forked = true; why += ", checkpoint from versionCode ${cp.version}" }
        // the engine did not come back exactly: stop at the checkpoint's picture, never draw a wrong tail
        if (!forked && problem != null) { forked = true; why += ", engine state differs, tail not replayed ($problem)" }
        if (!forked) {
            val n = tail.sumOf { it.second.size }
            val t0 = System.nanoTime()
            val r = replay(tail.flatMap { it.second })
            why += ", replayed $n records (${tail.size} actions) in %.1f ms".format((System.nanoTime() - t0) / 1e6)
            if (r != null) {
                forked = true; why += ", stopped: $r"
                // a wrong state: back to the checkpoint's picture (nothing of the tail is kept)
                if (r.contains("engine fingerprint")) {
                    val next = History.nextId
                    applyCheckpoint(cp, History.frames.get(cp.o.getLong("main"))!!)
                    History.nextId = maxOf(History.nextId, next) // frames the replay stored keep their ids
                    why += ", back to the checkpoint"
                }
            }
        }
        if (forked || header == null) {
            startSegment(chainPrefix + intArrayOf(segment, cp.blocks))
            why += "; new segment $segment"
        } else {
            writer = LogWriter(logFile, io, header)
            coder = decoder
            blocks = complete.size
            ckptSeq = (checkpoints(cp.dir).maxOfOrNull { it.seq } ?: cp.seq) + 1
            lastRefs = listOf((cp.ids("past") + cp.ids("future") + cp.o.getLong("main")).toSet() to cp.o.getLong("next"))
            lastCkpt = cp
            actionsSince = tail.size
            coder.prints = true
            if (tail.isNotEmpty()) { costSinceNs = (checkpointCostMs * 1e6).toLong(); maybeCheckpoint() }
            // a checkpoint of an older build (a description of the state): a new one now, so that
            // what follows can be replayed from a checkpoint that keeps the state itself
            if (!cp.o.has("graph") && quiescent()) checkpoint()
        }
        return why
    }

    /**
     * Applies [recs] (a log tail) as the app applied them: the clock moves to each record's time
     * first (timers due run), then the record runs. Sounds stay silent (with the app's timing),
     * the UI sees nothing (the app refreshes its views after). Returns null, or why it stopped:
     * the KRandom state at an action's start differs from the recorded one (the replay would no
     * longer be what happened).
     */
    fun replay(recs: List<Rec>, onRecord: ((Rec) -> Unit)? = null, player: SoundPlayer? = null): String? {
        val ui = KUi.ui; val snd = KSounds.player
        KUi.ui = object : Ui {}
        KSounds.player = player ?: replaySounds()
        replaying = true
        try {
            for ((i, r) in recs.withIndex()) {
                val a = r.action
                clock.advanceTo(r.time)
                if (a is Action.Shift) { clock.shiftTasks(a.d); continue }
                if (r.random != null && r.random != KRandom.state) return "record $i (${a.code}): random state differs"
                if (r.print != null && r.print != ActionPrint.now()) return "record $i (${a.code}): engine fingerprint differs".also { log("replay: $it") }
                try { a.apply() } catch (t: Throwable) { FrameStore.logError("replayed action ${a.code} failed", t) }
                onRecord?.invoke(r)
            }
            return null
        } finally {
            replaying = false
            KUi.ui = ui; KSounds.player = snd
        }
    }

    // ---------------------------------------------------------------- replay as a feature (API)

    /**
     * Plays the current segment from its first checkpoint onto the canvas, [speed] times as fast
     * as it was drawn (Double.POSITIVE_INFINITY: at once). [schedule] runs a step after a delay
     * (the app: a Handler); [done] runs at the end, when the picture is back where it was. Input
     * is dropped while it plays. Not used by the UI yet.
     */
    fun playSegment(speed: Double, schedule: (Long, () -> Unit) -> Unit, done: (Boolean) -> Unit) {
        val d = segDir ?: return done(false)
        flushBlock(true)
        drainIo()
        val zero = checkpoints(d).lastOrNull()?.takeIf { it.seq == 0 } ?: return done(false)
        val main = History.frames.get(zero.o.getLong("main")) ?: return done(false)
        val parsed = LogFormat.read(File(d, "log.kpl"))
        val header = parsed.header ?: return done(false)
        val recs = LogFormat.records(header, LogWriter.completeBlocks(parsed)).flatMap { it.second }
        val keepWriter = writer; val keepCoder = coder
        // where the live clock is now: after the last record the timers ran on to here (animations ending)
        val endTime = maxOf(clock.time, liveNow?.invoke() ?: clock.time)
        writer = null
        playing = true
        playPieces(listOf(Piece(zero, main, recs)), endTime, speed, schedule) { r ->
            playing = false
            writer = keepWriter; coder = keepCoder; done(r == null)
        }
    }

    /** One piece of a chain: a segment's checkpoint zero, its picture, the records to replay after it. */
    class Piece(val zero: Checkpoint, val main: Bitmap, val recs: List<Rec>)

    /**
     * The pieces of [chain] ((segment, blocks): checkpoint zero of the segment and the records of
     * its first `blocks` blocks), read with [frames]. Fails (null, why) when a piece is gone, was
     * written by another versionCode (never a wrong replay: such a save point opens from its
     * frames only) or is shorter than recorded.
     */
    fun loadChain(chain: List<IntArray>, frames: FrameStore = History.frames): Pair<List<Piece>?, String> {
        if (chain.isEmpty()) return null to "no history"
        val out = ArrayList<Piece>()
        for (p in chain) {
            val d = segFile(p[0])
            val zero = checkpoints(d).lastOrNull()?.takeIf { it.seq == 0 } ?: return null to "segment ${p[0]}: no checkpoint zero"
            if (zero.version != versionCode) return null to "segment ${p[0]}: versionCode ${zero.version}"
            if (zero.o.getInt("w") != w || zero.o.getInt("h") != h) return null to "segment ${p[0]}: another canvas size"
            val main = frames.get(zero.o.getLong("main")) ?: return null to "segment ${p[0]}: picture missing"
            val parsed = LogFormat.read(File(d, "log.kpl"))
            val header = parsed.header ?: return null to "segment ${p[0]}: log unreadable"
            val blocksIn = LogWriter.completeBlocks(parsed).filter { it.index < p[1] }
            if (p[1] > 0 && blocksIn.none { it.index == p[1] - 1 }) return null to "segment ${p[0]}: log shorter than ${p[1]} blocks"
            if (blocksIn.any { it.version != versionCode }) return null to "segment ${p[0]}: blocks of another versionCode"
            val recs = runCatching { LogFormat.records(header, blocksIn).flatMap { it.second } }.getOrNull() ?: return null to "segment ${p[0]}: undecodable"
            out.add(Piece(zero, main, recs))
        }
        return out to "ok"
    }

    /**
     * Plays [pieces] one after the other onto the canvas (each from its checkpoint zero), [speed]
     * times as fast as they were drawn, then runs the timers on to [endTime]. [done] gets null or
     * why the replay stopped. The caller puts the live picture back afterwards.
     */
    fun playPieces(pieces: List<Piece>, endTime: Long, speed: Double, schedule: (Long, () -> Unit) -> Unit, done: (String?) -> Unit) {
        var k = -1
        var recs: List<Rec> = emptyList()
        var i = 0
        var t0 = 0L
        var startWall = 0L
        var player: SoundPlayer? = null
        fun next(): Boolean {
            k++
            if (k >= pieces.size) return false
            val p = pieces[k]
            val problem = applyCheckpoint(p.zero, p.main)
            // one sound model per piece, as a restore has (a fresh one per step would decide
            // differently when a sound a tool waits on spans two steps)
            player = replaySounds()
            // an engine state that did not come back exactly: only the piece's picture
            recs = if (problem == null) p.recs else emptyList(); i = 0
            t0 = recs.firstOrNull()?.time ?: clock.time
            startWall = System.nanoTime()
            return true
        }
        fun step() {
            while (true) {
                val target = if (speed.isInfinite()) Long.MAX_VALUE else t0 + ((System.nanoTime() - startWall) / 1e6 * speed).toLong()
                var j = i
                while (j < recs.size && recs[j].time <= target) j++
                val r = replay(recs.subList(i, j), null, player)
                i = j
                KUi.ui.invalidate()
                if (r != null) return done(r)
                if (i < recs.size) return schedule(16) { step() }
                if (!next()) break
            }
            replaying = true; try { clock.advanceTo(maxOf(endTime, clock.time)) } finally { replaying = false }
            KUi.ui.invalidate()
            done(null)
        }
        if (!next()) return done("nothing to play")
        step()
    }

    /**
     * Plays a save point's history ([chain], see [chainNow]; [endTime]: the engine time it was
     * taken) from its start onto the canvas, [speed] times as fast as it was drawn, then puts the
     * live picture, history and tool back. Needs a quiet moment (a checkpoint is taken first).
     * [done] gets null or why it could not play, and the picture the replay ended with (before
     * the live one came back). Frames the replay stores stay in memory. For a later "watch it
     * being drawn" button and the debug verify; no UI.
     */
    fun playSavePoint(chain: List<IntArray>, endTime: Long, speed: Double, schedule: (Long, () -> Unit) -> Unit,
                      done: (String?, Bitmap?) -> Unit) {
        if (!recording || playing) return done("not recording", null)
        if (!quiescent()) return done("busy", null)
        flushBlock(true)
        checkpoint()
        val live = lastCkpt ?: return done("no checkpoint", null)
        val liveMain = KDisplay.main_canvas.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        drainIo()
        val real = History.frames
        val (pieces, why) = loadChain(chain, real)
        if (pieces == null) return done(why, null)
        val keepWriter = writer; val keepCoder = coder
        val keepSince = actionsSince; val keepCost = costSinceNs
        writer = null; playing = true
        History.frames = real.overlay()
        playPieces(pieces, endTime, speed, schedule) { r ->
            val result = KDisplay.main_canvas.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            History.frames = real
            applyCheckpoint(live, liveMain)
            writer = keepWriter; coder = keepCoder; actionsSince = keepSince; costSinceNs = keepCost
            playing = false
            KUi.ui.invalidate()
            done(r, result)
        }
    }

    /** Runs the timers due up to [time] as a replay does (no UI, [player]'s sounds, input dropped). */
    fun replayTo(time: Long, player: SoundPlayer?) {
        val ui = KUi.ui; val snd = KSounds.player
        KUi.ui = object : Ui {}
        KSounds.player = player ?: replaySounds()
        replaying = true
        try { clock.advanceTo(maxOf(time, clock.time)) } finally {
            replaying = false
            KUi.ui = ui; KSounds.player = snd
        }
    }

    // ---------------------------------------------------------------- the play button (log/Watch.kt)

    /**
     * Everything the play button's replay changes, as it was before (in memory only: watching
     * writes nothing). The replay runs in a fresh engine world on new objects; [endWatch] puts
     * the live ones back: the `by world` objects (tools with all their fields), the shared
     * objects' fields (current tool and colours, the layer canvases with their pictures and
     * drawing state, the clock), the undo history (the same objects, the same next frame id),
     * KRandom and the log's own recording state. Nothing is rebuilt from a description.
     */
    class Live internal constructor(
        internal val world: dev.kidpix.paint.engine.World.Saved, internal val objects: EngineObjects,
        internal val history: History.Saved, internal val random: Int, internal val time: Long,
        internal val writer: LogWriter?, internal val coder: RecordCoder,
        internal val actionsSince: Int, internal val costSinceNs: Long, internal val lastCkpt: Checkpoint?,
        internal val inputs: Map<String, Bitmap>, internal val inputSeq: Int,
    )

    /**
     * Starts a play-button replay: needs a quiet moment (null when something is in progress or
     * the log is not recording). The live state is kept in memory; from now on nothing reaches
     * the disk (no log writer, frames go to an in-memory overlay) and input is dropped, until
     * [endWatch].
     */
    fun beginWatch(): Live? {
        if (!recording || playing) return null
        if (!quiescent()) return null
        flushBlock(true)
        val live = Live(dev.kidpix.paint.engine.World.save(), EngineObjects.take(), History.save(), KRandom.state, clock.time,
            writer, coder, actionsSince, costSinceNs, lastCkpt, HashMap(inputs), inputSeq)
        drainIo()
        writer = null; playing = true
        History.frames = live.history.frames.overlay()
        // from here on the replay works in a fresh world on new objects; the live ones wait untouched
        val ui = KUi.ui
        KUi.ui = object : Ui {}
        try { Engine.start(w, h, null); kidpix_android_boot(); History.clear() } finally { KUi.ui = ui }
        return live
    }

    /** Ends a play-button replay: the app exactly as [beginWatch] found it. */
    fun endWatch(l: Live) {
        try {
            KTime.reset() // the replay's timers
            dev.kidpix.paint.engine.World.restore(l.world)
            l.objects.restore()
            History.restore(l.history)
            KRandom.state = l.random
            clock.time = l.time
        } finally {
            writer = l.writer; coder = l.coder; actionsSince = l.actionsSince; costSinceNs = l.costSinceNs
            lastCkpt = l.lastCkpt; inputs.clear(); inputs.putAll(l.inputs); inputSeq = l.inputSeq
            playing = false; showDrags = false
        }
        KUi.ui.invalidate()
    }

    /**
     * One piece of the play button's replay: a picture that appears ([main]), then, if the piece
     * can be replayed faithfully ([zero] != null: this versionCode, complete, readable), its
     * records. A piece that cannot be replayed only shows its picture (the nearest stored frame).
     */
    class WatchPiece(val zero: Checkpoint?, val main: Bitmap, val recs: List<Rec>)

    /**
     * The pieces of [chain] for the play button, never failing: a piece whose log cannot be
     * replayed (another versionCode, cut short, damaged) keeps only its picture; a piece whose
     * picture is gone is left out (the next one's picture appears instead).
     */
    fun watchPieces(chain: List<IntArray>, frames: FrameStore = History.frames): List<WatchPiece> {
        val out = ArrayList<WatchPiece>()
        for (p in chain) runCatching { watchPiece(p, frames) }.getOrNull()?.let { out.add(it) }
        return out
    }

    /**
     * Piece [p] ((segment, blocks)): replayed from its earliest checkpoint after which every
     * block up to `blocks` is this versionCode's, complete and readable (checkpoint zero,
     * normally; a later one after an update in the middle of a segment). None: only the newest stored picture of the piece. Null: no picture at all.
     */
    private fun watchPiece(p: IntArray, frames: FrameStore): WatchPiece? {
        val d = segFile(p[0])
        val cps = checkpoints(d).filter { it.blocks <= p[1] && it.o.optInt("w") == w && it.o.optInt("h") == h }.sortedBy { it.seq }
        if (cps.isEmpty()) return null
        val decoded: Map<Int, Pair<Int, List<Rec>>>? = runCatching {
            val parsed = LogFormat.read(File(d, "log.kpl"))
            val header = parsed.header ?: return@runCatching null
            val blocks = LogWriter.completeBlocks(parsed).filter { it.index < p[1] }
            val recs = LogFormat.records(header, blocks)
            val versions = blocks.associate { it.index to it.version }
            recs.associate { (idx, r) -> idx to ((versions[idx] ?: -1) to r) }
        }.getOrNull()
        if (decoded != null) for (cp in cps) {
            // (a checkpoint of another versionCode is a start only when everything after it is this
            // version's: the live app went on from exactly that checkpoint after an update)
            val idx = (cp.blocks until p[1])
            if (idx.any { decoded[it]?.first != versionCode }) continue
            val main = frames.get(cp.o.getLong("main")) ?: continue
            return WatchPiece(cp, main, idx.flatMap { decoded.getValue(it).second })
        }
        for (cp in cps.reversed()) frames.get(cp.o.getLong("main"))?.let { return WatchPiece(null, it, emptyList()) }
        return null
    }

    // ---------------------------------------------------------------- export (debug)

    /**
     * Copies the current segment (log, checkpoints, opened pictures) and every frame its
     * checkpoints need into [dest], on the writer thread after what is pending. The headless
     * harness replays it: `./gradlew :kidpix:replayLog -Plog=<dest> -Pout=<png>`.
     */
    fun export(dest: File, done: (Boolean) -> Unit = {}) {
        val d = segDir ?: return done(false)
        flushBlock(true)
        writer?.sync()
        History.frames.afterWrites {
            val ok = runCatching {
                dest.deleteRecursively()
                val sd = File(dest, d.name); sd.mkdirs()
                d.listFiles()?.forEach { if (it.isFile && !it.name.endsWith(".tmp")) it.copyTo(File(sd, it.name), true) }
                val ids = HashSet<Long>()
                for (cp in checkpoints(sd)) {
                    ids += cp.ids("past"); ids += cp.ids("future"); ids += cp.o.getLong("main")
                    cp.o.optJSONObject("layers")?.let { l -> l.keys().forEach { ids += l.getLong(it) } }
                }
                val fd = File(dest, "frames"); fd.mkdirs()
                val src = File(root, "frames")
                for (id in History.frames.closure(ids)) {
                    val f = File(src, "f-%08d.kpf".format(id))
                    if (f.isFile) f.copyTo(File(fd, f.name), true)
                }
                true
            }.getOrElse { FrameStore.logError("export failed", it); false }
            done(ok)
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Waits (at most 3 s) until the writer has written everything handed to it. */
    fun drainIo() {
        val e = io ?: return
        val latch = java.util.concurrent.CountDownLatch(1)
        e.execute { latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun runIo(f: () -> Unit) { val e = io; if (e != null) e.execute(f) else f() }
    /** Runs [f] on the log's writer thread after everything handed to it so far (headless: now). */
    fun onWriter(f: () -> Unit) = runIo(f)

    fun writeRaw(b: Bitmap, f: File) {
        val px = IntArray(b.width * b.height)
        b.copyPixelsToBuffer(java.nio.IntBuffer.wrap(px))
        val raw = java.nio.ByteBuffer.allocate(px.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        raw.asIntBuffer().put(px)
        val z = FrameStore.deflate(raw.array())
        val o = Bytes(z.size + 16)
        o.i32(0x5850484b); o.i32(b.width); o.i32(b.height); o.i32(z.size)
        o.bytes(z, 0, z.size)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeBytes(o.toByteArray()); tmp.renameTo(f)
    }

    fun readRaw(f: File): Bitmap? {
        val a = f.readBytes()
        val r = Reader(a)
        if (r.i32() != 0x5850484b) return null
        val bw = r.i32(); val bh = r.i32(); val n = r.i32()
        val raw = FrameStore.inflate(a.copyOfRange(r.p, r.p + n), bw * bh * 4) ?: return null
        val px = IntArray(bw * bh)
        java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(px)
        return Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(java.nio.IntBuffer.wrap(px)) }
    }

    /** Debug: the state of the log as one line (perfLog, tests). */
    fun describe(): String = "quiet ${KAndroid.state.activeId == null}/${clock.active}/${KSounds.player.quietAt() - clock.time}/${!KAndroid.toolIsDown(KCurrent.tool)} segment $segment blocks $blocks ckpt $ckptSeq actionsSince $actionsSince " +
        "frames ${History.frames.ids().size} ${History.frames.totalBytes / 1024} KB undo ${History.pastIds.size}/${History.futureIds.size}"
}
