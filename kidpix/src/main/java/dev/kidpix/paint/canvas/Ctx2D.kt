package dev.kidpix.paint.canvas

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The HTML CanvasRenderingContext2D on android.graphics (software Skia on an ARGB_8888 bitmap).
 * Names and semantics follow the web so upstream JS translates line for line. Numeric arguments
 * take any Number so Int and Double literals mix like in JS.
 *
 * Paths are kept in device space (each point is transformed by the matrix current when it is
 * added, as the spec says) and mapped back to user space at fill/stroke time, so line widths and
 * patterns follow the transform current at fill/stroke time.
 */
class Ctx2D internal constructor(val canvas: KCanvas) {
    private var c = Canvas(canvas.bitmap)

    private class State {
        var fill: Any = 0xff000000.toInt()
        var stroke: Any = 0xff000000.toInt()
        var globalAlpha = 1.0
        var gco = "source-over"
        var lineWidth = 1.0
        var lineCap = "butt"
        var lineJoin = "miter"
        var miterLimit = 10.0
        var dash = DoubleArray(0)
        var dashOffset = 0.0
        var font = "10px sans-serif"
        var textAlign = "start"
        var textBaseline = "alphabetic"
        var shadowColor = 0
        var shadowBlur = 0.0
        var shadowOffsetX = 0.0
        var shadowOffsetY = 0.0
        var smoothing = true
        var smoothingQuality = "low"
        var filter = "none"
        val ctm = Matrix()
        /** clip() was called in this save level (dirty boxes: a clear may not clear everything). */
        var clipped = false
        /** ctm is known to be the identity (false is only "maybe not"). */
        var ident = true

        fun copy() = State().also { s ->
            s.fill = fill; s.stroke = stroke; s.globalAlpha = globalAlpha; s.gco = gco
            s.lineWidth = lineWidth; s.lineCap = lineCap; s.lineJoin = lineJoin; s.miterLimit = miterLimit
            s.dash = dash; s.dashOffset = dashOffset; s.font = font; s.textAlign = textAlign
            s.textBaseline = textBaseline; s.shadowColor = shadowColor; s.shadowBlur = shadowBlur
            s.shadowOffsetX = shadowOffsetX; s.shadowOffsetY = shadowOffsetY; s.smoothing = smoothing
            s.smoothingQuality = smoothingQuality; s.filter = filter; s.ctm.set(ctm); s.clipped = clipped; s.ident = ident
        }
    }

    private var st = State()
    private val stack = ArrayList<State>()
    // The current path (device space). Built lazily: a path that is still one moveTo and one
    // lineTo lives only in seg (a tree tap is 57,000 of them, and the batched stroke needs no
    // Path); anything that reads [path] gets it with those ops applied first.
    private val pathObj = Path()
    private var pathReset = true // pathObj may hold a previous path: reset before use
    private var pathLazy = 0      // leading ops (moveTo, lineTo) in seg not yet in pathObj
    private val path: Path
        get() {
            if (pathReset) { pathObj.reset(); pathReset = false }
            if (pathLazy >= 1) pathObj.moveTo(seg[0], seg[1])
            if (pathLazy >= 2) pathObj.lineTo(seg[2], seg[3])
            pathLazy = 0
            return pathObj
        }
    private var hasCurrent = false
    private var curX = 0f
    private var curY = 0f
    private var startX = 0f
    private var startY = 0f
    private val pts = FloatArray(8)

    // The path as one straight segment (device space), when that is all it is: such strokes are
    // rasterised like Chrome does (AaStroke). segN: points so far, -1 once the path is anything else.
    private var segN = 0
    private val seg = FloatArray(4)

    // The path in device space while it is only moveTo/lineTo (type 0 move, 1 line; x; y), for
    // incremental MSAA restrokes of a growing path. pathEpoch changes whenever the path restarts.
    private var ops = FloatArray(3 * 64)
    private var opsN = 0
    private var opsOk = true
    private var pathEpoch = 0
    private fun logOp(type: Float, x: Float, y: Float) {
        if (!opsOk) return
        if (3 * opsN + 3 > ops.size) ops = ops.copyOf(ops.size * 2)
        ops[3 * opsN] = type; ops[3 * opsN + 1] = x; ops[3 * opsN + 2] = y; opsN++
    }
    private fun resetOps() { opsN = 0; opsOk = true; pathEpoch++; histN = 0 }

    // Every op of the current path in device space since it started (type, then its points: 0 move
    // and 1 line x,y; 2 cubic 6; 3 quad 4; 4 close), so a checkpoint can keep the path and a
    // restore rebuild it exactly (log/EngineGraph.kt; tools may leave a path between actions).
    private var hist = FloatArray(96)
    private var histN = 0
    private fun hist(n: Int): Int {
        if (histN + n > hist.size) hist = hist.copyOf(maxOf(hist.size * 2, histN + n))
        val at = histN; histN += n; return at
    }
    private fun hist2(t: Float, x: Float, y: Float) { val i = hist(3); hist[i] = t; hist[i + 1] = x; hist[i + 2] = y }

    /** Debug (action log checks): the drawing state as one line. */
    fun debugState(): String = "stack ${stack.size} gco ${st.gco} alpha ${st.globalAlpha} fill ${st.fill.let { if (it is Int) Integer.toHexString(it) else it.javaClass.simpleName }} " +
        "stroke ${st.stroke.let { if (it is Int) Integer.toHexString(it) else it.javaClass.simpleName }} lw ${st.lineWidth} cap ${st.lineCap} join ${st.lineJoin} " +
        "font ${st.font} align ${st.textAlign} base ${st.textBaseline} smooth ${st.smoothing}/${st.smoothingQuality} filter ${st.filter} " +
        "shadow ${st.shadowColor}/${st.shadowBlur} dash ${st.dash.size} ident ${st.ident} ctm ${st.ctm} clip ${st.clipped} path $segN/$opsN/$hasCurrent"

    /**
     * The drawing state (and the save() stack) as JSON, for the action log's checkpoints: tools
     * leave state on the shared contexts between actions. Colours are kept; a pattern or gradient
     * style is not (null: the default stays); clips are not; the path is not.
     */
    fun exportState(): org.json.JSONObject {
        fun one(x: State): org.json.JSONObject {
            val m = FloatArray(9); x.ctm.getValues(m)
            return org.json.JSONObject().put("fill", (x.fill as? Int) ?: org.json.JSONObject.NULL).put("stroke", (x.stroke as? Int) ?: org.json.JSONObject.NULL)
                .put("alpha", x.globalAlpha).put("gco", x.gco).put("lw", x.lineWidth).put("cap", x.lineCap).put("join", x.lineJoin)
                .put("miter", x.miterLimit).put("dash", org.json.JSONArray(x.dash.toList())).put("dashOffset", x.dashOffset).put("font", x.font)
                .put("align", x.textAlign).put("base", x.textBaseline).put("shadowColor", x.shadowColor).put("shadowBlur", x.shadowBlur)
                .put("shadowX", x.shadowOffsetX).put("shadowY", x.shadowOffsetY).put("smooth", x.smoothing).put("quality", x.smoothingQuality)
                .put("filter", x.filter).put("ctm", org.json.JSONArray(m.map { it.toDouble() })).put("ident", x.ident)
        }
        return org.json.JSONObject().put("st", one(st)).put("stack", org.json.JSONArray(stack.map { one(it) }))
    }

    fun importState(o: org.json.JSONObject) {
        fun one(j: org.json.JSONObject): State = State().also { x ->
            if (!j.isNull("fill")) x.fill = j.getInt("fill")
            if (!j.isNull("stroke")) x.stroke = j.getInt("stroke")
            x.globalAlpha = j.getDouble("alpha"); x.gco = j.getString("gco"); x.lineWidth = j.getDouble("lw")
            x.lineCap = j.getString("cap"); x.lineJoin = j.getString("join"); x.miterLimit = j.getDouble("miter")
            x.dash = j.getJSONArray("dash").let { a -> DoubleArray(a.length()) { a.getDouble(it) } }; x.dashOffset = j.getDouble("dashOffset")
            x.font = j.getString("font"); x.textAlign = j.getString("align"); x.textBaseline = j.getString("base")
            x.shadowColor = j.getInt("shadowColor"); x.shadowBlur = j.getDouble("shadowBlur")
            x.shadowOffsetX = j.getDouble("shadowX"); x.shadowOffsetY = j.getDouble("shadowY")
            x.smoothing = j.getBoolean("smooth"); x.smoothingQuality = j.getString("quality"); x.filter = j.getString("filter")
            x.ctm.setValues(j.getJSONArray("ctm").let { a -> FloatArray(9) { a.getDouble(it).toFloat() } }); x.ident = j.getBoolean("ident")
        }
        while (stack.isNotEmpty()) restore()
        val sa = o.getJSONArray("stack")
        for (i in 0 until sa.length()) { stack.add(one(sa.getJSONObject(i))); c.save() }
        st = one(o.getJSONObject("st"))
    }

    /**
     * The drawing state for a checkpoint (log/EngineGraph.kt stores it generically): the state and
     * its save() stack (styles with patterns and gradients), the current path as its device ops.
     * [unstorable]: why it cannot be kept exactly (a clip is set: no tool leaves one between actions).
     */
    class GraphState(val st: Any, val stack: List<Any>, val path: Any?, val unstorable: String? = null)

    fun graphState(): GraphState = GraphState(st, ArrayList<Any>(stack), arrayOf<Any?>(hist.copyOf(histN), pathEpoch),
        if (st.clipped || stack.any { it.clipped }) "clip" else null)

    /** The multisampled strokes' samples (MsaaSurface.exportSamples), empty when there are none. */
    fun msaaSamples(): IntArray = msaa?.exportSamples() ?: IntArray(0)

    /** Puts back [msaaSamples] (the surface starts fresh with them). */
    fun setMsaaSamples(a: IntArray) {
        msaa = null; msaaLast = null
        if (a.isNotEmpty()) MsaaSurface(canvas.bitmap.width, canvas.bitmap.height).also { msaa = it }.importSamples(a)
    }

    /** Puts back a [graphState] (its State objects become this context's). */
    fun setGraphState(g: GraphState) {
        while (stack.isNotEmpty()) restore()
        for (x in g.stack) { stack.add(x as State); c.save() }
        st = g.st as State
        beginPath()
        val p = g.path as Array<*>
        val ops = p[0] as FloatArray
        var i = 0
        while (i < ops.size) {
            when (ops[i].toInt()) {
                0 -> { devMove(ops[i + 1], ops[i + 2]); i += 3 }
                1 -> { devLine(ops[i + 1], ops[i + 2]); i += 3 }
                2 -> { devCubic(ops.copyOfRange(i + 1, i + 7)); i += 7 }
                3 -> { devQuad(ops.copyOfRange(i + 1, i + 5)); i += 5 }
                else -> { closePath(); i += 1 }
            }
        }
        pathEpoch = p[1] as Int
    }

    /** Called by KCanvas when its size is set: new bitmap, default state, empty path. */
    internal fun rebind() {
        c = Canvas(canvas.bitmap)
        st = State(); stack.clear(); pathReset = true; pathLazy = 0; hasCurrent = false; segN = 0; msaa = null; resetOps(); msaaLast = null
    }

    // ------------------------------------------------------------------ style properties

    /** A CSS color string, CanvasPattern or CanvasGradient. Invalid values (and null) are ignored. */
    var fillStyle: Any?
        get() = styleOut(st.fill)
        set(v) { styleIn(v)?.let { st.fill = it } }

    var strokeStyle: Any?
        get() = styleOut(st.stroke)
        set(v) { styleIn(v)?.let { st.stroke = it } }

    private fun styleIn(v: Any?): Any? = when (v) {
        is String -> CssColor.parse(v)
        is CanvasPattern, is CanvasGradient -> v
        is Int -> v
        else -> null
    }

    private fun styleOut(v: Any): Any = if (v is Int) CssColor.serialize(v) else v

    var globalAlpha: Double
        get() = st.globalAlpha
        set(v) { if (v in 0.0..1.0) st.globalAlpha = v }

    fun setGlobalAlpha(v: Number) { globalAlpha = v.toDouble() }

    var globalCompositeOperation: String
        get() = st.gco
        set(v) { if (Gco.modes.containsKey(v)) st.gco = v }

    var lineWidth: Double
        get() = st.lineWidth
        set(v) { if (v > 0 && v.isFinite()) st.lineWidth = v }

    var lineCap: String
        get() = st.lineCap
        set(v) { if (v == "butt" || v == "round" || v == "square") st.lineCap = v }

    var lineJoin: String
        get() = st.lineJoin
        set(v) { if (v == "miter" || v == "round" || v == "bevel") st.lineJoin = v }

    var miterLimit: Double
        get() = st.miterLimit
        set(v) { if (v > 0 && v.isFinite()) st.miterLimit = v }

    var lineDashOffset: Double
        get() = st.dashOffset
        set(v) { if (v.isFinite()) st.dashOffset = v }

    fun setLineDash(segments: List<Number>) {
        val d = segments.map { it.toDouble() }
        if (d.any { it < 0 || !it.isFinite() }) return
        st.dash = (if (d.size % 2 == 1) d + d else d).toDoubleArray()
    }

    fun getLineDash(): List<Double> = st.dash.toList()

    var font: String
        get() = st.font
        set(v) { if (parseFont(v) != null) st.font = v }

    var textAlign: String
        get() = st.textAlign
        set(v) { if (v in setOf("start", "end", "left", "right", "center")) st.textAlign = v }

    var textBaseline: String
        get() = st.textBaseline
        set(v) { if (v in setOf("top", "hanging", "middle", "alphabetic", "ideographic", "bottom")) st.textBaseline = v }

    var shadowColor: String
        get() = CssColor.serialize(st.shadowColor)
        set(v) { CssColor.parse(v)?.let { st.shadowColor = it } }

    var shadowBlur: Double
        get() = st.shadowBlur
        set(v) { if (v >= 0 && v.isFinite()) st.shadowBlur = v }

    var shadowOffsetX: Double
        get() = st.shadowOffsetX
        set(v) { if (v.isFinite()) st.shadowOffsetX = v }

    var shadowOffsetY: Double
        get() = st.shadowOffsetY
        set(v) { if (v.isFinite()) st.shadowOffsetY = v }

    var imageSmoothingEnabled: Boolean
        get() = st.smoothing
        set(v) { st.smoothing = v }

    var imageSmoothingQuality: String
        get() = st.smoothingQuality
        set(v) { st.smoothingQuality = v }

    /** CSS filter. Only "none" and "blur(Npx)" have an effect (applied as a mask blur). */
    var filter: String
        get() = st.filter
        set(v) { st.filter = v }

    // ------------------------------------------------------------------ state + transforms

    fun save() {
        stack.add(st.copy()); c.save()
    }

    fun restore() {
        if (stack.isEmpty()) return
        st = stack.removeAt(stack.size - 1); c.restore()
    }

    fun translate(x: Number, y: Number) { st.ctm.preTranslate(x.toFloat(), y.toFloat()); st.ident = false }
    fun rotate(angle: Number) { st.ctm.preRotate((angle.toDouble() * 180.0 / PI).toFloat()); st.ident = false }
    fun scale(x: Number, y: Number) { st.ctm.preScale(x.toFloat(), y.toFloat()); st.ident = false }

    fun transform(a: Number, b: Number, cc: Number, d: Number, e: Number, f: Number) {
        st.ctm.preConcat(matrixOf(a, b, cc, d, e, f)); st.ident = false
    }

    fun setTransform(a: Number, b: Number, cc: Number, d: Number, e: Number, f: Number) {
        st.ctm.set(matrixOf(a, b, cc, d, e, f)); st.ident = st.ctm.isIdentity
    }

    fun setTransform() = resetTransform()
    fun resetTransform() { st.ctm.reset(); st.ident = true }

    /** [a, b, c, d, e, f] like DOMMatrix. */
    fun getTransform(): DoubleArray {
        val v = FloatArray(9); st.ctm.getValues(v)
        return doubleArrayOf(v[0].toDouble(), v[3].toDouble(), v[1].toDouble(), v[4].toDouble(), v[2].toDouble(), v[5].toDouble())
    }

    private fun matrixOf(a: Number, b: Number, cc: Number, d: Number, e: Number, f: Number) = Matrix().apply {
        setValues(floatArrayOf(a.toFloat(), cc.toFloat(), e.toFloat(), b.toFloat(), d.toFloat(), f.toFloat(), 0f, 0f, 1f))
    }

    // ------------------------------------------------------------------ paths

    fun beginPath() { pathReset = true; pathLazy = 0; hasCurrent = false; segN = 0; resetOps() }

    fun closePath() {
        if (!hasCurrent) return
        path.close(); curX = startX; curY = startY; segN = -1; opsOk = false
        val hi = hist(1); hist[hi] = 4f
    }

    private fun map(x: Double, y: Double) {
        pts[0] = x.toFloat(); pts[1] = y.toFloat(); if (!st.ident) st.ctm.mapPoints(pts, 0, pts, 0, 1)
    }

    fun moveTo(x: Number, y: Number) = moveTo(x.toDouble(), y.toDouble())
    fun lineTo(x: Number, y: Number) = lineTo(x.toDouble(), y.toDouble())

    // Double overloads: no boxing for the common call (a tree is 57,000 moveTo/lineTo pairs)
    fun moveTo(xd: Double, yd: Double) {
        if (!xd.isFinite() || !yd.isFinite()) return
        map(xd, yd); devMove(pts[0], pts[1])
    }

    fun lineTo(xd: Double, yd: Double) {
        if (!xd.isFinite() || !yd.isFinite()) return
        map(xd, yd)
        if (!hasCurrent) devMove(pts[0], pts[1]) else devLine(pts[0], pts[1])
    }

    private fun devMove(x: Float, y: Float) {
        val lazy = segN == 0 && pathReset && pathLazy == 0
        if (!lazy) path.moveTo(x, y)
        hasCurrent = true; curX = x; curY = y; startX = x; startY = y; logOp(0f, x, y); hist2(0f, x, y)
        if (segN == 0) { seg[0] = x; seg[1] = y; segN = 1 } else segN = -1
        if (lazy) pathLazy = 1
    }

    private fun devLine(x: Float, y: Float) {
        val lazy = segN == 1 && pathLazy == 1
        if (!lazy) path.lineTo(x, y)
        curX = x; curY = y; logOp(1f, x, y); hist2(1f, x, y)
        if (segN == 1) { seg[2] = x; seg[3] = y; segN = 2 } else segN = -1
        if (lazy) pathLazy = 2
    }

    fun bezierCurveTo(cp1x: Number, cp1y: Number, cp2x: Number, cp2y: Number, x: Number, y: Number) {
        val p = floatArrayOf(cp1x.toFloat(), cp1y.toFloat(), cp2x.toFloat(), cp2y.toFloat(), x.toFloat(), y.toFloat())
        if (p.any { !it.isFinite() }) return
        st.ctm.mapPoints(p)
        if (!hasCurrent) devMove(p[0], p[1])
        devCubic(p)
    }

    private fun devCubic(p: FloatArray) {
        path.cubicTo(p[0], p[1], p[2], p[3], p[4], p[5]); curX = p[4]; curY = p[5]; segN = -1; opsOk = false
        val i = hist(7); hist[i] = 2f; System.arraycopy(p, 0, hist, i + 1, 6)
    }

    private fun devQuad(p: FloatArray) {
        devQuad(p)
        val i = hist(5); hist[i] = 3f; System.arraycopy(p, 0, hist, i + 1, 4)
    }

    fun quadraticCurveTo(cpx: Number, cpy: Number, x: Number, y: Number) {
        val p = floatArrayOf(cpx.toFloat(), cpy.toFloat(), x.toFloat(), y.toFloat())
        if (p.any { !it.isFinite() }) return
        st.ctm.mapPoints(p)
        if (!hasCurrent) devMove(p[0], p[1])
        devQuad(p)
    }

    fun rect(x: Number, y: Number, w: Number, h: Number) {
        val xd = x.toDouble(); val yd = y.toDouble(); val wd = w.toDouble(); val hd = h.toDouble()
        if (!(xd.isFinite() && yd.isFinite() && wd.isFinite() && hd.isFinite())) return
        moveTo(xd, yd); lineTo(xd + wd, yd); lineTo(xd + wd, yd + hd); lineTo(xd, yd + hd); closePath()
        moveTo(xd, yd)
    }

    fun arc(x: Number, y: Number, radius: Number, startAngle: Number, endAngle: Number, anticlockwise: Boolean = false) =
        ellipse(x, y, radius, radius, 0, startAngle, endAngle, anticlockwise)

    fun ellipse(
        x: Number, y: Number, radiusX: Number, radiusY: Number, rotation: Number,
        startAngle: Number, endAngle: Number, anticlockwise: Boolean = false,
    ) {
        val cx = x.toDouble(); val cy = y.toDouble(); val rx = radiusX.toDouble(); val ry = radiusY.toDouble()
        val rot = rotation.toDouble(); var sa = startAngle.toDouble(); val ea0 = endAngle.toDouble()
        if (!(cx.isFinite() && cy.isFinite() && rx.isFinite() && ry.isFinite() && rot.isFinite() && sa.isFinite() && ea0.isFinite())) return
        require(rx >= 0 && ry >= 0) { "IndexSizeError: negative radius" }
        // Chrome's canonicalization (third_party/blink canvas_path.cc)
        val twoPi = 2 * PI
        var ea = adjustEndAngle(sa, ea0, anticlockwise)
        val newSa = sa % twoPi
        val shift = sa - (if (newSa < 0) newSa + twoPi else newSa)
        sa -= shift; ea -= shift
        val sweep = ea - sa
        val cosR = cos(rot); val sinR = sin(rot)
        fun pt(a: Double): DoubleArray {
            val ex = rx * cos(a); val ey = ry * sin(a)
            return doubleArrayOf(cx + ex * cosR - ey * sinR, cy + ex * sinR + ey * cosR)
        }
        val p0 = pt(sa)
        lineTo(p0[0], p0[1])
        if (sweep == 0.0) return
        val n = max(1, ceil(abs(sweep) / (PI / 2) - 1e-9).toInt())
        val step = sweep / n
        val k = 4.0 / 3.0 * kotlin.math.tan(step / 4)
        var a0 = sa
        for (i in 0 until n) {
            val a1 = a0 + step
            // derivative of the ellipse point at angle a: (-rx sin a, ry cos a) rotated
            fun d(a: Double): DoubleArray {
                val dx = -rx * sin(a); val dy = ry * cos(a)
                return doubleArrayOf(dx * cosR - dy * sinR, dx * sinR + dy * cosR)
            }
            val s = pt(a0); val e = pt(a1); val d0 = d(a0); val d1 = d(a1)
            bezierCurveTo(s[0] + k * d0[0], s[1] + k * d0[1], e[0] - k * d1[0], e[1] - k * d1[1], e[0], e[1])
            a0 = a1
        }
    }

    private fun adjustEndAngle(start: Double, end: Double, ccw: Boolean): Double {
        val twoPi = 2 * PI
        return when {
            !ccw && end - start >= twoPi -> start + twoPi
            ccw && start - end >= twoPi -> start - twoPi
            !ccw && start > end -> start + (twoPi - ((start - end) % twoPi))
            ccw && start < end -> start - (twoPi - ((end - start) % twoPi))
            else -> end
        }
    }

    fun arcTo(x1: Number, y1: Number, x2: Number, y2: Number, radius: Number) {
        // rarely used upstream; approximate via android arcTo in device space when untransformed
        val p = floatArrayOf(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat())
        if (!hasCurrent) { moveTo(x1, y1); return }
        val inv = Matrix(); if (!st.ctm.invert(inv)) return
        val cur = floatArrayOf(curX, curY); inv.mapPoints(cur)
        val x0 = cur[0].toDouble(); val y0 = cur[1].toDouble()
        val ax = p[0] - x0; val ay = p[1] - y0; val bx = p[2] - p[0].toDouble(); val by = p[3] - p[1].toDouble()
        val r = radius.toDouble()
        val la = kotlin.math.hypot(ax, ay); val lb = kotlin.math.hypot(bx, by)
        val cross = ax * by - ay * bx
        if (r == 0.0 || la == 0.0 || lb == 0.0 || abs(cross) < 1e-9) { lineTo(x1, y1); return }
        val cosT = -(ax * bx + ay * by) / (la * lb)
        val theta = kotlin.math.acos(cosT.coerceIn(-1.0, 1.0))
        val dist = r / kotlin.math.tan(theta / 2)
        val t1x = p[0] - ax / la * dist; val t1y = p[1] - ay / la * dist
        val t2x = p[0] + bx / lb * dist; val t2y = p[1] + by / lb * dist
        val sign = if (cross > 0) 1 else -1
        val ncx = t1x + (-ay / la) * r * sign; val ncy = t1y + (ax / la) * r * sign
        val aStart = kotlin.math.atan2(t1y - ncy, t1x - ncx)
        val aEnd = kotlin.math.atan2(t2y - ncy, t2x - ncx)
        lineTo(t1x, t1y)
        arc(ncx, ncy, r, aStart, aEnd, cross < 0)
    }

    /** The current path in user space (for fill/stroke/clip with the current matrix). */
    private fun userPath(rule: String = "nonzero"): Path? {
        val inv = Matrix()
        if (!st.ctm.invert(inv)) return null
        val p = Path(path)
        p.transform(inv)
        p.fillType = if (rule == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
        return p
    }

    fun fill(rule: String = "nonzero") {
        val p = userPath(rule) ?: return
        val paint = stylePaint(st.fill, Paint.Style.FILL) ?: return
        render(paint, devicePathBounds(), 0.0, device = true) { c.drawPath(p, it) }
    }

    fun stroke() {
        if (batchDepth > 0 && strokeBatchedSegment()) return
        val p = userPath() ?: return
        val paint = stylePaint(st.stroke, Paint.Style.STROKE) ?: return
        if (strokeLikeChrome(paint)) return
        render(paint, devicePathBounds(), strokePad(), device = true) { c.drawPath(p, it) }
    }

    /**
     * Strokes on a canvas Chrome GPU-accelerates, rasterised the way Chrome does (AaStroke,
     * MsaaSurface): one straight segment with butt/square caps at least 1 px wide gets analytic
     * coverage; every other stroke (round caps, polylines, curves, dashes, under 1 px) is 4x MSAA.
     * Needs a similarity transform and no shadow/filter; analytic with a pattern/gradient needs the
     * identity transform (its device-space mask would move them); MSAA needs source-over, no clip.
     * False: not handled, Skia draws it.
     */
    private fun strokeLikeChrome(paint: Paint): Boolean {
        // Chrome rasterises small canvases on the CPU (Blink: area < kMinimumAccelerated2dCanvasSize,
        // 128 * 129), where Skia draws as here; brush-14-swirl's 50x50 brush shows the CPU hairline.
        if (canvas.bitmap.width.toLong() * canvas.bitmap.height < 128L * 129) return false
        if (!chromeStrokes || draft || path.isEmpty) return false
        if (paint.maskFilter != null || paint.colorFilter != null) return false
        if ((st.shadowColor ushr 24) != 0 && (st.shadowBlur != 0.0 || st.shadowOffsetX != 0.0 || st.shadowOffsetY != 0.0)) return false
        if (st.filter != "none" && st.filter.isNotEmpty()) return false
        val v = FloatArray(9); st.ctm.getValues(v)
        if (v[Matrix.MPERSP_0] != 0f || v[Matrix.MPERSP_1] != 0f) return false
        val a = v[Matrix.MSCALE_X].toDouble(); val b = v[Matrix.MSKEW_Y].toDouble()
        val cc = v[Matrix.MSKEW_X].toDouble(); val d = v[Matrix.MSCALE_Y].toDouble()
        val sx = kotlin.math.hypot(a, b); val sy = kotlin.math.hypot(cc, d)
        if (abs(sx - sy) > 1e-6 * max(sx, 1.0) || abs(a * cc + b * d) > 1e-6 * max(sx * sy, 1.0)) return false
        val dw = st.lineWidth * sx
        val dashed = paint.pathEffect != null
        val segLen = if (segN == 2) kotlin.math.hypot((seg[2] - seg[0]).toDouble(), (seg[3] - seg[1]).toDouble()) else 0.0
        if (segN == 2 && segLen >= 1e-6 && !dashed && st.lineCap != "round" && dw >= 1.0) {
            if (paint.shader != null && !st.ctm.isIdentity) return false
            strokeAnalytic(paint, dw)
            return true
        }
        if (st.gco != "source-over") return false
        if (dashed && !st.ctm.isIdentity) return false
        if (paint.shader != null) flushBatch()
        c.setMatrix(null)
        val clipR = Rect()
        if (!c.getClipBounds(clipR) || clipR != Rect(0, 0, canvas.bitmap.width, canvas.bitmap.height)) return false
        val sp = Paint(paint).apply { strokeWidth = dw.toFloat() }
        // one thin straight segment (all but the trunk of a tree: some 57,000 strokes a tap):
        // sampled directly, no outline (MsaaSurface.fillSegment). Wider round caps keep Skia's
        // outline: on those the exact circle's difference shows (the pencil moves by up to 0.006).
        if (segN == 2 && !dashed && segLen >= 1e-6 && (st.lineCap != "round" || dw < THIN_ROUND)) {
            paint.style = Paint.Style.FILL
            val surf = msaa ?: MsaaSurface(canvas.bitmap.width, canvas.bitmap.height).also { msaa = it }
            canvas.beforeDraw()
            surf.fillSegment(canvas.bitmap, c, seg[0].toDouble(), seg[1].toDouble(), seg[2].toDouble(), seg[3].toDouble(), dw / 2, st.lineCap, paint, st.ctm)
            if (KCanvas.trackDirty) {
                val r = (dw / 2 + 2).toFloat()
                canvas.markDirty(min(seg[0], seg[2]) - r, min(seg[1], seg[3]) - r, max(seg[0], seg[2]) + r, max(seg[1], seg[3]) + r)
            }
            msaaLast = MsaaLast(pathEpoch, opsN, canvas.bitmap.generationId, st.stroke, dw.toFloat(), sp.strokeCap, sp.strokeJoin, sp.strokeMiter, v, st.smoothing)
            return true
        }
        val now = MsaaLast(pathEpoch, opsN, 0, st.stroke, dw.toFloat(), sp.strokeCap, sp.strokeJoin, sp.strokeMiter, v, st.smoothing)
        val last = msaaLast
        msaaLast = null
        val grown = msaaIncremental && !dashed && opsOk && opsN >= 1 && last != null && last.continuedBy(now, canvas.bitmap.generationId) && paintsOpaque()
        if (grown && last!!.n == opsN) { msaaLast = now.copy(gen = canvas.bitmap.generationId); return true }
        val outline = Path()
        sp.getFillPath(path, outline)
        if (outline.isEmpty) return true
        paint.style = Paint.Style.FILL; paint.pathEffect = null
        val surf = msaa ?: MsaaSurface(canvas.bitmap.width, canvas.bitmap.height).also { msaa = it }
        canvas.beforeDraw()
        if (grown) surf.fill(canvas.bitmap, c, outline, paint, st.ctm, newPartWindows(last!!.n, sp))
        else surf.fill(canvas.bitmap, c, outline, paint, st.ctm)
        if (!dashed && opsOk) msaaLast = now.copy(gen = canvas.bitmap.generationId)
        // source-over, no shadow or filter here: the outline's device box (+1, MsaaSurface's margin)
        if (KCanvas.trackDirty) { outline.computeBounds(tb, true); canvas.markDirty(tb.left - 1, tb.top - 1, tb.right + 1, tb.bottom + 1) }
        return true
    }

    /**
     * Inside [batchStrokes]: one thin straight segment in a plain colour, identity transform, no
     * clip/shadow/filter/dash, source-over (exactly what strokeLikeChrome sends to
     * MsaaSurface.fillSegment), done without a Paint, a Path copy or a matrix. False: not such a
     * stroke, take the general way.
     */
    private fun strokeBatchedSegment(): Boolean {
        if (segN != 2 || !st.ident || st.clipped || !chromeStrokes) return false
        val style = st.stroke as? Int ?: (st.stroke as? CanvasPattern)?.solidColor ?: return false
        if (st.gco != "source-over" || (st.filter != "none" && st.filter.isNotEmpty())) return false
        if ((st.shadowColor ushr 24) != 0 && (st.shadowBlur != 0.0 || st.shadowOffsetX != 0.0 || st.shadowOffsetY != 0.0)) return false
        if (st.dash.isNotEmpty() && st.dash.any { it > 0 }) return false
        val bmp = canvas.bitmap
        if (bmp.width.toLong() * bmp.height < 128L * 129) return false
        val dw = st.lineWidth
        if (st.lineCap == "round") { if (dw >= THIN_ROUND) return false } else if (dw >= 1.0) return false
        val x0 = seg[0].toDouble(); val y0 = seg[1].toDouble(); val x1 = seg[2].toDouble(); val y1 = seg[3].toDouble()
        if (kotlin.math.hypot(x1 - x0, y1 - y0) < 1e-6) return false
        val a = ((style ushr 24) * st.globalAlpha + 0.5).toInt()
        val color = (style and 0xffffff) or (a shl 24)
        val surf = msaa ?: MsaaSurface(bmp.width, bmp.height).also { msaa = it }
        canvas.beforeDraw()
        if (!surf.fillSegmentBatched(bmp, x0, y0, x1, y1, dw / 2, st.lineCap, color)) return false
        if (KCanvas.trackDirty) {
            val r = (dw / 2 + 2).toFloat()
            canvas.markDirty(min(seg[0], seg[2]) - r, min(seg[1], seg[3]) - r, max(seg[0], seg[2]) + r, max(seg[1], seg[3]) + r)
        }
        msaaLast = null
        return true
    }

    /**
     * Preview strokes (line and oval while dragging): plain Skia anti-aliasing instead of the
     * Chrome-like AaStroke/MSAA rasterisation. Cost follows the stroke's outline, not its box; the
     * edge pixels differ slightly from the final stroke, which tools draw with this off.
     */
    var draft = false

    /** The multisampled surface for MSAA strokes (created on the first one; dropped on resize). */
    private var msaa: MsaaSurface? = null
        set(v) { field = v; v?.batching = batchDepth > 0; v?.batchRegion = batchRegion }

    private var batchDepth = 0
    private var batchRegion: Rect? = null

    /**
     * Runs [block] with this canvas's MSAA strokes of a plain colour done on a copy of its pixels,
     * written back once at the end (the tree brush: some 57,000 twigs a tap, each otherwise a crop,
     * a read and a write of the bitmap). Same pixels as without. Anything else that draws on or
     * reads this canvas inside the block (other strokes, fills, images, pixels, drawing it
     * elsewhere) writes the batch back first. Code inside must not touch [KCanvas.bitmap] directly.
     */
    fun <T> batchStrokes(block: () -> T): T = batchStrokes(null, block)

    /** [batchStrokes] whose strokes all fall inside [region] (device px): only that part is copied. */
    fun <T> batchStrokes(region: Rect?, block: () -> T): T {
        batchDepth++
        if (batchDepth == 1) batchRegion = region
        msaa?.batching = true
        msaa?.batchRegion = batchRegion
        val t0 = if (batchTimer != null) System.nanoTime() else 0L
        try {
            return block()
        } finally {
            if (--batchDepth == 0) {
                val t1 = if (batchTimer != null) System.nanoTime() else 0L
                flushBatch(); msaa?.batching = false
                batchRegion = null; msaa?.batchRegion = null
                batchTimer?.let { it(t1 - t0, System.nanoTime() - t1) }
            }
        }
    }

    /** Writes pending batched strokes back to the bitmap (no-op when there are none). */
    internal fun flushBatch() {
        val r = msaa?.flush(canvas.bitmap) ?: return
        msaaLast = null
        canvas.markDirty(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    /**
     * The last MSAA stroke on this canvas, so that restroking a path that has only grown since
     * (brush-01-zig-zag and brush-12-kaleidoscope stroke their whole path on every move) can
     * touch just the pixels around the new part (newPartWindows). With a colour that paints covered
     * samples fully, the samples the old part covers already hold that colour, so stroking them
     * again changes nothing: the result is the same as stroking everything, and the per-move cost
     * no longer grows with the bounding box of the whole scribble.
     * Valid only while nothing else has written the bitmap (generation id) and the path, style,
     * width, caps/joins and matrix are unchanged.
     */
    private data class MsaaLast(
        val epoch: Int, val n: Int, val gen: Int, val style: Any, val dw: Float, val cap: Paint.Cap,
        val join: Paint.Join, val miter: Float, val ctm: FloatArray, val smoothing: Boolean,
    ) {
        fun continuedBy(o: MsaaLast, generation: Int): Boolean {
            if (o.epoch != epoch || o.n < n || n < 1 || gen != generation) return false
            if (o.dw != dw || o.cap != cap || o.join != join || o.miter != miter || o.smoothing != smoothing) return false
            return when (style) {
                is Int -> o.style == style
                is CanvasPattern -> o.style is CanvasPattern && style.sameAs(o.style) && ctm.contentEquals(o.ctm)
                else -> false
            }
        }
    }
    private var msaaLast: MsaaLast? = null

    /** The stroke style covers every sample it touches fully (opaque colour or opaque repeating pattern). */
    private fun paintsOpaque(): Boolean = st.globalAlpha >= 1.0 && when (val s = st.stroke) {
        is Int -> (s ushr 24) == 255
        is CanvasPattern -> s.opaqueRepeat
        else -> false
    }

    /**
     * Device boxes around the segments added since [oldN] ops (each from the point before it),
     * grown by how far a join or cap can reach: the whole path's outline differs from the one
     * stroked last time only inside them (new segments, the old end cap become a join). Outside
     * them the coverage is the same as last time, so those samples already hold the colour.
     * Overlapping boxes are merged.
     */
    private fun newPartWindows(oldN: Int, sp: Paint): List<Rect> {
        val hw = sp.strokeWidth / 2
        val reach = (if (sp.strokeJoin == Paint.Join.MITER) hw * max(1f, sp.strokeMiter) else hw) + 3f
        val out = ArrayList<Rect>()
        for (k in oldN until opsN) {
            if (ops[3 * k] != 1f) continue
            val x0 = ops[3 * k - 2]; val y0 = ops[3 * k - 1]; val x1 = ops[3 * k + 1]; val y1 = ops[3 * k + 2]
            val r = Rect(floor(min(x0, x1) - reach).toInt(), floor(min(y0, y1) - reach).toInt(),
                ceil(max(x0, x1) + reach).toInt(), ceil(max(y0, y1) + reach).toInt())
            var merged = true
            while (merged) {
                merged = false
                val it = out.iterator()
                while (it.hasNext()) { val o = it.next(); if (Rect.intersects(o, r)) { r.union(o); it.remove(); merged = true } }
            }
            out.add(r)
        }
        return out
    }

    private fun strokeAnalytic(paint: Paint, dw: Double) {
        if (!AaStroke.mayCover(seg[0].toDouble(), seg[1].toDouble(), seg[2].toDouble(), seg[3].toDouble(), dw, st.lineCap, canvas.bitmap.width, canvas.bitmap.height)) return
        paint.style = Paint.Style.FILL
        val style = paint.shader
        val mb = RectF() // the bands drawn (render marks it dirty after drawing)
        // one render (one layer for the unbounded composites) for all the bands
        render(paint, mb, 0.0, device = true) { p ->
            c.setMatrix(null)
            AaStroke.bands(seg[0].toDouble(), seg[1].toDouble(), seg[2].toDouble(), seg[3].toDouble(),
                dw, st.lineCap, canvas.bitmap.width, canvas.bitmap.height) { l, t, w, h, m ->
                mb.union(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat())
                if (style == null) {
                    // the band only (the scratch is 0 beyond it), drawn as a sprite like a bitmap of its own
                    c.save(); c.clipRect(l, t, l + w, t + h)
                    c.drawBitmap(m, l.toFloat(), t.toFloat(), p)
                    c.restore()
                } else {
                    // drawBitmap(x, y) would move the pattern by (x, y): multiply the pattern by a mask
                    // shader instead and fill the band's rectangle in device space (ctm is identity here).
                    val sh = android.graphics.BitmapShader(m, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
                    sh.setLocalMatrix(Matrix().apply { setTranslate(l.toFloat(), t.toFloat()) })
                    p.shader = android.graphics.ComposeShader(style, sh, BlendMode.DST_IN)
                    c.drawRect(l.toFloat(), t.toFloat(), (l + w).toFloat(), (t + h).toFloat(), p)
                }
            }
        }
    }

    fun clip(rule: String = "nonzero") {
        val p = userPath(rule) ?: return
        c.setMatrix(st.ctm)
        c.clipPath(p)
        st.clipped = true
    }

    fun isPointInPath(x: Number, y: Number, rule: String = "nonzero"): Boolean {
        val p = Path(path)
        p.fillType = if (rule == "evenodd") Path.FillType.EVEN_ODD else Path.FillType.WINDING
        val r = android.graphics.Region(); val clipR = android.graphics.Region(-100000, -100000, 100000, 100000)
        r.setPath(p, clipR)
        return r.contains(x.toInt(), y.toInt())
    }

    // ------------------------------------------------------------------ rects

    fun fillRect(x: Number, y: Number, w: Number, h: Number) {
        val r = rectF(x, y, w, h) ?: return
        if (r.width() == 0f || r.height() == 0f) return
        val paint = stylePaint(st.fill, Paint.Style.FILL) ?: return
        render(paint, r, 0.0) { c.drawRect(r, it) }
    }

    fun strokeRect(x: Number, y: Number, w: Number, h: Number) {
        val r = rectF(x, y, w, h) ?: return
        val paint = stylePaint(st.stroke, Paint.Style.STROKE) ?: return
        if (r.width() == 0f && r.height() == 0f) return
        render(paint, r, strokePad()) {
            if (r.width() == 0f || r.height() == 0f) c.drawLine(r.left, r.top, r.right, r.bottom, it)
            else c.drawRect(r, it)
        }
    }

    fun clearRect(x: Number, y: Number, w: Number, h: Number) {
        val r = rectF(x, y, w, h) ?: return
        if (r.width() == 0f || r.height() == 0f) return
        flushBatch()
        canvas.beforeDraw()
        c.setMatrix(st.ctm)
        c.drawRect(r, clearPaint)
        if (KCanvas.trackDirty) {
            st.ctm.mapRect(tb, r)
            // anti-aliased edges of a fractional or rotated box are only partly cleared: ink stays there
            val exact = st.ctm.rectStaysRect() && tb.left == floor(tb.left) && tb.top == floor(tb.top) && tb.right == floor(tb.right) && tb.bottom == floor(tb.bottom)
            if (exact) canvas.markCleared(tb.left, tb.top, tb.right, tb.bottom, !st.clipped)
            else canvas.markCleared(tb.left - 2, tb.top - 2, tb.right + 2, tb.bottom + 2, false)
        }
    }

    private val clearPaint = Paint().apply { blendMode = BlendMode.CLEAR; isAntiAlias = true }

    private fun rectF(x: Number, y: Number, w: Number, h: Number): RectF? {
        val xd = x.toDouble(); val yd = y.toDouble(); val wd = w.toDouble(); val hd = h.toDouble()
        if (!(xd.isFinite() && yd.isFinite() && wd.isFinite() && hd.isFinite())) return null
        return RectF(min(xd, xd + wd).toFloat(), min(yd, yd + hd).toFloat(), max(xd, xd + wd).toFloat(), max(yd, yd + hd).toFloat())
    }

    // ------------------------------------------------------------------ images

    fun drawImage(image: CanvasImageSource, dx: Number, dy: Number) {
        val w = image.width; val h = image.height
        drawImage(image, 0, 0, w, h, dx, dy, w, h)
    }

    fun drawImage(image: CanvasImageSource, dx: Number, dy: Number, dw: Number, dh: Number) {
        drawImage(image, 0, 0, image.width, image.height, dx, dy, dw, dh)
    }

    fun drawImage(
        image: CanvasImageSource, sx: Number, sy: Number, sw: Number, sh: Number,
        dx: Number, dy: Number, dw: Number, dh: Number,
    ) {
        var bmp = image.sourceBitmap() ?: return
        var sxd = sx.toDouble(); var syd = sy.toDouble(); var swd = sw.toDouble(); var shd = sh.toDouble()
        var dxd = dx.toDouble(); var dyd = dy.toDouble(); var dwd = dw.toDouble(); var dhd = dh.toDouble()
        if (listOf(sxd, syd, swd, shd, dxd, dyd, dwd, dhd).any { !it.isFinite() }) return
        if (swd < 0) { sxd += swd; swd = -swd }
        if (shd < 0) { syd += shd; shd = -shd }
        if (dwd < 0) { dxd += dwd; dwd = -dwd }
        if (dhd < 0) { dyd += dhd; dhd = -dhd }
        if (swd == 0.0 || shd == 0.0 || dwd == 0.0 || dhd == 0.0) return
        // clip the source rect to the image, shrinking the destination proportionally
        val iw = image.width.toDouble(); val ih = image.height.toDouble()
        val scaleX = dwd / swd; val scaleY = dhd / shd
        if (sxd < 0) { dxd -= sxd * scaleX; dwd += sxd * scaleX; swd += sxd; sxd = 0.0 }
        if (syd < 0) { dyd -= syd * scaleY; dhd += syd * scaleY; shd += syd; syd = 0.0 }
        if (sxd + swd > iw) { val cut = sxd + swd - iw; swd -= cut; dwd -= cut * scaleX }
        if (syd + shd > ih) { val cut = syd + shd - ih; shd -= cut; dhd -= cut * scaleY }
        if (swd <= 0 || shd <= 0 || dwd <= 0 || dhd <= 0) return
        if (bmp === canvas.bitmap) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val paint = basePaint()
        paint.isFilterBitmap = st.smoothing
        paint.isAntiAlias = st.smoothing
        val dst = RectF(dxd.toFloat(), dyd.toFloat(), (dxd + dwd).toFloat(), (dyd + dhd).toFloat())
        val integral = sxd == floor(sxd) && syd == floor(syd) && swd == floor(swd) && shd == floor(shd)
        render(paint, dst, 0.0) { p ->
            if (integral) {
                c.drawBitmap(bmp, Rect(sxd.toInt(), syd.toInt(), (sxd + swd).toInt(), (syd + shd).toInt()), dst, p)
            } else {
                val m = Matrix()
                m.setRectToRect(RectF(sxd.toFloat(), syd.toFloat(), (sxd + swd).toFloat(), (syd + shd).toFloat()), dst, Matrix.ScaleToFit.FILL)
                c.save(); c.clipRect(dst); c.drawBitmap(bmp, m, p); c.restore()
            }
        }
    }

    /** JS sometimes draws a Bitmap directly (our KImage/KCanvas cover the rest). */
    fun drawImage(image: Bitmap, dx: Number, dy: Number) = drawImage(BitmapSource(image), dx, dy)
    fun drawImage(image: Bitmap, dx: Number, dy: Number, dw: Number, dh: Number) = drawImage(BitmapSource(image), dx, dy, dw, dh)

    // ------------------------------------------------------------------ pixels

    fun createImageData(w: Number, h: Number) = ImageData(abs(w.toInt()), abs(h.toInt()))
    fun createImageData(other: ImageData) = ImageData(other.width, other.height)

    fun getImageData(sx: Number, sy: Number, sw: Number, sh: Number): ImageData {
        // WebIDL [EnforceRange] long: fractions truncate toward zero (Chrome: getImageData(10.7, 0, 79.8, 1) is 79 wide from x 10)
        var x = sx.toDouble().toInt()
        var y = sy.toDouble().toInt()
        var w = sw.toDouble().toInt()
        var h = sh.toDouble().toInt()
        if (w < 0) { x += w; w = -w }
        if (h < 0) { y += h; h = -h }
        require(w > 0 && h > 0) { "IndexSizeError: getImageData size 0" }
        flushBatch()
        val bw = canvas.width; val bh = canvas.height
        val ix0 = max(0, x); val iy0 = max(0, y); val ix1 = min(bw, x + w); val iy1 = min(bh, y + h)
        if (ix0 == x && iy0 == y && ix1 == x + w && iy1 == y + h) {
            // all inside the canvas: keep the ARGB ints, bytes only if a tool reads them
            val px = IntArray(w * h)
            canvas.bitmap.getPixels(px, 0, w, x, y, w, h)
            return ImageData(w, h, Uint8Clamped(w * h * 4, px))
        }
        val out = ImageData(w, h)
        if (ix1 <= ix0 || iy1 <= iy0) return out
        val cw = ix1 - ix0; val ch = iy1 - iy0
        val px = IntArray(cw * ch)
        canvas.bitmap.getPixels(px, 0, cw, ix0, iy0, cw, ch)
        val b = out.data.bytes
        for (row in 0 until ch) {
            var j = ((iy0 - y + row) * w + (ix0 - x)) * 4
            var k = row * cw
            for (col in 0 until cw) {
                val cc = px[k++]
                b[j] = (cc shr 16).toByte(); b[j + 1] = (cc shr 8).toByte(); b[j + 2] = cc.toByte(); b[j + 3] = (cc ushr 24).toByte()
                j += 4
            }
        }
        return out
    }

    /**
     * clearRect of the whole canvas then drawImage(image, 0, 0) (source-over, alpha 1, no
     * transform or clip) in one copy: the same pixels when [image] is the canvas's size.
     */
    fun replaceWith(image: CanvasImageSource) {
        val bmp = image.sourceBitmap() ?: return
        flushBatch()
        canvas.beforeDraw()
        c.save(); c.setMatrix(null)
        c.drawBitmap(bmp, 0f, 0f, srcCopy)
        c.restore()
        canvas.markAllDirty()
    }
    private val srcCopy = Paint().apply { blendMode = BlendMode.SRC; isFilterBitmap = false }

    fun putImageData(img: ImageData, dx: Number, dy: Number) =
        putImageData(img, dx, dy, 0, 0, img.width, img.height)

    fun putImageData(img: ImageData, dx: Number, dy: Number, dirtyX: Number, dirtyY: Number, dirtyW: Number, dirtyH: Number) {
        val ox = dx.toDouble().toInt(); val oy = dy.toDouble().toInt()
        var drx = dirtyX.toInt(); var dry = dirtyY.toInt(); var drw = dirtyW.toInt(); var drh = dirtyH.toInt()
        if (drw < 0) { drx += drw; drw = -drw }
        if (drh < 0) { dry += drh; drh = -drh }
        if (drx < 0) { drw += drx; drx = 0 }
        if (dry < 0) { drh += dry; dry = 0 }
        if (drx + drw > img.width) drw = img.width - drx
        if (dry + drh > img.height) drh = img.height - dry
        // intersect with the canvas
        var x0 = ox + drx; var y0 = oy + dry; var x1 = x0 + drw; var y1 = y0 + drh
        x0 = max(0, x0); y0 = max(0, y0); x1 = min(canvas.width, x1); y1 = min(canvas.height, y1)
        if (x1 <= x0 || y1 <= y0) return
        val cw = x1 - x0; val ch = y1 - y0
        flushBatch()
        img.data.argb?.let { src -> // untouched getImageData pixels: straight to the bitmap
            canvas.beforeDraw()
            canvas.bitmap.setPixels(src, (y0 - oy) * img.width + (x0 - ox), img.width, x0, y0, cw, ch)
            canvas.markDirty(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
            return
        }
        val px = IntArray(cw * ch)
        val b = img.data.bytes
        for (row in 0 until ch) {
            var j = ((y0 - oy + row) * img.width + (x0 - ox)) * 4
            var k = row * cw
            for (col in 0 until cw) {
                val r = b[j].toInt() and 0xff; val g = b[j + 1].toInt() and 0xff
                val bl = b[j + 2].toInt() and 0xff; val a = b[j + 3].toInt() and 0xff
                px[k++] = (a shl 24) or (r shl 16) or (g shl 8) or bl
                j += 4
            }
        }
        canvas.beforeDraw()
        canvas.bitmap.setPixels(px, 0, cw, x0, y0, cw, ch)
        canvas.markDirty(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat())
    }

    // ------------------------------------------------------------------ text

    fun fillText(text: String, x: Number, y: Number, maxWidth: Number? = null) =
        drawText(text, x.toDouble(), y.toDouble(), maxWidth?.toDouble(), Paint.Style.FILL)

    fun strokeText(text: String, x: Number, y: Number, maxWidth: Number? = null) =
        drawText(text, x.toDouble(), y.toDouble(), maxWidth?.toDouble(), Paint.Style.STROKE)

    fun measureText(text: String): TextMetrics {
        val p = textPaint(Paint())
        val w = (emojiRuns(text, p)?.let { runWidths(text, it, p).sum() } ?: p.measureText(text)).toDouble()
        val bounds = Rect(); p.getTextBounds(text, 0, text.length, bounds)
        val fm = p.fontMetrics
        return TextMetrics(w, -bounds.left.toDouble(), bounds.right.toDouble(), -bounds.top.toDouble(), bounds.bottom.toDouble(),
            -fm.ascent.toDouble(), fm.descent.toDouble())
    }

    private fun drawText(text: String, x: Double, y: Double, maxWidth: Double?, style: Paint.Style) {
        if (!x.isFinite() || !y.isFinite()) return
        if (maxWidth != null && (maxWidth <= 0 || !maxWidth.isFinite())) return
        val paint = stylePaint(if (style == Paint.Style.FILL) st.fill else st.stroke, style) ?: return
        textPaint(paint)
        val fm = paint.fontMetrics
        val by = y + textBaselineOffset(paint.textSize, fm)
        val runs = emojiRuns(text, paint)
        if (runs != null) {
            // Chrome's emoji fallback (EmojiFallback): runs side by side, aligned as one string.
            val widths = runWidths(text, runs, paint)
            val w = widths.sum()
            val squeeze = if (maxWidth != null && w > maxWidth) (maxWidth / w) else 1.0
            val x0 = when (paint.textAlign) { Paint.Align.CENTER -> -w / 2; Paint.Align.RIGHT -> -w; else -> 0f }
            render(paint, textBox(x, by, w, paint.textSize), if (style == Paint.Style.STROKE) st.lineWidth else 0.0) { p ->
                c.save(); c.translate(x.toFloat(), 0f); if (squeeze != 1.0) c.scale(squeeze.toFloat(), 1f)
                val align = p.textAlign
                val tf0 = p.typeface
                p.textAlign = Paint.Align.LEFT
                var rx = x0
                runs.forEachIndexed { i, (s, e, tf) ->
                    p.typeface = tf
                    c.drawText(text, s, e, rx, by.toFloat(), p)
                    rx += widths[i]
                }
                p.textAlign = align; p.typeface = tf0
                c.restore()
            }
            return
        }
        val w = paint.measureText(text)
        val squeeze = if (maxWidth != null && w > maxWidth) (maxWidth / w) else 1.0
        render(paint, textBox(x, by, w, paint.textSize), if (style == Paint.Style.STROKE) st.lineWidth else 0.0) { p ->
            if (squeeze != 1.0) {
                c.save(); c.translate(x.toFloat(), 0f); c.scale(squeeze.toFloat(), 1f)
                c.drawText(text, 0f, by.toFloat(), p); c.restore()
            } else c.drawText(text, x.toFloat(), by.toFloat(), p)
        }
    }

    /**
     * How far below y the alphabetic baseline goes for the current textBaseline. Blink
     * (CanvasRenderingContext2D::GetFontBaseline): top/middle/bottom use the em box, the font's
     * ascent and descent scaled to add up to the font size; hanging is 80% of the ascent;
     * ideographic is the descent.
     */
    private fun textBaselineOffset(size: Float, fm: Paint.FontMetrics): Double {
        val emAscent = size.toDouble() * -fm.ascent / (fm.descent - fm.ascent)
        val emDescent = size - emAscent
        return when (st.textBaseline) {
            "top" -> emAscent
            "hanging" -> -fm.ascent * 0.8
            "middle" -> (emAscent - emDescent) / 2
            "bottom" -> -emDescent
            "ideographic" -> -fm.descent.toDouble()
            else -> 0.0
        }
    }

    /** Generous user-space box of a string of width [w] drawn at x, baseline [by] (any alignment). */
    private fun textBox(x: Double, by: Double, w: Float, size: Float) =
        RectF((x - w - size).toFloat(), (by - 2 * size).toFloat(), (x + w + size).toFloat(), (by + size).toFloat())

    /** Runs (start, end, typeface) when Chrome would draw part of [text] with the emoji font, else null. */
    private fun emojiRuns(text: String, paint: Paint): List<Triple<Int, Int, Typeface>>? {
        if (paint.typeface !in sansFaces) return null
        if (text.all { it.code < 0x80 }) return null
        val runs = EmojiFallback.runs(text)
        if (runs.none { it.third }) return null
        val emoji = EmojiFallback.emojiTypeface ?: return null
        return runs.map { (s, e, isEmoji) -> Triple(s, e, if (isEmoji) emoji else paint.typeface) }
    }

    private fun runWidths(text: String, runs: List<Triple<Int, Int, Typeface>>, paint: Paint): List<Float> {
        val tf0 = paint.typeface
        val ws = runs.map { (s, e, tf) -> paint.typeface = tf; paint.measureText(text, s, e) }
        paint.typeface = tf0
        return ws
    }

    private fun textPaint(p: Paint): Paint {
        val f = parseFont(st.font) ?: parseFont("10px sans-serif")!!
        p.textSize = f.first
        p.typeface = f.second
        p.isAntiAlias = true
        // Chrome positions glyphs at sub-pixel offsets; without this Skia snaps each glyph origin
        // to whole pixels (the paw prints and suits came out up to half a pixel off).
        p.isSubpixelText = true
        p.textAlign = when (st.textAlign) {
            "center" -> Paint.Align.CENTER
            "right", "end" -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        return p
    }

    // ------------------------------------------------------------------ patterns, gradients

    fun createPattern(image: CanvasImageSource, repetition: String? = "repeat"): CanvasPattern? {
        val bmp = image.sourceBitmap() ?: return null
        return CanvasPattern(bmp.copy(Bitmap.Config.ARGB_8888, false), repetition ?: "repeat")
    }

    fun createLinearGradient(x0: Number, y0: Number, x1: Number, y1: Number) =
        CanvasGradient.linear(x0.toDouble(), y0.toDouble(), x1.toDouble(), y1.toDouble())

    fun createRadialGradient(x0: Number, y0: Number, r0: Number, x1: Number, y1: Number, r1: Number) =
        CanvasGradient.radial(x0.toDouble(), y0.toDouble(), r0.toDouble(), x1.toDouble(), y1.toDouble(), r1.toDouble())

    // ------------------------------------------------------------------ painting internals

    private fun basePaint(): Paint {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.alpha = (st.globalAlpha * 255 + 0.5).toInt()
        applyShadow(p)
        applyFilter(p)
        return p
    }

    private fun stylePaint(style: Any, s: Paint.Style): Paint? {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = s
        when (style) {
            is Int -> {
                val a = ((style ushr 24) * st.globalAlpha + 0.5).toInt()
                p.color = (style and 0xffffff) or (a shl 24)
            }
            is CanvasPattern -> {
                p.shader = style.shader(); p.isFilterBitmap = st.smoothing
                p.alpha = (st.globalAlpha * 255 + 0.5).toInt()
            }
            is CanvasGradient -> {
                val sh = style.shader() ?: return null
                p.shader = sh; p.alpha = (st.globalAlpha * 255 + 0.5).toInt()
            }
        }
        if (s == Paint.Style.STROKE) {
            p.strokeWidth = st.lineWidth.toFloat()
            p.strokeCap = when (st.lineCap) { "round" -> Paint.Cap.ROUND; "square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
            p.strokeJoin = when (st.lineJoin) { "round" -> Paint.Join.ROUND; "bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
            p.strokeMiter = st.miterLimit.toFloat()
            if (st.dash.isNotEmpty() && st.dash.any { it > 0 }) {
                p.pathEffect = DashPathEffect(st.dash.map { it.toFloat() }.toFloatArray(), st.dashOffset.toFloat())
            }
        }
        applyShadow(p)
        applyFilter(p)
        return p
    }

    private fun applyShadow(p: Paint) {
        if ((st.shadowColor ushr 24) == 0) return
        if (st.shadowBlur == 0.0 && st.shadowOffsetX == 0.0 && st.shadowOffsetY == 0.0) return
        // canvas: sigma = blur / 2; Skia's legacy radius->sigma is r * 0.57735 + 0.5
        val sigma = st.shadowBlur / 2
        val radius = if (sigma <= 0.5) 0.01f else ((sigma - 0.5) / 0.57735).toFloat()
        p.setShadowLayer(radius, st.shadowOffsetX.toFloat(), st.shadowOffsetY.toFloat(), st.shadowColor)
    }

    private fun applyFilter(p: Paint) {
        val f = st.filter
        if (f == "none" || f.isEmpty()) return
        val m = Regex("blur\\(([0-9.]+)px\\)").find(f) ?: return
        val r = m.groupValues[1].toFloatOrNull() ?: return
        if (r > 0) p.maskFilter = BlurMaskFilter(r, BlurMaskFilter.Blur.NORMAL)
    }

    // ---- touched boxes (KCanvas.takeDirty, for the app's low-latency pen layer): generous on purpose

    private val tb = RectF()
    private val mv = FloatArray(9)

    /** Device bounds of the current path (it is kept in device space). */
    private fun devicePathBounds(): RectF = RectF().also { path.computeBounds(it, true) }

    /** How far a stroke can reach past its path: half the width, miters and square caps included. */
    private fun strokePad(): Double = st.lineWidth / 2 * max(st.miterLimit, 1.5)

    /** Records that [box] (user space, or device space when [device]) plus [pad] user px may have changed. */
    private fun touched(box: RectF, pad: Double, device: Boolean) {
        if (!KCanvas.trackDirty) return
        if (st.gco in Gco.unbounded) { canvas.markAllDirty(); return }
        st.ctm.getValues(mv)
        val scale = max(1.0, max(kotlin.math.hypot(mv[0].toDouble(), mv[3].toDouble()), kotlin.math.hypot(mv[1].toDouble(), mv[4].toDouble())))
        if (device) tb.set(box) else st.ctm.mapRect(tb, box)
        var p = pad * scale + 2
        if ((st.shadowColor ushr 24) != 0) p += abs(st.shadowOffsetX) + abs(st.shadowOffsetY) + 3 * st.shadowBlur * scale
        if (st.filter != "none" && st.filter.isNotEmpty()) {
            val r = Regex("blur\\(([0-9.]+)px\\)").find(st.filter)?.groupValues?.get(1)?.toDoubleOrNull()
            if (r == null) { canvas.markAllDirty(); return }
            p += 3 * r * scale
        }
        val pf = p.toFloat()
        canvas.markDirty(tb.left - pf, tb.top - pf, tb.right + pf, tb.bottom + pf)
    }

    /** Draws with the current matrix and composite operation (unbounded modes via a layer). */
    private inline fun render(paint: Paint, box: RectF, pad: Double, device: Boolean = false, draw: (Paint) -> Unit) {
        flushBatch()
        canvas.beforeDraw()
        renderNoTouch(paint, draw)
        touched(box, pad, device)
    }

    private inline fun renderNoTouch(paint: Paint, draw: (Paint) -> Unit) {
        c.setMatrix(st.ctm)
        val mode = Gco.modes[st.gco]
        if (mode == null) {
            draw(paint)
        } else if (st.gco in Gco.unbounded) {
            val layer = Paint().apply { blendMode = mode }
            c.saveLayer(null, layer)
            draw(paint)
            c.restore()
        } else {
            paint.blendMode = mode
            draw(paint)
        }
    }

    companion object {
        /** AaStroke/MsaaSurface on accelerated-size canvases (off: every stroke is plain Skia; for measuring). */
        @JvmStatic var chromeStrokes = true

        /** Measuring aid (perfLog): called after each outermost batchStrokes with (block ns, write-back ns). */
        @JvmStatic var batchTimer: ((Long, Long) -> Unit)? = null

        /** Restrokes of a grown path stroke only the new part (MsaaLast); off only to test that it changes nothing. */
        @JvmStatic var msaaIncremental = true

        /** Round-capped single segments thinner than this (device px) are sampled analytically. */
        const val THIN_ROUND = 4.0

        private val fonts = HashMap<String, Typeface>()

        /** Register a font face so `ctx.font = '40px <name>'` can use it (stamps/alphabet faces). */
        fun registerFont(name: String, tf: Typeface) { fonts[name.lowercase()] = tf }

        private val fontCache = HashMap<String, Pair<Float, Typeface>?>()

        /** "[style] [weight] <size>px <family>[, fallback...]" -> (px, typeface). */
        fun parseFont(f: String): Pair<Float, Typeface>? = fontCache.getOrPut(f) {
            val m = Regex("(?:(italic|oblique|normal)\\s+)?(?:(bold|bolder|lighter|normal|[1-9]00)\\s+)?([0-9.]+)px\\s+(.+)")
                .find(f.trim()) ?: return@getOrPut null
            val size = m.groupValues[3].toFloatOrNull() ?: return@getOrPut null
            val bold = m.groupValues[2].let { it == "bold" || it == "bolder" || (it.toIntOrNull() ?: 400) >= 600 }
            val italic = m.groupValues[1].let { it == "italic" || it == "oblique" }
            val styleFlag = when {
                bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL
            }
            val families = m.groupValues[4].split(',').map { it.trim().trim('"', '\'').lowercase() }
            val base = families.firstNotNullOfOrNull { fam ->
                fonts[fam] ?: when (fam) {
                    "sans-serif", "arial", "helvetica", "system-ui" -> Typeface.SANS_SERIF
                    "serif", "times", "times new roman" -> Typeface.SERIF
                    "monospace", "courier", "courier new" -> Typeface.MONOSPACE
                    else -> null
                }
            } ?: Typeface.create(families.firstOrNull() ?: "sans-serif", Typeface.NORMAL)
            Typeface.create(base, styleFlag).let { tf ->
                if (base == Typeface.SANS_SERIF) sansFaces.add(tf)
                size to tf
            }
        }

        /** Typefaces that stand for the generic sans-serif family (Chrome's emoji fallback applies). */
        private val sansFaces: MutableSet<Typeface> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())
    }
}

/** globalCompositeOperation names -> Android blend modes. */
object Gco {
    val modes: Map<String, BlendMode?> = mapOf(
        "source-over" to null,
        "source-in" to BlendMode.SRC_IN,
        "source-out" to BlendMode.SRC_OUT,
        "source-atop" to BlendMode.SRC_ATOP,
        "destination-over" to BlendMode.DST_OVER,
        "destination-in" to BlendMode.DST_IN,
        "destination-out" to BlendMode.DST_OUT,
        "destination-atop" to BlendMode.DST_ATOP,
        "lighter" to BlendMode.PLUS,
        "copy" to BlendMode.SRC,
        "xor" to BlendMode.XOR,
        "multiply" to BlendMode.MULTIPLY,
        "screen" to BlendMode.SCREEN,
        "overlay" to BlendMode.OVERLAY,
        "darken" to BlendMode.DARKEN,
        "lighten" to BlendMode.LIGHTEN,
        "color-dodge" to BlendMode.COLOR_DODGE,
        "color-burn" to BlendMode.COLOR_BURN,
        "hard-light" to BlendMode.HARD_LIGHT,
        "soft-light" to BlendMode.SOFT_LIGHT,
        "difference" to BlendMode.DIFFERENCE,
        "exclusion" to BlendMode.EXCLUSION,
        "hue" to BlendMode.HUE,
        "saturation" to BlendMode.SATURATION,
        "color" to BlendMode.COLOR,
        "luminosity" to BlendMode.LUMINOSITY,
    )

    /** Modes that also clear destination pixels outside the source shape in the browser. */
    val unbounded = setOf("source-in", "source-out", "destination-in", "destination-atop", "copy")
}
