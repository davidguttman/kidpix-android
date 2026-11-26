package dev.kidpix.paint.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KUi
import dev.kidpix.paint.log.KLog
import java.io.File

/**
 * Debug hooks for the action log, for `adb shell am broadcast` (the receiver needs the DUMP
 * permission, which the shell has and apps do not). Results go to logcat, tag KidPixLog.
 *
 *     am broadcast -a dev.kidpix.LOG_EXPORT -n <pkg>/dev.kidpix.paint.app.LogDebugReceiver
 *         copies the current segment to <external files>/kplog-export:
 *         adb pull /sdcard/Android/data/<pkg>/files/kplog-export, then
 *         ./gradlew :kidpix:replayLog -Plog=kplog-export -Pout=replay.png
 *     am broadcast -a dev.kidpix.LOG_VERIFY ...   replays the current segment from its first
 *         checkpoint in this process and compares the picture with the one before
 *     am broadcast -a dev.kidpix.LOG_STATS ...    log size, frames, per-record cost
 */
class LogDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (KLog.root == null || !KDisplay.isReady) { Log.i(TAG, "${intent.action}: the log is not running"); return }
        when (intent.action) {
            "dev.kidpix.LOG_EXPORT" -> {
                val dest = File(context.getExternalFilesDir(null), "kplog-export")
                val pending = goAsync()
                KLog.export(dest) { ok -> Log.i(TAG, "export ${if (ok) "done" else "FAILED"}: $dest"); pending.finish() }
            }
            "dev.kidpix.LOG_VERIFY" -> {
                val before = KDisplay.main_canvas.snapshot()
                fun ctxs() = listOf("main" to KDisplay.main_context, "bnim" to KDisplay.bnimContext, "anim" to KDisplay.animContext,
                    "preview" to KDisplay.previewContext, "tmp" to KDisplay.context).map { (n, c) -> "$n: ${c.debugState()}" }
                val liveCtx = ctxs()
                val t0 = System.nanoTime()
                KLog.playSegment(Double.POSITIVE_INFINITY, { ms, f -> Handler(Looper.getMainLooper()).postDelayed(f, ms) }) { ok ->
                    val ms = (System.nanoTime() - t0) / 1e6
                    val after = KDisplay.main_canvas.bitmap
                    var diff = 0
                    if (after.width == before.width && after.height == before.height) {
                        val a = IntArray(before.width * before.height); val b = IntArray(a.size)
                        before.copyPixelsToBuffer(java.nio.IntBuffer.wrap(a)); after.copyPixelsToBuffer(java.nio.IntBuffer.wrap(b))
                        for (i in a.indices) if (a[i] != b[i]) diff++
                    } else diff = -1
                    if (diff != 0) runCatching { // both pictures for a look: <external files>/verify-{live,replay}.png
                        val dir = context.getExternalFilesDir(null)
                        File(dir, "verify-live.png").outputStream().use { before.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        File(dir, "verify-replay.png").outputStream().use { after.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    val replayCtx = ctxs()
                    for (i in liveCtx.indices) if (liveCtx[i] != replayCtx[i]) Log.i(TAG, "verify ctx live    ${liveCtx[i]}\nverify ctx replay  ${replayCtx[i]}")
                    Log.i(TAG, "verify: replay ${if (ok) "complete" else "STOPPED"}, ${if (diff == 0) "identical" else "$diff pixels differ"}, %.0f ms; ${KLog.describe()}".format(ms))
                    KUi.ui.invalidate()
                }
            }
            "dev.kidpix.LOG_STATS" -> Log.i(TAG, "stats: ${KLog.describe()}; records ${KLog.records}, logging %.4f ms/record; restore: ${KLog.lastRestore}"
                .format(if (KLog.records > 0) KLog.recordNs / 1e6 / KLog.records else 0.0))
        }
    }

    companion object { const val TAG = "KidPixLog" }
}
