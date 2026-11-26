package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import org.junit.Assert.assertEquals
import org.junit.Test

/** Upstream's Library.playRand draws one Math.random() per call, sound device or not. */
class SoundsRandTest {
    @Test
    fun playRandConsumesOneRandomNumber() {
        KRandom.seed(1234)
        KRandom.random()
        val second = KRandom.random()
        KRandom.seed(1234)
        KiddoPaint.Sounds.bubblepops()
        assertEquals(second, KRandom.random(), 0.0)
    }
}
