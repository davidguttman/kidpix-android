package dev.kidpix.paint.log

import dev.kidpix.paint.KAndroid
import dev.kidpix.paint.KCurrent
import dev.kidpix.paint.KUi

/**
 * A cheap fingerprint of the engine state an action runs with, recorded with every action start
 * in the log (next to the KRandom state) and checked when the log is replayed: the tool object in
 * use (its class), the selected tool button, the submenu showing and its lit options, the colours,
 * and a hash of the current tool's fields (numbers, strings, flags, and for the closures an option
 * put there, their class and captured values). A replay whose engine differs (a wrong tool after a
 * restore) stops before it draws anything wrong. A few microseconds per action, nothing per move.
 */
object ActionPrint {
    fun now(): Int {
        var h = 0x2545F491
        val tool = KCurrent.tool
        h = mix(h, tool.javaClass.name.hashCode())
        h = mix(h, KAndroid.currentToolId.hashCode())
        h = mix(h, KUi.currentSubmenu.hashCode())
        h = mix(h, (KAndroid.currentSubmenu ?: "").hashCode())
        KAndroid.subSelection[KAndroid.currentSubmenu]?.let { m -> for ((k, v) in m.entries.sortedBy { it.key }) { h = mix(h, k); h = mix(h, v) } }
        h = mix(h, KCurrent.color.hashCode()); h = mix(h, KCurrent.altColor.hashCode()); h = mix(h, KCurrent.terColor.hashCode())
        h = fields(h, tool, 2)
        return h
    }

    private fun fields(h0: Int, o: Any, depth: Int): Int {
        var h = h0
        for ((k, f) in EngineGraph.info(o.javaClass).fields) {
            h = mix(h, k.hashCode())
            val t = f.type
            h = when {
                t == Int::class.javaPrimitiveType -> mix(h, f.getInt(o))
                t == Double::class.javaPrimitiveType -> mix(h, f.getDouble(o).toRawBits().hashCode())
                t == Boolean::class.javaPrimitiveType -> mix(h, if (f.getBoolean(o)) 1 else 2)
                t == Long::class.javaPrimitiveType -> mix(h, f.getLong(o).hashCode())
                t == Float::class.javaPrimitiveType -> mix(h, f.getFloat(o).toRawBits())
                t.isPrimitive -> h
                else -> value(h, f.get(o), depth)
            }
        }
        return h
    }

    private fun value(h: Int, v: Any?, depth: Int): Int = when (v) {
        null -> mix(h, 7)
        is String -> mix(h, v.hashCode())
        is Number -> mix(h, v.toDouble().toRawBits().hashCode())
        is Boolean -> mix(h, if (v) 11 else 13)
        is Enum<*> -> mix(h, v.name.hashCode())
        is Collection<*> -> mix(h, v.size)
        is Map<*, *> -> mix(h, v.size)
        is Function<*> -> if (depth > 0) fields(mix(h, v.javaClass.name.hashCode()), v, depth - 1) else mix(h, v.javaClass.name.hashCode())
        else -> mix(h, v.javaClass.name.hashCode())
    }

    private fun mix(h: Int, x: Int): Int { var k = x * -0x3361d2af; k = (k shl 15) or (k ushr 17); k *= 0x1b873593; val r = h xor k; return ((r shl 13) or (r ushr 19)) * 5 + -0x19ab949c }
}
