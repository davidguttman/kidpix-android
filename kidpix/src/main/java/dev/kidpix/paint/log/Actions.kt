package dev.kidpix.paint.log

import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.stamp
import dev.kidpix.paint.PEvent
import dev.kidpix.paint.color_next
import dev.kidpix.paint.color_prev
import dev.kidpix.paint.colorSelect
import dev.kidpix.paint.key_down
import dev.kidpix.paint.key_up

/**
 * One entry into the engine: what the app calls (PaintView, PaintActivity) and what a replay
 * calls again. The vocabulary is the scenario format's (tool, sub, letter, color, palette,
 * stroke = pointer down/move/up, hover = moves with nothing down, key), at the level of the
 * overlay's Pointer Events so pen pressure, pointer type, buttons and coalesced samples are
 * replayed too, plus what else changes the engine: the wheel, opening a picture, the app
 * pausing (sounds stop), and a clock shift (the process was frozen).
 */
sealed class Action {
    abstract val code: Int
    /** Starts an action: the record carries the KRandom state (a replay checks it). */
    open val starts: Boolean get() = true
    abstract fun apply()
    /** Leaves nothing in progress: the log block can be closed (flushed) after it. */
    open val ends: Boolean get() = true

    class Pointer(val kind: Int, val e: PEvent) : Action() {
        override val code get() = kind
        override val starts get() = kind != MOVE
        override val ends get() = KAndroid.state.activeId == null
        override fun apply() = when (kind) {
            DOWN -> KAndroid.pointerdown(e)
            MOVE -> KAndroid.pointermove(e)
            UP -> KAndroid.pointerup(e)
            CANCEL -> KAndroid.pointercancel(e)
            else -> KAndroid.pointerleave(e)
        }
    }
    class Wheel(val delta: Double) : Action() { override val code get() = WHEEL; override fun apply() = KAndroid.wheel(delta) }
    class Tool(val id: String, val ctrl: Boolean) : Action() { override val code get() = TOOL; override fun apply() { KAndroid.pressMain(id, ctrl) } }
    class Sub(val index: Int, val contextmenu: Boolean, val ctrl: Boolean) : Action() {
        override val code get() = SUB
        override fun apply() { KAndroid.pressSubmenu(index, contextmenu, ctrl) }
    }
    class Letter(val index: Int) : Action() { override val code get() = LETTER; override fun apply() = KAndroid.pressAlphabet(index) }
    class Color(val index: Int, val which: Int) : Action() { override val code get() = COLOR; override fun apply() = colorSelect(index, which) }
    class Palette(val next: Boolean) : Action() { override val code get() = PALETTE; override fun apply() { if (next) color_next() else color_prev() } }
    class Key(val down: Boolean, val keyCode: Int, val ctrl: Boolean, val key: String) : Action() {
        override val code get() = if (down) KEYDOWN else KEYUP
        override fun apply() { if (down) key_down(keyCode, ctrl, key) else key_up(keyCode) }
    }
    /** A picture opened from the gallery: [name] is its file next to the log. */
    class Open(val name: String) : Action() {
        override val code get() = OPEN
        override fun apply() { KLog.inputPicture(name)?.let { KAndroid.loadPicture(it) } }
    }
    /**
     * The first action after a picture was opened with its history (KLog.openState: checkpoint
     * zero of a new segment holds the picture): the sound opening a picture makes.
     */
    class Opened : Action() { override val code get() = OPENED; override fun apply() { KiddoPaint.Sounds.stamp() } }
    class Pause : Action() { override val code get() = PAUSE; override fun apply() = KSounds.player.stopAll() }
    /** Every pending timer [d] ms later (applied before the clock moves to the record's time). */
    class Shift(val d: Long) : Action() { override val code get() = SHIFT; override val starts get() = false; override fun apply() {} }

    companion object {
        const val DOWN = 1; const val MOVE = 2; const val UP = 3; const val CANCEL = 4; const val LEAVE = 5
        const val WHEEL = 6; const val TOOL = 7; const val SUB = 8; const val LETTER = 9; const val COLOR = 10
        const val PALETTE = 11; const val KEYDOWN = 12; const val KEYUP = 13; const val OPEN = 14; const val PAUSE = 15
        const val SHIFT = 16; const val OPENED = 17
    }
}

/**
 * A decoded record: the action, its engine time, and (for action starts) the KRandom state and,
 * in blocks that carry them, the engine's fingerprint (ActionPrint) before the action ran.
 */
class Rec(val action: Action, val time: Long, val random: Int?, val print: Int? = null)

/** Growable byte buffer with LEB128 varints. */
class Bytes(cap: Int = 256) {
    var a = ByteArray(cap)
    var n = 0
    fun clear() { n = 0 }
    private fun room(k: Int) { if (n + k > a.size) a = a.copyOf(maxOf(a.size * 2, n + k)) }
    fun byte(v: Int) { room(1); a[n++] = v.toByte() }
    fun u(v: Long) {
        room(10)
        var x = v
        while (x and 0x7fL.inv() != 0L) { a[n++] = ((x and 0x7f) or 0x80).toByte(); x = x ushr 7 }
        a[n++] = x.toByte()
    }
    fun u(v: Int) = u(v.toLong() and 0xffffffffL)
    fun s(v: Long) = u((v shl 1) xor (v shr 63))
    fun i32(v: Int) { room(4); for (k in 0 until 4) a[n++] = (v ushr (8 * k)).toByte() }
    fun f64(v: Double) { val b = v.toRawBits(); room(8); for (k in 0 until 8) a[n++] = (b ushr (8 * k)).toByte() }
    fun str(v: String) { val b = v.toByteArray(Charsets.UTF_8); u(b.size); bytes(b, 0, b.size) }
    fun bytes(b: ByteArray, off: Int, len: Int) { room(len); System.arraycopy(b, off, a, n, len); n += len }
    fun toByteArray() = a.copyOf(n)
}

class Reader(val a: ByteArray, var p: Int = 0, val end: Int = a.size) {
    class Bad : RuntimeException("bad record")
    fun more() = p < end
    fun byte(): Int { if (p >= end) throw Bad(); return a[p++].toInt() and 0xff }
    fun u(): Long {
        var r = 0L; var sh = 0
        while (true) {
            val b = byte()
            r = r or ((b and 0x7f).toLong() shl sh)
            if (b and 0x80 == 0) return r
            sh += 7
            if (sh > 63) throw Bad()
        }
    }
    fun ui(): Int = u().toInt()
    fun s(): Long { val v = u(); return (v ushr 1) xor -(v and 1) }
    fun i32(): Int { var v = 0; for (k in 0 until 4) v = v or (byte() shl (8 * k)); return v }
    fun f64(): Double { var b = 0L; for (k in 0 until 8) b = b or (byte().toLong() shl (8 * k)); return Double.fromBits(b) }
    fun str(): String { val n = ui(); if (n < 0 || p + n > end) throw Bad(); val s = String(a, p, n, Charsets.UTF_8); p += n; return s }
}

/**
 * Record coding. Within one log the coder keeps the previous record's time and the previous
 * pointer sample, so a record is mostly small deltas: a pen sample is ~5 bytes. Coordinates and
 * pressure are exact: the app hands the engine values on a 1/16 px and 1/1024 grid (PaintView),
 * stored as integer deltas; any other double is stored raw (8 bytes), so every value replays
 * bit for bit.
 */
class RecordCoder {
    var lastTime = 0L
    private var pid = 0
    private var qx = 0L; private var qy = 0L; private var qp = 0L; private var qt = 0L
    private var buttons = 0
    /** Action starts carry an engine fingerprint (blocks with flag 4). */
    var prints = true

    fun reset(time: Long) { lastTime = time; pid = 0; qx = 0; qy = 0; qp = 0; qt = 0; buttons = 0 }

    // --- quantized doubles: delta of v*scale when that is a whole number, else raw ---
    private fun qPut(o: Bytes, v: Double, scale: Double, prev: Long): Long {
        val q = v * scale
        if (q == Math.floor(q) && Math.abs(q) < 1e15 && (q / scale) == v && !(v == 0.0 && 1.0 / v < 0)) {
            val ql = q.toLong()
            o.u(zz(ql - prev) shl 1)
            return ql
        }
        o.u(1); o.f64(v)
        return prev
    }

    private fun qGet(r: Reader, scale: Double, prev: LongArray, i: Int): Double {
        val v = r.u()
        if (v and 1L == 1L) return r.f64()
        val ql = prev[i] + unzz(v ushr 1)
        prev[i] = ql
        return ql / scale
    }

    private fun zz(v: Long) = (v shl 1) xor (v shr 63)
    private fun unzz(v: Long) = (v ushr 1) xor -(v and 1)

    private fun typeCode(t: String) = when (t) { "mouse" -> 0; "pen" -> 1; "touch" -> 2; else -> 3 }

    private fun putSample(o: Bytes, e: PEvent) {
        val tc = typeCode(e.pointerType)
        var f = tc
        if (e.isPrimary) f = f or 4
        if (e.pointerId == pid) f = f or 8
        if (e.buttons == buttons) f = f or 16
        if (e.button == 0) f = f or 32
        o.byte(f)
        if (e.pointerId != pid) { o.s((e.pointerId - pid).toLong()); pid = e.pointerId }
        if (tc == 3) o.str(e.pointerType)
        if (e.buttons != buttons) { o.u(e.buttons); buttons = e.buttons }
        if (e.button != 0) o.u(e.button)
        qx = qPut(o, e.clientX, XY, qx)
        qy = qPut(o, e.clientY, XY, qy)
        qp = qPut(o, e.pressure, PRESSURE, qp)
        qt = qPut(o, e.timeStamp, 1.0, qt)
    }

    private val st = LongArray(4)
    private fun getSample(r: Reader, coalesced: List<PEvent>? = null): PEvent {
        val f = r.byte()
        if (f and 8 == 0) pid += r.s().toInt()
        val type = when (f and 3) { 0 -> "mouse"; 1 -> "pen"; 2 -> "touch"; else -> r.str() }
        if (f and 16 == 0) buttons = r.ui()
        val button = if (f and 32 == 0) r.ui() else 0
        st[0] = qx; st[1] = qy; st[2] = qp; st[3] = qt
        val x = qGet(r, XY, st, 0); val y = qGet(r, XY, st, 1); val p = qGet(r, PRESSURE, st, 2); val t = qGet(r, 1.0, st, 3)
        qx = st[0]; qy = st[1]; qp = st[2]; qt = st[3]
        return PEvent(pid, type, x, y, p, buttons, f and 4 != 0, button, t, coalesced)
    }

    private fun same(a: PEvent, b: PEvent) = a.pointerId == b.pointerId && a.pointerType == b.pointerType &&
        a.clientX.toRawBits() == b.clientX.toRawBits() && a.clientY.toRawBits() == b.clientY.toRawBits() &&
        a.pressure.toRawBits() == b.pressure.toRawBits() && a.buttons == b.buttons && a.isPrimary == b.isPrimary &&
        a.button == b.button && a.timeStamp.toRawBits() == b.timeStamp.toRawBits()

    fun put(o: Bytes, a: Action, time: Long, random: Int, print: Int = 0) {
        o.byte(a.code)
        o.u(time - lastTime); lastTime = time
        if (a.starts) { o.i32(random); if (prints) o.i32(print) }
        when (a) {
            is Action.Pointer -> {
                val c = a.e.coalescedOrNull
                if (c == null) { o.u(0); putSample(o, a.e) }
                else {
                    o.u(c.size)
                    for (s in c) putSample(o, s)
                    if (c.isNotEmpty() && same(c.last(), a.e)) o.byte(1)
                    else { o.byte(0); putSample(o, a.e) }
                }
            }
            is Action.Wheel -> o.f64(a.delta)
            is Action.Tool -> { o.str(a.id); o.byte(if (a.ctrl) 1 else 0) }
            is Action.Sub -> { o.u(a.index); o.byte((if (a.contextmenu) 1 else 0) or (if (a.ctrl) 2 else 0)) }
            is Action.Letter -> o.u(a.index)
            is Action.Color -> { o.u(a.index); o.u(a.which) }
            is Action.Palette -> o.byte(if (a.next) 1 else 0)
            is Action.Key -> { o.u(a.keyCode); o.byte(if (a.ctrl) 1 else 0); o.str(a.key) }
            is Action.Open -> o.str(a.name)
            is Action.Pause -> {}
            is Action.Opened -> {}
            is Action.Shift -> o.u(a.d)
        }
    }

    fun get(r: Reader): Rec {
        val code = r.byte()
        val time = lastTime + r.u(); lastTime = time
        val starts = code != Action.MOVE && code != Action.SHIFT
        val random = if (starts) r.i32() else null
        val print = if (starts && prints) r.i32() else null
        val a: Action = when (code) {
            Action.DOWN, Action.MOVE, Action.UP, Action.CANCEL, Action.LEAVE -> {
                val n = r.ui()
                if (n == 0) Action.Pointer(code, getSample(r))
                else {
                    val c = ArrayList<PEvent>(n)
                    repeat(n) { c.add(getSample(r)) }
                    val main = if (r.byte() == 1) c.last().let { l -> PEvent(l.pointerId, l.pointerType, l.clientX, l.clientY, l.pressure, l.buttons, l.isPrimary, l.button, l.timeStamp, c) }
                    else getSample(r, c)
                    Action.Pointer(code, main)
                }
            }
            Action.WHEEL -> Action.Wheel(r.f64())
            Action.TOOL -> Action.Tool(r.str(), r.byte() == 1)
            Action.SUB -> { val i = r.ui(); val f = r.byte(); Action.Sub(i, f and 1 != 0, f and 2 != 0) }
            Action.LETTER -> Action.Letter(r.ui())
            Action.COLOR -> Action.Color(r.ui(), r.ui())
            Action.PALETTE -> Action.Palette(r.byte() == 1)
            Action.KEYDOWN, Action.KEYUP -> Action.Key(code == Action.KEYDOWN, r.ui(), r.byte() == 1, r.str())
            Action.OPEN -> Action.Open(r.str())
            Action.PAUSE -> Action.Pause()
            Action.OPENED -> Action.Opened()
            Action.SHIFT -> Action.Shift(r.u())
            else -> throw Reader.Bad()
        }
        return Rec(a, time, random, print)
    }

    companion object {
        /** Grid of clientX/clientY (PaintView rounds to it) and of pressure. */
        const val XY = 16.0
        const val PRESSURE = 1024.0

        fun quantXY(v: Double): Double = Math.round(v * XY) / XY
        fun quantPressure(v: Double): Double = Math.round(v * PRESSURE) / PRESSURE
    }
}
