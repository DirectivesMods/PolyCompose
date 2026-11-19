package org.polyfrost.compose.effect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import org.polyfrost.compose.backend.PolyDrawCanvas
import org.polyfrost.compose.backend.drawBackdropGlass
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostListener
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.alphaByte
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val DEFAULT_REFRACTION = 0.85f
private val DefaultBand = 22.dp
private val DefaultReach = 160.dp
private val RestingLight = Offset(-0.57f, -0.82f)
private const val LIGHT_HEIGHT = 0.25f
private const val RIM_TILT_XY = 0.6f
private const val RIM_TILT_Z = 0.8f
private const val MIN_LIGHT = 0.008f
private const val CELL = 32f
private val GateSlack = 160.dp
private val Follow = sqrt(200f)
private const val FADE = 12f
private const val FADED = 0.002f
private const val SNAP = 0.02f
private const val NO_CELL = Int.MIN_VALUE
private const val RIM_CACHE = 256
private const val RGB_MASK = 0xFFFFFF
private const val RIM_ALPHA_CAP = 0.7f
private const val LIGHT_GAIN = 0.4f

public object PolyGlassLight {
    private var targetX = 0f
    private var targetY = 0f
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var presenceTarget = 0f
    private var last = 0L
    private var cellX = NO_CELL
    private var cellY = NO_CELL
    private var trailX = NO_CELL
    private var trailY = NO_CELL

    private var listening = false

    private val hostListener = object : HostListener {
        override fun onPointer(screen: Any, position: Offset?) {
            if (position == null) leave() else moveTo(position)
        }

        override fun onScreenRemoved(screen: Any) {
            reset()
        }
    }

    internal fun listen() {
        if (listening) return
        listening = true
        ComposeHost.listeners += hostListener
    }

    public var known: Boolean = false
        private set

    public var presence: Float = 0f
        private set

    internal val position: Offset get() = Offset(x, y)

    internal var cell: Offset? by mutableStateOf(null)
        private set

    internal var trail: Offset? by mutableStateOf(null)
        private set

    public fun moveTo(p: Offset) {
        if (!known || presenceTarget == 0f && presence < SNAP) {
            known = true
            x = p.x
            y = p.y
            vx = 0f
            vy = 0f
            last = System.nanoTime()
            follow()
        }
        targetX = p.x
        targetY = p.y
        presenceTarget = 1f
        val cx = floor(p.x / CELL).toInt()
        val cy = floor(p.y / CELL).toInt()
        if (cx == cellX && cy == cellY) return
        cellX = cx
        cellY = cy
        cell = center(cx, cy)
    }

    public fun leave() {
        presenceTarget = 0f
    }

    public fun reset() {
        known = false
        presence = 0f
        presenceTarget = 0f
        vx = 0f
        vy = 0f
        clearCells()
    }

    internal fun sample() {
        val now = System.nanoTime()
        val dt = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        if (dt <= 0f) return
        val decay = exp(-Follow * dt)
        val ex = x - targetX
        val ey = y - targetY
        val kx = vx + Follow * ex
        val ky = vy + Follow * ey
        x = targetX + (ex + kx * dt) * decay
        y = targetY + (ey + ky * dt) * decay
        vx = (vx - Follow * kx * dt) * decay
        vy = (vy - Follow * ky * dt) * decay
        presence += (presenceTarget - presence) * (1f - exp(-FADE * dt))
        if (presenceTarget == 0f && presence < FADED) {
            clearCells()
            return
        }
        follow()
    }

    private fun follow() {
        val cx = floor(x / CELL).toInt()
        val cy = floor(y / CELL).toInt()
        if (cx == trailX && cy == trailY) return
        trailX = cx
        trailY = cy
        trail = center(cx, cy)
    }

    private fun clearCells() {
        if (cellX != NO_CELL) {
            cellX = NO_CELL
            cellY = NO_CELL
            cell = null
        }
        if (trailX != NO_CELL) {
            trailX = NO_CELL
            trailY = NO_CELL
            trail = null
        }
    }

    private fun center(cx: Int, cy: Int) = Offset(cx * CELL + CELL / 2f, cy * CELL + CELL / 2f)
}

@Composable
public fun Modifier.polyGlass(
    shape: Shape,
    frosted: Boolean = false,
    tint: Color = Color.Transparent,
    rimColor: Color = Color.White,
    light: Boolean = true,
    lightColor: Color = Color.White,
    alpha: () -> Float = { 1f },
    band: Dp = DefaultBand,
    refraction: Float = DEFAULT_REFRACTION,
    reach: Dp = DefaultReach,
): Modifier {
    val density = LocalDensity.current
    val reachPx = with(density) { reach.toPx() }
    if (!light) return glassSurface(shape, frosted, tint, rimColor, lightColor, alpha, band, refraction, reachPx, { false }) { Offset.Unspecified }
    PolyGlassLight.listen()
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val range = reachPx * 2f
    val slack = with(density) { GateSlack.toPx() }
    val near = remember(range, slack) { derivedStateOf { lightNear(bounds, range + slack) } }
    return this
        .onGloballyPositioned { bounds = Rect(it.positionInWindow(), it.size.toSize()) }
        .graphicsLayer {}
        .glassSurface(shape, frosted, tint, rimColor, lightColor, alpha, band, refraction, reachPx, { near.value }) { cursorNear(bounds, range) }
}

public fun Modifier.polyGlassSplit(
    shape: Shape,
    splitX: Dp,
    leftAlpha: Float,
    rightAlpha: Float,
    tint: Color = Color.Transparent,
    band: Dp = DefaultBand,
    refraction: Float = DEFAULT_REFRACTION,
): Modifier = drawWithCache {
    val radius = (shape.createOutline(size, layoutDirection, this) as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    val bandPx = band.toPx()
    val split = splitX.roundToPx().coerceIn(0, size.width.roundToInt()).toFloat()
    onDrawWithContent {
        if (LiquidGlassSettings.reduceTransparency) {
            drawContent()
            return@onDrawWithContent
        }
        clipRect(right = split) {
            drawBackdropGlass(size, radius, bandPx, refraction, leftAlpha)
            drawGlassTint(tint, radius, leftAlpha)
        }
        clipRect(left = split) {
            drawBackdropGlass(size, radius, bandPx, refraction, rightAlpha)
            drawGlassTint(tint, radius, rightAlpha)
        }
        drawContent()
    }
}

private fun DrawScope.drawGlassTint(tint: Color, radius: Float, alpha: Float) {
    if (tint.alpha <= 0f || alpha <= 0f) return
    drawRoundRect(tint.copy(alpha = tint.alpha * alpha.coerceAtMost(1f)), cornerRadius = CornerRadius(radius))
}

private fun lightNear(bounds: Rect, range: Float): Boolean {
    if (bounds.isEmpty) return false
    val c = PolyGlassLight.cell
    val t = PolyGlassLight.trail
    return c != null && distance(bounds, c) <= range || t != null && distance(bounds, t) <= range
}

private fun cursorNear(bounds: Rect, range: Float): Offset {
    PolyGlassLight.sample()
    if (!PolyGlassLight.known || bounds.isEmpty || PolyGlassLight.presence <= FADED) return Offset.Unspecified
    val p = PolyGlassLight.position
    if (distance(bounds, p) > range) return Offset.Unspecified
    return p - bounds.topLeft
}

private fun lightKey(cursor: Offset, alpha: Float, rgb: Int): Int {
    if (cursor.isUnspecified) return 0
    var h = (cursor.x * 8f).roundToInt()
    h = 31 * h + (cursor.y * 8f).roundToInt()
    h = 31 * h + (PolyGlassLight.presence * 255f).roundToInt()
    h = 31 * h + rgb
    return 31 * h + alpha.toRawBits()
}

private fun distance(bounds: Rect, p: Offset): Float {
    val dx = max(max(bounds.left - p.x, p.x - bounds.right), 0f)
    val dy = max(max(bounds.top - p.y, p.y - bounds.bottom), 0f)
    return sqrt(dx * dx + dy * dy)
}

private class RimTint(val alpha: Float, val rgb: Int, val colors: IntArray)

private class Perimeter(val points: FloatArray, val normals: FloatArray, val shape: FloatArray) {
    val size: Int get() = points.size / 2
    val resting = FloatArray(size) { restingLight(normals[it * 2], normals[it * 2 + 1]) }

    @Volatile
    private var tint: RimTint? = null

    fun restingColors(alpha: Float, rgb: Int): IntArray {
        tint?.let { if (it.alpha == alpha && it.rgb == rgb) return it.colors }
        val colors = IntArray(size) { tinted(resting[it] * alpha, rgb) }
        tint = RimTint(alpha, rgb, colors)
        return colors
    }
}

private data class RimKey(val w: Float, val h: Float, val radius: Float, val width: Float, val lineStep: Float, val cornerStep: Float)

private val rims = object : LinkedHashMap<RimKey, Perimeter>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RimKey, Perimeter>?): Boolean = size > RIM_CACHE
}

private fun cachedPerimeter(w: Float, h: Float, radius: Float, width: Float, lineStep: Float, cornerStep: Float): Perimeter = synchronized(rims) {
    rims.getOrPut(RimKey(w, h, radius, width, lineStep, cornerStep)) { perimeter(w, h, radius, width / 2f, lineStep, cornerStep) }
}

private fun perimeter(w: Float, h: Float, radius: Float, inset: Float, lineStep: Float, cornerStep: Float): Perimeter {
    val pts = ArrayList<Float>()
    val nrm = ArrayList<Float>()
    val l = inset
    val t = inset
    val rt = w - inset
    val b = h - inset
    val r = min(max(radius - inset, 0f), min((rt - l) / 2f, (b - t) / 2f))
    fun add(px: Float, py: Float, nx: Float, ny: Float) {
        pts += px
        pts += py
        nrm += nx
        nrm += ny
    }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, nx: Float, ny: Float) {
        val n = max(1, ceil(hypot(x1 - x0, y1 - y0) / lineStep).toInt())
        for (i in 0 until n) add(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n, nx, ny)
    }
    fun corner(cx: Float, cy: Float, from: Double) {
        if (r <= 0f) {
            add(cx, cy, cos(from).toFloat(), sin(from).toFloat())
            return
        }
        val n = max(2, ceil(r * PI / 2 / cornerStep).toInt())
        for (i in 0 until n) {
            val a = from + PI / 2 * i / n
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            add(cx + c * r, cy + s * r, c, s)
        }
    }
    line(l + r, t, rt - r, t, 0f, -1f)
    corner(rt - r, t + r, -PI / 2)
    line(rt, t + r, rt, b - r, 1f, 0f)
    corner(rt - r, b - r, 0.0)
    line(rt - r, b, l + r, b, 0f, 1f)
    corner(l + r, b - r, PI / 2)
    line(l, b - r, l, t + r, -1f, 0f)
    corner(l + r, t + r, PI)
    return Perimeter(pts.toFloatArray(), nrm.toFloatArray(), floatArrayOf(0f, 0f, w, h, radius))
}

private fun Modifier.glassSurface(
    shape: Shape,
    frosted: Boolean,
    tint: Color,
    rimColor: Color,
    lightColor: Color,
    alpha: () -> Float,
    band: Dp,
    refraction: Float,
    reach: Float,
    near: () -> Boolean,
    cursor: () -> Offset,
) = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val radius = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
    val bandPx = band.toPx()
    val width = 1.dp.toPx()
    val rim = if (outline is Outline.Generic) null else cachedPerimeter(size.width, size.height, radius, width, 8.dp.toPx(), 3.dp.toPx())
    val lit = rim?.let { IntArray(it.size) }
    val fringe = width / 2f + 2f
    val rimRgb = rimColor.toArgb() and RGB_MASK
    val lightRgb = lightColor.toArgb() and RGB_MASK
    onDrawWithContent {
        val a = alpha()
        if (frosted && !LiquidGlassSettings.reduceTransparency) {
            drawBackdropGlass(size, radius, bandPx, refraction, a)
            if (tint.alpha > 0f && a > 0f) drawOutline(outline, tint.copy(alpha = tint.alpha * a.coerceAtMost(1f)))
        }
        drawContent()
        if (rim == null || lit == null || a <= 0f) return@onDrawWithContent
        drawRestingRim(rim, width, a * rimColor.alpha, rimRgb)
        val lightAlpha = a * lightColor.alpha
        if (near()) drawLight(-fringe, -fringe, size.width + fringe * 2f, size.height + fringe * 2f, { lightKey(cursor(), lightAlpha, lightRgb) }) {
            val c = cursor()
            if (!c.isUnspecified) it.drawRimLight(rim, lit, c, reach, width, lightAlpha, lightRgb)
        }
    }
}

private fun DrawScope.drawLight(x: Float, y: Float, w: Float, h: Float, key: () -> Int, block: (PolyRenderer) -> Unit) =
    drawPoly { it.renderer.live(x, y, w, h, key, block) }

private fun restingLight(nx: Float, ny: Float): Float {
    val facing = max(nx * RestingLight.x + ny * RestingLight.y, 0f)
    val back = max(-(nx * RestingLight.x + ny * RestingLight.y), 0f)
    return 0.05f + 0.3f * facing * facing + 0.08f * back * back
}

private fun tinted(alpha: Float, rgb: Int) = (alphaByte(alpha) shl 24) or (rgb and RGB_MASK)

private fun DrawScope.drawRestingRim(rim: Perimeter, width: Float, alpha: Float, rgb: Int) {
    val colors = rim.restingColors(alpha, rgb)
    drawIntoCanvas { canvas -> (canvas as? PolyDrawCanvas)?.polyOp { it.renderer.strokeColorRing(rim.points, rim.normals, colors, width, rim.shape) } }
}

private fun PolyRenderer.drawRimLight(rim: Perimeter, colors: IntArray, cursor: Offset, reach: Float, width: Float, alpha: Float, rgb: Int) {
    val presence = PolyGlassLight.presence
    if (presence <= 0f) return
    val n = rim.size
    val pts = rim.points
    val nrm = rim.normals
    val resting = rim.resting
    val lift = reach * LIGHT_HEIGHT
    val lift2 = lift * lift
    val invReach2 = 1f / (reach * reach)
    var any = false
    for (i in 0 until n) {
        val dx = cursor.x - pts[i * 2]
        val dy = cursor.y - pts[i * 2 + 1]
        val d2 = dx * dx + dy * dy
        val facing = max((nrm[i * 2] * dx + nrm[i * 2 + 1] * dy) * RIM_TILT_XY + lift * RIM_TILT_Z, 0f)
        val lit = LIGHT_GAIN * facing * facing / (d2 + lift2) / (1f + d2 * invReach2) * presence
        val base = resting[i] * alpha
        val total = min(resting[i] + lit, RIM_ALPHA_CAP) * alpha
        val over = 1f - (1f - total) / (1f - base)
        colors[i] = if (over < MIN_LIGHT) {
            0
        } else {
            any = true
            tinted(over, rgb)
        }
    }
    if (any) strokeColorRing(pts, nrm, colors, width, rim.shape)
}
