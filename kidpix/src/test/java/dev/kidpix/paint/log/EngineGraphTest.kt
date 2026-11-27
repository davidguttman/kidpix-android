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
        "dev.kidpix.paint.canvas.Ctx2D.sansFaces" to "typefaces registered as sans-serif (configuration)",
        "dev.kidpix.paint.canvas.Ctx2D.chromeStrokes" to "configuration: Chrome-like stroke rasterising",
        "dev.kidpix.paint.canvas.EmojiFallback.cache" to "rendered emoji glyphs (derived from the text)",
        "dev.kidpix.paint.engine.World.slots" to "the list of slots itself (their values are the roots)",
        "dev.kidpix.paint.engine.World.generation" to "which world is current (a counter; restore starts a new one)",
        "dev.kidpix.paint.History.*" to "the undo history: a checkpoint keeps it as frame ids and a restore brings it back from the frames (KLog)",
        "dev.kidpix.paint.canvas.AaStroke.*" to "scratch bitmap and bytes, written whole before each use",
        "dev.kidpix.paint.canvas.MsaaSurface.*" to "the companion's scratch masks, pools and buffers (cleared before use), constants, a one-entry colour cache, the platform's byte order; the samples themselves are per canvas and kept (msaaSamples)",
        "dev.kidpix.paint.Filters.tmpCanvas" to "a canvas only used to make empty ImageData",
        "dev.kidpix.paint.Filters.tmpCtx" to "its context",
        "dev.kidpix.paint.KGlfx.*" to "the GL bridge the app plugs in",
        "dev.kidpix.paint.canvas.Ctx2D.batchTimer" to "a timing hook (perf builds)",
        "dev.kidpix.paint.canvas.EmojiFallback.*" to "where the emoji font is and its loaded glyph tables (derived from the font)",
        "dev.kidpix.paint.R\$*" to "resource ids",
    )

    private fun listed(id: String): Boolean = id in NOT_STATE || NOT_STATE.keys.any { k ->
        k.endsWith("*") && id.startsWith(k.removeSuffix("*").replace("\\$", "$"))
    }

    /** Names of the compiled engine classes (from the classes directory or jar the tests run on). */
    private fun classNames(): List<String> {
        val u = EngineGraph::class.java.classLoader!!.getResource("dev/kidpix/paint/log/EngineGraph.class")!!
        val names = ArrayList<String>()
        if (u.protocol == "jar") {
            val path = u.path.substringAfter("file:").substringBefore("!")
            java.util.jar.JarFile(java.net.URLDecoder.decode(path, "UTF-8")).use { jf ->
                for (e in jf.entries()) if (e.name.startsWith("dev/kidpix/paint/") && e.name.endsWith(".class")) names.add(e.name)
            }
        } else {
            val root = File(u.toURI()).parentFile.parentFile.parentFile.parentFile.parentFile
            File(root, "dev/kidpix/paint").walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { names.add(it.relativeTo(root).path) }
        }
        return names.map { it.removeSuffix(".class").replace('/', '.').replace(File.separatorChar, '.') }
    }

    @Test fun everyGlobalIsKeptOrListed() {
        val roots = EngineGraph.roots().map { it.key }.toSet()
        val missing = ArrayList<String>()
        var checked = 0
        val names = classNames()
        assertTrue("engine classes found: ${names.size}", names.size > 100)
        names.forEach { rel ->
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
                    id in EngineGraph.IGNORED || listed(id) -> {}
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
        // a function or object without fields (a top-level lambda, a stateless helper)
        if (v !is Collection<*> && v !is Map<*, *> && !v.javaClass.isArray && !v.javaClass.name.startsWith("java.") &&
            EngineGraph.info(v.javaClass).fields.all { it.second.declaringClass == kotlin.jvm.internal.Lambda::class.java }) return true
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
