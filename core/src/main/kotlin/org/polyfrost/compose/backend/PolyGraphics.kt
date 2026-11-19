@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.backend

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PathIterator
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.platform.PlatformBlurFilter
import androidx.compose.ui.graphics.platform.PlatformColorFilter
import androidx.compose.ui.graphics.platform.PlatformGraphics
import androidx.compose.ui.graphics.platform.PlatformRenderEffect
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyFade
import org.polyfrost.compose.render.PolyGradient
import org.polyfrost.compose.render.PolyPath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

public object PolyGraphics : PlatformGraphics {
    override fun createCanvas(image: ImageBitmap): Canvas {
        val target = image as PolyImageBitmap
        // Canvases on one image share its renderer, so pending draws from each reach the pixels in order
        return PolyCanvas(target.renderer ?: BitmapRenderer(target).also { target.renderer = it })
    }
    override fun createPaint(): Paint = PolyPaint()
    override fun isBlendModeSupported(blendMode: BlendMode): Boolean = blendMode == BlendMode.SrcOver
    override fun isTileModeSupported(tileMode: TileMode): Boolean = true
    override fun createPath(): Path = PolyComposePath()
    override fun createPathIterator(path: Path, conicEvaluation: PathIterator.ConicEvaluation, tolerance: Float): PathIterator =
        PolyPathIterator(path, conicEvaluation, tolerance)
    override fun createPathMeasure(): PathMeasure = PolyPathMeasure()

    override fun createImageBitmap(width: Int, height: Int, config: ImageBitmapConfig, hasAlpha: Boolean, colorSpace: ColorSpace): ImageBitmap =
        PolyImageBitmap(PolyBitmap(width, height, ByteArray(width * height * 4)), config)

    override fun decodeImageBitmap(bytes: ByteArray): ImageBitmap =
        PolyImageBitmap(PolyBitmap.decode(bytes) ?: error("cannot decode image (${bytes.size} bytes)"))

    override fun createLinearGradientShader(from: Offset, to: Offset, colors: List<Color>, colorStops: List<Float>?, tileMode: TileMode): Shader =
        Shader(GradientShader(PolyGradient.Points(colors.map(::polyColor), colorStops, from.x, from.y, to.x, to.y), null, 0f, tileMode))

    override fun createRadialGradientShader(center: Offset, radius: Float, colors: List<Color>, colorStops: List<Float>?, tileMode: TileMode): Shader =
        Shader(GradientShader(PolyGradient.Radial(colors.map(::polyColor), colorStops), center, radius, tileMode))

    override fun createSweepGradientShader(center: Offset, colors: List<Color>, colorStops: List<Float>?): Shader =
        Shader(GradientShader(PolyGradient.Sweep(colors.map(::polyColor), colorStops), center, 0f))

    override fun createImageShader(image: ImageBitmap, tileModeX: TileMode, tileModeY: TileMode): Shader = Shader(ImageShader(image, tileModeX, tileModeY))
    override fun createCompositeShader(destination: Shader, source: Shader, blendMode: BlendMode): Shader =
        Shader(composite(destination.platformShader, source.platformShader, blendMode))

    private fun composite(dst: PlatformShader, src: PlatformShader, mode: BlendMode): PlatformShader {
        if (mode == BlendMode.Src) return src
        if (mode == BlendMode.Dst) return dst
        val color = (dst as? GradientShader)?.takeIf { it.tile == TileMode.Clamp }
        val mask = (src as? GradientShader)?.takeIf { it.tile == TileMode.Clamp }?.fade(1f)
        return if (mode == BlendMode.DstIn && color != null && mask != null) MaskedShader(color, mask, src) else CompositeShader(dst, src, mode)
    }

    override fun transformShader(shader: Shader, matrix: Matrix): Shader {
        val m = affineOf(matrix)
        if (m.contentEquals(IDENTITY)) return shader
        val inv = invertAffine(m) ?: return Shader(ClearShader)
        val s = shader.platformShader
        if (s is MaskedShader) return Shader(composite(transformedShader(s.color, m, inv), transformedShader(s.source, m, inv), BlendMode.DstIn))
        return Shader(transformedShader(s, m, inv))
    }

    override fun createBlurRenderEffect(renderEffect: RenderEffect?, radiusX: Float, radiusY: Float, edgeTreatment: TileMode): PlatformRenderEffect {
        val rx = max(radiusX, 0f)
        val ry = max(radiusY, 0f)
        return chained(renderEffect, if (rx > 0f || ry > 0f) PolyEffect.Blur(rx, ry, edgeTreatment != TileMode.Decal) else null)
    }
    override fun createOffsetRenderEffect(renderEffect: RenderEffect?, offset: Offset): PlatformRenderEffect =
        chained(renderEffect, if (offset.x != 0f || offset.y != 0f) PolyEffect.Offset(offset.x, offset.y) else null)

    private fun chained(inner: RenderEffect?, stage: PolyEffect?): PlatformRenderEffect {
        val prev = (inner?.platformRenderEffect as? ChainEffect)?.items.orEmpty()
        if (stage == null) return if (prev.isEmpty()) NoEffect else ChainEffect(prev)
        return ChainEffect(prev + stage)
    }
    override fun createBlurFilter(radius: Float): PlatformBlurFilter = BlurFilter(radius)
    override fun setBlurFilter(paint: Paint, blur: PlatformBlurFilter?) { (paint as? PolyPaint)?.blur = (blur as? BlurFilter)?.radius ?: 0f }

    override fun createTintColorFilter(color: Color, blendMode: BlendMode): PlatformColorFilter = TintFilter(color, blendMode)
    override fun createColorMatrixColorFilter(colorMatrix: ColorMatrix): PlatformColorFilter = MatrixFilter(colorMatrix)
    override fun createLightingColorFilter(multiply: Color, add: Color): PlatformColorFilter = MatrixFilter(
        ColorMatrix(
            floatArrayOf(
                multiply.red, 0f, 0f, 0f, add.red * 255f, 0f, multiply.green, 0f, 0f, add.green * 255f,
                0f, 0f, multiply.blue, 0f, add.blue * 255f, 0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )
    override fun colorMatrixFromFilter(filter: PlatformColorFilter): ColorMatrix = (filter as? MatrixFilter)?.matrix ?: ColorMatrix()

    override fun createCornerPathEffect(radius: Float): PathEffect = if (radius > 0f) CornerEffect(radius) else NoPathEffect
    override fun createDashPathEffect(intervals: FloatArray, phase: Float): PathEffect = DashEffect(intervals, phase)
    override fun createChainPathEffect(outer: PathEffect, inner: PathEffect): PathEffect = when {
        outer === NoPathEffect -> inner
        inner === NoPathEffect -> outer
        else -> ChainPathEffect(outer, inner)
    }
    override fun createStampedPathEffect(shape: Path, advance: Float, phase: Float, style: StampedPathEffectStyle): PathEffect {
        val p = shape as PolyComposePath
        val contours = p.allContours()
        if (!(advance > 0f) || contours.isEmpty()) return NoPathEffect
        return StampEffect(contours.map { it.copyOf() }, BooleanArray(contours.size) { p.closedAt(it) }, advance, phase, style)
    }

    internal fun polyColor(c: Color) = PolyColor(c.toArgb())
}

internal fun GradientShader.fade(alpha: Float, tint: Boolean = false): PolyFade? {
    val g = brush as? PolyGradient.Points ?: return null
    val vertical = g.x0 == g.x1
    if (!vertical && g.y0 != g.y1) return null
    val a = if (vertical) g.y0 else g.x0
    val b = if (vertical) g.y1 else g.x1
    val n = g.colors.size
    val stops = FloatArray(n) { a + (b - a) * (g.stops?.get(it) ?: if (n == 1) 0f else it / (n - 1f)) }
    val alphas = FloatArray(n) { g.colors[it].alpha / 255f * alpha }
    val colors = if (tint) IntArray(n) { g.colors[it].argb and 0xFFFFFF } else null
    return if (b < a) PolyFade(vertical, stops.reversedArray(), alphas.reversedArray(), colors?.reversedArray()) else PolyFade(vertical, stops, alphas, colors)
}

internal class BlurFilter(val radius: Float) : PlatformBlurFilter()
internal class TintFilter(val color: Color, val blendMode: BlendMode) : PlatformColorFilter()
internal class MatrixFilter(val matrix: ColorMatrix) : PlatformColorFilter()
internal class DashEffect(val intervals: FloatArray, val phase: Float) : PathEffect
internal class CornerEffect(val radius: Float) : PathEffect
internal class StampEffect(val contours: List<FloatArray>, val closed: BooleanArray, val advance: Float, val phase: Float, val style: StampedPathEffectStyle) : PathEffect
internal class ChainPathEffect(val outer: PathEffect, val inner: PathEffect) : PathEffect
internal object NoPathEffect : PathEffect
internal object NoEffect : PlatformRenderEffect

internal class ChainEffect(val items: List<Any>) : PlatformRenderEffect {
    override fun equals(other: Any?): Boolean = other is ChainEffect && items == other.items
    override fun hashCode(): Int = items.hashCode()
}

public class PolyImageBitmap internal constructor(private var current: PolyBitmap, override val config: ImageBitmapConfig) : ImageBitmap {
    public constructor(bitmap: PolyBitmap) : this(bitmap, ImageBitmapConfig.Argb8888) {
        handedOut = true
    }

    internal var renderer: BitmapRenderer? = null
    private var handedOut = false

    /** The pixels drawn so far, including any drawing through a `Canvas` created for this image. */
    public val bitmap: PolyBitmap
        get() {
            renderer?.sync()
            handedOut = true
            return current
        }

    override val width: Int get() = current.width
    override val height: Int get() = current.height
    override val colorSpace: ColorSpace get() = ColorSpaces.Srgb
    override val hasAlpha: Boolean get() = true

    // Renderers cache textures by PolyBitmap identity, so a bitmap that may have been uploaded must not change in place
    internal fun pixelsForDrawing(): ByteArray {
        if (handedOut) {
            current = PolyBitmap(current.width, current.height, current.pixels.copyOf())
            handedOut = false
        }
        return current.pixels
    }

    override fun readPixels(buffer: IntArray, startX: Int, startY: Int, width: Int, height: Int, bufferOffset: Int, stride: Int) {
        renderer?.sync()
        val px = current.pixels
        for (y in 0 until height) for (x in 0 until width) {
            val o = ((startY + y) * current.width + startX + x) * 4
            buffer[bufferOffset + y * stride + x] = ((px[o + 3].toInt() and 0xFF) shl 24) or ((px[o].toInt() and 0xFF) shl 16) or
                ((px[o + 1].toInt() and 0xFF) shl 8) or (px[o + 2].toInt() and 0xFF)
        }
    }

    override fun prepareToDraw() {}
}

public fun PolyBitmap.asComposeImageBitmap(): ImageBitmap = PolyImageBitmap(this)

public class PolyPaint : Paint {
    override var isAntiAlias: Boolean = true
    override var color: Color = Color.Black
    override var alpha: Float
        get() = color.alpha
        set(value) { color = color.copy(alpha = value) }
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var style: PaintingStyle = PaintingStyle.Fill
    override var strokeWidth: Float = 0f
    override var strokeCap: StrokeCap = StrokeCap.Butt
    override var strokeJoin: StrokeJoin = StrokeJoin.Miter
    override var strokeMiterLimit: Float = 4f
    override var filterQuality: FilterQuality = FilterQuality.Low
    override var shader: Shader? = null
    override var colorFilter: ColorFilter? = null
    override var pathEffect: PathEffect? = null
    public var blur: Float = 0f

    public fun copy(): PolyPaint = PolyPaint().also {
        it.isAntiAlias = isAntiAlias; it.color = color; it.blendMode = blendMode; it.style = style
        it.strokeWidth = strokeWidth; it.strokeCap = strokeCap; it.strokeJoin = strokeJoin; it.strokeMiterLimit = strokeMiterLimit
        it.filterQuality = filterQuality; it.shader = shader; it.colorFilter = colorFilter; it.pathEffect = pathEffect; it.blur = blur
    }

    public fun argb(): Int {
        val filter = colorFilter ?: return color.toArgb()
        return filtered(filterPlatform(filter), color.toArgb())
    }

    public companion object {
        private val platformField = runCatching {
            ColorFilter::class.java.declaredFields.first { PlatformColorFilter::class.java.isAssignableFrom(it.type) }.apply { isAccessible = true }
        }.getOrNull()

        internal fun filterPlatform(filter: ColorFilter): Any? = platformField?.get(filter)

        internal fun filtered(filter: Any?, argb: Int): Int = when (filter) {
            is TintFilter -> blendArgb(filter.color.toArgb(), argb, filter.blendMode)
            is MatrixFilter -> {
                val m = filter.matrix.values
                var out = 0
                for (c in 0..3) {
                    val i = c * 5
                    val v = m[i] * (argb shr 16 and 0xFF) + m[i + 1] * (argb shr 8 and 0xFF) + m[i + 2] * (argb and 0xFF) + m[i + 3] * (argb ushr 24) + m[i + 4]
                    out = out or (v.toInt().coerceIn(0, 255) shl (if (c == 3) 24 else 16 - c * 8))
                }
                out
            }
            else -> argb
        }
    }
}

public class PolyComposePath : Path {
    internal val contours = ArrayList<FloatArray>()
    private var cur = FloatArrayList()
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var closed = ArrayList<Boolean>()

    internal var roundRect: RoundRect? = null
    internal var plainRect: Rect? = null
    private var poly: PolyPath? = null

    override var fillType: PathFillType = PathFillType.NonZero
        set(value) {
            field = value
            poly = null
        }
    override val isConvex: Boolean get() = roundRect != null || plainRect != null
    override val isEmpty: Boolean get() = contours.isEmpty() && cur.size < 4

    private fun shapeChanged() { roundRect = null; plainRect = null; poly = null }

    internal fun polyPath(): PolyPath {
        poly?.let { return it }
        val out = PolyPath()
        out.evenOdd = fillType == PathFillType.EvenOdd
        for (c in allContours()) {
            if (c.size < 6) continue
            out.moveTo(c[0], c[1])
            for (k in 2 until c.size step 2) out.lineTo(c[k], c[k + 1])
            out.close()
        }
        poly = out
        return out
    }

    private fun flush(close: Boolean) {
        if (cur.size >= 4) { contours.add(cur.toArray()); closed.add(close) }
        cur = FloatArrayList()
    }

    override fun moveTo(x: Float, y: Float) {
        shapeChanged()
        flush(false)
        cur.add(x, y)
        startX = x; startY = y; lastX = x; lastY = y
    }

    override fun relativeMoveTo(dx: Float, dy: Float): Unit = moveTo(lastX + dx, lastY + dy)

    override fun lineTo(x: Float, y: Float) {
        shapeChanged()
        if (cur.size == 0) cur.add(lastX, lastY)
        cur.add(x, y)
        lastX = x; lastY = y
    }

    override fun relativeLineTo(dx: Float, dy: Float): Unit = lineTo(lastX + dx, lastY + dy)

    // abstract on Path, whose replacement quadraticTo delegates to it by default
    @Suppress("OVERRIDE_DEPRECATION")
    override fun quadraticBezierTo(x1: Float, y1: Float, x2: Float, y2: Float) {
        val x0 = lastX; val y0 = lastY
        val n = segments(abs(x1 - x0) + abs(y1 - y0) + abs(x2 - x1) + abs(y2 - y1))
        for (i in 1..n) {
            val t = i / n.toFloat(); val u = 1f - t
            lineTo(u * u * x0 + 2 * u * t * x1 + t * t * x2, u * u * y0 + 2 * u * t * y1 + t * t * y2)
        }
    }

    // abstract on Path, whose replacement relativeQuadraticTo delegates to it by default
    @Suppress("OVERRIDE_DEPRECATION")
    override fun relativeQuadraticBezierTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float): Unit =
        quadraticBezierTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2)

    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val x0 = lastX; val y0 = lastY
        val n = segments(abs(x1 - x0) + abs(y1 - y0) + abs(x2 - x1) + abs(y2 - y1) + abs(x3 - x2) + abs(y3 - y2))
        for (i in 1..n) {
            val t = i / n.toFloat(); val u = 1f - t
            val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
            lineTo(a * x0 + b * x1 + c * x2 + d * x3, a * y0 + b * y1 + c * y2 + d * y3)
        }
    }

    override fun relativeCubicTo(dx1: Float, dy1: Float, dx2: Float, dy2: Float, dx3: Float, dy3: Float): Unit =
        cubicTo(lastX + dx1, lastY + dy1, lastX + dx2, lastY + dy2, lastX + dx3, lastY + dy3)

    override fun arcTo(rect: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float, forceMoveTo: Boolean) {
        val start = Math.toRadians(startAngleDegrees.toDouble())
        val sweep = Math.toRadians(sweepAngleDegrees.toDouble())
        val cx = rect.center.x; val cy = rect.center.y
        val rx = rect.width / 2f; val ry = rect.height / 2f
        val n = max(2, segments(abs(sweep).toFloat() * max(rx, ry)))
        for (i in 0..n) {
            val a = start + sweep * i / n
            val x = cx + rx * cos(a).toFloat(); val y = cy + ry * sin(a).toFloat()
            if (i == 0 && (forceMoveTo || cur.size == 0)) moveTo(x, y) else lineTo(x, y)
        }
    }

    override fun addRect(rect: Rect, direction: Path.Direction) {
        val empty = isEmpty
        moveTo(rect.left, rect.top); lineTo(rect.right, rect.top); lineTo(rect.right, rect.bottom); lineTo(rect.left, rect.bottom); close()
        wind(direction)
        if (empty) plainRect = rect
    }

    // shapes are built clockwise; counter-clockwise ones (Compose's default) are reversed after their first point, like Skia
    private fun wind(direction: Path.Direction) {
        if (direction != Path.Direction.CounterClockwise || contours.isEmpty()) return
        val c = contours.last()
        val n = c.size / 2
        for (i in 0 until (n - 1) / 2) {
            val a = (1 + i) * 2
            val b = (n - 1 - i) * 2
            val x = c[a]; val y = c[a + 1]
            c[a] = c[b]; c[a + 1] = c[b + 1]
            c[b] = x; c[b + 1] = y
        }
    }

    override fun addOval(oval: Rect, direction: Path.Direction) {
        val empty = isEmpty
        arcTo(oval, 0f, 359.999f, true); close()
        wind(direction)
        if (empty && oval.width == oval.height) roundRect = RoundRect(oval, CornerRadius(oval.width / 2f))
    }

    override fun addRoundRect(roundRect: RoundRect, direction: Path.Direction) {
        val empty = isEmpty
        val r = roundRect.withFittedRadii()
        fun corner(cx: Float, cy: Float, rx: Float, ry: Float, startDeg: Float) {
            if (rx <= 0f || ry <= 0f) { lineTo(cx + rx * cos(Math.toRadians(startDeg + 45.0)).toFloat() * 1.4142135f, cy + ry * sin(Math.toRadians(startDeg + 45.0)).toFloat() * 1.4142135f); return }
            val n = segments(PI.toFloat() / 2f * max(rx, ry))
            for (i in 0..n) {
                val a = Math.toRadians(startDeg + 90.0 * i / n)
                lineTo(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
            }
        }
        moveTo(r.left + r.topLeftCornerRadius.x, r.top)
        corner(r.right - r.topRightCornerRadius.x, r.top + r.topRightCornerRadius.y, r.topRightCornerRadius.x, r.topRightCornerRadius.y, 270f)
        corner(r.right - r.bottomRightCornerRadius.x, r.bottom - r.bottomRightCornerRadius.y, r.bottomRightCornerRadius.x, r.bottomRightCornerRadius.y, 0f)
        corner(r.left + r.bottomLeftCornerRadius.x, r.bottom - r.bottomLeftCornerRadius.y, r.bottomLeftCornerRadius.x, r.bottomLeftCornerRadius.y, 90f)
        corner(r.left + r.topLeftCornerRadius.x, r.top + r.topLeftCornerRadius.y, r.topLeftCornerRadius.x, r.topLeftCornerRadius.y, 180f)
        close()
        wind(direction)
        if (empty) this.roundRect = r
    }

    override fun addArcRad(oval: Rect, startAngleRadians: Float, sweepAngleRadians: Float): Unit =
        addArc(oval, Math.toDegrees(startAngleRadians.toDouble()).toFloat(), Math.toDegrees(sweepAngleRadians.toDouble()).toFloat())

    override fun addArc(oval: Rect, startAngleDegrees: Float, sweepAngleDegrees: Float): Unit = arcTo(oval, startAngleDegrees, sweepAngleDegrees, true)

    override fun addPath(path: Path, offset: Offset) {
        val other = path as PolyComposePath
        val empty = isEmpty
        poly = null
        flush(false)
        other.allContours().forEachIndexed { i, pts ->
            contours.add(FloatArray(pts.size) { k -> pts[k] + if (k % 2 == 0) offset.x else offset.y })
            closed.add(other.closedAt(i))
        }
        other.allContours().lastOrNull()?.let { c ->
            val k = if (other.closedAt(other.allContours().size - 1)) 0 else c.size - 2
            lastX = c[k] + offset.x; lastY = c[k + 1] + offset.y
            startX = c[0] + offset.x; startY = c[1] + offset.y
        }
        if (empty && offset == Offset.Zero) { roundRect = other.roundRect; plainRect = other.plainRect } else shapeChanged()
    }

    override fun close() {
        if (cur.size >= 2) { lastX = startX; lastY = startY }
        poly = null
        flush(true)
    }

    override fun reset() {
        contours.clear(); closed = ArrayList(); cur = FloatArrayList()
        lastX = 0f; lastY = 0f; startX = 0f; startY = 0f
        shapeChanged()
    }

    override fun rewind(): Unit = reset()

    override fun translate(offset: Offset) {
        for (c in contours) for (k in c.indices) c[k] += if (k % 2 == 0) offset.x else offset.y
        cur.offset(offset.x, offset.y)
        startX += offset.x; startY += offset.y; lastX += offset.x; lastY += offset.y
        poly = null
        roundRect = roundRect?.let { it.copy(left = it.left + offset.x, top = it.top + offset.y, right = it.right + offset.x, bottom = it.bottom + offset.y) }
        plainRect = plainRect?.translate(offset)
    }

    override fun getBounds(): Rect = bounds(allContours())

    override fun op(path1: Path, path2: Path, operation: PathOperation): Boolean {
        val (result, type) = pathOp(path1 as PolyComposePath, path2 as PolyComposePath, operation)
        reset()
        fillType = type
        for (c in result) { contours.add(c); closed.add(true) }
        return true
    }

    @Deprecated("") override fun addRect(rect: Rect): Unit = addRect(rect, Path.Direction.CounterClockwise)
    @Deprecated("") override fun addOval(oval: Rect): Unit = addOval(oval, Path.Direction.CounterClockwise)
    @Deprecated("") override fun addRoundRect(roundRect: RoundRect): Unit = addRoundRect(roundRect, Path.Direction.CounterClockwise)

    internal fun allContours(): List<FloatArray> = if (cur.size >= 4) contours + cur.toArray() else contours
    internal fun closedAt(i: Int): Boolean = if (i < closed.size) closed[i] else false

    private fun segments(length: Float) = ceil(sqrt(max(length, 1f)) * 1.5f).toInt().coerceIn(1, 64)
}

private fun RoundRect.withFittedRadii(): RoundRect {
    fun fit(side: Float, a: Float, b: Float) = if (a + b > side) (side / (a + b)).coerceAtLeast(0f) else 1f
    val scale = minOf(
        minOf(fit(width, topLeftCornerRadius.x, topRightCornerRadius.x), fit(width, bottomLeftCornerRadius.x, bottomRightCornerRadius.x)),
        minOf(fit(height, topLeftCornerRadius.y, bottomLeftCornerRadius.y), fit(height, topRightCornerRadius.y, bottomRightCornerRadius.y)),
    )
    if (scale >= 1f) return this
    return copy(
        topLeftCornerRadius = topLeftCornerRadius * scale,
        topRightCornerRadius = topRightCornerRadius * scale,
        bottomRightCornerRadius = bottomRightCornerRadius * scale,
        bottomLeftCornerRadius = bottomLeftCornerRadius * scale,
    )
}

internal fun bounds(contours: List<FloatArray>): Rect {
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (c in contours) for (k in c.indices step 2) {
        l = min(l, c[k]); r = max(r, c[k]); t = min(t, c[k + 1]); b = max(b, c[k + 1])
    }
    return if (l > r) Rect.Zero else Rect(l, t, r, b)
}

internal class FloatArrayList {
    private var a = FloatArray(16)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (size + 2 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = x; a[size++] = y
    }

    operator fun get(i: Int): Float = a[i]
    fun dropPoint() { size -= 2 }
    fun offset(dx: Float, dy: Float) { for (k in 0 until size) a[k] += if (k % 2 == 0) dx else dy }
    fun toArray(): FloatArray = a.copyOf(size)
}
