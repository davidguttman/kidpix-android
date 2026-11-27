package dev.kidpix.paint.engine

import kotlin.reflect.KProperty

/**
 * Per-world singletons. Upstream objects such as `KiddoPaint.Tools.Pencil = new Toolbox.Pencil()`
 * are created once per page load. Here they are created lazily per "world": [World.reset] (a new
 * scenario, a fresh app start) makes every `by world { }` property re-run its initializer.
 *
 *     val KiddoPaint.Tools.Pencil by world { ToolboxPencil() }
 *     var KiddoPaint.Submenu.sprites by world { mutableListOf<SubmenuItem>() }
 */
object World {
    var generation = 0
        private set

    fun reset() { generation++ }

    /** Every `by world { }` slot (they are created once, when their file's class loads). */
    internal val slots = java.util.Collections.synchronizedList(ArrayList<WorldSlot<*>>())

    /**
     * The slot of the property named [n] (a [WorldSlot.name]: owner class # property # signature),
     * also when that property was never used in this process: its `<property>$delegate` field.
     */
    /** Tests: as after a process start, no slot knows its name until its property is used. */
    fun forgetNames() = synchronized(slots) { slots.forEach { it.forgetName() } }

    fun slotNamed(n: String): WorldSlot<*>? {
        synchronized(slots) { slots.firstOrNull { it.name == n } }?.let { return it }
        val parts = n.split("#")
        if (parts.size < 2) return null
        val owner = runCatching { Class.forName(parts[0], true, World::class.java.classLoader) }.getOrNull() ?: return null
        for (f in owner.declaredFields) {
            if (!java.lang.reflect.Modifier.isStatic(f.modifiers) || !f.name.startsWith(parts[1] + "\$delegate")) continue
            val s = runCatching { f.isAccessible = true; f.get(null) as? WorldSlot<*> }.getOrNull() ?: continue
            if (s.name != null && s.name != n) continue
            s.nameAs(n)
            return s
        }
        return null
    }

    /** The values of this world's slots (to come back to this world after a replay ran in another). */
    class Saved internal constructor(internal val values: List<Pair<WorldSlot<*>, Any?>>)

    fun save(): Saved = Saved(synchronized(slots) { slots.filter { it.gen == generation }.map { it to it.value } })

    /** Back to the world [s] was taken of: its very objects (slots it had not created yet start fresh). */
    fun restore(s: Saved) {
        generation++
        for ((slot, v) in s.values) slot.put(v)
    }
}

class WorldSlot<T>(private val init: () -> T) {
    internal var gen = -1
    internal var value: T? = null
    /**
     * The property this slot backs (its file or object, name and JVM signature), known from its
     * first use: the name a checkpoint stores the slot's value under (log/EngineGraph.kt).
     */
    var name: String? = null
        private set

    init { World.slots.add(this) }

    @Suppress("UNCHECKED_CAST")
    internal fun put(v: Any?) { gen = World.generation; value = v as T? }

    /** Sets the value in this world (a checkpoint's value written back: log/EngineGraph.kt). */
    fun store(v: Any?) = put(v)

    /** In this world (created or set since the last reset). */
    val live: Boolean get() = gen == World.generation

    /** The value in this world, created by its initializer if it does not exist yet. */
    fun ensure(): Any? {
        if (gen != World.generation) { gen = World.generation; value = init() }
        return value
    }

    /** Names a slot found by [World.slotNamed] (its property not used yet in this process). */
    internal fun nameAs(n: String) { if (name == null) name = n }
    internal fun forgetName() { name = null }

    private fun named(property: KProperty<*>) {
        if (name != null) return
        val ref = property as? kotlin.jvm.internal.CallableReference
        val owner = (ref?.owner as? kotlin.jvm.internal.ClassBasedDeclarationContainer)?.jClass?.name ?: "?"
        name = owner + "#" + property.name + "#" + (ref?.signature ?: "")
    }

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        if (name == null) named(property)
        if (gen != World.generation) { gen = World.generation; value = init() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, v: T) {
        if (name == null) named(property)
        gen = World.generation; value = v
    }
}

fun <T> world(init: () -> T) = WorldSlot(init)

/**
 * A field that is not engine state: a buffer every use writes before it reads, so nothing in it
 * outlives one action. Checkpoints do not keep it (log/EngineGraph.kt); [why] says why that is safe.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Scratch(val why: String)
