@file:JvmName("sounds_Sounds")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import kotlin.math.floor

// Port of js/sounds/sounds.js: the triggers. Playback is behind SoundPlayer (app/SoundPoolPlayer
// implements it with SoundPool); the default plays nothing, so headless runs and tests are silent.
//
// The checks and the random pick of sounds.js live here, not in the player: playRand draws one
// Math.random() per call whether or not anything can be heard (ref/README.md), so every player,
// the silent one included, sees the same calls and the generator stays in step with the WebView.

/**
 * Plays upstream sounds. Calls arrive already checked (enabled, known name, index in range).
 * In sounds.js every library entry is one HTMLAudioElement, so a player keeps per (sound, idx)
 * the browser's behaviour: play() while that entry is still playing does nothing, entries overlap
 * each other. Implementations must never block the caller.
 */
interface SoundPlayer {
    /** playRand: entry [idx] of library [sound], [idx] already drawn with KRandom. */
    fun playRand(sound: String, idx: Int) {}
    /** playIdx: entry [idx] of library [sound] (multipart start/during/end). */
    fun playIdx(sound: String, idx: Int) {}
    /** playSingle: entry 0 of library [sound]. */
    fun playSingle(sound: String) {}
    /**
     * pplaySingle: entry 0 of [sound]; call [onEnded] on the engine thread (KTime) when it ends,
     * like `audio.onended`. A later pplaySingle of the same entry replaces the pending [onEnded]
     * (sounds.js overwrites `onended`). Silent default: after the file's length in virtual time.
     */
    fun pplaySingle(sound: String, onEnded: () -> Unit) {
        KTime.setTimeout(onEnded, KSounds.durationMs(sound, 0))
    }
    /** playKey: a fresh `new Audio(english[key])` per call (overlaps itself). */
    fun playKey(key: String) {}
    /** The app paused: everything playing stops (the action log records it as an action). */
    fun stopAll() {}
    /**
     * KTime from which no sound is playing whose end decides what the engine does (one a
     * pplaySingle waited on: playing it again then waits only for the rest). The action log takes
     * checkpoints after it, so a restored engine's fresh player decides the same.
     */
    fun quietAt(): Long = Long.MIN_VALUE
}

/** The value of `KiddoPaint.Sounds.Library.pplaySingle(...)`: only `.then` is used upstream. */
class SoundPromise {
    private var done = false
    private var cb: (() -> Unit)? = null
    fun resolve() { if (done) return; done = true; cb?.invoke(); cb = null }
    fun then(f: () -> Unit): SoundPromise { if (done) f() else cb = f; return this }
}

/** KiddoPaint.Sounds.Library (also reachable as `KiddoPaint.Sounds.Library`). */
object KSounds {
    val silent: SoundPlayer = object : SoundPlayer {}
    var player: SoundPlayer = silent
    /** KiddoPaint.Sounds.Library.enabled */
    var enabled = true

    /** KiddoPaint.Sounds.Library: name -> asset paths (sndmp3/...), in upstream order. */
    val Library: Map<String, List<String>> = mapOf(
        "explosion" to listOf("sndmp3/kidpix-tool-eraser-tnt-explosion.wav.mp3"),
        "oops" to listOf("sndmp3/oops0.wav.mp3", "sndmp3/oops1.wav.mp3", "sndmp3/oops2.wav.mp3", "sndmp3/oops3.wav.mp3"),
        "bubblepops" to listOf("sndmp3/bubble-pop-2WAVSOUND.R_0004edd3.wav.mp3", "sndmp3/bubble-pop-3WAVSOUND.R_0004fccd.wav.mp3", "sndmp3/bubble-pop-4WAVSOUND.R_0004f480.wav.mp3", "sndmp3/bubble-pop-WAVSOUND.R_00050452.wav.mp3", "sndmp3/bubble-pop-WAVSOUND.R_000031f6.wav.mp3"),
        "pencil" to listOf("sndmp3/kidpix-tool-pencil.wav.mp3"),
        "stamp" to listOf("sndmp3/stamp0.wav.mp3"),
        "flood" to listOf("sndmp3/flood0.wav.mp3"),
        "mainmenu" to listOf("sndmp3/kidpix-menu-click-main-tools.wav.mp3"),
        "submenucolor" to listOf("sndmp3/kidpix-menu-click-submenu-color.wav.mp3"),
        "submenuoption" to listOf("sndmp3/kidpix-menu-click-submenu-options.wav.mp3"),
        "box" to listOf("sndmp3/kidpix-tool-box-during-approx.wav.mp3"),
        "circle" to listOf("sndmp3/kidpix-tool-circle-during-approx.wav.mp3"),
        "eraserfade" to listOf("sndmp3/eraser-tool-fade-2WAVSOUND.R_0002f58b.wav.mp3"),
        "doordingdong" to listOf("sndmp3/kidpix-eraser-doorbell-ding-dong.wav.mp3"),
        "doorcreak" to listOf("sndmp3/kidpix-eraser-doorbell-door-creak.wav.mp3"),
        "doorwow" to listOf("sndmp3/kidpix-eraser-doorbell-wwoooowwww.wav.mp3"),
        "brushbubbly" to listOf("sndmp3/kidpix-submenu-brush-bubbly.wav.mp3"),
        "brushleakypen" to listOf("sndmp3/kidpix-submenu-brush-leaky-pen.wav.mp3"),
        "brushzigzag" to listOf("sndmp3/kidpix-submenu-brush-zigzag.wav.mp3"),
        "brushdots" to listOf("sndmp3/kidpix-submenu-brush-dots.wav.mp3"),
        "brushpies" to listOf("sndmp3/kidpix-submenu-brush-pies.wav.mp3"),
        "brushecho" to listOf("sndmp3/kidpix-submenu-brush-owl.wav.mp3"),
        "brushnorthern" to listOf("sndmp3/kidpix-submenu-brush-northern.wav.mp3"),
        "brushfuzzer" to listOf("sndmp3/kidpix-submenu-brush-fuzzer.wav.mp3"),
        "brushzoom" to listOf("sndmp3/kidpix-submenu-brush-zoom.wav.mp3"),
        "brushpines" to listOf("sndmp3/kidpix-submenu-brush-pines.wav.mp3"),
        "brushtwirly" to listOf("sndmp3/kidpix-submenu-brush-twirly.wav.mp3"),
        "brushkaliediscope" to listOf("sndmp3/kidpix-submenu-brush-kaliediscope.wav.mp3"),
        "brushrollingdots" to listOf("sndmp3/kidpix-submenu-brush-rollingdots.wav.mp3"),
        "brushinvert" to listOf("sndmp3/kidpix-submenu-brush-inverter.wav.mp3"),
        "brushguil" to listOf("sndmp3/kidpix-submenu-brush-guilloche.wav.mp3"),
        "brushtree" to listOf("sndmp3/kidpix-submenu-brush-tree.wav.mp3"),
        "brushstars" to listOf("sndmp3/kidpix-submenu-brush-stars.wav.mp3"),
        "brushxos" to listOf("sndmp3/kidpix-submenu-brush-xos.wav.mp3"),
        "brushcards" to listOf("sndmp3/kidpix-submenu-brush-cards.wav.mp3"),
        "brushshapes" to listOf("sndmp3/kidpix-submenu-brush-shapes.wav.mp3"),
        "brushprints" to listOf("sndmp3/kidpix-submenu-brush-prints.wav.mp3"),
        "brushspraypaint" to listOf("sndmp3/kidpix-submenu-brush-spraypaint.wav.mp3"),
        "mixerwallpaper" to listOf("sndmp3/electric-mixer-wallpaper-jitter-boingo-WAVSOUND.R_00024fcc.wav.mp3"),
        "mixerinvert" to listOf("sndmp3/electric-mixer-inverter-rolling-sound-WAVSOUND.R_0001fcfa.wav.mp3"),
        "mixervenetian" to listOf("sndmp3/electric-mixer-venetian-WAVSOUND.R_0001df56.wav.mp3"),
        "mixershadowbox" to listOf("sndmp3/electric-mixer-shadow-boxes-WAVSOUND.R_0002a07a.wav.mp3"),
        "mixerpip" to listOf("sndmp3/electric-mixer-pip-drum-crash-1WAVSOUND.R_0002d96e.wav.mp3"),
        "mixerframe" to listOf("sndmp3/western-gun-shot-twirl-WAVSOUND.R_0005ed70.wav.mp3"),
        "unimpl" to listOf("sndmp3/chord.wav.mp3"),
        "line" to listOf("sndmp3/kidpix-tool-line-start.wav.mp3", "sndmp3/kidpix-tool-line-start.wav.mp3", "sndmp3/kidpix-tool-line-end.wav.mp3"),
        "truck" to listOf("sndmp3/kidpix-truck-truckin.wav.mp3", "sndmp3/kidpix-truck-truckin-go.wav.mp3", "sndmp3/kidpix-truck-skid.wav.mp3"),
        "xy" to listOf("sndmp3/kidpix-submenu-brush-xy-start.wav.mp3", "sndmp3/kidpix-submenu-brush-xy-during.wav.mp3", "sndmp3/kidpix-submenu-brush-xy-end.wav.mp3"),
    )

    /** Spoken keys (KiddoPaint.Sounds.Library.english). */
    val english: Map<String, String> = mapOf(
        "A" to "sndmp3/english/alpha-a-WAVSOUND.R_0007d8f2.wav.mp3",
        "B" to "sndmp3/english/alpha-b-WAVSOUND.R_0007ee1f.wav.mp3",
        "C" to "sndmp3/english/alpha-c-WAVSOUND.R_000803fc.wav.mp3",
        "D" to "sndmp3/english/alpha-d-WAVSOUND.R_000815df.wav.mp3",
        "E" to "sndmp3/english/alpha-e-WAVSOUND.R_00082fcc.wav.mp3",
        "F" to "sndmp3/english/alpha-f-WAVSOUND.R_00084629.wav.mp3",
        "G" to "sndmp3/english/alpha-g-WAVSOUND.R_000853d0.wav.mp3",
        "H" to "sndmp3/english/alpha-h-WAVSOUND.R_00086213.wav.mp3",
        "I" to "sndmp3/english/alpha-i-WAVSOUND.R_00087a00.wav.mp3",
        "J" to "sndmp3/english/alpha-j-WAVSOUND.R_00088ced.wav.mp3",
        "K" to "sndmp3/english/alpha-k-WAVSOUND.R_0008a72e.wav.mp3",
        "L" to "sndmp3/english/alpha-l-WAVSOUND.R_0008bda3.wav.mp3",
        "M" to "sndmp3/english/alpha-m-WAVSOUND.R_0008d0f8.wav.mp3",
        "N" to "sndmp3/english/alpha-n-WAVSOUND.R_0008e695.wav.mp3",
        "O" to "sndmp3/english/alpha-o-WAVSOUND.R_0008fcaa.wav.mp3",
        "P" to "sndmp3/english/alpha-p-WAVSOUND.R_00091bdb.wav.mp3",
        "Q" to "sndmp3/english/alpha-q-WAVSOUND.R_00092aee.wav.mp3",
        "R" to "sndmp3/english/alpha-r-WAVSOUND.R_0009639f.wav.mp3",
        "S" to "sndmp3/english/alpha-s-WAVSOUND.R_00097948.wav.mp3",
        "T" to "sndmp3/english/alpha-t-WAVSOUND.R_00099085.wav.mp3",
        "U" to "sndmp3/english/alpha-u-WAVSOUND.R_0009a406.wav.mp3",
        "V" to "sndmp3/english/alpha-v-WAVSOUND.R_0009bbcf.wav.mp3",
        "W" to "sndmp3/english/alpha-w-WAVSOUND.R_0009d8cc.wav.mp3",
        "X" to "sndmp3/english/alpha-x-WAVSOUND.R_0009ff1d.wav.mp3",
        "Y" to "sndmp3/english/alpha-y-WAVSOUND.R_000a177a.wav.mp3",
        "Z" to "sndmp3/english/alpha-z-WAVSOUND.R_000a2fe7.wav.mp3",
        "0" to "sndmp3/english/number-0-WAVSOUND.R_000a7832.wav.mp3",
        "1" to "sndmp3/english/number-1-WAVSOUND.R_000a9f1f.wav.mp3",
        "2" to "sndmp3/english/number-2-WAVSOUND.R_000ab58c.wav.mp3",
        "3" to "sndmp3/english/number-3-WAVSOUND.R_000aca17.wav.mp3",
        "4" to "sndmp3/english/number-4-WAVSOUND.R_000ae7a4.wav.mp3",
        "5" to "sndmp3/english/number-5-WAVSOUND.R_000afbb1.wav.mp3",
        "6" to "sndmp3/english/number-6-WAVSOUND.R_000b205a.wav.mp3",
        "7" to "sndmp3/english/number-7-WAVSOUND.R_000b43e7.wav.mp3",
        "8" to "sndmp3/english/number-8-WAVSOUND.002_000555ac.wav.mp3",
        "9" to "sndmp3/english/number-9-WAVSOUND.R_000b7db1.wav.mp3",
        "&" to "sndmp3/english/number-ampersand-WAVSOUND.R_000be96f.wav.mp3",
        "=" to "sndmp3/english/number-equals-WAVSOUND.R_000bce22.wav.mp3",
        "-" to "sndmp3/english/number-minus-WAVSOUND.R_000bb0e5.wav.mp3",
        "+" to "sndmp3/english/number-plus-WAVSOUND.R_000b9a58.wav.mp3",
        "?" to "sndmp3/english/number-question-mark-WAVSOUND.R_000a661d.wav.mp3",
        "!" to "sndmp3/english/number-eclamation-WAVSOUND.R_000a5774.wav.mp3",
    )


    /** Length of entry [idx] of [sound] in ms (0 when unknown); see sounds/SoundDurations.kt. */
    fun durationMs(sound: String, idx: Int): Long =
        Library[sound]?.getOrNull(idx)?.let { KSoundDurationsMs[it]?.toLong() } ?: 0L

    fun playRand(sound: String) {
        val lib = Library[sound]
        if (enabled && lib != null) {
            val idx = floor(KRandom.random() * lib.size).toInt()
            player.playRand(sound, idx)
        }
    }

    fun playKey(key: String) {
        if (enabled && english[key] != null) player.playKey(key)
    }

    fun playIdx(sound: String, idx: Int) {
        val lib = Library[sound]
        if (enabled && lib != null && idx < lib.size) player.playIdx(sound, idx)
    }

    fun playSingle(sound: String) {
        if (enabled && Library[sound] != null) player.playSingle(sound)
    }

    /** `async function`: resolves when the sound ends, or at once (next tick) when nothing plays. */
    fun pplaySingle(sound: String): SoundPromise {
        val p = SoundPromise()
        if (enabled && Library[sound] != null) player.pplaySingle(sound) { p.resolve() }
        else KTime.setTimeout({ p.resolve() }, 0)
        return p
    }
}

val KiddoPaint.Sounds.Library: KSounds get() = KSounds

fun KiddoPaint.Sounds.playRand(sound: String) = KSounds.playRand(sound)
fun KiddoPaint.Sounds.playIdx(sound: String, idx: Int) = KSounds.playIdx(sound, idx)
fun KiddoPaint.Sounds.playSingle(sound: String) = KSounds.playSingle(sound)
/** KiddoPaint.Sounds.Library.playKey */
fun KiddoPaint.Sounds.playKey(key: String) = KSounds.playKey(key)

fun KiddoPaint.Sounds.explosion() = playRand("explosion")
fun KiddoPaint.Sounds.oops() = playRand("oops")
fun KiddoPaint.Sounds.bubblepops() = playRand("bubblepops")
fun KiddoPaint.Sounds.lineStart() = playIdx("line", 0)
fun KiddoPaint.Sounds.lineDuring() = playIdx("line", 1)
fun KiddoPaint.Sounds.lineEnd() = playIdx("line", 2)
fun KiddoPaint.Sounds.truckStart() = playIdx("truck", 0)
fun KiddoPaint.Sounds.truckDuring() = playIdx("truck", 1)
fun KiddoPaint.Sounds.truckEnd() = playIdx("truck", 2)
fun KiddoPaint.Sounds.xyStart() = playIdx("xy", 0)
fun KiddoPaint.Sounds.xyDuring() = playIdx("xy", 1)
fun KiddoPaint.Sounds.xyEnd() = playIdx("xy", 2)
fun KiddoPaint.Sounds.pencil() = playSingle("pencil")
fun KiddoPaint.Sounds.box() = playSingle("box")
fun KiddoPaint.Sounds.circle() = playSingle("circle")
fun KiddoPaint.Sounds.stamp() = playSingle("stamp")
fun KiddoPaint.Sounds.flood() = playSingle("flood")
fun KiddoPaint.Sounds.mainmenu() = playSingle("mainmenu")
fun KiddoPaint.Sounds.submenucolor() = playSingle("submenucolor")
fun KiddoPaint.Sounds.submenuoption() = playSingle("submenuoption")
fun KiddoPaint.Sounds.unimpl() = playSingle("unimpl")
fun KiddoPaint.Sounds.brushzigzag() = playSingle("brushzigzag")
fun KiddoPaint.Sounds.brushleakypen() = playSingle("brushleakypen")
fun KiddoPaint.Sounds.brushbubbly() = playSingle("brushbubbly")
fun KiddoPaint.Sounds.brushdots() = playSingle("brushdots")
fun KiddoPaint.Sounds.brushpies() = playSingle("brushpies")
fun KiddoPaint.Sounds.brushecho() = playSingle("brushecho")
fun KiddoPaint.Sounds.brushnorthern() = playSingle("brushnorthern")
fun KiddoPaint.Sounds.brushfuzzer() = playSingle("brushfuzzer")
fun KiddoPaint.Sounds.brushzoom() = playSingle("brushzoom")
fun KiddoPaint.Sounds.brushpines() = playSingle("brushpines")
fun KiddoPaint.Sounds.brushtwirly() = playSingle("brushtwirly")
fun KiddoPaint.Sounds.brushkaliediscope() = playSingle("brushkaliediscope")
fun KiddoPaint.Sounds.brushrollingdots() = playSingle("brushrollingdots")
fun KiddoPaint.Sounds.brushinvert() = playSingle("brushinvert")
fun KiddoPaint.Sounds.brushguil() = playSingle("brushguil")
fun KiddoPaint.Sounds.brushtree() = playSingle("brushtree")
fun KiddoPaint.Sounds.brushstars() = playSingle("brushstars")
fun KiddoPaint.Sounds.brushxos() = playSingle("brushxos")
fun KiddoPaint.Sounds.brushcards() = playSingle("brushcards")
fun KiddoPaint.Sounds.brushshapes() = playSingle("brushshapes")
fun KiddoPaint.Sounds.brushprints() = playSingle("brushprints")
fun KiddoPaint.Sounds.brushspraypaint() = playSingle("brushspraypaint")
fun KiddoPaint.Sounds.mixerwallpaper() = playSingle("mixerwallpaper")
fun KiddoPaint.Sounds.mixerinvert() = playSingle("mixerinvert")
fun KiddoPaint.Sounds.mixervenetian() = playSingle("mixervenetian")
fun KiddoPaint.Sounds.mixershadowbox() = playSingle("mixershadowbox")
fun KiddoPaint.Sounds.mixerpip() = playSingle("mixerpip")
fun KiddoPaint.Sounds.mixerframe() = playSingle("mixerframe")
fun KiddoPaint.Sounds.eraserfadea() = playSingle("eraserfade")
fun KiddoPaint.Sounds.eraserfadeb() = playSingle("mixerpip")
