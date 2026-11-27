package dev.kidpix.paint.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import dev.kidpix.paint.KSoundDurationsMs
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.SoundPlayer
import dev.kidpix.paint.engine.KTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Sound for the app: call once from `PaintActivity.onCreate` (after `KTime.backend` is set):
 *
 * ```kotlin
 * private lateinit var sounds: AutoCloseable
 * sounds = installSounds(this)        // onCreate
 * sounds.close()                      // onDestroy (also done automatically for an Activity)
 * ```
 *
 * Installs a [SoundPoolPlayer] as `KSounds.player`. Files load on a background thread, so the
 * first frame never waits; a sound asked for before its file is decoded is skipped. For an
 * Activity it also follows the lifecycle: every playing sound stops in `onPause` (the WebView
 * app's `webView.onPause()` pauses its media), and the pool is released in `onDestroy`. The volume
 * keys control the media stream, as in the WebView app.
 */
fun installSounds(context: Context): AutoCloseable {
    val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    val backend = SoundPoolBackend(pool, context.applicationContext.assets)
    liveSoundBackend = backend
    val player = SoundPoolPlayer(backend)
    KSounds.player = player
    backend.loadAll(soundLoadOrder())

    val activity = context as? Activity
    var callbacks: Application.ActivityLifecycleCallbacks? = null
    val handle = object : AutoCloseable {
        private var closed = false
        override fun close() {
            if (closed) return
            closed = true
            callbacks?.let { activity?.unregisterActivityLifecycleCallbacks(it) }
            if (KSounds.player === player) KSounds.player = KSounds.silent
            if (liveSoundBackend === backend) liveSoundBackend = null
            player.close()
        }
    }
    if (activity != null) {
        activity.volumeControlStream = AudioManager.STREAM_MUSIC
        callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPaused(a: Activity) { player.stopAll() }
            override fun onActivityDestroyed(a: Activity) { handle.close() }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
        }
        activity.registerActivityLifecycleCallbacks(callbacks)
    }
    return handle
}

private const val MAX_STREAMS = 16

/** The app's sound pool (installSounds), for the play button's replay ([ReplaySoundBackend]). */
var liveSoundBackend: SoundBackend? = null
    private set

/**
 * The play button's replay plays the app's sounds through the live pool, sped up: at most
 * [MAX_BURST] new sounds in [BURST_MS] of wall time (a fast replay would pile them up), and every
 * sound it started stops when the replay ends or is stopped ([stopAll]).
 */
class ReplaySoundBackend(private val b: SoundBackend, private val nowMs: () -> Long = { android.os.SystemClock.uptimeMillis() }) : SoundBackend {
    private val starts = ArrayDeque<Long>()
    private val streams = ArrayList<Int>()
    override fun play(path: String): Int {
        val now = nowMs()
        while (starts.isNotEmpty() && now - starts.first() > BURST_MS) starts.removeFirst()
        if (starts.size >= MAX_BURST) return 0
        val s = b.play(path)
        if (s != 0) { starts.addLast(now); streams.add(s); if (streams.size > 64) streams.removeAt(0) }
        return s
    }
    override fun stop(stream: Int) { b.stop(stream) }
    override fun release() { stopAll() } // the live pool stays
    fun stopAll() { streams.forEach { b.stop(it) }; streams.clear() }
    companion object { const val MAX_BURST = 4; const val BURST_MS = 250L }
}

/** Every file KSounds can name, the clicks every session hears first. */
internal fun soundLoadOrder(): List<String> {
    val first = listOf("mainmenu", "submenuoption", "submenucolor", "pencil", "oops")
    val paths = LinkedHashSet<String>()
    for (s in first) KSounds.Library[s]?.let { paths.addAll(it) }
    for (l in KSounds.Library.values) paths.addAll(l)
    paths.addAll(KSounds.english.values)
    return paths.toList()
}

/** What [SoundPoolPlayer] needs from SoundPool (a fake in the unit tests). */
interface SoundBackend {
    /** Starts [path] once at full volume; returns a stream id, 0 if it is not loaded (yet). */
    fun play(path: String): Int
    fun stop(stream: Int)
    fun release()
}

/**
 * sounds.js on Android. Each library entry (sound, idx) behaves like its HTMLAudioElement:
 * `play()` while it is still playing does nothing (no restart, no overlap with itself); once it
 * has ended, the next play starts it again from the start. Different entries overlap freely, also
 * `line` 0 and 1, which are two elements with the same file. `playKey` is a fresh `new Audio`
 * every time, so letters overlap themselves. Nothing loops and nothing changes the volume
 * upstream, so neither happens here. "Still playing" is the file's length from
 * [KSoundDurationsMs] (SoundPool cannot tell when a stream ends).
 */
class SoundPoolPlayer(
    private val backend: SoundBackend,
    // engine time: what is "still playing" then follows the action log (replays decide the same)
    private val clock: () -> Long = { KTime.now() },
) : SoundPlayer, AutoCloseable {

    private class Element { var stream = 0; var endsAt = Long.MIN_VALUE; var onEndedTimer = 0 }

    private val elements = HashMap<String, Element>()
    private val keyStreams = ArrayList<Pair<Int, Long>>() // playKey streams and their end times
    private var closed = false

    private fun element(sound: String, idx: Int) = elements.getOrPut("$sound#$idx") { Element() }

    /** HTMLMediaElement.play() for entry [idx] of [sound]; returns the element. */
    private fun start(sound: String, idx: Int): Element {
        val el = element(sound, idx)
        if (closed) return el
        val now = clock()
        if (now < el.endsAt) return el // still playing: play() is a no-op
        val path = KSounds.Library[sound]?.getOrNull(idx) ?: return el
        val stream = backend.play(path)
        el.stream = stream
        // a file that is not decoded yet stays silent, but still "plays" for its length, so a
        // pplaySingle waiting on it (the doorbell) carries on instead of hanging
        el.endsAt = now + duration(path)
        return el
    }

    private fun duration(path: String): Long = (KSoundDurationsMs[path] ?: 0).toLong()

    override fun playRand(sound: String, idx: Int) { start(sound, idx) }
    override fun playIdx(sound: String, idx: Int) { start(sound, idx) }
    override fun playSingle(sound: String) { start(sound, 0) }

    /** Entries a pplaySingle waited on: only their "still playing" changes what the engine does. */
    private val awaited = HashSet<Element>()

    override fun pplaySingle(sound: String, onEnded: () -> Unit) {
        val el = start(sound, 0)
        awaited.add(el)
        KTime.clearTimeout(el.onEndedTimer) // `audio.onended = res` replaces the older handler
        val left = (el.endsAt - clock()).coerceAtLeast(0)
        el.onEndedTimer = KTime.setTimeout({ el.onEndedTimer = 0; onEnded() }, left)
    }

    override fun playKey(key: String) {
        if (closed) return
        val path = KSounds.english[key] ?: return
        val now = clock()
        keyStreams.removeAll { it.second <= now }
        val stream = backend.play(path)
        if (stream != 0) keyStreams.add(stream to now + duration(path))
    }

    /**
     * Activity paused: stop everything that is playing. Entries count as ended, so the next press
     * plays from the start. A pending pplaySingle callback still fires at its time (the doorbell
     * animation finishes instead of hanging with a half-erased picture).
     */
    override fun stopAll() {
        val now = clock()
        for (el in elements.values) {
            if (now < el.endsAt && el.stream != 0) backend.stop(el.stream)
            el.stream = 0
            el.endsAt = Long.MIN_VALUE
        }
        for ((s, end) in keyStreams) if (now < end) backend.stop(s)
        keyStreams.clear()
    }

    override fun quietAt(): Long = awaited.maxOfOrNull { it.endsAt } ?: Long.MIN_VALUE

    override fun close() {
        if (closed) return
        stopAll()
        for (el in elements.values) KTime.clearTimeout(el.onEndedTimer)
        closed = true
        backend.release()
    }
}

/** SoundPool with asset files; loading happens on its own thread. */
internal class SoundPoolBackend(private val pool: SoundPool, private val assets: AssetManager) : SoundBackend {
    private val loaded = ConcurrentHashMap<String, Int>()   // path -> sample id, decoded
    private val pending = ConcurrentHashMap<Int, String>()  // sample id -> path asked for
    private val fallbackTried = ConcurrentHashMap.newKeySet<String>()
    private val loader: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "kidpix-sounds").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
    }
    @Volatile private var released = false

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status ->
            val path = pending.remove(sampleId) ?: return@setOnLoadCompleteListener
            if (status == 0) {
                loaded[path] = sampleId
            } else {
                pool.unload(sampleId)
                // too short for the MP3 sniffer (kidpix-submenu-brush-northern: 313 bytes): the
                // original WAV is in the assets too (snd/<name>.wav)
                val wav = wavFallback(path)
                // a load can complete after release() (app closed within a second): loader is shut down then
                if (wav != null && !released && fallbackTried.add(path)) runCatching { loader.execute { load(path, wav) } }
                else Log.w(TAG, "cannot decode $path")
            }
        }
    }

    fun loadAll(paths: List<String>) {
        runCatching { loader.execute { for (p in paths) load(p, p) } }
    }

    /** Loads asset [file] as the sample for [path]. */
    private fun load(path: String, file: String) {
        if (released) return
        try {
            assets.openFd(file).use { fd ->
                val id = pool.load(fd, 1)
                if (id != 0) pending[id] = path
            }
        } catch (t: Exception) {
            Log.w(TAG, "cannot load $file: $t")
        }
    }

    override fun play(path: String): Int {
        if (released) return 0
        val id = loaded[path] ?: return 0
        return pool.play(id, 1f, 1f, 1, 0, 1f)
    }

    override fun stop(stream: Int) { if (!released) pool.stop(stream) }

    override fun release() {
        released = true
        loader.shutdownNow()
        pool.release()
    }

    companion object {
        private const val TAG = "KidPixSounds"
        fun wavFallback(path: String): String? =
            if (path.startsWith("sndmp3/") && path.endsWith(".mp3")) "snd/" + path.removePrefix("sndmp3/").removeSuffix(".mp3") else null
    }
}
