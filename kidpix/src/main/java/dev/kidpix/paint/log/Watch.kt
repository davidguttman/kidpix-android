package dev.kidpix.paint.log

import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.SoundPlayer
import kotlin.math.exp

/**
 * The play button: the picture on the canvas drawn again from its beginning (the whole chain of
 * the current picture: KLog.chainNow) to how it is now, stroke by stroke by the engine, with the
 * app's sounds, in about 6-20 s whatever the session's length (idle gaps are skipped, the rest is
 * sped up; a short picture still takes a couple of seconds).
 *
 * Watching changes nothing: the live state is kept in memory (KLog.beginWatch), nothing is
 * written while it plays, and at the end, or when it is stopped, the app is put back exactly as
 * it was (KLog.endWatch), from that copy, never from the replay. A piece of the chain that cannot
 * be replayed faithfully (another versionCode, cut short, damaged, a random-state mismatch) shows
 * its stored picture and the replay goes on from the next piece.
 *
 * [schedule] runs a step after a delay (the app: a Handler), [nowMs] is the wall clock, [sounds]
 * makes the sound player for one piece (the app: audible; null: the silent replay model),
 * [onFrame] asks for a redraw, [onEnd] runs once when the app is back as it was.
 */
class Watch private constructor(
    private val live: KLog.Live,
    private val pieces: List<KLog.WatchPiece>,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val nowMs: () -> Long,
    private val sounds: (() -> SoundPlayer)?,
    private val onFrame: () -> Unit,
    private val onEnd: (Watch) -> Unit,
) {
    /** Engine ms of drawing to show (idle gaps capped at [CAP_MS]) and the wall ms it takes. */
    val engineMs: Long = pieces.sumOf { activeMs(it.recs) }
    val bodyMs: Long = bodyFor(engineMs)
    private val rate: Double = if (engineMs == 0L) 1.0 else engineMs.toDouble() / bodyMs
    /** Wall ms the whole replay is planned to take (beats included). */
    val plannedMs: Long = LEAD_MS + bodyMs + (pieces.size - 1).coerceAtLeast(0) * BEAT_MS + TAIL_MS

    var finished = false
        private set
    /** True when it was stopped by a tap (or the app pausing) before the end. */
    var stopped = false
        private set
    /** Pieces that could only show their picture, and pieces whose replay stopped early. */
    var piecesPictureOnly = pieces.count { it.zero == null || it.recs.isEmpty() }
        private set
    var piecesCut = 0
        private set
    var recordsPlayed = 0
        private set
    val startedAt = nowMs()
    var endedAt = 0L
        private set
    /** Keep the picture the replay ended with in [replayEnd] (tests, the debug log). */
    var keepEnd = true
    var replayEnd: android.graphics.Bitmap? = null
        private set
    /** Longest main-thread step (ms), for the report. */
    var maxStepMs = 0.0
        private set

    private var k = -1
    private var i = 0
    private var e = 0L                // engine cursor
    private var lastActive = 0L       // engine time of the last record that was not a hover
    private var beatLeft = LEAD_MS    // wall ms of a pause still to show
    private var tailLeft = -1L
    private var lastWall = startedAt
    private var player: SoundPlayer? = null
    private var recs: List<Rec> = emptyList()
    /** Engine ms of playing earned and not used yet (fractions carry over between frames). */
    private var budget = 0.0

    private fun startPiece(): Boolean {
        k++
        if (k >= pieces.size) return false
        val p = pieces[k]
        if (k > 0) beatLeft = BEAT_MS
        if (p.zero != null) KLog.applyCheckpoint(p.zero, p.main)
        else {
            // only the stored picture: it appears
            KDisplay.main_context.replaceWith(dev.kidpix.paint.canvas.BitmapSource(p.main))
            KDisplay.clearTmp(); KDisplay.clearPreview(); KDisplay.clearAnim(); KDisplay.clearBnim()
        }
        player = sounds?.invoke() ?: KLog.replaySounds()
        recs = if (p.zero != null) p.recs else emptyList()
        i = 0
        e = KLog.clock.time
        lastActive = e
        return true
    }

    private fun step() {
        if (finished) return
        val t0 = System.nanoTime()
        try { advance(t0) } catch (t: Throwable) {
            FrameStore.logError("watch step failed", t)
            finish(false); return
        }
        val ms = (System.nanoTime() - t0) / 1e6
        if (ms > maxStepMs) maxStepMs = ms
        if (finished) return
        onFrame()
        schedule(FRAME_MS) { step() }
    }

    private fun advance(t0: Long) {
        val now = nowMs()
        var wall = (now - lastWall).coerceIn(0, MAX_STEP_WALL_MS)
        lastWall = now
        while (!finished) {
            if (System.nanoTime() - t0 > STEP_CPU_NS) return // keep the UI responsive; the rest next frame
            if (beatLeft > 0) {
                val use = minOf(wall, beatLeft); beatLeft -= use; wall -= use
                if (beatLeft > 0) return
                continue
            }
            if (tailLeft >= 0) {
                val use = minOf(wall, tailLeft); tailLeft -= use; wall -= use
                if (tailLeft > 0) return
                finish(false); return
            }
            if (wall > 0) { budget = minOf(budget + wall * rate, MAX_STEP_WALL_MS * maxOf(rate, 1.0)); wall = 0 }
            if (i >= recs.size) {
                // the piece's records are done: let animations it started finish, then the next piece
                if (KLog.clock.active > 0 && e - lastActive < TIMERS_MAX_MS && recs.isNotEmpty()) {
                    // (at least as fast as drawn: a slowed-down short picture does not slow its animations)
                    val adv = maxOf(budget.toLong(), (budget / rate).toLong()).coerceAtLeast(0)
                    if (adv == 0L) return
                    e += adv; budget = maxOf(0.0, budget - adv)
                    KLog.replayTo(e, player)
                    continue
                }
                if (!startPiece()) { tailLeft = TAIL_MS; continue }
                continue
            }
            val r = recs[i]
            if (r.time > e) {
                val quiet = KLog.clock.active == 0 && KAndroid.state.activeId == null
                if (quiet && e - lastActive >= CAP_MS) {
                    e = r.time // an idle gap (or hovering): skipped
                } else {
                    val adv = minOf(budget.toLong(), r.time - e).coerceAtLeast(0)
                    if (adv == 0L) return
                    e += adv; budget -= adv
                    KLog.replayTo(e, player)
                    if (e < r.time) return
                }
            }
            var j = i
            while (j < recs.size && recs[j].time <= e) j++
            val why = KLog.replay(recs.subList(i, j), null, player)
            recordsPlayed += j - i
            for (x in i until j) if (!isHover(recs[x])) lastActive = recs[x].time
            i = j
            if (why != null) {
                // no longer what happened: this piece stops here, the next one's picture appears
                KLog.log("watch: piece $k cut: $why")
                piecesCut++
                i = recs.size; recs = emptyList()
            }
        }
    }

    fun describe(): String = "piece $k/${pieces.size} rec $i/${recs.size} e $e lastActive $lastActive clock ${KLog.clock.time} active ${KLog.clock.active} beat $beatLeft tail $tailLeft"

    /** Stops at once: the app is back as it was. */
    fun stop() { if (!finished) finish(true) }

    private fun finish(byStop: Boolean) {
        if (finished) return
        finished = true
        stopped = byStop
        runCatching { player?.stopAll() }
        // what the replay ended with (the report and tests compare it with the live picture)
        if (!byStop && keepEnd) replayEnd = runCatching { KDisplay.main_canvas.bitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, false) }.getOrNull()
        KLog.endWatch(live)
        endedAt = nowMs()
        onEnd(this)
    }

    companion object {
        const val FRAME_MS = 16L
        /** An idle gap shows at most this much engine time (then it is skipped). */
        const val CAP_MS = 600L
        const val LEAD_MS = 400L
        const val BEAT_MS = 300L
        const val TAIL_MS = 600L
        /** Animations after a piece's last record run at most this long. */
        const val TIMERS_MAX_MS = 4000L
        const val MAX_STEP_WALL_MS = 100L
        const val STEP_CPU_NS = 12_000_000L

        /** Wall ms for [engineMs] of drawing: as drawn up to 6 s (at least 1.2 s), then sped up toward 20 s. */
        fun bodyFor(engineMs: Long): Long = when {
            engineMs <= 6000 -> maxOf(engineMs, 1200L)
            else -> (6000 + 14000 * (1 - exp(-(engineMs - 6000) / 60000.0))).toLong()
        }

        fun isHover(r: Rec): Boolean {
            val a = r.action
            return a is Action.Pointer && a.kind == Action.MOVE && a.e.buttons == 0
        }

        /** A record that changes the picture (what makes a replay worth watching). */
        fun draws(r: Rec): Boolean = when (val a = r.action) {
            is Action.Pointer -> a.kind == Action.DOWN
            is Action.Key -> a.down
            is Action.Open -> true
            is Action.Tool -> a.id == "undo"
            else -> false
        }

        /** Engine ms of [recs] as played: gaps between records that are not hovers, each capped. */
        fun activeMs(recs: List<Rec>): Long {
            var sum = 0L
            var prev: Long? = null
            for (r in recs) {
                if (isHover(r)) continue
                prev?.let { sum += minOf(r.time - it, CAP_MS).coerceAtLeast(0) }
                prev = r.time
            }
            return sum
        }

        /** Why [start] did not start. */
        enum class No { BUSY, NOTHING }

        /**
         * Starts the replay of the current picture. Returns the Watch, or why not: [No.BUSY]
         * (something is in progress, or it is already playing) or [No.NOTHING] (nothing was drawn:
         * the app nudges the button). Nothing changes when it does not start.
         */
        fun start(
            schedule: (Long, () -> Unit) -> Unit, nowMs: () -> Long, sounds: (() -> SoundPlayer)?,
            onFrame: () -> Unit, onEnd: (Watch) -> Unit,
        ): Any {
            if (!KLog.recording || KLog.playing || !KLog.quiescent()) return No.BUSY
            val chain = KLog.chainNow()
            val live = KLog.beginWatch() ?: return No.BUSY
            val pieces = try { KLog.watchPieces(chain) } catch (t: Throwable) {
                FrameStore.logError("watch: chain unreadable", t); emptyList()
            }
            if (pieces.none { p -> p.zero != null && p.recs.any { draws(it) } }) {
                KLog.endWatch(live)
                return No.NOTHING
            }
            KLog.showDrags = true
            val w = Watch(live, pieces, schedule, nowMs, sounds, onFrame, onEnd)
            try {
                if (!w.startPiece()) { w.finish(false); return No.NOTHING }
            } catch (t: Throwable) {
                FrameStore.logError("watch: start failed", t); w.finish(false); return No.NOTHING
            }
            onFrame()
            schedule(FRAME_MS) { w.step() }
            return w
        }
    }
}
