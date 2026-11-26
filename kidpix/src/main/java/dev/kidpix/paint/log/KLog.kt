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
    /** Main frame of checkpoint zero of each segment (kept for replays from the start). */
    private val zeroFrames = java.util.concurrent.ConcurrentHashMap<Int, Long>()
    /** History refs of the last two checkpoints (kept when pruning). */
    private var lastRefs: List<Pair<Set<Long>, Long>> = emptyList()

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
        inputs.clear(); zeroFrames.clear(); lastRefs = emptyList()
        root = null
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
        val msg = try { restore() } catch (t: Throwable) { FrameStore.logError("restore failed", t); null }
        lastRestore = if (msg != null) msg else {
            Engine.start(width, height, null)
            kidpix_android_boot()
            fresh()
            clock.runDue()
            History.clear(); History.nextId = (History.frames.ids().maxOrNull() ?: 0L) + 1
            segment = existingSegments().maxOrNull() ?: 0
            startSegment()
            "fresh start, segment $segment"
        }
        lastRestore += " in %.1f ms".format((System.nanoTime() - t0) / 1e6)
        log(lastRestore)
        return lastRestore
    }

    private fun existingSegments(): List<Int> = (root?.list() ?: emptyArray()).mapNotNull {
        if (it.startsWith("seg-")) it.removePrefix("seg-").toIntOrNull() else null
    }.sorted()

    private fun segFile(seg: Int) = File(root, "seg-%06d".format(seg))

    // ---------------------------------------------------------------- live entries

    /** Runs [a] in the engine, recording it first. All app input goes through here. */
    fun entry(a: Action) {
        if (replaying) return // input during a replay (the replay API) is dropped
        liveNow?.invoke()?.let { liveAdvance(it) }
        val rec = recording
        val t0 = if (rec) System.nanoTime() else 0L
        if (rec) { coder.put(cur, a, clock.time, KRandom.state); curHasRecords = true }
        val t1 = if (rec) System.nanoTime() else 0L
        try { a.apply() } catch (t: Throwable) { FrameStore.logError("action ${a.code} failed", t) }
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
        if (replaying) return
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
        wr.append(o.toByteArray(), complete)
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
        if (rollWanted) { flushBlock(true); startSegment() } else checkpoint()
        return true
    }

    /** Opens segment [segment] + 1 and takes its checkpoint zero (the state now). */
    private fun startSegment() {
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
        flushBlock(true)
        trimToCap()
        val (mainId, mainCopy) = History.store(KDisplay.main_canvas.bitmap)
        val layers = JSONObject()
        for ((name, c) in overlayLayers()) if (!c.knownBlank) layers.put(name, History.store(c.bitmap).first)
        History.remember(KDisplay.main_canvas.bitmap, mainId, mainCopy)
        if (ckptSeq == 0) zeroFrames[segment] = mainId
        val o = JSONObject()
            .put("v", 1).put("seg", segment).put("seq", ckptSeq).put("blocks", blocks).put("recTime", coder.lastTime)
            .put("time", clock.time).put("random", KRandom.state).put("version", versionCode).put("w", w).put("h", h)
            .put("engine", EngineState.capture())
            .put("past", JSONArray(History.pastIds)).put("future", JSONArray(History.futureIds)).put("next", History.nextId)
            .put("main", mainId).put("layers", layers).put("inputSeq", inputSeq)
        val text = o.toString()
        val crc = CRC32().apply { update(text.toByteArray()) }.value
        val f = File(d, "ckpt-%06d.json".format(ckptSeq))
        val seq = ckptSeq
        ckptSeq++
        actionsSince = 0; costSinceNs = 0
        val refs = (History.pastIds + History.futureIds + mainId + layers.keys().asSequence().map { layers.getLong(it) }).toSet()
        lastRefs = (lastRefs + (refs to History.nextId)).takeLast(2)
        val keep = lastRefs.flatMap { it.first } + zeroFrames.values
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
            }.onFailure { FrameStore.logError("checkpoint $seq write failed", it) }
        }
        History.frames.prune(keep, below)
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

    /** Writer thread: old segments' logs go (the newest [KEEP_SEGMENTS], within [logCapBytes]). */
    private fun pruneSegments(current: Int) {
        val segs = existingSegments().filter { it < current }.sortedDescending()
        var bytes = File(segFile(current), "log.kpl").length()
        for ((i, s) in segs.withIndex()) {
            val dir = segFile(s)
            val size = dir.listFiles()?.sumOf { it.length() } ?: 0
            bytes += size
            if (i >= KEEP_SEGMENTS - 1 || bytes > logCapBytes) {
                dir.listFiles()?.forEach { it.delete() }; dir.delete()
                zeroFrames.remove(s)
            }
        }
    }

    // ---------------------------------------------------------------- restore

    class Checkpoint(val o: JSONObject, val dir: File) {
        val seg = o.getInt("seg"); val seq = o.getInt("seq"); val blocks = o.getInt("blocks")
        val version = o.getInt("version")
        fun ids(key: String): List<Long> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
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
                applyCheckpoint(cp, main)
                for (s in existingSegments()) segFile(s).let { sd -> checkpoints(sd).lastOrNull()?.let { z -> if (z.seq == 0) zeroFrames[s] = z.o.getLong("main") } }
                return replayAfter(cp)
            }
        }
        return null
    }

    /** The engine as checkpoint [cp] left it ([main]: its picture). */
    fun applyCheckpoint(cp: Checkpoint, main: Bitmap) {
        val o = cp.o
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
    }

    /**
     * Replays the log of [cp]'s segment after it. The tail is replayed only if it was written by
     * this versionCode (a newer app never replays an older app's actions: the checkpoint picture
     * stays). Recording then goes on in the same segment, or in a new one when the tail could not
     * be replayed (so the log always ends in the state the app is in).
     */
    private fun replayAfter(cp: Checkpoint): String {
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
        if (!forked) {
            val n = tail.sumOf { it.second.size }
            val t0 = System.nanoTime()
            val r = replay(tail.flatMap { it.second })
            why += ", replayed $n records (${tail.size} actions) in %.1f ms".format((System.nanoTime() - t0) / 1e6)
            if (r != null) { forked = true; why += ", stopped: $r" }
        }
        if (forked || header == null) {
            startSegment()
            why += "; new segment $segment"
        } else {
            writer = LogWriter(logFile, io, header)
            coder = decoder
            blocks = complete.size
            ckptSeq = (checkpoints(cp.dir).maxOfOrNull { it.seq } ?: cp.seq) + 1
            lastRefs = listOf((cp.ids("past") + cp.ids("future") + cp.o.getLong("main")).toSet() to cp.o.getLong("next"))
            actionsSince = tail.size
            if (tail.isNotEmpty()) { costSinceNs = (checkpointCostMs * 1e6).toLong(); maybeCheckpoint() }
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
    fun replay(recs: List<Rec>, onRecord: ((Rec) -> Unit)? = null): String? {
        val ui = KUi.ui; val snd = KSounds.player
        KUi.ui = object : Ui {}
        KSounds.player = replaySounds()
        replaying = true
        try {
            for ((i, r) in recs.withIndex()) {
                val a = r.action
                clock.advanceTo(r.time)
                if (a is Action.Shift) { clock.shiftTasks(a.d); continue }
                if (r.random != null && r.random != KRandom.state) return "record $i (${a.code}): random state differs"
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
        applyCheckpoint(zero, main)
        val t0 = recs.firstOrNull()?.time ?: clock.time
        var i = 0
        val startWall = System.nanoTime()
        fun step() {
            val target = if (speed.isInfinite()) Long.MAX_VALUE else t0 + ((System.nanoTime() - startWall) / 1e6 * speed).toLong()
            var j = i
            while (j < recs.size && recs[j].time <= target) j++
            val r = replay(recs.subList(i, j))
            i = j
            KUi.ui.invalidate()
            if (r != null || i >= recs.size) {
                if (r == null) { replaying = true; try { clock.advanceTo(endTime) } finally { replaying = false } }
                writer = keepWriter; coder = keepCoder; done(r == null)
            }
            else schedule(16) { step() }
        }
        step()
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
