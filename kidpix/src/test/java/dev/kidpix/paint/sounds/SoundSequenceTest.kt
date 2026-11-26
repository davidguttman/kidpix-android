package dev.kidpix.paint

import android.app.Activity
import dev.kidpix.paint.app.installSounds
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.harness.ScenarioRunner

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Scripted tool use against a recording [SoundPlayer]. Every expected sequence is DERIVED BY
 * READING THE JS (upstream js/init/kiddopaint.js, js/init/submenus.js, js/tools/pixelpencil.js,
 * js/sounds/sounds.js), not captured from the WebView: the reference capture records pictures,
 * not sounds.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SoundSequenceTest {
    class Recorder : SoundPlayer {
        val calls = ArrayList<String>()
        override fun playRand(sound: String, idx: Int) { calls.add("rand $sound $idx") }
        override fun playIdx(sound: String, idx: Int) { calls.add("idx $sound $idx") }
        override fun playSingle(sound: String) { calls.add(sound) }
        override fun pplaySingle(sound: String, onEnded: () -> Unit) {
            calls.add("pplay $sound"); super.pplaySingle(sound, onEnded)
        }
        override fun playKey(key: String) { calls.add("key $key") }
    }

    private val rec = Recorder()
    private lateinit var tmp: File

    @Before fun setUp() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KSounds.player = rec
        KSounds.enabled = true
        tmp = Files.createTempDirectory("kp-sounds").toFile()
    }

    @After fun tearDown() {
        KSounds.player = KSounds.silent
        KSounds.enabled = true
        tmp.deleteRecursively()
    }

    /** Runs one scenario (docs/SCENARIOS.md format) and returns the sound calls it made. */
    private fun run(steps: String, seed: Int = 1234): List<String> {
        val f = File(tmp, "s.json")
        f.writeText("""{"canvas":{"w":400,"h":300},"seed":$seed,"scenarios":[{"id":"s","steps":[$steps]}]}""")
        rec.calls.clear()
        val r = ScenarioRunner(File(tmp, "out")) {}.runFile(f)
        assertTrue(r.single().error ?: "", r.single().ok)
        return rec.calls.toList()
    }

    @Test fun bootIsSilent() {
        // init_kiddo_paint / init_kiddo_defaults / show_generic_submenu play nothing
        assertEquals(emptyList<String>(), run(""))
    }

    @Test fun pencilSession() {
        val calls = run("""
            {"op":"tool","id":"pencil"},
            {"op":"sub","index":2},
            {"op":"color","index":5},
            {"op":"stroke","points":[[100,100],[160,140],[300,220]]},
            {"op":"palette","dir":"next"},
            {"op":"palette","dir":"prev"}
        """)
        // #pencil mousedown: mainmenu; submenu button: submenuoption (init/submenus.js:68), the
        // pencil size handler is silent; color button: submenucolor; pixelpencil mousemove while
        // down: pencil, once per move, and mouseup calls mousemove once more (pixelpencil.js:34);
        // #colornext/#colorprev: submenucolor
        assertEquals(listOf("mainmenu", "submenuoption", "submenucolor", "pencil", "pencil", "pencil", "submenucolor", "submenucolor"), calls)
    }

    @Test fun hoverIsSilent() {
        assertEquals(emptyList<String>(), run("""{"op":"hover","points":[[10,10],[50,50]]}"""))
    }

    @Test fun undoDrawsOneRandomNumberForOops() {
        // The Undo Guy (overlay/android.js) says oops only when there was something to undo, after
        // the double-tap window: draw first (the pencil draws no random number), then undo.
        val calls = run("""{"op":"stroke","points":[[100,100],[200,200]]},{"op":"tool","id":"undo"},{"op":"wait","ms":500}""", seed = 1234)
        // mainmenu, oops (playRand: floor(Math.random() * 4)); mulberry32(1234) first = 0.0733
        assertEquals(listOf("mainmenu", "rand oops 0"), calls.filter { it != "pencil" })
        assertEquals(0.7034119898453355, KRandom.random(), 0.0)   // exactly one number was drawn
    }

    @Test fun playRandConsumesRandomEvenWhenSilent() {
        KSounds.player = KSounds.silent
        KRandom.seed(1234)
        KiddoPaint.Sounds.oops()
        assertEquals(0.7034119898453355, KRandom.random(), 0.0)
        KRandom.seed(1234)
        KiddoPaint.Sounds.bubblepops()
        KiddoPaint.Sounds.explosion()
        assertEquals(0.9028560190927237, KRandom.random(), 0.0)
    }

    @Test fun randomPicksMatchMathFloor() {
        KRandom.seed(1234)  // 0.0733, 0.7034, 0.9029
        KiddoPaint.Sounds.oops(); KiddoPaint.Sounds.bubblepops(); KiddoPaint.Sounds.explosion()
        assertEquals(listOf("rand oops 0", "rand bubblepops 3", "rand explosion 0"), rec.calls)
    }

    @Test fun disabledPlaysNothingAndDrawsNothing() {
        KSounds.enabled = false
        KRandom.seed(1234)
        KiddoPaint.Sounds.oops(); KiddoPaint.Sounds.pencil(); KiddoPaint.Sounds.lineStart(); KiddoPaint.Sounds.playKey("A")
        assertEquals(emptyList<String>(), rec.calls)
        assertEquals(0.07329497812315822, KRandom.random(), 0.0)
    }

    @Test fun everyTriggerNamesTheJsEntry() {
        val S = KiddoPaint.Sounds
        S.lineStart(); S.lineDuring(); S.lineEnd()
        S.truckStart(); S.truckDuring(); S.truckEnd()
        S.xyStart(); S.xyDuring(); S.xyEnd()
        S.eraserfadea(); S.eraserfadeb()
        S.brushecho(); S.brushinvert(); S.brushguil(); S.mixerframe(); S.unimpl()
        S.playIdx("line", 3)              // idx >= length: nothing
        S.playSingle("nosuch"); S.playRand("nosuch")
        S.playKey("A"); S.playKey("a")    // english has upper case only
        assertEquals(listOf("idx line 0", "idx line 1", "idx line 2", "idx truck 0", "idx truck 1", "idx truck 2",
            "idx xy 0", "idx xy 1", "idx xy 2", "eraserfade", "mixerpip",
            "brushecho", "brushinvert", "brushguil", "mixerframe", "unimpl", "key A"), rec.calls)
    }

    @Test fun pplaySingleResolvesWhenTheSoundEnds() {
        val clock = VirtualClock(); KTime.backend = clock
        var t = -1L
        KiddoPaint.Sounds.Library.pplaySingle("doordingdong").then { t = clock.time - VirtualClock.T0 }
        assertEquals(listOf("pplay doordingdong"), rec.calls)
        clock.advance(887); assertEquals(-1L, t)
        clock.advance(1); assertEquals(888L, t)   // ffprobe: 0.888 s
        // disabled: the async function resolves at once
        KSounds.enabled = false
        var r = false
        KiddoPaint.Sounds.Library.pplaySingle("doordingdong").then { r = true }
        clock.runDue(); assertTrue(r)
    }

    @Test fun installSoundsFollowsTheActivity() {
        // Robolectric shadows SoundPool: nothing is decoded or played on the host
        val ctl = Robolectric.buildActivity(Activity::class.java).setup()
        val handle = installSounds(ctl.get())
        assertTrue(KSounds.player is dev.kidpix.paint.app.SoundPoolPlayer)
        KiddoPaint.Sounds.mainmenu()
        ctl.pause().resume()
        ctl.pause().stop().destroy()
        assertSame(KSounds.silent, KSounds.player)
        handle.close() // idempotent
    }
}
