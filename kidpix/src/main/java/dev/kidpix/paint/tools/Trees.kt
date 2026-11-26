@file:JvmName("tools_Trees")

package dev.kidpix.paint

import dev.kidpix.paint.engine.KRandom
import dev.kidpix.paint.engine.world
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// Port of js/tools/trees.js

class ToolboxTree : Tool() {
    val tool = this
    var isDown = false

    override fun mousedown(ev: Ev) {
        tool.isDown = true
        KiddoPaint.Sounds.brushtree()
        // native: the tree's ~57,000 strokes go to the pixels in one batch (same picture, see Ctx2D.batchStrokes)
        KiddoPaint.Display.context.batchStrokes {
            drawTree(ev._x, ev._y, 32 * KiddoPaint.Current.scaling, -PI / 2, 12, 15.0)
        }
    }

    override fun mousemove(ev: Ev) {}

    override fun mouseup(ev: Ev) {
        if (tool.isDown) {
            tool.isDown = false
            KiddoPaint.Display.saveMain()
        }
    }
}

val KiddoPaint.Tools.Tree by world { ToolboxTree() }

/** "rgb(0," + g + ",0)" and "rgb(0," + g + ",20)" for g 0..255 (drawTree's colours). */
private val treeLeaf = Array(256) { g -> "rgb(0,$g,0)" }
private val treeWood = Array(256) { g -> "rgb(0,$g,20)" }

// https://github.com/PavlyukVadim/amadev/tree/master/RecursiveTree
fun drawTree(startX: Double, startY: Double, length: Double, angle: Double, depth: Int, branchWidthIn: Double) {
    fun rand() = KRandom.random() // (a local fun: a lambda would box every Double)
    var branchWidth = branchWidthIn
    val maxBranch = 3
    val maxAngle = 2 * PI / 6

    KiddoPaint.Display.context.beginPath()
    KiddoPaint.Display.context.moveTo(startX, startY)
    val endX = startX + length * cos(angle)
    val endY = startY + length * sin(angle)
    KiddoPaint.Display.context.lineCap = "round"
    KiddoPaint.Display.context.lineWidth = branchWidth
    KiddoPaint.Display.context.lineTo(endX, endY)

    // native: the same strings, made once (treeLeaf/treeWood) instead of once per stroke
    if (depth <= 2) {
        KiddoPaint.Display.context.strokeStyle = treeLeaf[((rand() * 64) + 128).toInt()]
    } else {
        KiddoPaint.Display.context.strokeStyle = treeWood[((rand() * 64) + 64).toInt()]
    }
    KiddoPaint.Display.context.stroke()
    val newDepth = depth - 1

    if (newDepth == 0) {
        return
    }
    val subBranches = (rand() * (maxBranch - 1)) + 1
    branchWidth *= 0.7

    var i = 0
    while (i < subBranches) {
        val newAngle = angle + rand() * maxAngle - maxAngle * 0.5
        val newLength = length * (0.7 + rand() * 0.3)
        drawTree(endX, endY, newLength, newAngle, newDepth, branchWidth)
        i++
    }
}
