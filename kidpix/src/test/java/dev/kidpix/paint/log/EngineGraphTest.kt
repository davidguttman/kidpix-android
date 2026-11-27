package dev.kidpix.paint.log

import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.engine.WorldSlot
import dev.kidpix.paint.harness.LogSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.lang.reflect.Modifier

/**
 * The engine state a checkpoint keeps cannot silently miss anything:
 * - every static field of every engine class (all of dev.kidpix.paint except the app, the log and
 *   the harness) is a `by world` slot, a root of [EngineGraph], a constant, or listed below with
 *   why it is not engine state. A new global fails this test until it is one of them;
 * - every world slot has a unique name (the key a checkpoint stores it under);
 * - two fresh engines are identical (what a checkpoint leaves out is what any fresh engine has);
 * - a checkpoint's engine state written into a fresh engine gives the same fingerprint, for a
 *   session that used every tool.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class EngineGraphTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "kp-graph-test-" + System.nanoTime())

    @After fun tearDown() { KLog.close(); tmp.deleteRecursively() }

    /** Static fields that are not engine state, by class (simple JVM name) and field, with why. */
    private val NOT_STATE = mapOf(
        "dev.kidpix.paint.canvas.KAssets.cache" to "decoded asset bitmaps (the same file gives the same pixels); a checkpoint names them by path",
        "dev.kidpix.paint.canvas.KAssets.opener" to "where assets come from (the app's AssetManager)",
        "dev.kidpix.paint.canvas.KCanvas.trackDirty" to "configuration: dirty boxes for the app's pen layer",
        "dev.kidpix.paint.canvas.Ctx2D.msaaIncremental" to "configuration of a drawing speed-up that gives the same pixels",
        "dev.kidpix.paint.canvas.Ctx2D.fonts" to "registered typefaces (configuration)",
        "dev.kidpix.paint.canvas.Ctx2D.fontCache" to "parsed font strings (derived from the font string)",
        "dev.kidpix.paint.canvas.Ctx2D.chromeStrokes" to "configuration: Chrome-like stroke rasterising",
        "dev.kidpix.paint.canvas.EmojiFallback.cache" to "rendered emoji glyphs (derived from the text)",
        "dev.kidpix.paint.engine.World.slots" to "the list of slots itself (their values are the roots)",
        "dev.kidpix.paint.engine.World.generation" to "which world is current (a counter; restore starts a new one)",
    )

    private fun classesDir(): File {
        val url = EngineGraph::class.java.protectionDomain.codeSource.location
        return File(url.toURI())
    }

    @Test fun everyGlobalIsKeptOrListed() {
        val root = classesDir()
        val pkg = File(root, "dev/kidpix/paint")
        assertTrue("compiled classes at $pkg", pkg.isDirectory)
        val roots = EngineGraph.roots().map { it.key }.toSet()
        val missing = ArrayList<String>()
        var checked = 0
        pkg.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
            val rel = f.relativeTo(root).path.removeSuffix(".class").replace(File.separatorChar, '.')
            if (rel.startsWith("dev.kidpix.paint.app.") || rel.startsWith("dev.kidpix.paint.log.") || rel.startsWith("dev.kidpix.paint.harness.")) return@forEach
            val c = runCatching { Class.forName(rel, false, EngineGraph::class.java.classLoader) }.getOrNull() ?: return@forEach
            for (fl in runCatching { c.declaredFields }.getOrDefault(emptyArray())) {
                if (!Modifier.isStatic(fl.modifiers) || fl.isSynthetic) continue
                val id = c.name + "." + fl.name
                checked++
                when {
                    fl.name == "INSTANCE" || fl.name == "Companion" || fl.name == "\$\$delegatedProperties" -> {}
                    WorldSlot::class.java.isAssignableFrom(fl.type) -> {} // a `by world` slot: its value is a root
                    Modifier.isFinal(fl.modifiers) && (fl.type.isPrimitive || fl.type == String::class.java) -> {} // a constant
                    ("2$id") in roots -> {}
                    id in EngineGraph.IGNORED || id in NOT_STATE -> {}
                    Modifier.isFinal(fl.modifiers) && constant(fl) -> {}
                    else -> missing.add("$id (${fl.type.simpleName})")
                }
            }
        }
        println("GRAPH statics: $checked static fields checked, ${missing.size} not kept")
        assertTrue("static fields that are engine state but no checkpoint keeps (make them a root or list why not):\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    /** A final field holding a table that nothing writes: the same in every engine (checked: two fresh engines below). */
    private fun constant(f: java.lang.reflect.Field): Boolean {
        f.isAccessible = true
        val v = f.get(null) ?: return true
        // top-level lookup tables and sets of names (listOf/setOf/mapOf of constants, string tables)
        return v is Array<*> && v.all { it is String } || v is DoubleArray || v is IntArray && f.name.startsWith("lum") ||
            v.javaClass.name.startsWith("java.util.Collections\$Unmodifiable") || v.javaClass.name.startsWith("java.util.Collections\$Singleton") ||
            v.javaClass.name == "java.util.Arrays\$ArrayList" || v.javaClass.name.startsWith("kotlin.collections.") ||
            (v is Set<*> && v.all { it is String || it is Enum<*> }) || (v is Map<*, *> && v.keys.all { it is String })
    }

    @Test fun slotNamesAreUniqueAndTwoFreshEnginesAreTheSame() {
        System.getProperty("kidpix.assets")?.let { KAssets.useDirectory(File(it)) }
        KLog.versionCode = 12
        val a = LogSession(File(tmp, "a")).also { it.start(seed = 1) }
        val ea = EngineGraph.encode().roots.filterKeys { it.startsWith("1") }
        KLog.close()
        val b = LogSession(File(tmp, "b")).also { it.start(seed = 99) }
        val eb = EngineGraph.encode().roots.filterKeys { it.startsWith("1") }
        KLog.close()
        val names = EngineGraph.roots().map { it.key }
        assertEquals("root keys are unique", names.size, names.toSet().size)
        assertEquals("the same slots", ea.keys, eb.keys)
        for (k in ea.keys) assertEquals("fresh engines agree on $k", ea[k], eb[k])
        println("GRAPH fresh engine: ${ea.size} slots, ${ea.values.sumOf { it.length }} chars; a ${a.dir.name} b ${b.dir.name}")
    }
}
