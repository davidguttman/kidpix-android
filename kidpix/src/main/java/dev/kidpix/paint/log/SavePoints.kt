package dev.kidpix.paint.log

import android.graphics.Bitmap
import dev.kidpix.paint.History
import dev.kidpix.paint.KDisplay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.CRC32

/**
 * Save points: a saved picture keeps the history of how it was made.
 *
 * A save point is taken when the picture is saved to the gallery (kind [SAVE], keyed to the
 * saved file: its MediaStore id and a hash of the file, so a file replaced or edited outside the
 * app is seen) and when a drawing with unsaved changes is put aside because another picture is
 * opened (kind [DRAFT]: app-private, never in the gallery). It holds:
 * - the picture (a frame, numbered from [FrameStore.OOB_BASE] up),
 * - the undo history at that moment (frame ids, both sides),
 * - the chain: the log pieces ((segment, blocks)) whose replays one after the other end in the
 *   picture (KLog.playSavePoint plays it from its start),
 * - the engine state, the engine time, the versionCode and the canvas size.
 *
 * Frames and log segments are shared: between save points, and with the live history. Nothing is
 * copied; a frame or segment stays on disk while any save point, draft or the live picture
 * names it (KLog prunes by marking everything [pins] and the live history name, then deleting
 * the rest), so two saves of one drawing cost only what differs, and dropping one never breaks
 * another.
 *
 * Opening one (KLog.openState): the canvas becomes its picture and its history, and a new
 * segment starts, continuing its chain. Drawing on from there is a new branch; the save point
 * itself never changes. Opening never loses anything: the drawing on the canvas, if it has
 * anything not saved, is parked first as a draft ([park]). One draft per parked drawing: a
 * draft opened again and parked again replaces itself, a draft saved goes away; at most
 * [MAX_DRAFTS], the oldest dropped.
 *
 * Files: `saves/sp-<id>.json` (one line of JSON, then a CRC line; written to a .tmp and renamed,
 * on the log's writer thread after the frames they name) and `saves/origin.json` (what the
 * canvas was opened from or saved as, to tell whether it has unsaved changes).
 *
 * Storage: logs + frames + drafts within [capBytes]. Over it, the oldest save points lose their
 * history first (the record goes; their pictures stay in the gallery and open flat), then the
 * oldest drafts, then the oldest pieces of the current picture's own chain (its replay from the
 * start; never its picture or undo history).
 */
object SavePoints {
    const val SAVE = "save"
    const val DRAFT = "draft"
    const val MAX_DRAFTS = 8
    var capBytes = 512L shl 20

    class SavePoint(
        val id: Long, val kind: String, val media: Long, val hash: String,
        val version: Int, val w: Int, val h: Int, val main: Long, val past: List<Long>, val future: List<Long>,
        val chain: List<IntArray>, val time: Long, val engine: JSONObject?,
    ) {
        /** Frames it names directly (the closure adds their delta bases). */
        fun frames(): Set<Long> = HashSet<Long>().also { s -> s += main; s += past; s += future; for (p in chain) s += KLog.zeroRefs(p[0]) }
        fun segments(): Set<Int> = chain.map { it[0] }.toSet()
        fun withMedia(m: Long, hsh: String) = SavePoint(id, SAVE, m, hsh, version, w, h, main, past, future, chain, time, engine)

        fun toJson(): JSONObject = JSONObject().put("v", 1).put("id", id).put("kind", kind).put("media", media).put("hash", hash)
            .put("version", version).put("w", w).put("h", h).put("main", main)
            .put("past", JSONArray(past)).put("future", JSONArray(future))
            .put("chain", JSONArray().also { a -> chain.forEach { p -> a.put(JSONArray().put(p[0]).put(p[1])) } })
            .put("time", time).put("engine", engine ?: JSONObject())

        companion object {
            fun fromJson(o: JSONObject): SavePoint {
                fun ids(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getLong(it) } } ?: emptyList()
                val ch = o.optJSONArray("chain")?.let { a -> (0 until a.length()).map { val p = a.getJSONArray(it); intArrayOf(p.getInt(0), p.getInt(1)) } } ?: emptyList()
                return SavePoint(o.getLong("id"), o.getString("kind"), o.optLong("media", -1), o.optString("hash", ""),
                    o.getInt("version"), o.getInt("w"), o.getInt("h"), o.getLong("main"), ids("past"), ids("future"), ch,
                    o.optLong("time"), o.optJSONObject("engine"))
            }
        }
    }

    /**
     * What the canvas is (as far as unsaved changes go): opened from or saved as save point [sp]
     * (or a picture without one: -1), whose picture has pixel CRC [crc] (-1: not known yet) and
     * whose redo side was [future]. [gen]: the canvas bitmap's generation then (same: unchanged).
     */
    class Origin(val sp: Long, @Volatile var crc: Long, val future: List<Long>, val gen: Int, val bmp: Bitmap?)

    private val lock = Object()
    private val all = java.util.TreeMap<Long, SavePoint>()
    private var dir: File? = null
    private var nextId = 1L
    private var nextOob = FrameStore.OOB_BASE
    @Volatile var origin: Origin? = null
        private set
    var log: (String) -> Unit = { KLog.log(it) }

    val ready: Boolean get() = dir != null

    fun close() { synchronized(lock) { all.clear(); dir = null; nextId = 1; nextOob = FrameStore.OOB_BASE; origin = null } }

    /** KLog.start: reads the save points under [root] and hooks them into the log's pruning. */
    fun load(root: File) {
        val d = File(root, "saves"); d.mkdirs()
        synchronized(lock) {
            all.clear(); dir = d
            for (f in d.listFiles() ?: emptyArray()) {
                if (f.name.endsWith(".tmp")) { f.delete(); continue }
                if (!f.name.startsWith("sp-") || !f.name.endsWith(".json")) continue
                val sp = readCrcJson(f)?.let { runCatching { SavePoint.fromJson(it) }.getOrNull() }
                if (sp == null) { log("save point ${f.name} unreadable: ignored"); continue }
                all[sp.id] = sp
            }
            nextId = (all.keys.maxOrNull() ?: 0L) + 1
            nextOob = maxOf(FrameStore.OOB_BASE, (History.frames.ids().filter { it >= FrameStore.OOB_BASE }.maxOrNull() ?: (FrameStore.OOB_BASE - 1)) + 1)
            origin = readCrcJson(File(d, "origin.json"))?.let { o ->
                runCatching { Origin(o.getLong("sp"), o.getLong("crc"), ids(o.optJSONArray("future")), Int.MIN_VALUE, null) }.getOrNull()
            }
        }
        KLog.pinned = { pins() }
        KLog.afterCheckpointWrite = { runCatching { enforceCap() }.onFailure { FrameStore.logError("save point cap failed", it) } }
    }

    private fun ids(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.getLong(i) } } ?: emptyList()

    fun get(id: Long): SavePoint? = synchronized(lock) { all[id] }
    fun list(): List<SavePoint> = synchronized(lock) { all.values.toList() }
    /** Drafts, newest first (the picker shows them before the saved pictures). */
    fun drafts(): List<SavePoint> = synchronized(lock) { all.values.filter { it.kind == DRAFT }.sortedByDescending { it.id } }
    /** The save point of the gallery picture [media] (null: none, it opens flat). */
    fun forMedia(media: Long): SavePoint? = synchronized(lock) { all.values.lastOrNull { it.kind == SAVE && it.media == media } }

    /**
     * The save point of gallery picture [media] if the file is still the one saved ([hash]) and
     * fits this canvas; null: open it flat. A file changed outside the app frees its save point.
     */
    fun forFile(media: Long, hash: String): SavePoint? {
        val sp = forMedia(media) ?: return null
        if (sp.hash != hash) { log("picture $media changed outside the app: opened flat"); delete(sp.id); return null }
        if (!KDisplay.isReady || sp.w != KDisplay.main_canvas.width || sp.h != KDisplay.main_canvas.height) return null
        return sp
    }

    /** Frames and segments every save point holds (KLog keeps them). */
    fun pins(): Pair<Set<Long>, Set<Int>> = synchronized(lock) {
        val f = HashSet<Long>(); val s = HashSet<Int>()
        for (sp in all.values) { f += sp.frames(); s += sp.segments() }
        f to s
    }

    // ------------------------------------------------------------------ taking one (main thread)

    /**
     * A save point of the canvas now (main thread, between or inside actions): the picture
     * becomes a frame (written on the log's writer), the history and the chain are noted. It is
     * held (its frames pinned) from now on; [commitSave] writes it, [discard] lets it go.
     */
    fun capture(kind: String): SavePoint {
        val b = KDisplay.main_canvas.bitmap
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        val sp: SavePoint
        synchronized(lock) {
            val frameId = nextOob++
            History.frames.put(frameId, copy)
            sp = SavePoint(nextId++, kind, -1, "", KLog.versionCode, b.width, b.height, frameId,
                History.pastIds, History.futureIds, KLog.chainNow(), KLog.clock.time, runCatching { EngineState.capture() }.getOrNull())
            all[sp.id] = sp
        }
        return sp
    }

    /** Saves taken but not written yet: the origin before each (restored if the save fails) and the draft it replaces. */
    private val pendingSaves = HashMap<Long, Pair<Origin?, Long>>()

    /**
     * Save tapped (main thread): a save point of the canvas, and the canvas is now "saved as" it.
     * The caller writes the PNG, then calls [commitSave] (or [discard] if that failed).
     */
    fun saving(): SavePoint {
        val before = origin
        val sp = capture(SAVE)
        val draft = before?.sp?.let { get(it) }?.takeIf { it.kind == DRAFT }?.id ?: -1L
        synchronized(lock) { pendingSaves[sp.id] = before to draft }
        setOrigin(sp.id, sp.future)
        return sp
    }

    /** The picture of [sp] was saved to the gallery as [media] (file hash [hash]): written (any thread). */
    fun commitSave(sp: SavePoint, media: Long, hash: String) {
        val done = sp.withMedia(media, hash)
        val draft = synchronized(lock) {
            val p = pendingSaves.remove(sp.id)
            if (all[sp.id] == null) return // dropped meanwhile (the cap)
            all[sp.id] = done
            p?.second ?: -1L
        }
        write(done)
        // a draft that has now been saved goes (one copy of a drawing, not two)
        if (draft >= 0) delete(draft)
    }

    /** The save of [sp] failed: nothing to keep, and the canvas has its unsaved changes again. */
    fun discard(sp: SavePoint) {
        val before = synchronized(lock) { all.remove(sp.id); pendingSaves.remove(sp.id) }
        if (origin?.sp == sp.id) origin = before?.first ?: Origin(-2, -1, emptyList(), Int.MIN_VALUE, null)
    }

    // ------------------------------------------------------------------ unsaved changes

    /** CRC of a picture's pixels. */
    fun crc(b: Bitmap): Long {
        val buf = java.nio.ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(buf)
        return CRC32().apply { update(buf.array(), 0, buf.position()) }.value
    }

    /**
     * True if the canvas has something no save point or saved picture holds: it differs from
     * what it was opened from or saved as (pixels, or the redo side). A canvas never opened or
     * saved (a fresh start): if it has any history. (-2: a save that failed: always.)
     */
    fun dirty(): Boolean {
        val o = origin
        val b = KDisplay.main_canvas.bitmap
        if (o == null) return History.canUndo || History.canRedo
        if (o.sp == -2L) return true
        if (History.futureIds != o.future) return true
        if (o.bmp === b && b.generationId == o.gen) return false
        val c = o.crc
        return c == -1L || crc(b) != c
    }

    /**
     * The canvas is now [sp] (-1: a picture without a save point); its CRC is computed on the
     * writer thread. [draftKept]: keep this draft id as the origin record (a save that will
     * remove it): the origin is [sp] either way.
     */
    private fun setOrigin(sp: Long, future: List<Long>) {
        val b = KDisplay.main_canvas.bitmap
        val copy = b.copy(Bitmap.Config.ARGB_8888, false)
        val o = Origin(sp, -1, future, b.generationId, b)
        origin = o
        runIo {
            o.crc = crc(copy)
            if (origin === o) writeOrigin(o)
        }
    }

    private fun writeOrigin(o: Origin) {
        val d = dir ?: return
        writeCrcJson(File(d, "origin.json"), JSONObject().put("sp", o.sp).put("crc", o.crc).put("future", JSONArray(o.future)))
    }

    // ------------------------------------------------------------------ park and open (main thread)

    /**
     * Before another picture is opened: if the canvas has unsaved changes, it is kept as a draft
     * (replacing the draft it was opened from, if it was one). Returns the draft, or null when
     * there was nothing to keep. Written on the log's writer thread before anything the open
     * writes, so a kill at any point leaves the drawing either live or a draft.
     */
    fun park(): SavePoint? {
        if (!dirty()) return null
        val from = origin?.sp?.let { get(it) }?.takeIf { it.kind == DRAFT }
        val d = capture(DRAFT)
        write(d)
        if (from != null) delete(from.id)
        // at most MAX_DRAFTS: the oldest go
        drafts().drop(MAX_DRAFTS).forEach { delete(it.id) }
        log("parked the drawing as draft ${d.id}" + (from?.let { " (replacing ${it.id})" } ?: ""))
        return d
    }

    /**
     * Opens [sp] (its picture [picture], read off the main thread from its frame): the drawing
     * on the canvas is parked first if needed, then the canvas becomes [sp] and its history.
     * False when the engine is busy (try again a moment later; nothing was changed).
     */
    fun open(sp: SavePoint, picture: Bitmap): Boolean {
        if (!openable()) return false
        // it is on the canvas already: unchanged, or the draft the canvas came from (the canvas is its newer state)
        if (origin?.sp == sp.id && (sp.kind == DRAFT || !dirty())) return true
        park()
        val ok = KLog.openState(picture, sp.past, sp.future, sp.chain)
        if (ok) setOrigin(sp.id, sp.future)
        log("opened save point ${sp.id} (${sp.kind}, ${sp.past.size} undo steps): $ok")
        return ok
    }

    /** Opens a picture without a save point (fitted to the canvas): parked first, then flat, no history. */
    fun openFlat(picture: Bitmap): Boolean {
        if (!openable()) return false
        park()
        val fitted = fit(picture)
        val ok = KLog.openState(fitted, emptyList(), emptyList(), emptyList())
        if (ok) setOrigin(-1, emptyList())
        return ok
    }

    /** Nothing in progress (KLog.openState would refuse otherwise): check before parking. */
    fun openable(): Boolean = !KLog.replaying && !KLog.playing && (!KLog.recording || KLog.quiescent())

    /** [picture] on the canvas's size, as opening a picture always drew it (contain, centred, smoothed). */
    fun fit(picture: Bitmap): Bitmap {
        val c = KDisplay.main_canvas
        if (picture.width == c.width && picture.height == c.height) return picture
        val out = Bitmap.createBitmap(c.width, c.height, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        val s = minOf(c.width.toFloat() / picture.width, c.height.toFloat() / picture.height)
        val w = picture.width * s; val h = picture.height * s
        val l = (c.width - w) / 2; val t = (c.height - h) / 2
        cv.drawBitmap(picture, null, android.graphics.RectF(l, t, l + w, t + h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    /** The picture of [sp] (blocking; any thread), or null if its frame is gone. */
    fun picture(sp: SavePoint): Bitmap? = History.frames.get(sp.main)

    // ------------------------------------------------------------------ dropping

    /** Forgets [id] (its frames and segments go at the next prune unless something else holds them). */
    fun delete(id: Long) {
        val d = dir
        synchronized(lock) { all.remove(id) }
        if (d != null) runIo { File(d, "sp-%08d.json".format(id)).delete() }
    }

    /** At start: saved pictures no longer in the gallery ([existing]: the MediaStore ids there) free their save points. */
    fun retainMedia(existing: Set<Long>) {
        val gone = synchronized(lock) { all.values.filter { it.kind == SAVE && it.media >= 0 && it.media !in existing } }
        for (sp in gone) delete(sp.id)
        if (gone.isNotEmpty()) log("${gone.size} saved pictures are gone from the gallery: their histories freed")
    }

    /**
     * Writer thread, after each checkpoint: logs + frames + drafts within [capBytes]. Counts what
     * would remain after pruning (the frames everything names, with their bases, and every
     * segment kept) and drops, oldest first, the histories of save points, then drafts, then the
     * oldest pieces of the live chain, until it fits. Never the live picture or its undo history.
     */
    fun enforceCap() {
        val fs = History.frames
        fun liveFrames(): Set<Long> = HashSet<Long>().also { s ->
            s += History.pastIds; s += History.futureIds
            for (p in KLog.chainPrefix) s += KLog.zeroRefs(p[0])
            s += KLog.zeroRefs(KLog.segment)
        }
        fun total(): Long {
            val pf = pins().first
            val frames = fs.bytesOf(fs.closure(liveFrames() + pf))
            val segs = (KLog.segments().toSet()).sumOf { KLog.segmentBytes(it) }
            return frames + segs
        }
        var t = total()
        if (t <= capBytes) return
        var dropped = 0
        while (t > capBytes) {
            val victim = synchronized(lock) {
                all.values.firstOrNull { it.kind == SAVE } ?: all.values.firstOrNull { it.kind == DRAFT }
            }
            if (victim != null) { delete(victim.id); dropped++ }
            else {
                val pre = KLog.chainPrefix
                if (pre.isEmpty()) break
                KLog.chainPrefix = pre.drop(1)
                dropped++
            }
            t = estimateAfterPrune(liveFrames())
        }
        if (dropped > 0) log("storage cap: dropped $dropped histories, about ${t / 1024} KB kept")
    }

    /** Bytes after the next prune: frames still named, segments still held (live chain, pins, newest [KLog.KEEP_SEGMENTS]). */
    private fun estimateAfterPrune(live: Set<Long>): Long {
        val fs = History.frames
        val (pf, ps) = pins()
        val held = ps + KLog.chainPrefix.map { it[0] } + KLog.segment
        val recent = KLog.segments().sortedDescending().take(KLog.KEEP_SEGMENTS)
        val segs = (held + recent).sumOf { KLog.segmentBytes(it) }
        return fs.bytesOf(fs.closure(live + pf)) + segs
    }

    // ------------------------------------------------------------------ files

    private fun runIo(f: () -> Unit) = KLog.onWriter(f)

    /** Writes [sp] on the writer thread (after the frames it names). */
    private fun write(sp: SavePoint) {
        val d = dir ?: return
        runIo {
            if (get(sp.id) == null) return@runIo // dropped before it was written
            runCatching { writeCrcJson(File(d, "sp-%08d.json".format(sp.id)), sp.toJson()) }
                .onFailure { FrameStore.logError("save point ${sp.id} write failed", it) }
        }
    }

    fun writeCrcJson(f: File, o: JSONObject) {
        val text = o.toString()
        val crc = CRC32().apply { update(text.toByteArray()) }.value
        val tmp = File(f.parentFile, f.name + ".tmp")
        java.io.FileOutputStream(tmp).use { out -> out.write((text + "\n#crc=" + "%08x".format(crc) + "\n").toByteArray()); runCatching { out.fd.sync() } }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun readCrcJson(f: File): JSONObject? = runCatching {
        if (!f.isFile) return null
        val lines = f.readText().split("\n")
        val crc = lines.firstOrNull { it.startsWith("#crc=") }?.removePrefix("#crc=")?.toLong(16) ?: return null
        if (CRC32().apply { update(lines[0].toByteArray()) }.value != crc) return null
        JSONObject(lines[0])
    }.getOrNull()

    /**
     * Debug: copies save point [sp] (its record, the segments of its chain and every frame it
     * needs) to [dest], on the writer thread. `:kidpix:replayLog -Plog=<dest>` replays it.
     */
    fun export(sp: SavePoint, dest: File, done: (Boolean) -> Unit) {
        val root = KLog.root ?: return done(false)
        KLog.onWriter {
            val ok = runCatching {
                dest.deleteRecursively(); dest.mkdirs()
                for (s in sp.segments()) {
                    val sd = File(root, "seg-%06d".format(s)); val td = File(dest, sd.name); td.mkdirs()
                    sd.listFiles()?.forEach { if (it.isFile && !it.name.endsWith(".tmp")) it.copyTo(File(td, it.name), true) }
                }
                val fd = File(dest, "frames"); fd.mkdirs()
                for (id in History.frames.closure(sp.frames())) {
                    val f = File(File(root, "frames"), "f-%08d.kpf".format(id))
                    if (f.isFile) f.copyTo(File(fd, f.name), true)
                }
                val sd = File(dest, "saves"); sd.mkdirs()
                writeCrcJson(File(sd, "sp-%08d.json".format(sp.id)), sp.toJson())
                true
            }.getOrElse { FrameStore.logError("save point export failed", it); false }
            done(ok)
        }
    }
}
