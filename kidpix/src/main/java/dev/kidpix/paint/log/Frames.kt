package dev.kidpix.paint.log

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.concurrent.Executor
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Stored pictures ("frames"): every undo step and every checkpoint's picture, by id. A frame is
 * a key frame (all pixels, deflated) or a delta: the box of pixels that differ from the frame
 * written just before it, XOR that frame (unchanged pixels are zeros), deflated. Reading a delta reads its base first, so chains are cut by a
 * key frame every [MAX_DEPTH] frames or when the box is over [KEY_AREA] of the picture.
 *
 * Pixels are the bitmap's raw premultiplied ARGB_8888 words (exact; no PNG).
 *
 * [put] takes a private copy and returns at once: diffing, deflating and writing run on [io]
 * (null: at once on the caller's thread, as headless tests do). Until it is written a frame is
 * served from memory, so [get] never misses a frame that was put.
 */
class FrameStore(private val backend: Backend, private val io: Executor?, seed: Map<Long, Info>? = null) {

    /** Where encoded frames live: a directory in the app, a map in tests. */
    interface Backend {
        fun write(id: Long, bytes: ByteArray)
        fun read(id: Long): ByteArray?
        fun delete(id: Long)
        fun ids(): List<Long>
        fun size(id: Long): Long
        /** The first [n] bytes (the header) of frame [id]. */
        fun head(id: Long, n: Int): ByteArray? = read(id)
    }

    class DirBackend(val dir: File) : Backend {
        init { dir.mkdirs() }
        private fun f(id: Long) = File(dir, "f-%08d.kpf".format(id))
        override fun write(id: Long, bytes: ByteArray) {
            val tmp = File(dir, "f-%08d.tmp".format(id))
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f(id))) { f(id).delete(); tmp.renameTo(f(id)) }
        }
        override fun read(id: Long): ByteArray? = f(id).takeIf { it.isFile }?.let { runCatching { it.readBytes() }.getOrNull() }
        override fun delete(id: Long) { f(id).delete() }
        override fun ids(): List<Long> = (dir.list() ?: emptyArray()).mapNotNull {
            if (it.startsWith("f-") && it.endsWith(".kpf")) it.substring(2, it.length - 4).toLongOrNull() else null
        }.sorted()
        override fun size(id: Long): Long = f(id).length()
        override fun head(id: Long, n: Int): ByteArray? = runCatching {
            f(id).inputStream().use { i -> val b = ByteArray(n); var o = 0
                while (o < n) { val k = i.read(b, o, n - o); if (k < 0) break; o += k }
                b.copyOf(o) }
        }.getOrNull()
    }

    class MemBackend : Backend {
        val map = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
        override fun write(id: Long, bytes: ByteArray) { map[id] = bytes }
        override fun read(id: Long): ByteArray? = map[id]
        override fun delete(id: Long) { map.remove(id) }
        override fun ids(): List<Long> = map.keys.sorted()
        override fun size(id: Long): Long = (map[id]?.size ?: 0).toLong()
    }

    /** A decoded frame header. */
    class Info(val id: Long, val key: Boolean, val base: Long, val depth: Int, val w: Int, val h: Int, val bytes: Long)

    private val lock = Object()
    /** Put but not written yet (served by [get] from here). */
    private val pending = HashMap<Long, Bitmap>()
    /** Written frames' headers (base chains, sizes) for [closure] and pruning. */
    private val infos = HashMap<Long, Info>()
    /** The newest frame written and its pixels: the base of the next delta (writer thread only). */
    private var lastId = -1L
    private var lastPx: IntArray? = null
    private var lastDepth = 0
    /** Frames that must be written whole (the base chain is not trusted, e.g. replayed ones). */
    private var forceKey = false

    /** Bytes of the encoded frames (written ones). */
    val totalBytes: Long get() = synchronized(lock) { infos.values.sumOf { it.bytes } }

    init {
        if (seed != null) infos.putAll(seed) else for (id in backend.ids()) {
            val b = backend.head(id, HEADER_BYTES) ?: continue
            header(b)?.let { infos[id] = it.copyWith(bytes = backend.size(id)) }
        }
    }

    private fun Info.copyWith(bytes: Long) = Info(id, key, base, depth, w, h, bytes)

    fun has(id: Long): Boolean = synchronized(lock) { id in pending || id in infos }

    /** Ids stored (written or pending). */
    fun ids(): Set<Long> = synchronized(lock) { infos.keys + pending.keys }

    fun info(id: Long): Info? = synchronized(lock) { infos[id] }

    /**
     * Stores [bmp] (the caller hands it over: it must not change it afterwards) as frame [id].
     * [key]: write it whole, no base (frames made while replaying a log after a restart).
     */
    fun put(id: Long, bmp: Bitmap, key: Boolean = false) {
        synchronized(lock) { pending[id] = bmp }
        val job = Runnable {
            try { encodeAndWrite(id, bmp, key) } catch (t: Throwable) { logError("frame $id write failed", t) }
            synchronized(lock) { pending.remove(id) }
        }
        if (io != null) io.execute(job) else job.run()
    }

    /** Runs [f] on the writer after everything put so far is written. */
    fun afterWrites(f: () -> Unit) { if (io != null) io.execute { f() } else f() }

    private fun pixels(bmp: Bitmap): IntArray {
        val px = IntArray(bmp.width * bmp.height)
        bmp.copyPixelsToBuffer(IntBuffer.wrap(px))
        return px
    }

    private fun encodeAndWrite(id: Long, bmp: Bitmap, wantKey: Boolean) {
        val w = bmp.width; val h = bmp.height
        val px = pixels(bmp)
        val base = lastPx
        var key = wantKey || forceKey || base == null || base.size != px.size || lastDepth >= MAX_DEPTH
        var l = 0; var t = 0; var r = w; var b = h
        if (!key) {
            // the box of pixels that differ from the base
            t = -1; b = -1; l = w; r = -1
            for (y in 0 until h) {
                val o = y * w
                var x0 = -1
                for (x in 0 until w) if (px[o + x] != base!![o + x]) { x0 = x; break }
                if (x0 < 0) continue
                var x1 = x0
                for (x in w - 1 downTo x0) if (px[o + x] != base!![o + x]) { x1 = x; break }
                if (t < 0) t = y
                b = y
                if (x0 < l) l = x0
                if (x1 > r) r = x1
            }
            if (t < 0) { l = 0; t = 0; r = 0; b = 0 } else { r += 1; b += 1 }
            if ((r - l).toLong() * (b - t) > KEY_AREA * w * h) key = true
        }
        if (key) { l = 0; t = 0; r = w; b = h }
        val cw = r - l; val ch = b - t
        val raw = ByteBuffer.allocate(cw * ch * 4).order(ByteOrder.LITTLE_ENDIAN)
        val ib = raw.asIntBuffer()
        if (key) for (y in t until b) ib.put(px, y * w + l, cw)
        else {
            // a delta stores the box XOR the base: unchanged pixels are zeros (deflate makes them nearly free)
            val row = IntArray(cw)
            for (y in t until b) { val o = y * w + l; for (x in 0 until cw) row[x] = px[o + x] xor base!![o + x]; ib.put(row) }
        }
        val data = deflate(raw.array())
        val crc = CRC32().apply { update(data) }.value.toInt()
        val depth = if (key) 0 else lastDepth + 1
        val out = ByteArrayOutputStream(data.size + 64)
        DataOutputStream(out).use { o ->
            o.writeInt(MAGIC); o.writeLong(id); o.writeByte(if (key) 0 else 1); o.writeLong(if (key) -1 else lastId)
            o.writeInt(depth); o.writeInt(w); o.writeInt(h)
            o.writeInt(l); o.writeInt(t); o.writeInt(r); o.writeInt(b)
            o.writeInt(crc); o.writeInt(data.size); o.write(data)
        }
        val bytes = out.toByteArray()
        backend.write(id, bytes)
        synchronized(lock) { infos[id] = Info(id, key, if (key) -1 else lastId, depth, w, h, bytes.size.toLong()) }
        lastId = id; lastPx = px; lastDepth = depth
    }

    /** From now on until the next [put] with key = true... frames are whole (see [put]). */
    fun keyFramesOnly(on: Boolean) { forceKey = on }

    /**
     * A store that reads this one's written frames and keeps its own writes in memory (replays
     * of save points: what they store never reaches the disk). Call after the writer drained.
     */
    fun overlay(): FrameStore = FrameStore(OverlayBackend(backend), null, synchronized(lock) { HashMap(infos) })

    private class OverlayBackend(val under: Backend) : Backend {
        val map = java.util.concurrent.ConcurrentHashMap<Long, ByteArray>()
        override fun write(id: Long, bytes: ByteArray) { map[id] = bytes }
        override fun read(id: Long): ByteArray? = map[id] ?: under.read(id)
        override fun delete(id: Long) { map.remove(id) }
        override fun ids(): List<Long> = (map.keys + under.ids()).sorted()
        override fun size(id: Long): Long = map[id]?.size?.toLong() ?: under.size(id)
    }

    /** The picture of frame [id], or null if it is missing or damaged (blocking; any thread). */
    fun get(id: Long): Bitmap? {
        synchronized(lock) { pending[id] }?.let { return it }
        val px = decode(id, 0) ?: return null
        val h = synchronized(lock) { infos[id] } ?: return null
        val bmp = Bitmap.createBitmap(h.w, h.h, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(IntBuffer.wrap(px))
        return bmp
    }

    private fun decode(id: Long, level: Int): IntArray? {
        if (level > MAX_DEPTH + 2) return null
        synchronized(lock) { pending[id] }?.let { return pixels(it) }
        val bytes = backend.read(id) ?: return null
        val hd = header(bytes) ?: return null
        DataInputStream(bytes.inputStream()).use { inp ->
            inp.skipBytes(4 + 8 + 1 + 8 + 4 + 4 + 4)
            val l = inp.readInt(); val t = inp.readInt(); val r = inp.readInt(); val b = inp.readInt()
            val crc = inp.readInt(); val n = inp.readInt()
            if (n < 0 || n > bytes.size) return null
            val data = ByteArray(n); inp.readFully(data)
            if (CRC32().apply { update(data) }.value.toInt() != crc) return null
            val cw = r - l; val ch = b - t
            if (cw < 0 || ch < 0 || r > hd.w || b > hd.h || l < 0 || t < 0) return null
            val raw = inflate(data, cw * ch * 4) ?: return null
            val px = if (hd.key) IntArray(hd.w * hd.h) else (decode(hd.base, level + 1) ?: return null)
            if (px.size != hd.w * hd.h) return null
            val ib = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer()
            if (hd.key) for (y in t until b) ib.get(px, y * hd.w + l, cw)
            else for (y in t until b) { val o = y * hd.w + l; for (x in 0 until cw) px[o + x] = px[o + x] xor ib.get() }
            return px
        }
    }

    /** [ids] and every frame their deltas are built on. */
    fun closure(ids: Collection<Long>): Set<Long> = synchronized(lock) {
        val out = HashSet<Long>()
        for (start in ids) {
            var id = start
            while (id >= 0 && out.add(id)) id = infos[id]?.base ?: -1
        }
        out
    }

    fun bytesOf(ids: Collection<Long>): Long = synchronized(lock) { ids.sumOf { infos[it]?.bytes ?: 0L } }

    /**
     * Deletes written frames below [below] that are not in [keep] (with their bases): the writer
     * runs it after the frames put so far, so nothing still needed as a base goes.
     */
    fun prune(keep: Collection<Long>, below: Long) {
        afterWrites {
            val live = closure(keep) + setOfNotNull(lastId.takeIf { it >= 0 })
            // save points' and drafts' frames ([OOB_BASE] and up) are not numbered by the history: only [keep] holds them
            val gone = synchronized(lock) { infos.keys.filter { (it < below || it >= OOB_BASE) && it !in live && it !in pending } }
            for (id in gone) { backend.delete(id); synchronized(lock) { infos.remove(id) } }
        }
    }

    companion object {
        const val MAGIC = 0x4b504631 // "KPF1"
        const val MAX_DEPTH = 12
        const val KEY_AREA = 2.0 // XOR deltas stay small however big the box: key frames come by depth only
        const val HEADER_BYTES = 64
        /**
         * Frames of save points and drafts (log/SavePoints.kt) are numbered from here: they are
         * taken outside the actions, so a replay (which numbers the history's frames exactly as
         * the live app did) never meets one.
         */
        const val OOB_BASE = 1L shl 40

        fun header(b: ByteArray): Info? = runCatching {
            DataInputStream(b.inputStream()).use { i ->
                if (i.readInt() != MAGIC) return null
                val id = i.readLong(); val kind = i.readByte().toInt(); val base = i.readLong()
                val depth = i.readInt(); val w = i.readInt(); val h = i.readInt()
                if (w <= 0 || h <= 0 || w > 16384 || h > 16384) return null
                Info(id, kind == 0, base, depth, w, h, b.size.toLong())
            }
        }.getOrNull()

        fun deflate(raw: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_SPEED)
            d.setInput(raw); d.finish()
            val out = ByteArrayOutputStream(maxOf(64, raw.size / 8))
            val chunk = ByteArray(64 * 1024)
            while (!d.finished()) out.write(chunk, 0, d.deflate(chunk))
            d.end()
            return out.toByteArray()
        }

        fun inflate(data: ByteArray, size: Int): ByteArray? {
            val inf = Inflater()
            try {
                inf.setInput(data)
                val raw = ByteArray(size)
                var off = 0
                while (off < size && !inf.finished()) {
                    val n = inf.inflate(raw, off, size - off)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) return null
                    off += n
                }
                return if (off == size) raw else null
            } catch (t: Throwable) {
                return null
            } finally { inf.end() }
        }

        var logError: (String, Throwable) -> Unit = { m, t -> System.err.println("$m: $t") }
    }
}
