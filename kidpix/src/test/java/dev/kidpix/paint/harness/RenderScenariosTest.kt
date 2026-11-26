package dev.kidpix.paint.harness

import dev.kidpix.paint.canvas.KAssets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * `./gradlew :kidpix:renderScenarios -Pscenarios=<file.json> -Pout=<dir> [-Ponly=<glob>]`
 * Without -P it renders kidpix/scenarios/pencil.json into kidpix/build/scenario-out.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class RenderScenariosTest {
    @Test
    fun render() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        HarnessFonts.install()
        val file = File(System.getProperty("kidpix.scenarios") ?: System.getProperty("kidpix.defaultScenarios")!!)
        val out = File(System.getProperty("kidpix.out") ?: System.getProperty("kidpix.defaultOut")!!)
        val results = ScenarioRunner(out).runFile(file, System.getProperty("kidpix.only"))
        println("rendered ${results.size} scenario(s) from $file into $out; failed: ${results.count { !it.ok }}")
        val failed = results.filter { !it.ok }
        assertTrue("scenarios failed: " + failed.joinToString { it.id }, failed.isEmpty())
    }
}
