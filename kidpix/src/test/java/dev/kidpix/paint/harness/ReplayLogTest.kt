package dev.kidpix.paint.harness

import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** `./gradlew :kidpix:replayLog -Plog=<dir> -Pout=<png>` (see LogSession.replayExport). Skipped without -Plog. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ReplayLogTest {
    @Test fun replay() {
        val log = System.getProperty("kidpix.log")
        assumeTrue("no -Plog", log != null)
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        HarnessFonts.install()
        KCanvas.trackDirty = true
        val out = File(System.getProperty("kidpix.out") ?: "replay.png")
        LogSession.replayExport(File(log!!), out)
    }
}
