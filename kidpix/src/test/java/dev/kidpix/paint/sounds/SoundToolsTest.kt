package dev.kidpix.paint

import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.harness.ScenarioRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files

/**
 * Sound calls of every tool in the parity-audit groups (line, square, circle, eraser, flood,
 * truck, alphabet, stamp, jumble), scripted like [SoundSequenceTest]. Every expected sequence is
 * DERIVED BY READING THE JS (upstream js/tools/<tool>.js, js/submenus/<tool>.js,
 * js/init/kiddopaint.js, js/init/submenus.js, js/sounds/sounds.js), never captured: no picture
 * shows a sound. Where a tool draws random numbers, the test also counts them, because a missing
 * or extra draw shifts every later random number (and so the pictures).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SoundToolsTest {
    private val rec = SoundSequenceTest.Recorder()
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

    private fun run(steps: String, settle: Boolean = true, seed: Int = 1234): List<String> {
        val f = File(tmp, "s.json")
        f.writeText("""{"canvas":{"w":400,"h":300},"seed":$seed,"scenarios":[{"id":"s","settle":$settle,"steps":[$steps]}]}""")
        rec.calls.clear()
        val r = ScenarioRunner(File(tmp, "out")) {}.runFile(f)
        assertTrue(r.single().error ?: "", r.single().ok)
        return rec.calls.toList()
    }

    /** How many numbers the scenario drew from the seeded generator (searches up to [max]). */
    private fun drawsSoFar(seed: Int = 1234, max: Int = 2000): Int {
        val next = KRandom.random()
        KRandom.seed(seed)
        for (n in 0..max) if (KRandom.random() == next) return n
        error("more than $max draws")
    }

    private val S3 = """{"op":"stroke","points":[[100,100],[150,130],[200,160]]}"""
    private val HOVER = """{"op":"hover","points":[[50,50],[60,60]]}"""

    @Test fun line() {
        // line.js: mousedown lineStart; mousemove while down lineDuring; mouseup calls mousemove
        // (lineDuring) then lineEnd. The size option is silent apart from submenuoption.
        assertEquals(listOf("mainmenu", "submenuoption", "idx line 0", "idx line 1", "idx line 1", "idx line 1", "idx line 2"),
            run("""{"op":"tool","id":"line"},{"op":"sub","index":2},$S3,$HOVER"""))
    }

    @Test fun square() {
        // square.js: box() on every mousemove while startEv is set; mouseup sets isDown false,
        // calls mousemove (box again), then clears startEv, so the hover after is silent.
        assertEquals(listOf("mainmenu", "submenuoption", "box", "box", "box"),
            run("""{"op":"tool","id":"square"},{"op":"sub","index":4},$S3,$HOVER"""))
    }

    @Test fun circle() {
        // circle.js: circle() on mousemove while down; mouseup calls mousemove before isDown=false.
        assertEquals(listOf("mainmenu", "submenuoption", "circle", "circle", "circle"),
            run("""{"op":"tool","id":"circle"},{"op":"sub","index":4},$S3,$HOVER"""))
    }

    @Test fun truck() {
        // cut.js: mousedown truckStart then mousemove(ev) (truckDuring, isDown); 2 moves; mouseup
        // truckEnd (no extra mousemove). Hover shows the dashed box: silent.
        assertEquals(listOf("mainmenu", "submenuoption", "idx truck 0", "idx truck 1", "idx truck 1", "idx truck 1", "idx truck 2"),
            run("""{"op":"tool","id":"truck"},{"op":"sub","index":1},$S3,$HOVER"""))
    }

    @Test fun truckOption13IsUnimpl() {
        assertEquals(listOf("mainmenu", "submenuoption", "unimpl"), run("""{"op":"tool","id":"truck"},{"op":"sub","index":13}"""))
    }

    @Test fun flood() {
        // flood.js: boundedFill plays flood once per mousedown; mousemove/mouseup are empty.
        assertEquals(listOf("mainmenu", "submenuoption", "flood", "flood"),
            run("""{"op":"tool","id":"flood"},{"op":"sub","index":3},{"op":"tap","points":[[100,100]]},
                {"op":"stroke","points":[[300,200],[320,220]]},$HOVER"""))
    }

    @Test fun alphabet() {
        // kiddopaint.js: #alphabet mousedown mainmenu; a letter's mousedown playKey(letter);
        // #alnext submenuoption. stamp.js: mousedown stamp(); hover draws the preview, silent.
        val calls = run("""{"op":"tool","id":"alphabet"},{"op":"letter","index":3},{"op":"tap","points":[[100,100]]},$HOVER,
            {"op":"letter","index":26},{"op":"letter","index":0},{"op":"tap","points":[[200,100]]}""")
        assertEquals("mainmenu", calls[0])
        assertEquals("key D", calls[1])
        assertEquals(listOf("stamp", "submenuoption"), calls.subList(2, 4))
        assertTrue(calls[4], calls[4].startsWith("key "))
        assertEquals("stamp", calls[5])
        assertEquals(6, calls.size)
    }

    @Test fun stampSprite() {
        // sprites.js: the sprite handler (submenuoption from init/submenus.js) sets soundBefore =
        // stamp once the sheet loads; spriteplacer.js mousedown: mousemove (soundDuring, empty)
        // then soundBefore. Page/pack arrows: submenuoption only.
        assertEquals(listOf("mainmenu", "submenuoption", "stamp", "stamp", "submenuoption", "submenuoption", "submenuoption", "stamp"),
            run("""{"op":"tool","id":"stamp"},{"op":"sub","index":5},{"op":"tap","points":[[100,100]]},
                {"op":"stroke","points":[[200,100],[220,120],[240,140]]},$HOVER,
                {"op":"sub","index":14},{"op":"sub","index":15},{"op":"sub","index":2},{"op":"tap","points":[[300,100]]}"""))
    }

    @Test fun plainErasersAreSilent() {
        for (i in 0..3) assertEquals("eraser option $i", listOf("mainmenu", "submenuoption"),
            run("""{"op":"tool","id":"eraser"},{"op":"sub","index":$i},$S3,$HOVER"""))
    }

    @Test fun firecrackerDrawsOneNumber() {
        // tnt.js animate: one explosion() (playRand: one draw), nothing random in drawBomb
        val calls = run("""{"op":"tool","id":"eraser"},{"op":"sub","index":4},{"op":"tap","points":[[200,150]]}""")
        assertEquals(listOf("mainmenu", "submenuoption", "rand explosion 0"), calls)
        assertEquals(1, drawsSoFar())
    }

    @Test fun whiteCirclesDrawThreeNumbersPerMove() {
        // eraser-white-circles.js: bubblepops() (1 draw) + getRandomFloat x2 per mousemove while
        // down; mousedown and mouseup each call mousemove: 1 + 2 + 1 = 4 moves, 12 draws.
        val calls = run("""{"op":"tool","id":"eraser"},{"op":"sub","index":6},$S3""")
        assertEquals(listOf("mainmenu", "submenuoption"), calls.take(2))
        assertEquals(4, calls.drop(2).count { it.startsWith("rand bubblepops ") })
        assertEquals(6, calls.size)
        assertEquals(12, drawsSoFar())
    }

    @Test fun lettersEraserTicksEvery10ms() {
        // eraser-letters.js: toolDraw on mousedown and then every 10 ms while held; each toolDraw:
        // mixershadowbox + rx, ry, rs, letter, 2 x randomAllColor, Math.random = 7 draws
        val calls = run("""{"op":"tool","id":"eraser"},{"op":"sub","index":8},
            {"op":"stroke","points":[[200,150]],"up":false},{"op":"wait","ms":35}""", settle = false)
        assertEquals(listOf("mainmenu", "submenuoption") + List(4) { "mixershadowbox" }, calls)
        assertEquals(28, drawsSoFar())
    }

    @Test fun fadeAway() {
        // eraser-fade-away.js: eraserfadeb (mixerpip) at 500, eraserfadea (eraserfade) at 1200,
        // eraserfadeb at 1900, clearAll at 2000
        assertEquals(listOf("mainmenu", "submenuoption", "mixerpip", "eraserfade", "mixerpip"),
            run("""{"op":"tool","id":"eraser"},{"op":"sub","index":9},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun slipSlidingAway() {
        // eraser-doorbell.js: pplaySingle(doordingdong).then(doorcreak + 107 frames) then doorwow
        assertEquals(listOf("mainmenu", "submenuoption", "doordingdong", "doorcreak", "doorwow"),
            run("""{"op":"tool","id":"eraser"},{"op":"sub","index":7},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun hiddenPicturesSilent() {
        assertEquals(listOf("mainmenu", "submenuoption"),
            run("""{"op":"tool","id":"eraser"},{"op":"sub","index":5},$S3"""))
    }

    @Test fun blackHoleAndCountDownAreUnimpl() {
        for (i in 10..11) assertEquals(listOf("mainmenu", "submenuoption", "unimpl"),
            run("""{"op":"tool","id":"eraser"},{"op":"sub","index":$i},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleUnimplOptions() {
        for (i in listOf(5, 7, 8, 10, 12)) assertEquals("option $i", listOf("mainmenu", "submenuoption", "unimpl"),
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":$i},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleButtonAloneIsWholeInvert() {
        // #jumble: mainmenu, tool = WholeCanvasEffect(INVERT); wholefx.js plays nothing
        assertEquals(listOf("mainmenu"), run("""{"op":"tool","id":"jumble"},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleInvert() {
        // wacky-mixer-inverter.js: mixerinvert once per mousedown (animate)
        assertEquals(listOf("mainmenu", "submenuoption", "mixerinvert"),
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":0},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleCheckerboardSilent() {
        assertEquals(listOf("mainmenu", "submenuoption"),
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":2},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleTimedMixers() {
        // wallpaper (animInterval 50) / venetian blinds (100): toolDraw on mousedown, then every
        // interval while held, each toolDraw plays its sound: 250 ms held = 6 and 3 sounds
        for ((i, s, n) in listOf(Triple(3, "mixerwallpaper", 6), Triple(4, "mixervenetian", 3))) assertEquals("option $i",
            listOf("mainmenu", "submenuoption") + List(n) { s },
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":$i},
                {"op":"stroke","points":[[200,150]],"up":false},{"op":"wait","ms":250}""", settle = false))
    }

    @Test fun jumbleShadowBoxes() {
        // wacky-mixer-shadow-boxes.js: same shape, sound inside toolDraw
        val calls = run("""{"op":"tool","id":"jumble"},{"op":"sub","index":6},
            {"op":"stroke","points":[[200,150]],"up":false},{"op":"wait","ms":250}""", settle = false)
        assertEquals(listOf("mainmenu", "submenuoption"), calls.take(2))
        assertTrue(calls.toString(), calls.drop(2).isNotEmpty() && calls.drop(2).all { it == "mixershadowbox" })
    }

    @Test fun jumblePictureInPicture() {
        // wacky-mixer-pip.js: mixerpip on mousedown, mixershadowbox on mouseup; the Placer it
        // switches to is silent (placer.js has no sound)
        assertEquals(listOf("mainmenu", "submenuoption", "mixerpip", "mixershadowbox"),
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":9},{"op":"tap","points":[[200,150]]},
                {"op":"tap","points":[[100,100]]}"""))
    }

    @Test fun jumblePatternMaker() {
        assertEquals(listOf("mainmenu", "submenuoption", "mixerframe"),
            run("""{"op":"tool","id":"jumble"},{"op":"sub","index":11},{"op":"tap","points":[[200,150]]}"""))
    }

    @Test fun jumbleRaindropsOneBubblepopPerTexture() {
        // submenus/jumble.js Raindrops: AnimBrush texture() plays bubblepops (one draw) each call
        val calls = run("""{"op":"tool","id":"jumble"},{"op":"sub","index":1},$S3""")
        assertEquals(listOf("mainmenu", "submenuoption"), calls.take(2))
        assertTrue(calls.toString(), calls.drop(2).isNotEmpty() && calls.drop(2).all { it.startsWith("rand bubblepops ") })
    }
}
