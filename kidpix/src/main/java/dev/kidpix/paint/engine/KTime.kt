package dev.kidpix.paint.engine

import java.util.PriorityQueue

/**
 * `Date.now()`, `setTimeout`, `setInterval`, `requestAnimationFrame` for the engine.
 * Headless tests use virtual time ([VirtualClock], advanced by the harness); the app installs a
 * main-Looper backend. Handles are Ints like in the browser; 0 is never a valid handle.
 */
interface KTimeBackend {
    fun now(): Long
    fun schedule(id: Int, delayMs: Long, fn: () -> Unit)
    fun cancel(id: Int)
    /** Next display frame; the callback gets a DOMHighResTimeStamp-like ms value. */
    fun frame(id: Int, fn: (Double) -> Unit)
}

object KTime {
    var backend: KTimeBackend = VirtualClock()
    /** Runs after every timer/frame callback (the app uses it to redraw the PaintView). */
    var afterCallback: () -> Unit = {}
    private var nextId = 1
    private val intervals = HashMap<Int, Boolean>()

    fun now(): Long = backend.now()

    /** performance.now(): ms since the clock's origin. */
    fun performanceNow(): Double = backend.now().toDouble() - origin
    var origin: Long = VirtualClock.T0

    fun setTimeout(fn: () -> Unit, ms: Number = 0): Int {
        val id = nextId++
        backend.schedule(id, ms.toLong().coerceAtLeast(0)) { fn(); afterCallback() }
        return id
    }

    fun clearTimeout(id: Int?) { if (id != null && id != 0) backend.cancel(id) }

    fun setInterval(fn: () -> Unit, ms: Number = 0): Int {
        val id = nextId++
        val delay = ms.toLong().coerceAtLeast(1) // browsers clamp nested timers; never spin at 0
        intervals[id] = true
        fun tick() {
            if (intervals[id] != true) return
            backend.schedule(id, delay) { if (intervals[id] == true) { fn(); afterCallback(); tick() } }
        }
        tick()
        return id
    }

    fun clearInterval(id: Int?) {
        if (id == null || id == 0) return
        intervals.remove(id); backend.cancel(id)
    }

    fun requestAnimationFrame(fn: (Double) -> Unit): Int {
        val id = nextId++
        backend.frame(id) { t -> fn(t); afterCallback() }
        return id
    }

    fun cancelAnimationFrame(id: Int?) { if (id != null && id != 0) backend.cancel(id) }

    /** Drops every pending timer (scenario reset). */
    fun reset() {
        intervals.clear()
        (backend as? VirtualClock)?.clear()
    }
}

/**
 * Deterministic virtual time, the same model as the WebView reference capture
 * (scripts/ref_capture.py): Date.now() = T0 + elapsed, requestAnimationFrame fires 16 ms later,
 * tasks run in (due, insertion) order.
 */
class VirtualClock(var time: Long = T0) : KTimeBackend {
    companion object { const val T0 = 1_700_000_000_000L }
    private class Task(val at: Long, val seq: Long, val id: Int, val fn: () -> Unit)
    private val q = PriorityQueue<Task>(compareBy<Task>({ it.at }, { it.seq }))
    private val cancelled = HashSet<Int>()
    private var seq = 0L

    override fun now() = time

    /** Runs each task (the app: catches and logs a throwing timer, like a JS exception in a timer). */
    var wrap: ((() -> Unit) -> Unit)? = null

    /** Called after a task is queued (the app's pump re-arms its Handler for [nextDue]). */
    var onScheduled: (() -> Unit)? = null

    override fun schedule(id: Int, delayMs: Long, fn: () -> Unit) {
        cancelled.remove(id)
        q.add(Task(time + delayMs, seq++, id, fn))
        onScheduled?.invoke()
    }

    override fun cancel(id: Int) { cancelled.add(id) }

    /** Due time of the earliest task that will run, or null. */
    fun nextDue(): Long? = q.filter { it.id !in cancelled }.minOfOrNull { it.at }

    /** Tasks that will still run (cancelled ones not counted). */
    val active: Int get() = q.count { it.id !in cancelled }

    /** Moves time forward to [t] (never back), running due tasks in order. */
    fun advanceTo(t: Long) { if (t > time) advance(t - time) else runDue() }

    /**
     * Every queued task [d] ms later (the process was frozen or the device slept: the timers
     * carry on where they were instead of catching up in a burst). The action log records it.
     */
    fun shiftTasks(d: Long) {
        if (d <= 0) return
        val all = ArrayList(q); q.clear()
        for (t in all) q.add(Task(t.at + d, t.seq, t.id, t.fn))
        onScheduled?.invoke()
    }

    override fun frame(id: Int, fn: (Double) -> Unit) {
        schedule(id, 16) { fn((time - T0).toDouble()) }
    }

    fun clear() { q.clear(); cancelled.clear() }

    /** Runs everything due at the current time (zero-delay timers, onload callbacks). */
    fun runDue(maxTasks: Int = 100_000) = advance(0, maxTasks)

    /** Moves time forward [ms], running due tasks in order. */
    fun advance(ms: Long, maxTasks: Int = 1_000_000) {
        val end = time + ms
        var n = 0
        while (n < maxTasks) {
            val t = q.peek() ?: break
            if (t.at > end) break
            q.poll()
            if (t.at > time) time = t.at
            if (cancelled.remove(t.id)) continue
            n++
            val w = wrap
            if (w != null) w(t.fn) else t.fn()
        }
        time = end
    }

    val pending get() = q.size
}
