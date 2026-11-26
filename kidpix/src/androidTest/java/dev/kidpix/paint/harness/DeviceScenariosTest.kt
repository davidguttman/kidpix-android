package dev.kidpix.paint.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.kidpix.paint.canvas.KAssets
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The headless ScenarioRunner, on a device: same scenarios, but with a real GLES context, so the
 * glfx effects (swirl, pancake stack...) render instead of being skipped.
 *
 * ```
 * P=dev.kidpix.tablet          # files go to the app's internal files dir (shell-pushed files
 *                              # under /sdcard/Android/data are not readable by the app)
 * adb shell run-as $P mkdir -p files/sc
 * for f in jumble.json base.png; do adb shell run-as $P sh -c "'cat > files/sc/$f'" < $f; done
 * adb shell am instrument -w [-e scenarios files/sc/jumble.json] [-e only 'jumble-*'] \
 *     $P.test/androidx.test.runner.AndroidJUnitRunner
 * adb exec-out run-as $P tar c -C files/scenario-out jumble | tar x -C <dir>   # then scripts/compare.py
 * ```
 * Relative paths are under the app's files dir. Output: `files/scenario-out/<scenario file name
 * without .json>/` (or `-e out <dir>`).
 */
@RunWith(AndroidJUnit4::class)
class DeviceScenariosTest {
    @Test
    fun render() {
        val args = InstrumentationRegistry.getArguments()
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val files = ctx.filesDir
        fun path(p: String) = File(p).let { if (it.isAbsolute) it else File(files.parentFile, p) }
        val scenarios = path(args.getString("scenarios") ?: "files/sc/jumble.json")
        val out = path(args.getString("out") ?: "files/scenario-out/" + scenarios.nameWithoutExtension)
        KAssets.opener = { p -> runCatching { ctx.assets.open(p) }.getOrNull() }
        val results = ScenarioRunner(out).runFile(scenarios, args.getString("only"))
        println("rendered ${results.size} scenario(s) from $scenarios into $out; failed: ${results.count { !it.ok }}; skipped: ${results.count { it.skipped != null }}")
        val failed = results.filter { !it.ok }
        assertTrue("scenarios failed: " + failed.joinToString { it.id + ": " + it.error }, failed.isEmpty())
        val skipped = results.filter { it.skipped != null }
        assertTrue("scenarios skipped on a device: " + skipped.joinToString { it.id }, skipped.isEmpty())
    }
}
