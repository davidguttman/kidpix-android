package dev.kidpix.paint.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.kidpix.paint.PictureStore
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * 1.1.0: the action log (log/KLog.kt) replaced this autosave; only [readLegacy] is used, once,
 * to turn the picture an older build saved into the log's checkpoint zero.
 *
 * saveToLocalStorage for the app (1.0.0): the current picture in app-private storage.
 *
 * Changes are coalesced: the picture is copied [IDLE_MS] after the last change, or [MAX_WAIT_MS]
 * after the first unsaved one while changes keep coming, and written on a background thread.
 * The file is the raw premultiplied pixels, deflated at the fastest level (exact, and about ten
 * times cheaper than a PNG of the whole canvas, which the WebView-era store wrote after every
 * stroke). [flush] (onPause/onStop, before a restart) writes what is pending at once. So a
 * process killed without warning loses at most the last [MAX_WAIT_MS] of drawing; a normal
 * background kill (after onStop) loses nothing.
 *
 * Reading also accepts the older picture.png (1.0.0 builds before this one).
 */
class FilePictureStore(dir: File) : PictureStore {
    private val file = File(dir, "picture.kpx")
    private val tmp = File(dir, "picture.kpx.tmp")
    private val png = File(dir, "picture.png")
    private val pending = AtomicReference<Bitmap?>(null)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "kidpix-save").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())

    /** The newest unsaved change's copier (main thread), and when the first unsaved change came. */
    private var dirty: (() -> Bitmap)? = null
    private var firstDirty = 0L
    private val copyNow = Runnable { takeCopy() }

    override fun save(picture: Bitmap) {
        main.removeCallbacks(copyNow); dirty = null
        if (pending.getAndSet(picture) == null) io.execute { drain() }
    }

    override fun changed(snapshot: () -> Bitmap) {
        val now = SystemClock.uptimeMillis()
        if (dirty == null) firstDirty = now
        dirty = snapshot
        main.removeCallbacks(copyNow)
        main.postAtTime(copyNow, minOf(now + IDLE_MS, firstDirty + MAX_WAIT_MS))
    }

    /** Main thread: copy the picture now and hand it to the writer. */
    private fun takeCopy() {
        val s = dirty ?: return
        dirty = null
        main.removeCallbacks(copyNow)
        val b = runCatching { s() }.getOrNull() ?: return
        if (pending.getAndSet(b) == null) io.execute { drain() }
    }

    private fun drain() {
        synchronized(this) {
            val b = pending.getAndSet(null) ?: return
            runCatching {
                write(b, tmp)
                if (!tmp.renameTo(file)) error("rename failed")
                png.delete() // the older format, now stale
            }
        }
    }

    /** Writes a picture still waiting first, so a restart reads the newest. Main thread. */
    override fun load(): Bitmap? {
        flush()
        if (file.isFile) runCatching { read(file) }.getOrNull()?.let { return it }
        return if (png.isFile) BitmapFactory.decodeFile(png.absolutePath) else null
    }

    /** Blocks until the newest picture is on disk (before the process may go away). Main thread. */
    fun flush() {
        if (Looper.myLooper() == Looper.getMainLooper()) takeCopy()
        drain()
    }

    override fun clear() {
        main.removeCallbacks(copyNow); dirty = null
        pending.set(null)
        io.execute { synchronized(this) { if (pending.get() == null) { file.delete(); png.delete() } } }
    }

    companion object {
        const val IDLE_MS = 500L
        const val MAX_WAIT_MS = 2000L
        private const val MAGIC = 0x4b505831 // "KPX1"

        fun write(b: Bitmap, f: File) {
            val buf = ByteBuffer.allocate(b.rowBytes * b.height)
            b.copyPixelsToBuffer(buf)
            DataOutputStream(f.outputStream().buffered(1 shl 16)).use { out ->
                out.writeInt(MAGIC); out.writeInt(b.width); out.writeInt(b.height); out.writeInt(b.rowBytes)
                val d = Deflater(Deflater.BEST_SPEED)
                DeflaterOutputStream(out, d, 1 shl 16).let { z -> z.write(buf.array(), 0, buf.position()); z.finish() }
                d.end()
            }
        }

        /** The picture the autosave of 1.0.0 left (picture.kpx, or the older picture.png), or null. */
        fun readLegacy(dir: File): Bitmap? {
            val kpx = File(dir, "picture.kpx")
            if (kpx.isFile) runCatching { read(kpx) }.getOrNull()?.let { return it }
            val png = File(dir, "picture.png")
            return if (png.isFile) BitmapFactory.decodeFile(png.absolutePath) else null
        }

        fun read(f: File): Bitmap? = DataInputStream(f.inputStream().buffered(1 shl 16)).use { inp ->
            if (inp.readInt() != MAGIC) return null
            val w = inp.readInt(); val h = inp.readInt(); val stride = inp.readInt()
            if (w <= 0 || h <= 0 || w > 16384 || h > 16384 || stride != w * 4) return null
            val raw = ByteArray(stride * h)
            DataInputStream(InflaterInputStream(inp)).readFully(raw)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(ByteBuffer.wrap(raw)) }
        }
    }
}
