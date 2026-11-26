package dev.kidpix.paint.log

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import dev.kidpix.paint.harness.LogSession.Companion.crc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class FrameStoreTest {
    @Test fun framesReadBackExactlyAcrossDeltaChainsAndReopen() {
        val dir = File(System.getProperty("java.io.tmpdir"), "kp-frames-" + System.nanoTime())
        val fs = FrameStore(FrameStore.DirBackend(dir), null)
        val rnd = java.util.Random(2)
        val b = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val crcs = HashMap<Long, Long>()
        for (id in 1L..80L) {
            val p = Paint().apply { color = rnd.nextInt() or (rnd.nextInt(256) shl 24); isAntiAlias = rnd.nextBoolean() }
            if (rnd.nextInt(15) == 0) c.drawRect(0f, 0f, 300f, 200f, p)
            else c.drawCircle(rnd.nextFloat() * 300, rnd.nextFloat() * 200, rnd.nextFloat() * 30, p)
            fs.put(id, b.copy(Bitmap.Config.ARGB_8888, false))
            crcs[id] = crc(b)
        }
        for ((id, want) in crcs) assertEquals("frame $id", want, crc(fs.get(id)!!))
        val keys = (1L..80L).count { fs.info(it)!!.key }
        assertTrue("some key frames ($keys)", keys in 2..40)
        val again = FrameStore(FrameStore.DirBackend(dir), null)
        for ((id, want) in crcs) assertEquals("reopened frame $id", want, crc(again.get(id)!!))
        dir.deleteRecursively()
    }
}
