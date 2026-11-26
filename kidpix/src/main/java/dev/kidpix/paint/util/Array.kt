@file:JvmName("util_Array")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import kotlin.math.floor

// Port of js/util/array.js

/** Array.prototype.random */
fun <T> List<T>.random(): T = this[floor(KRandom.random() * this.size).toInt()]
fun <T> Array<T>.random(): T = this[floor(KRandom.random() * this.size).toInt()]

// https://stackoverflow.com/a/25984542
fun <T> fisherYatesArrayShuffle(array: MutableList<T>) {
    var count = array.size
    while (count != 0) {
        val randomnumber = (KRandom.random() * count--).toInt()
        val temp = array[count]
        array[count] = array[randomnumber]
        array[randomnumber] = temp
    }
}
