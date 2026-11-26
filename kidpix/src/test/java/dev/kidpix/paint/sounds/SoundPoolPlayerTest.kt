package dev.kidpix.paint

import dev.kidpix.paint.app.SoundBackend
import dev.kidpix.paint.app.SoundPoolPlayer
import dev.kidpix.paint.app.SoundPoolBackend
import dev.kidpix.paint.app.soundLoadOrder
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** HTMLAudioElement behaviour of [SoundPoolPlayer] against a fake SoundPool (nothing plays). */
class SoundPoolPlayerTest {
    private class FakeBackend : SoundBackend {
        val played = ArrayList<String>()
        val stopped = ArrayList<Int>()
        var released = false
        private var next = 1
        override fun play(path: String): Int { played.add(path); return next++ }
        override fun stop(stream: Int) { stopped.add(stream) }
        override fun release() { released = true }
    }

    private lateinit var clock: VirtualClock
    private lateinit var backend: FakeBackend
    private lateinit var player: SoundPoolPlayer

    private fun path(sound: String, idx: Int = 0) = KSounds.Library[sound]!![idx]
    private fun dur(sound: String, idx: Int = 0) = KSoundDurationsMs[path(sound, idx)]!!.toLong()

    @Before fun setUp() {
        clock = VirtualClock()
        KTime.backend = clock
        backend = FakeBackend()
        player = SoundPoolPlayer(backend) { clock.time }
    }

    @After fun tearDown() { KTime.backend = VirtualClock() }

    @Test fun sameEntryDoesNotRestartWhilePlaying() {
        val d = dur("pencil")
        player.playSingle("pencil")
        clock.advance(d - 1)
        player.playSingle("pencil")              // still playing: no-op
        assertEquals(listOf(path("pencil")), backend.played)
        clock.advance(1)
        player.playSingle("pencil")              // ended: plays again from the start
        assertEquals(2, backend.played.size)
    }

    @Test fun differentEntriesOverlap() {
        player.playSingle("pencil")
        player.playSingle("mainmenu")
        player.playIdx("line", 0)
        player.playIdx("line", 1)                // a second element with the same file
        player.playIdx("line", 1)                // ... which is now playing
        assertEquals(listOf(path("pencil"), path("mainmenu"), path("line", 0), path("line", 1)), backend.played)
    }

    @Test fun randEntriesAreSeparateElements() {
        player.playRand("oops", 2)
        player.playRand("oops", 2)
        player.playRand("oops", 3)
        assertEquals(listOf(path("oops", 2), path("oops", 3)), backend.played)
    }

    @Test fun keysOverlapThemselves() {
        player.playKey("A"); player.playKey("A")
        assertEquals(2, backend.played.size)
        assertTrue(backend.played.all { it == KSounds.english["A"] })
    }

    @Test fun pplaySingleEndsAfterTheFileAndOnlyTheLastHandlerFires() {
        val ends = ArrayList<String>()
        player.pplaySingle("doordingdong") { ends.add("first") }
        clock.advance(100)
        player.pplaySingle("doordingdong") { ends.add("second") }  // replaces onended, no restart
        assertEquals(1, backend.played.size)
        clock.advance(dur("doordingdong") - 101)
        assertEquals(emptyList<String>(), ends)
        clock.advance(1)
        assertEquals(listOf("second"), ends)
    }

    @Test fun stopAllStopsWhatPlaysAndResets() {
        player.playSingle("pencil")              // stream 1
        player.playKey("B")                      // stream 2
        clock.advance(dur("pencil") + 1)
        player.playSingle("flood")               // stream 3, pencil has ended
        player.stopAll()
        val keyLen = KSoundDurationsMs[KSounds.english["B"]]!!.toLong()
        val expected = if (keyLen > dur("pencil") + 1) listOf(3, 2) else listOf(3)
        assertEquals(expected.sorted(), backend.stopped.sorted())
        player.playSingle("flood")               // stopped entries play again at once
        assertEquals(path("flood"), backend.played.last())
        assertEquals(4, backend.played.size)
    }

    @Test fun pendingEndStillFiresAfterPause() {
        var ended = false
        player.pplaySingle("doordingdong") { ended = true }
        player.stopAll()
        clock.advance(dur("doordingdong"))
        assertTrue(ended)
    }

    @Test fun closeReleasesAndGoesQuiet() {
        player.close()
        assertTrue(backend.released)
        player.playSingle("pencil"); player.playKey("A")
        assertEquals(emptyList<String>(), backend.played)
    }

    @Test fun loadOrderCoversEveryFileOnce() {
        val all = (KSounds.Library.values.flatten() + KSounds.english.values).toSet()
        val order = soundLoadOrder()
        assertEquals(all, order.toSet())
        assertEquals(order.size, order.toSet().size)
        assertEquals(path("mainmenu"), order.first())
    }

    @Test fun wavFallbackName() {
        assertEquals("snd/kidpix-submenu-brush-northern.wav",
            SoundPoolBackend.wavFallback("sndmp3/kidpix-submenu-brush-northern.wav.mp3"))
    }
}
