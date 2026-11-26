package dev.kidpix.paint.log

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.Executor
import java.util.zip.CRC32

/**
 * The log file of one segment: a header, then blocks. A block holds the records of one action
 * (a stroke from down to up, a press, a run of hover moves); it is written when the action ends,
 * so a kill loses at most the action in progress. Very long actions are cut into partial blocks
 * (not [Block.complete]); a replay stops after the last complete one.
 *
 *     header: "KPXL" format(1) versionCode w h segment t0(8) crc32
 *     block:  0xB7 len(varint) flags payload crc32(flags+payload)
 *             flags: 1 = complete, 2 = deflated (payload = rawLen(varint) + deflate data)
 *             raw payload: versionCode w h (varints), then records (RecordCoder)
 *
 * Every block carries a CRC32 over its bytes: a torn or damaged tail is cut off at the first
 * block that does not check, never fatal.
 */
object LogFormat {
    const val MAGIC = 0x4c58504b // "KPXL" little endian
    const val FORMAT = 1
    const val SYNC = 0xB7
    const val DEFLATE_OVER = 384

    class Header(val version: Int, val w: Int, val h: Int, val segment: Int, val t0: Long)

    class Block(val index: Int, val complete: Boolean, val version: Int, val w: Int, val h: Int, val records: ByteArray, val recStart: Int)

    class Parsed(val header: Header?, val blocks: List<Block>, val goodLength: Long, val damaged: Boolean)

    fun header(h: Header): ByteArray {
        val o = Bytes(48)
        o.i32(MAGIC); o.byte(FORMAT); o.i32(h.version); o.i32(h.w); o.i32(h.h); o.i32(h.segment)
        for (k in 0 until 8) o.byte((h.t0 ushr (8 * k)).toInt() and 0xff)
        val crc = CRC32().apply { update(o.a, 0, o.n) }.value.toInt()
        o.i32(crc)
        return o.toByteArray()
    }

    /** One block's bytes from a raw payload (versionCode w h + records). Runs on the writer thread. */
    fun block(raw: ByteArray, complete: Boolean): ByteArray {
        var flags = if (complete) 1 else 0
        val body = Bytes(raw.size + 16)
        if (raw.size > DEFLATE_OVER) {
            val z = FrameStore.deflate(raw)
            if (z.size + 5 < raw.size) {
                flags = flags or 2
                body.u(raw.size); body.bytes(z, 0, z.size)
            } else body.bytes(raw, 0, raw.size)
        } else body.bytes(raw, 0, raw.size)
        val crc = CRC32().apply { update(flags); update(body.a, 0, body.n) }.value.toInt()
        val o = Bytes(body.n + 16)
        o.byte(SYNC); o.u(body.n); o.byte(flags); o.bytes(body.a, 0, body.n); o.i32(crc)
        return o.toByteArray()
    }

    fun parse(bytes: ByteArray): Parsed {
        val r = Reader(bytes)
        val header = try {
            if (r.i32() != MAGIC || r.byte() != FORMAT) return Parsed(null, emptyList(), 0, bytes.isNotEmpty())
            val v = r.i32(); val w = r.i32(); val h = r.i32(); val seg = r.i32()
            var t0 = 0L; for (k in 0 until 8) t0 = t0 or (r.byte().toLong() shl (8 * k))
            val crc = r.i32()
            if (CRC32().apply { update(bytes, 0, r.p - 4) }.value.toInt() != crc) return Parsed(null, emptyList(), 0, true)
            Header(v, w, h, seg, t0)
        } catch (e: Reader.Bad) { return Parsed(null, emptyList(), 0, bytes.isNotEmpty()) }
        val blocks = ArrayList<Block>()
        var good = r.p.toLong()
        var damaged = false
        while (r.more()) {
            try {
                if (r.byte() != SYNC) { damaged = true; break }
                val len = r.ui()
                val flags = r.byte()
                if (len < 0 || r.p + len + 4 > bytes.size) { damaged = true; break }
                val bodyStart = r.p
                r.p += len
                val crc = r.i32()
                if (CRC32().apply { update(flags); update(bytes, bodyStart, len) }.value.toInt() != crc) { damaged = true; break }
                val raw = if (flags and 2 != 0) {
                    val br = Reader(bytes, bodyStart, bodyStart + len)
                    val rawLen = br.ui()
                    FrameStore.inflate(bytes.copyOfRange(br.p, bodyStart + len), rawLen) ?: run { damaged = true; null }
                } else bytes.copyOfRange(bodyStart, bodyStart + len)
                if (raw == null) break
                val pr = Reader(raw)
                val v = pr.ui(); val w = pr.ui(); val h = pr.ui()
                blocks.add(Block(blocks.size, flags and 1 != 0, v, w, h, raw, pr.p))
                good = r.p.toLong()
            } catch (e: Reader.Bad) { damaged = true; break }
        }
        return Parsed(header, blocks, good, damaged)
    }

    fun read(f: File): Parsed = if (f.isFile) parse(f.readBytes()) else Parsed(null, emptyList(), 0, false)

    /** Decodes [blocks] (in order, from the segment's start) into records with their times. */
    fun records(header: Header, blocks: List<Block>, from: Int = 0): List<Pair<Int, List<Rec>>> {
        val coder = RecordCoder()
        coder.reset(header.t0)
        val out = ArrayList<Pair<Int, List<Rec>>>()
        for (b in blocks) {
            val r = Reader(b.records, b.recStart)
            val recs = ArrayList<Rec>()
            while (r.more()) recs.add(coder.get(r))
            if (b.index >= from) out.add(b.index to recs)
        }
        return out
    }
}

/**
 * Appends blocks on [io] (the one writer thread, shared with frames and checkpoints so they
 * land in order). Each block is one write() to the kernel: a process kill after it loses
 * nothing; [sync] (on pause) also forces it to the disk.
 */
class LogWriter(val file: File, private val io: Executor?, header: LogFormat.Header) {
    @Volatile var broken = false
        private set
    private var out: FileOutputStream? = null
    /** Bytes written (writer thread). */
    var length = 0L
        private set

    init {
        run {
            try {
                file.parentFile?.mkdirs()
                val existing = LogFormat.read(file)
                if (existing.header == null) {
                    FileOutputStream(file, false).use { it.write(LogFormat.header(header)) }
                } else {
                    // a torn or partial tail goes: the next block follows the last complete one
                    val keep = lastCompleteEnd(file, existing)
                    RandomAccessFile(file, "rw").use { it.setLength(keep) }
                }
                out = FileOutputStream(file, true)
                length = file.length()
            } catch (t: Throwable) { broken = true; FrameStore.logError("log open failed", t) }
        }
    }

    private fun run(f: () -> Unit) { if (io != null) io.execute(f) else f() }

    fun append(raw: ByteArray, complete: Boolean) = run {
        if (broken) return@run
        try {
            val b = LogFormat.block(raw, complete)
            out!!.write(b)
            length += b.size
        } catch (t: Throwable) { broken = true; FrameStore.logError("log write failed", t) }
    }

    fun sync() = run { runCatching { out?.fd?.sync() } }

    fun close() = run { runCatching { out?.close() }; out = null }

    companion object {
        /** Byte length up to the end of the last complete block. */
        fun lastCompleteEnd(file: File, p: LogFormat.Parsed): Long {
            val n = p.blocks.indexOfLast { it.complete } + 1
            if (n == p.blocks.size) return p.goodLength
            // re-walk to find where block n starts
            val bytes = file.readBytes()
            val r = Reader(bytes)
            r.p = LogFormat.header(LogFormat.Header(0, 0, 0, 0, 0)).size
            repeat(n) {
                r.byte(); val len = r.ui(); r.byte(); r.p += len + 4
            }
            return r.p.toLong()
        }

        /** Blocks [n] of a log (the first n, the ones a checkpoint has seen), all complete ones after. */
        fun completeBlocks(p: LogFormat.Parsed): List<LogFormat.Block> {
            val n = p.blocks.indexOfLast { it.complete } + 1
            return p.blocks.subList(0, n)
        }
    }
}
