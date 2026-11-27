package dev.kidpix.paint.log

import android.graphics.Bitmap
import android.graphics.Matrix
import dev.kidpix.paint.KAlphabet
import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCache
import dev.kidpix.paint.KColors
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KDisplay
import dev.kidpix.paint.KSounds
import dev.kidpix.paint.KSprite
import dev.kidpix.paint.KStamps
import dev.kidpix.paint.KUi
import dev.kidpix.paint.KiddoPaint
import dev.kidpix.paint.canvas.Ctx2D
import dev.kidpix.paint.canvas.KAssets
import dev.kidpix.paint.canvas.KCanvas
import dev.kidpix.paint.engine.Engine
import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.KTime
import dev.kidpix.paint.engine.VirtualClock
import dev.kidpix.paint.engine.World
import dev.kidpix.paint.engine.WorldSlot
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.IdentityHashMap

/**
 * The engine's whole state as data, derived generically: every `by world { }` slot (the tools
 * with all their fields, the submenus, the overlay's selection), every field of the shared engine
 * objects (current tool and colours, pages, caches, the layer canvases and their contexts'
 * drawing state with patterns, gradients and the current path, the clock, KRandom), walked by
 * reflection. Nothing is listed by hand, so a field added to a tool later is in it without
 * anyone remembering to add it. A checkpoint stores this (only the roots that differ from a
 * freshly started engine, plus a fingerprint of all of it); a restore starts a fresh engine and
 * writes the stored values back, objects in place where they exist (identity kept: closures made
 * at start that hold a tool still see that tool), then checks the fingerprint.
 *
 * Values: scalars, enums, arrays, collections, plain objects and closures (lambdas are compiled
 * as named classes: class name + captured fields), Kotlin `object`s by name, references to an
 * object seen before (aliasing is kept: `Current.tool` is the very tool object of its slot).
 * Codecs for what is not plain data: canvases (the five layers' pixels are the checkpoint's
 * frames; any other canvas or bitmap is stored with its pixels, or by asset path), contexts
 * ([Ctx2D.graphState]), matrices, the virtual clock. What cannot be stored becomes a `U` value:
 * the fingerprint then can never match after a restore, so the restore stops safely
 * (KLog.applyCheckpoint) instead of replaying with a wrong state.
 */
object EngineGraph {
    const val FORMAT = 1

    // ------------------------------------------------------------------ roots

    class Root(val key: String, val get: () -> Any?, val set: ((Any?) -> Unit)?, val ensure: () -> Any?)

    /** Shared engine objects whose fields are roots (Kotlin objects: static fields; the clock below). */
    private fun targets(): List<Any> = listOf(KCurrent, KStamps, KAlphabet, KSprite, KUi, KDisplay, KColors, KColors.Palette,
        KColors.Current, KCache, KCache.Defaults, KTime, KRandom, KSounds, Engine, KAndroid, KiddoPaint)

    /**
     * Fields that are not engine state, with why. The environment the app plugs in (UI hook,
     * sound player, picture store, clock backend and its callbacks), and caches of reflection.
     */
    val IGNORED = mapOf(
        "dev.kidpix.paint.KUi.ui" to "the app's views (the UI hook)",
        "dev.kidpix.paint.KSounds.player" to "the sound output the app plugs in (a restore replays with a silent model; nothing is waiting on a sound at a checkpoint)",
        "dev.kidpix.paint.KSounds.silent" to "a constant silent player",
        "dev.kidpix.paint.KDisplay.store" to "the app's picture store",
        "dev.kidpix.paint.engine.KTime.backend" to "the clock itself (root ~clock)",
        "dev.kidpix.paint.engine.KTime.afterCallback" to "the app's redraw hook",
        "dev.kidpix.paint.KAndroid.isDownGetters" to "a cache of reflection lookups",
    )

    private val slotsField by lazy { World::class.java.getDeclaredField("slots").apply { isAccessible = true } }

    @Suppress("UNCHECKED_CAST")
    private fun allSlots(): List<WorldSlot<*>> = (slotsField.get(World) as MutableList<WorldSlot<*>>).let { synchronized(it) { ArrayList(it) } }

    private fun slotRoot(s: WorldSlot<*>): Root = Root("1" + s.name, { s.ensure() }, { v -> slotPut(s, v) }, { s.ensure() })

    private fun slotPut(s: WorldSlot<*>, v: Any?) { s.store(v) }

    private fun staticRoots(): List<Root> {
        val out = ArrayList<Root>()
        for (obj in targets()) for (f in obj.javaClass.declaredFields) {
            if (!Modifier.isStatic(f.modifiers) || f.isSynthetic || f.name == "INSTANCE" || f.name.endsWith("\$delegate")) continue
            val id = obj.javaClass.name + "." + f.name
            if (id in IGNORED) continue
            val final = Modifier.isFinal(f.modifiers)
            if (final && (f.type.isPrimitive || f.type == String::class.java)) continue // constants
            runCatching { f.isAccessible = true }.getOrNull() ?: continue
            out.add(Root("2$id", { f.get(null) }, if (final) null else { v -> f.set(null, v) }, { f.get(null) }))
        }
        return out
    }

    private val clockRoot = Root("3~clock", { KTime.backend }, null, { KTime.backend })

    /** The roots of the engine as it is now, in their fixed order (by key). */
    fun roots(): List<Root> {
        val r = ArrayList<Root>()
        for (s in allSlots()) if (s.live && s.name != null) r.add(slotRoot(s))
        r.addAll(staticRoots())
        r.add(clockRoot)
        r.sortBy { it.key }
        return r
    }

    private fun rootsByKey(): Map<String, Root> {
        val m = HashMap<String, Root>()
        for (s in allSlots()) if (s.name != null) m["1" + s.name] = slotRoot(s)
        for (r in staticRoots()) m[r.key] = r
        m[clockRoot.key] = clockRoot
        return m
    }

    // ------------------------------------------------------------------ the encoded state

    /** Every root's value as JSON text, in root order. */
    class Encoded(val roots: LinkedHashMap<String, String>) {
        val fingerprint: String by lazy { hash(roots) }
    }

    fun hash(roots: Map<String, String>): String {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64
        for ((k, v) in roots) {
            for (ch in k) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
            h = h xor 0x1F; h *= 0x100000001b3L
            for (ch in v) { h = h xor ch.code.toLong(); h *= 0x100000001b3L }
            h = h xor 0x1E; h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h)
    }

    fun encode(): Encoded {
        val e = Encoder(roots())
        return Encoded(e.all())
    }

    /** What a checkpoint keeps: the slots that differ from [baseline] (a fresh engine's), every other root, and the fingerprint of all. */
    fun capture(baseline: Map<String, String>?): JSONObject {
        val enc = encode()
        if (keepLastCapture) synchronized(captures) { captures[enc.fingerprint] = enc.roots; if (captures.size > 64) captures.remove(captures.keys.first()) }
        val stored = JSONObject()
        // slots are made anew by every engine start, so a fresh engine has the same ones: only those
        // that changed are kept; the shared objects' fields are not reset by a start: all are kept
        for ((k, v) in enc.roots) if (!k.startsWith("1") || baseline?.get(k) != v) stored.put(k, v)
        val gone = JSONArray()
        baseline?.keys?.forEach { if (it !in enc.roots) gone.put(it) }
        return JSONObject().put("v", FORMAT).put("fp", enc.fingerprint).put("roots", stored).put("gone", gone)
    }

    /** The shared objects' fields as they are (tests: two runs in one process start from the same). */
    fun captureStatics(): Map<String, String> = encode().roots.filterKeys { it.startsWith("2") }

    /** Writes [statics] (a [captureStatics]) back. */
    fun applyStatics(statics: Map<String, String>) {
        apply(JSONObject().put("roots", JSONObject(statics)).put("gone", JSONArray()).put("fp", ""))
    }

    /**
     * Writes [graph] (a [capture]) into the engine, which has just been started fresh. Returns
     * null when the engine's fingerprint afterwards is the stored one, else why not.
     */
    fun apply(graph: JSONObject, fresh: Map<String, String>? = null): String? {
        val stored = graph.getJSONObject("roots")
        val byKey = rootsByKey()
        val keys = java.util.TreeSet<String>()
        roots().forEach { keys.add(it.key) }
        stored.keys().forEach { keys.add(it) }
        val gone = graph.optJSONArray("gone")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet()
        val d = Decoder(byKey)
        val problems = ArrayList<String>()
        for (k in keys) {
            if (k in gone) continue
            val r = byKey[k] ?: run { problems.add("no root $k"); null } ?: continue
            val curV = runCatching { r.get() }.getOrNull()
            if (stored.has(k) && r.set == null && curV != null && !inPlace(curV)) {
                // a constant that cannot be written (an immutable collection in a final field): it must already be the same
                if (d.walk(r) != stored.getString(k)) problems.add("$k: a final constant differs")
            } else if (stored.has(k)) {
                try { d.root(r, JSONArray("[" + stored.getString(k) + "]").get(0)) }
                catch (t: Throwable) { problems.add("$k: ${t.javaClass.simpleName} ${t.message}") }
            } else d.walk(r)
        }
        val now = encode()
        val fp = graph.optString("fp")
        if (now.fingerprint == fp && problems.isEmpty()) return null
        lastMismatch = diff(stored, now, fresh, gone, synchronized(captures) { captures[fp] })
        return "engine state fingerprint ${now.fingerprint} != $fp" + (if (problems.isNotEmpty()) " (${problems.take(3)})" else "") +
            (lastMismatch?.let { " first difference: $it" } ?: "")
    }

    /** Tests: the full state of the last capture (a mismatch then names the root that differs). */
    var keepLastCapture = false
    private val captures = LinkedHashMap<String, Map<String, String>>()

    /** The first stored root that came back different (debug, logged with a mismatch). */
    var lastMismatch: String? = null
        private set

    private fun diff(stored: JSONObject, now: Encoded, fresh: Map<String, String>?, gone: Set<String>, lastCapture: Map<String, String>?): String? {
        val want = LinkedHashMap<String, String>()
        fresh?.forEach { (k, v) -> if (k !in gone) want[k] = v }
        for (k in stored.keys()) want[k] = stored.getString(k)
        for (k in now.roots.keys) if (k !in want) return "$k not in the checkpoint"
        lastCapture?.let { lc -> for (k in (lc.keys + now.roots.keys).toSortedSet()) if (lc[k] != now.roots[k]) {
            val a = lc[k] ?: "(none)"; val b = now.roots[k] ?: "(none)"
            var i = 0
            while (i < a.length && i < b.length && a[i] == b[i]) i++
            return "$k at $i (vs the capture): ${a.substring(maxOf(0, i - 60), minOf(a.length, i + 80))} | ${b.substring(maxOf(0, i - 60), minOf(b.length, i + 80))}"
        } }
        for ((k, a) in want) {
            val b = now.roots[k] ?: return "$k missing"
            if (a != b) {
                var i = 0
                while (i < a.length && i < b.length && a[i] == b[i]) i++
                return "$k at $i: ${a.substring(maxOf(0, i - 60), minOf(a.length, i + 60))} | ${b.substring(maxOf(0, i - 60), minOf(b.length, i + 60))}"
            }
        }
        return null
    }

    // ------------------------------------------------------------------ class info

    internal class Info(val cls: Class<*>) {
        /** Instance fields of the class and its superclasses (not java.lang.Object), by key. */
        val fields: List<Pair<String, Field>>
        val singleton: Any?

        init {
            val all = ArrayList<Field>()
            var c: Class<*>? = cls
            while (c != null && c != Any::class.java) {
                for (f in c.declaredFields) if (!Modifier.isStatic(f.modifiers) && !f.isAnnotationPresent(dev.kidpix.paint.engine.Scratch::class.java)) all.add(f)
                c = c.superclass
            }
            val names = all.groupingBy { it.name }.eachCount()
            fields = all.map { f -> (if (names.getValue(f.name) > 1) f.declaringClass.simpleName + "." + f.name else f.name) to f }
                .sortedBy { it.first }
            fields.forEach { runCatching { it.second.isAccessible = true } }
            singleton = runCatching {
                cls.getDeclaredField("INSTANCE").takeIf { Modifier.isStatic(it.modifiers) }?.let { it.isAccessible = true; it.get(null) }
            }.getOrNull()?.takeIf { it.javaClass == cls }
        }
    }

    private val infos = java.util.concurrent.ConcurrentHashMap<Class<*>, Info>()
    internal fun info(c: Class<*>): Info = infos.getOrPut(c) { Info(c) }

    /** Fields of plain objects that are derived caches: not stored, cleared when written back. */
    private val DERIVED = setOf("dev.kidpix.paint.canvas.CanvasGradient.cached")

    /**
     * A root's object that other roots refer to by the root's name: an object a restore writes
     * into where it is (a tool, a canvas), so the reference holds whichever root is written first.
     * Collections and arrays may be made anew by a restore: they are referred to where first seen.
     */
    private fun ownable(v: Any): Boolean = !isScalar(v) && v !is Collection<*> && v !is Map<*, *> && !v.javaClass.isArray &&
        !v.javaClass.name.startsWith("java.") && !v.javaClass.name.startsWith("kotlin.")

    private fun isScalar(v: Any?) = v == null || v is String || v is Boolean || v is Number || v is Char || v is Enum<*>

    private fun jstr(sb: StringBuilder, s: String) { sb.append(JSONObject.quote(s)) }

    // ------------------------------------------------------------------ encoder

    internal class Encoder(private val roots: List<Root>) {
        /** object -> (root key, preorder index in that root) */
        val ids = IdentityHashMap<Any, Pair<String, Int>>()
        val lists = HashMap<String, ArrayList<Any>>()
        val owners = IdentityHashMap<Any, String>()
        var cur = ""

        init {
            for (r in roots) {
                val v = runCatching { r.get() }.getOrNull() ?: continue
                if (ownable(v) && v !in owners) owners[v] = r.key
            }
        }

        fun all(): LinkedHashMap<String, String> {
            val out = LinkedHashMap<String, String>()
            for (r in roots) out[r.key] = one(r)
            return out
        }

        fun one(r: Root): String {
            cur = r.key
            val sb = StringBuilder()
            val v = r.get()
            if (r === clockRoot) clock(sb, v) else value(sb, v, own = true)
            return sb.toString()
        }

        fun register(v: Any) { val l = lists.getOrPut(cur) { ArrayList() }; ids[v] = cur to l.size; l.add(v) }

        private fun clock(sb: StringBuilder, v: Any?) {
            val c = v as? VirtualClock ?: return sb.append("[\"U\",\"clock\"]").let { }
            if (c.active != 0) { sb.append("[\"U\",\"timers pending\"]"); return }
            sb.append("[\"Q\",\"").append(c.time).append("\",\"").append(clockSeq.get(c)).append("\"]")
        }

        fun value(sb: StringBuilder, v: Any?, own: Boolean = false) {
            when (v) {
                null -> { sb.append("null"); return }
                is String -> { jstr(sb, v); return }
                is Boolean -> { sb.append(v); return }
                is Int -> { sb.append("[\"i\",").append(v).append(']'); return }
                is Double -> { sb.append("[\"d\",\"").append(v).append("\"]"); return }
                is Long -> { sb.append("[\"j\",\"").append(v).append("\"]"); return }
                is Float -> { sb.append("[\"f\",\"").append(v).append("\"]"); return }
                is Short -> { sb.append("[\"s\",").append(v).append(']'); return }
                is Byte -> { sb.append("[\"b\",").append(v).append(']'); return }
                is Char -> { sb.append("[\"c\",").append(v.code).append(']'); return }
                is Enum<*> -> { sb.append("[\"E\","); jstr(sb, v.declaringJavaClass.name); sb.append(','); jstr(sb, v.name); sb.append(']'); return }
            }
            v!!
            if (!own) owners[v]?.let { k -> if (k != cur) { sb.append("[\"W\","); jstr(sb, k); sb.append(']'); return } }
            ids[v]?.let { (k, i) -> sb.append("[\"R\","); jstr(sb, k); sb.append(',').append(i).append(']'); return }
            val c = v.javaClass
            val inf = info(c)
            if (inf.singleton === v) { sb.append("[\"S\","); jstr(sb, c.name); sb.append(']'); return }
            companionOf(c)?.let { if (it === v) { sb.append("[\"S\","); jstr(sb, c.name); sb.append(']'); return } }
            if (v is Ctx2D) { sb.append("[\"G\","); value(sb, v.canvas); sb.append(']'); return }
            register(v)
            when (v) {
                is IntArray -> { sb.append("[\"[I\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append("]]") }
                is DoubleArray -> { sb.append("[\"[D\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append('"').append(x).append('"') }; sb.append("]]") }
                is FloatArray -> { sb.append("[\"[F\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append('"').append(x).append('"') }; sb.append("]]") }
                is LongArray -> { sb.append("[\"[J\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append('"').append(x).append('"') }; sb.append("]]") }
                is BooleanArray -> { sb.append("[\"[Z\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append("]]") }
                is ByteArray -> { sb.append("[\"[B\",\""); sb.append(android.util.Base64.encodeToString(v, android.util.Base64.NO_WRAP)); sb.append("\"]") }
                is ShortArray -> { sb.append("[\"[S\",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append(x) }; sb.append("]]") }
                is CharArray -> { sb.append("[\"[C\","); jstr(sb, String(v)); sb.append(']') }
                is Array<*> -> { sb.append("[\"A\","); jstr(sb, c.componentType.name); sb.append(",["); v.forEachIndexed { i, x -> if (i > 0) sb.append(','); value(sb, x) }; sb.append("]]") }
                is KCanvas -> canvas(sb, v)
                is Ctx2D -> {} // below: a context is written as its canvas (which holds its state)
                is Bitmap -> bitmap(sb, v)
                is Matrix -> { val m = FloatArray(9); v.getValues(m); sb.append("[\"X\",["); m.forEachIndexed { i, x -> if (i > 0) sb.append(','); sb.append('"').append(x).append('"') }; sb.append("]]") }
                is java.util.PriorityQueue<*> -> sb.append("[\"U\",\"queue\"]")
                is Map<*, *> -> map(sb, v)
                is Set<*> -> { sb.append("[\"T\","); jstr(sb, c.name); sb.append(",["); items(sb, v, sorted = v !is LinkedHashSet<*> && v !is java.util.SortedSet<*>); sb.append("]]") }
                is Collection<*> -> { sb.append("[\"L\","); jstr(sb, c.name); sb.append(",["); items(sb, v, sorted = false); sb.append("]]") }
                else -> obj(sb, v, c, inf)
            }
        }

        private fun items(sb: StringBuilder, v: Collection<*>, sorted: Boolean) {
            val list = if (sorted && v.all { isScalar(it) }) v.sortedBy { scalarKey(it) } else v.toList()
            list.forEachIndexed { i, x -> if (i > 0) sb.append(','); value(sb, x) }
        }

        private fun map(sb: StringBuilder, v: Map<*, *>) {
            sb.append("[\"M\","); jstr(sb, v.javaClass.name); sb.append(",[")
            val ordered = v is LinkedHashMap<*, *> || v is java.util.SortedMap<*, *>
            val entries = if (!ordered && v.keys.all { isScalar(it) }) v.entries.sortedBy { scalarKey(it.key) } else v.entries.toList()
            entries.forEachIndexed { i, (k, x) -> if (i > 0) sb.append(','); sb.append('['); value(sb, k); sb.append(','); value(sb, x); sb.append(']') }
            sb.append("]]")
        }

        private fun obj(sb: StringBuilder, v: Any, c: Class<*>, inf: Info) {
            val n = c.name
            if (n.startsWith("java.") || n.startsWith("android.") || n.startsWith("javax.") || n.startsWith("sun.") || n.startsWith("dalvik.")) {
                if (c != Any::class.java) { sb.append("[\"U\","); jstr(sb, n); sb.append(']'); return }
            }
            sb.append("[\"O\","); jstr(sb, n); sb.append(",{")
            var first = true
            for ((k, f) in inf.fields) {
                if ((f.declaringClass.name + "." + f.name) in DERIVED) continue
                if (!first) sb.append(',')
                first = false
                jstr(sb, k); sb.append(':')
                val t = f.type
                when {
                    t == Int::class.javaPrimitiveType -> sb.append(f.getInt(v))
                    t == Double::class.javaPrimitiveType -> sb.append('"').append(f.getDouble(v)).append('"')
                    t == Boolean::class.javaPrimitiveType -> sb.append(f.getBoolean(v))
                    t == Long::class.javaPrimitiveType -> sb.append('"').append(f.getLong(v)).append('"')
                    t == Float::class.javaPrimitiveType -> sb.append('"').append(f.getFloat(v)).append('"')
                    t == Short::class.javaPrimitiveType -> sb.append(f.getShort(v))
                    t == Byte::class.javaPrimitiveType -> sb.append(f.getByte(v))
                    t == Char::class.javaPrimitiveType -> sb.append(f.getChar(v).code)
                    else -> value(sb, f.get(v))
                }
            }
            sb.append("}]")
        }

        private fun canvas(sb: StringBuilder, v: KCanvas) {
            val layer = layerName(v)
            sb.append("[\"K\","); value(sb, layer); sb.append(',').append(v.width).append(',').append(v.height).append(',')
            jstr(sb, v.id); sb.append(','); jstr(sb, v.className); sb.append(',')
            if (layer != null) sb.append("null") else pixels(sb, v.snapshotForGraph())
            sb.append(',')
            val ctx = v.contextOrNull()
            val g = ctx?.graphState()
            if (g == null) sb.append("null") else {
                ids[ctx] ?: register(ctx)
                sb.append('['); value(sb, g.st); sb.append(",["); g.stack.forEachIndexed { i, x -> if (i > 0) sb.append(','); value(sb, x) }
                sb.append("],")
                if (g.unstorable != null) { sb.append("[\"U\","); jstr(sb, g.unstorable); sb.append(']') } else value(sb, g.path)
                // the multisampled strokes' samples: a stroke over an edge pixel uses them
                sb.append(",\"").append(samples(ctx)).append("\"]")
            }
            sb.append(']')
        }

        /** [Ctx2D.msaaSamples] as text (cached while they cannot have changed). */
        private fun samples(ctx: Ctx2D): String {
            val key = ctx.msaaKey()
            synchronized(sampleCache) { sampleCache[ctx]?.let { (k, v) -> if (k == key) return v } }
            val ms = ctx.msaaSamples()
            val txt = if (ms.isEmpty()) "" else {
                val raw = java.nio.ByteBuffer.allocate(ms.size * 4); raw.asIntBuffer().put(ms)
                ms.size.toString() + ":" + android.util.Base64.encodeToString(FrameStore.deflate(raw.array()), android.util.Base64.NO_WRAP)
            }
            synchronized(sampleCache) { sampleCache[ctx] = key to txt }
            return txt
        }

        private fun bitmap(sb: StringBuilder, b: Bitmap) {
            val key = KAssets.keyOf(b)
            sb.append("[\"B\",")
            if (key != null) { jstr(sb, key); sb.append(']'); return }
            sb.append("null,"); pixels(sb, b); sb.append(']')
        }

        private fun pixels(sb: StringBuilder, b: Bitmap) {
            val gen = b.generationId
            synchronized(pixelCache) { pixelCache[b]?.let { (g, v) -> if (g == gen) { sb.append(v); return } } }
            val start = sb.length
            pixelsNow(sb, b)
            synchronized(pixelCache) { pixelCache[b] = gen to sb.substring(start) }
        }

        private fun pixelsNow(sb: StringBuilder, b: Bitmap) {
            val px = IntArray(b.width * b.height)
            b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
            val raw = java.nio.ByteBuffer.allocate(px.size * 4)
            raw.asIntBuffer().put(px)
            sb.append('[').append(b.width).append(',').append(b.height).append(",\"")
            sb.append(android.util.Base64.encodeToString(FrameStore.deflate(raw.array()), android.util.Base64.NO_WRAP))
            sb.append("\"]")
        }
    }

    /** Encodings of pixels and samples, reused while their bitmap or surface has not changed. */
    private val pixelCache = java.util.WeakHashMap<Bitmap, Pair<Int, String>>()
    private val sampleCache = java.util.WeakHashMap<Ctx2D, Pair<List<Any?>, String>>()

    private fun scalarKey(v: Any?): String = when (v) { null -> "0"; is String -> "s$v"; is Number -> "n" + v.javaClass.simpleName + v; else -> "x$v" }

    private fun layers(): List<Pair<String, KCanvas>> = listOf("main" to KDisplay.main_canvas, "tmp" to KDisplay.canvas, "anim" to KDisplay.animCanvas,
        "bnim" to KDisplay.bnimCanvas, "preview" to KDisplay.previewCanvas)

    /** The five layer canvases: their pixels are the checkpoint's frames, not stored here. */
    private fun layerName(c: KCanvas): String? = layers().firstOrNull { it.second === c }?.first

    private val companions = java.util.concurrent.ConcurrentHashMap<Class<*>, Any>()
    private val NONE = Any()
    private fun companionOf(c: Class<*>): Any? {
        val v = companions.getOrPut(c) {
            runCatching { c.declaringClass?.getDeclaredField("Companion")?.takeIf { Modifier.isStatic(it.modifiers) }?.let { it.isAccessible = true; it.get(null) } }.getOrNull() ?: NONE
        }
        return if (v === NONE) null else v
    }

    private val clockSeq: Field by lazy { VirtualClock::class.java.getDeclaredField("seq").apply { isAccessible = true } }

    // ------------------------------------------------------------------ decoder

    private class Decoder(private val byKey: Map<String, Root>) {
        private val enc = Encoder(roots())
        private var cur = ""

        /** Root [r] is unchanged from the fresh engine: only note its objects (references into it). */
        fun walk(r: Root): String? {
            encSetCur(r.key)
            return runCatching { enc.one(r) }.getOrNull()
        }

        private fun encSetCur(k: String) { cur = k; enc.cur = k }

        fun root(r: Root, j: Any?) {
            encSetCur(r.key)
            if (r === clockRoot) { clock(j); return }
            val before = r.ensure()
            val v = value(j, before)
            if (v !== before) {
                if (r.set != null) r.set.invoke(v) else throw IllegalStateException("final root ${r.key} would change object")
                if (v != null && ownable(v) && v !in enc.owners) enc.owners[v] = r.key
            }
        }

        private fun clock(j: Any?) {
            val a = j as JSONArray
            if (a.getString(0) != "Q") throw IllegalStateException("clock not stored")
            val c = KTime.backend as VirtualClock
            c.clear()
            c.time = a.getString(1).toLong()
            clockSeq.setLong(c, a.getString(2).toLong())
        }

        private fun reg(v: Any) { enc.register(v) }

        private fun resolve(k: String, i: Int): Any {
            val l = enc.lists[k] ?: throw IllegalStateException("reference into $k before it")
            return l.getOrNull(i) ?: throw IllegalStateException("reference $k/$i out of range")
        }

        /** [cur]: what is there now (reused in place when it is the same kind). */
        fun value(j: Any?, cur: Any?): Any? {
            if (j == null || j == JSONObject.NULL) return null
            if (j is String) return j
            if (j is Boolean) return j
            val a = j as JSONArray
            return when (val tag = a.getString(0)) {
                "i" -> a.getInt(1)
                "d" -> a.getString(1).toDouble()
                "j" -> a.getString(1).toLong()
                "f" -> a.getString(1).toFloat()
                "s" -> a.getInt(1).toShort()
                "b" -> a.getInt(1).toByte()
                "c" -> a.getInt(1).toChar()
                "E" -> enumValue(a.getString(1), a.getString(2))
                "W" -> (byKey[a.getString(1)] ?: throw IllegalStateException("no root ${a.getString(1)}")).ensure()
                "R" -> resolve(a.getString(1), a.getInt(2))
                "S" -> singleton(a.getString(1))
                "U" -> throw IllegalStateException("not storable: ${a.opt(1)}")
                "[I" -> { val x = a.getJSONArray(1); IntArray(x.length()) { x.getInt(it) }.also { reg(it) } }
                "[D" -> { val x = a.getJSONArray(1); DoubleArray(x.length()) { x.getString(it).toDouble() }.also { reg(it) } }
                "[F" -> { val x = a.getJSONArray(1); FloatArray(x.length()) { x.getString(it).toFloat() }.also { reg(it) } }
                "[J" -> { val x = a.getJSONArray(1); LongArray(x.length()) { x.getString(it).toLong() }.also { reg(it) } }
                "[Z" -> { val x = a.getJSONArray(1); BooleanArray(x.length()) { x.getBoolean(it) }.also { reg(it) } }
                "[B" -> android.util.Base64.decode(a.getString(1), android.util.Base64.NO_WRAP).also { reg(it) }
                "[S" -> { val x = a.getJSONArray(1); ShortArray(x.length()) { x.getInt(it).toShort() }.also { reg(it) } }
                "[C" -> a.getString(1).toCharArray().also { reg(it) }
                "A" -> {
                    val x = a.getJSONArray(2)
                    val comp = classFor(a.getString(1))
                    @Suppress("UNCHECKED_CAST")
                    val arr = (if (cur != null && cur.javaClass.componentType == comp && java.lang.reflect.Array.getLength(cur) == x.length()) cur
                        else java.lang.reflect.Array.newInstance(comp, x.length())) as Array<Any?>
                    reg(arr)
                    for (i in 0 until x.length()) arr[i] = value(x.get(i), arr[i])
                    arr
                }
                "L", "T" -> collection(a, cur)
                "M" -> map(a, cur)
                "K" -> canvas(a, cur)
                "G" -> (value(a.get(1), (cur as? Ctx2D)?.canvas) as KCanvas).getContext("2d")
                "B" -> bitmap(a)
                "X" -> {
                    val m = (cur as? Matrix) ?: Matrix()
                    reg(m)
                    val x = a.getJSONArray(1)
                    m.setValues(FloatArray(9) { x.getString(it).toFloat() })
                    m
                }
                "O" -> obj(a, cur)
                else -> throw IllegalStateException("unknown tag $tag")
            }
        }

        private fun collection(a: JSONArray, cur: Any?): Any {
            val cn = a.getString(1)
            val x = a.getJSONArray(2)
            @Suppress("UNCHECKED_CAST")
            val mutable = cur?.takeIf { it.javaClass.name == cn && it is MutableCollection<*> && isMutableKind(cn) } as MutableCollection<Any?>?
            if (mutable != null) {
                reg(mutable)
                val old = ArrayList(mutable)
                mutable.clear()
                for (i in 0 until x.length()) mutable.add(value(x.get(i), old.getOrNull(i)))
                return mutable
            }
            // a placeholder keeps the preorder index while the elements are decoded
            val l = enc.lists.getOrPut(this.cur) { ArrayList() }
            val idx = l.size
            l.add(Any())
            val items = ArrayList<Any?>()
            for (i in 0 until x.length()) items.add(value(x.get(i), null))
            val made: Any = makeCollection(cn, items)
            l[idx] = made; enc.ids[made] = this.cur to idx
            return made
        }

        private fun map(a: JSONArray, cur: Any?): Any {
            val cn = a.getString(1)
            val x = a.getJSONArray(2)
            @Suppress("UNCHECKED_CAST")
            val m = (cur?.takeIf { it.javaClass.name == cn && it is MutableMap<*, *> && isMutableKind(cn) } as MutableMap<Any?, Any?>?)
            if (m != null) {
                reg(m)
                val old = HashMap(m)
                m.clear()
                for (i in 0 until x.length()) {
                    val e = x.getJSONArray(i)
                    val k = value(e.get(0), null)
                    m[k] = value(e.get(1), old[k])
                }
                return m
            }
            val l = enc.lists.getOrPut(this.cur) { ArrayList() }
            val idx = l.size
            l.add(Any())
            val entries = ArrayList<Pair<Any?, Any?>>()
            for (i in 0 until x.length()) { val e = x.getJSONArray(i); val k = value(e.get(0), null); entries.add(k to value(e.get(1), null)) }
            val made: Any = makeMap(cn, entries)
            l[idx] = made; enc.ids[made] = this.cur to idx
            return made
        }

        private fun obj(a: JSONArray, cur: Any?): Any {
            val c = classFor(a.getString(1))
            val o = if (cur != null && cur.javaClass == c) cur else newInstance(c)
            reg(o)
            val fs = a.getJSONObject(2)
            for ((k, f) in info(c).fields) {
                if ((f.declaringClass.name + "." + f.name) in DERIVED) { if (!f.type.isPrimitive) f.set(o, null); continue }
                if (!fs.has(k)) continue
                val t = f.type
                when {
                    t == Int::class.javaPrimitiveType -> f.setInt(o, fs.getInt(k))
                    t == Double::class.javaPrimitiveType -> f.setDouble(o, fs.getString(k).toDouble())
                    t == Boolean::class.javaPrimitiveType -> f.setBoolean(o, fs.getBoolean(k))
                    t == Long::class.javaPrimitiveType -> f.setLong(o, fs.getString(k).toLong())
                    t == Float::class.javaPrimitiveType -> f.setFloat(o, fs.getString(k).toFloat())
                    t == Short::class.javaPrimitiveType -> f.setShort(o, fs.getInt(k).toShort())
                    t == Byte::class.javaPrimitiveType -> f.setByte(o, fs.getInt(k).toByte())
                    t == Char::class.javaPrimitiveType -> f.setChar(o, fs.getInt(k).toChar())
                    else -> {
                        val old = f.get(o)
                        val nv = value(fs.get(k), old)
                        if (nv !== old) f.set(o, nv)
                    }
                }
            }
            return o
        }

        private fun canvas(a: JSONArray, cur: Any?): KCanvas {
            val layer = if (a.isNull(1)) null else a.getString(1)
            val w = a.getInt(2); val h = a.getInt(3)
            val c = layer?.let { n -> layers().first { it.first == n }.second } ?: (cur as? KCanvas) ?: KCanvas(w, h)
            reg(c)
            if (c.width != w || c.height != h) c.setSize(w, h)
            c.id = a.getString(4); c.className = a.getString(5)
            if (layer == null && !a.isNull(6)) {
                val p = a.getJSONArray(6)
                putPixels(c.bitmap, p)
                c.markAllDirty()
            }
            if (!a.isNull(7)) {
                val g = a.getJSONArray(7)
                val ctx = c.getContext("2d")
                enc.ids[ctx] ?: reg(ctx)
                val old = ctx.graphState()
                val st = value(g.get(0), old.st)!!
                val sa = g.getJSONArray(1)
                val stack = (0 until sa.length()).map { value(sa.get(it), old.stack.getOrNull(it))!! }
                val path = value(g.get(2), null)
                ctx.setGraphState(Ctx2D.GraphState(st, stack, path))
                val ms = g.optString(3, "")
                ctx.setMsaaSamples(if (ms.isEmpty()) IntArray(0) else {
                    val n = ms.substringBefore(':').toInt()
                    val raw = FrameStore.inflate(android.util.Base64.decode(ms.substringAfter(':'), android.util.Base64.NO_WRAP), n * 4)
                        ?: throw IllegalStateException("samples unreadable")
                    IntArray(n).also { java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(it) }
                })
            }
            return c
        }

        private fun bitmap(a: JSONArray): Bitmap {
            if (!a.isNull(1)) return (KAssets.loadBitmap(a.getString(1)) ?: throw IllegalStateException("asset ${a.getString(1)} missing")).also { reg(it) }
            val p = a.getJSONArray(2)
            val b = Bitmap.createBitmap(p.getInt(0), p.getInt(1), Bitmap.Config.ARGB_8888)
            reg(b)
            putPixels(b, p)
            return b
        }

        private fun putPixels(b: Bitmap, p: JSONArray) {
            val w = p.getInt(0); val h = p.getInt(1)
            val raw = FrameStore.inflate(android.util.Base64.decode(p.getString(2), android.util.Base64.NO_WRAP), w * h * 4)
                ?: throw IllegalStateException("pixels unreadable")
            val px = IntArray(w * h)
            java.nio.ByteBuffer.wrap(raw).asIntBuffer().get(px)
            b.setPixels(px, 0, w, 0, 0, w, h)
        }
    }

    /** A value a decode can write into where it is (an object, an array, a mutable collection). */
    private fun inPlace(v: Any): Boolean {
        val n = v.javaClass.name
        if (v is Collection<*> || v is Map<*, *>) return isMutableKind(n)
        return !(n.startsWith("java.") || n.startsWith("kotlin.collections."))
    }

    private fun isMutableKind(cn: String) = cn == "java.util.ArrayList" || cn == "java.util.HashMap" || cn == "java.util.LinkedHashMap" ||
        cn == "java.util.HashSet" || cn == "java.util.LinkedHashSet" || cn == "java.util.LinkedList" || cn == "java.util.ArrayDeque" ||
        cn == "java.util.TreeMap" || cn == "java.util.TreeSet" || cn == "java.util.concurrent.ConcurrentHashMap"

    private fun makeCollection(cn: String, items: List<Any?>): Any = when (cn) {
        "java.util.ArrayList" -> ArrayList(items)
        "java.util.LinkedList" -> java.util.LinkedList(items)
        "java.util.ArrayDeque" -> java.util.ArrayDeque(items)
        "java.util.HashSet" -> HashSet(items)
        "java.util.LinkedHashSet" -> LinkedHashSet(items)
        "java.util.TreeSet" -> java.util.TreeSet(items)
        "java.util.Arrays\$ArrayList" -> java.util.Arrays.asList(*items.toTypedArray())
        "java.util.Collections\$SingletonList" -> java.util.Collections.singletonList(items.single())
        "java.util.Collections\$SingletonSet" -> java.util.Collections.singleton(items.single())
        "java.util.Collections\$UnmodifiableRandomAccessList", "java.util.Collections\$UnmodifiableList" -> java.util.Collections.unmodifiableList(ArrayList(items))
        "java.util.Collections\$UnmodifiableSet" -> java.util.Collections.unmodifiableSet(LinkedHashSet(items))
        "java.util.Collections\$SynchronizedRandomAccessList" -> java.util.Collections.synchronizedList(ArrayList(items))
        else -> throw IllegalStateException("collection $cn")
    }

    private fun makeMap(cn: String, entries: List<Pair<Any?, Any?>>): Any = when (cn) {
        "java.util.HashMap" -> HashMap<Any?, Any?>().also { m -> entries.forEach { m[it.first] = it.second } }
        "java.util.LinkedHashMap" -> LinkedHashMap<Any?, Any?>().also { m -> entries.forEach { m[it.first] = it.second } }
        "java.util.TreeMap" -> java.util.TreeMap<Any?, Any?>().also { m -> entries.forEach { m[it.first] = it.second } }
        "java.util.concurrent.ConcurrentHashMap" -> java.util.concurrent.ConcurrentHashMap<Any?, Any?>().also { m -> entries.forEach { m[it.first!!] = it.second!! } }
        "java.util.Collections\$SingletonMap" -> java.util.Collections.singletonMap(entries.single().first, entries.single().second)
        "java.util.Collections\$UnmodifiableMap" -> java.util.Collections.unmodifiableMap(LinkedHashMap<Any?, Any?>().also { m -> entries.forEach { m[it.first] = it.second } })
        else -> throw IllegalStateException("map $cn")
    }

    private fun classFor(n: String): Class<*> = when (n) {
        "int" -> Int::class.javaPrimitiveType!!; "double" -> Double::class.javaPrimitiveType!!; "float" -> Float::class.javaPrimitiveType!!
        "long" -> Long::class.javaPrimitiveType!!; "boolean" -> Boolean::class.javaPrimitiveType!!; "byte" -> Byte::class.javaPrimitiveType!!
        "short" -> Short::class.javaPrimitiveType!!; "char" -> Char::class.javaPrimitiveType!!
        else -> Class.forName(n, false, EngineGraph::class.java.classLoader)
    }

    private fun singleton(n: String): Any {
        val c = classFor(n)
        info(c).singleton?.let { return it }
        companionOf(c)?.let { return it }
        throw IllegalStateException("no singleton $n")
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumValue(cn: String, name: String): Any = java.lang.Enum.valueOf(classFor(cn) as Class<out Enum<*>>, name)

    private val unsafe: Any? by lazy {
        runCatching { Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) }.getOrNull()
    }
    private val allocate by lazy { unsafe?.javaClass?.getMethod("allocateInstance", Class::class.java) }

    /** A new object of [c] whose fields are then all written: its no-argument constructor, else allocated bare. */
    private fun newInstance(c: Class<*>): Any {
        runCatching { c.getDeclaredConstructor().apply { isAccessible = true }.newInstance() }.getOrNull()?.let { return it }
        val a = allocate ?: throw IllegalStateException("cannot create ${c.name}")
        return a.invoke(unsafe, c)!!
    }
}
