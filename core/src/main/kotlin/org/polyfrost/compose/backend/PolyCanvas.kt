@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.backend

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StampedPathEffectStyle
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.VertexMode
import androidx.compose.ui.graphics.Vertices
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.ChromaShader
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyFade
import org.polyfrost.compose.render.PolyGradient
import org.polyfrost.compose.render.PolyImageFill
import org.polyfrost.compose.render.PolyPath
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyShadow
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.compose.render.placeBlur
import org.polyfrost.compose.skiko.colorFilterEffect
import java.util.WeakHashMap
import java.util.function.IntSupplier
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.polyfrost.compose.render.FilterQuality as PolyFilterQuality

public interface PolyDrawCanvas : Canvas {
    public fun polyOp(op: (PolyCanvas) -> Unit)
}

private const val RECOLORS_KEPT = 8

private val recolorCache = WeakHashMap<PolyBitmap, LinkedHashMap<MatrixKey, PolyBitmap>>()

private class MatrixKey(val values: FloatArray) {
    override fun equals(other: Any?): Boolean = other is MatrixKey && values.contentEquals(other.values)
    override fun hashCode(): Int = values.contentHashCode()
}

private val whiteCache = WeakHashMap<PolyBitmap, Boolean>()

private val opaqueCache = WeakHashMap<PolyBitmap, Boolean>()

private val tintCache = WeakHashMap<PolyBitmap, LinkedHashMap<Long, PolyBitmap>>()

// keeps alpha and makes every color channel white, so multiplying by the tint afterwards gives SrcIn
private val WHITEN = ColorMatrix(floatArrayOf(0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))

// tinted images are usually already white masks, which SrcIn and multiplying treat the same, so those skip recoloring
private fun isWhite(bitmap: PolyBitmap): Boolean {
    synchronized(whiteCache) { whiteCache[bitmap] }?.let { return it }
    val made = run {
        val px = bitmap.pixels
        (px.indices step 4).all { o -> px[o + 3].toInt() == 0 || px[o].toInt() == -1 && px[o + 1].toInt() == -1 && px[o + 2].toInt() == -1 }
    }
    synchronized(whiteCache) { whiteCache[bitmap] = made }
    return made
}

private fun isOpaque(bitmap: PolyBitmap): Boolean {
    synchronized(opaqueCache) { opaqueCache[bitmap] }?.let { return it }
    val made = run {
        val px = bitmap.pixels
        (px.indices step 4).all { o -> px[o + 3].toInt() == -1 }
    }
    synchronized(opaqueCache) { opaqueCache[bitmap] = made }
    return made
}

private fun tinted(bitmap: PolyBitmap, color: Int, mode: BlendMode): PolyBitmap {
    val key = (color.toLong() shl 32) or (mode.hashCode().toLong() and 0xFFFFFFFFL)
    synchronized(tintCache) { tintCache[bitmap]?.get(key) }?.let { return it }
    val made = run {
        val src = bitmap.pixels
        val out = ByteArray(src.size)
        for (o in src.indices step 4) {
            val d = ((src[o + 3].toInt() and 0xFF) shl 24) or ((src[o].toInt() and 0xFF) shl 16) or ((src[o + 1].toInt() and 0xFF) shl 8) or (src[o + 2].toInt() and 0xFF)
            val c = blendArgb(color, d, mode)
            out[o] = (c shr 16).toByte(); out[o + 1] = (c shr 8).toByte(); out[o + 2] = c.toByte(); out[o + 3] = (c ushr 24).toByte()
        }
        PolyBitmap(bitmap.width, bitmap.height, out)
    }
    return synchronized(tintCache) {
        val kept = tintCache.getOrPut(bitmap) {
            object : LinkedHashMap<Long, PolyBitmap>(RECOLORS_KEPT + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, PolyBitmap>) = size > RECOLORS_KEPT
            }
        }
        kept.getOrPut(key) { made }
    }
}

private val imageBlurs = ThreadLocal.withInitial { FloatArray(1) }

internal var imageBlur: Float
    get() = imageBlurs.get()[0]
    set(value) {
        imageBlurs.get()[0] = value
    }

/**
 * A blurred copy of a bitmap, [scaleX] by [scaleY] times smaller and padded by [padX] and [padY] of its own pixels on
 * each side so the blur can spread past the original edges
 */
internal class Blurred(val bitmap: PolyBitmap, val scaleX: Int, val scaleY: Int, val padX: Int, val padY: Int)

/** Blurred copies kept per bitmap; an animated blur asks for a new radius most frames so older ones are dropped */
private const val BLURS_KEPT = 4

private const val MAX_BLUR_KEY = 2048

private const val MAX_BLUR_PIXELS = 4_000_000L

private val blurCache = WeakHashMap<PolyBitmap, LinkedHashMap<Long, Blurred>>()

internal fun blurred(bitmap: PolyBitmap, sigmaX: Float, sigmaY: Float): Blurred? {
    val keyX = (sigmaX * 4f).toInt().coerceIn(0, MAX_BLUR_KEY)
    val keyY = (sigmaY * 4f).toInt().coerceIn(0, MAX_BLUR_KEY)
    if (keyX <= 0 && keyY <= 0) return null
    val key = (keyX.toLong() shl 32) or keyY.toLong()
    synchronized(blurCache) { blurCache[bitmap]?.get(key) }?.let { return it }
    val made = run {
        val fullX = keyX / 4f
        val fullY = keyY / 4f
        // a wide blur hides detail anyway, so blurring a smaller copy looks the same for a fraction of the work
        var scaleX = (fullX / 3f).toInt().coerceIn(1, 64)
        var scaleY = (fullY / 3f).toInt().coerceIn(1, 64)
        fun padFor(full: Float, sc: Int) = ceil(full / sc * 3f).toInt()
        fun pixelsFor(sx: Int, sy: Int): Long =
            ((bitmap.width + sx - 1) / sx + 2L * padFor(fullX, sx)) * ((bitmap.height + sy - 1) / sy + 2L * padFor(fullY, sy))
        while (pixelsFor(scaleX, scaleY) > MAX_BLUR_PIXELS && max(scaleX, scaleY) < Int.MAX_VALUE / 4) {
            scaleX = scaleX * 3 / 2 + 1
            scaleY = scaleY * 3 / 2 + 1
        }
        val padX = padFor(fullX, scaleX)
        val padY = padFor(fullY, scaleY)
        val sw = (bitmap.width + scaleX - 1) / scaleX
        val sh = (bitmap.height + scaleY - 1) / scaleY
        val w = sw + 2 * padX
        val h = sh + 2 * padY
        val src = bitmap.pixels
        // premultiplied and averaged down to the smaller copy, with transparent padding around it
        val buf = FloatArray(w * h * 4)
        for (y in 0 until sh) for (x in 0 until sw) {
            var r = 0f; var g = 0f; var b = 0f; var a = 0f; var n = 0
            for (yy in y * scaleY until min((y + 1) * scaleY, bitmap.height)) for (xx in x * scaleX until min((x + 1) * scaleX, bitmap.width)) {
                val o = (yy * bitmap.width + xx) * 4
                val pa = (src[o + 3].toInt() and 0xFF) / 255f
                r += (src[o].toInt() and 0xFF) * pa
                g += (src[o + 1].toInt() and 0xFF) * pa
                b += (src[o + 2].toInt() and 0xFF) * pa
                a += pa * 255f
                n++
            }
            val o = ((y + padY) * w + x + padX) * 4
            buf[o] = r / n; buf[o + 1] = g / n; buf[o + 2] = b / n; buf[o + 3] = a / n
        }
        val tmp = FloatArray(buf.size)
        // three box passes approximate a gaussian; a fractional radius keeps an animated blur from stepping
        val sx = fullX / scaleX
        val sy = fullY / scaleY
        val radiusX = (sqrt(4f * sx * sx + 1f) - 1f) / 2f
        val radiusY = (sqrt(4f * sy * sy + 1f) - 1f) / 2f
        for (pass in 0 until 3) {
            boxBlur(buf, tmp, w, h, radiusX, 4, w * 4)
            boxBlur(tmp, buf, h, w, radiusY, w * 4, 4)
        }
        val out = ByteArray(w * h * 4)
        for (i in 0 until w * h) {
            val a = buf[i * 4 + 3]
            if (a <= 0f) continue
            for (c in 0..2) out[i * 4 + c] = (buf[i * 4 + c] / (a / 255f)).toInt().coerceIn(0, 255).toByte()
            out[i * 4 + 3] = a.toInt().coerceIn(0, 255).toByte()
        }
        Blurred(PolyBitmap(w, h, out), scaleX, scaleY, padX, padY)
    }
    return synchronized(blurCache) {
        val kept = blurCache.getOrPut(bitmap) {
            object : LinkedHashMap<Long, Blurred>(BLURS_KEPT + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Blurred>) = size > BLURS_KEPT
            }
        }
        kept.getOrPut(key) { made }
    }
}

/** One box blur along [len] samples of each line, where the window reaches [r] samples each side with the last one weighted by its fraction */
private fun boxBlur(src: FloatArray, dst: FloatArray, len: Int, lines: Int, r: Float, step: Int, lineStep: Int) {
    val whole = r.toInt()
    val frac = r - whole
    val norm = 1f / (2f * r + 1f)
    for (line in 0 until lines) {
        val base = line * lineStep
        for (c in 0..3) {
            // outside the line is transparent padding, so it counts as zero
            fun at(i: Int) = if (i < 0 || i >= len) 0f else src[base + i * step + c]
            var sum = 0f
            for (i in -whole..whole) sum += at(i)
            for (i in 0 until len) {
                dst[base + i * step + c] = (sum + frac * (at(i - whole - 1) + at(i + whole + 1))) * norm
                sum += at(i + whole + 1) - at(i - whole)
            }
        }
    }
}

private fun recolored(bitmap: PolyBitmap, matrix: ColorMatrix): PolyBitmap {
    val m = matrix.values
    synchronized(recolorCache) { recolorCache[bitmap]?.get(MatrixKey(m)) }?.let { return it }
    val made = run {
        val src = bitmap.pixels
        val out = ByteArray(src.size)
        for (o in src.indices step 4) {
            val r = (src[o].toInt() and 0xFF).toFloat()
            val g = (src[o + 1].toInt() and 0xFF).toFloat()
            val b = (src[o + 2].toInt() and 0xFF).toFloat()
            val a = (src[o + 3].toInt() and 0xFF).toFloat()
            for (c in 0..3) {
                val i = c * 5
                out[o + c] = (m[i] * r + m[i + 1] * g + m[i + 2] * b + m[i + 3] * a + m[i + 4]).toInt().coerceIn(0, 255).toByte()
            }
        }
        PolyBitmap(bitmap.width, bitmap.height, out)
    }
    return synchronized(recolorCache) {
        val kept = recolorCache.getOrPut(bitmap) {
            object : LinkedHashMap<MatrixKey, PolyBitmap>(RECOLORS_KEPT + 1, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MatrixKey, PolyBitmap>) = size > RECOLORS_KEPT
            }
        }
        kept.getOrPut(MatrixKey(m.copyOf())) { made }
    }
}

private fun Paint.fadeMask(): PolyFade? = (shader?.platformShader as? GradientShader)?.fade(alpha, blendMode == BlendMode.Modulate)

private fun Paint.masks() = blendMode == BlendMode.DstIn || blendMode == BlendMode.Modulate

private const val BLEND_IMAGE = 0
private const val BLEND_STROKE = 1
private const val BLEND_FILL = 2

internal fun blendOf(mode: BlendMode): Int = when (mode) {
    BlendMode.SrcOver -> PolyRenderer.BLEND_SRC_OVER
    BlendMode.Src -> PolyRenderer.BLEND_SRC
    BlendMode.DstOut -> PolyRenderer.BLEND_DST_OUT
    BlendMode.DstIn -> PolyRenderer.BLEND_DST_IN
    BlendMode.Plus -> PolyRenderer.BLEND_PLUS
    BlendMode.SrcIn -> PolyRenderer.BLEND_SRC_IN
    BlendMode.SrcOut -> PolyRenderer.BLEND_SRC_OUT
    BlendMode.SrcAtop -> PolyRenderer.BLEND_SRC_ATOP
    BlendMode.DstOver -> PolyRenderer.BLEND_DST_OVER
    BlendMode.DstAtop -> PolyRenderer.BLEND_DST_ATOP
    BlendMode.Xor -> PolyRenderer.BLEND_XOR
    BlendMode.Modulate -> PolyRenderer.BLEND_MODULATE
    BlendMode.Screen -> PolyRenderer.BLEND_SCREEN
    else -> -1
}

internal fun layerBlendOf(mode: BlendMode): Int = if (mode == BlendMode.Clear) PolyRenderer.BLEND_SRC else blendOf(mode).coerceAtLeast(PolyRenderer.BLEND_SRC_OVER)

internal fun layerAlphaOf(mode: BlendMode, alpha: Float): Float = if (mode == BlendMode.Clear || mode == BlendMode.Dst) 0f else alpha

internal fun isPortableBlend(mode: BlendMode): Boolean = when (mode) {
    BlendMode.SrcOver, BlendMode.Clear, BlendMode.Dst, BlendMode.Src, BlendMode.DstOut, BlendMode.DstIn, BlendMode.Plus -> true
    else -> false
}

private fun PolyPaint.solid(alpha: Float): PolyPaint = copy().also {
    it.color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))
    it.shader = null
    it.colorFilter = null
}

private class MaskOp(val fade: PolyFade) : (PolyCanvas) -> Unit {
    override fun invoke(c: PolyCanvas) = c.renderer.restore()
}

internal class DynamicOp(val block: (RenderContext) -> Unit) : (PolyCanvas) -> Unit {
    override fun invoke(c: PolyCanvas) = block(c.context)
}

public fun DrawScope.drawPoly(block: (RenderContext) -> Unit): Unit = drawIntoCanvas { c ->
    (c as? PolyDrawCanvas)?.polyOp(DynamicOp(block))
}

internal fun PolyRenderer.clipTo(path: Path, difference: Boolean = false) {
    val p = path as PolyComposePath
    val rect = p.plainRect
    if (difference) {
        if (rect != null) return clipOutRect(rect.left, rect.top, rect.width, rect.height)
        return clipPath(p.polyPath(), true)
    }
    val rr = p.roundRect
    if (rr != null && rr.isSimple) return clipRRect(rr.left, rr.top, rr.width, rr.height, rr.topLeftCornerRadius.x)
    if (rect != null) return clipRect(rect.left, rect.top, rect.width, rect.height)
    clipPath(p.polyPath(), false)
}

public class PolyCanvas(public val renderer: PolyRenderer) : PolyDrawCanvas {
    private val r get() = renderer
    public val context: RenderContext by lazy(LazyThreadSafetyMode.NONE) { RenderContext(renderer) }

    init {
        PolyCompose.install()
    }

    override fun polyOp(op: (PolyCanvas) -> Unit): Unit = op(this)

    override fun save(): Unit = r.save()
    override fun restore(): Unit = r.restore()

    private var layerFilter: ColorFilter? = null
    private var layerChain: PolyEffectChain? = null

    override fun saveLayer(bounds: Rect, paint: Paint) {
        val mode = layerBlendOf(paint.blendMode)
        val alpha = layerAlphaOf(paint.blendMode, paint.alpha)
        val finite = bounds.isFinite
        val chain = if (finite && r.effectSupport() and PolyRenderer.EFFECT_LAYER != 0) paint.colorFilter?.let(::filterChain) else null
        if (chain != null) r.saveLayerEffect(bounds.left, bounds.top, bounds.width, bounds.height, 0f, chain, alpha, mode)
        else r.saveLayerBlend(alpha, mode)
        if (finite) r.clipRect(bounds.left, bounds.top, bounds.width, bounds.height)
    }

    private fun filterChain(filter: ColorFilter): PolyEffectChain? {
        if (filter == layerFilter) return layerChain
        val chain = colorFilterEffect(filter)?.let { PolyEffectChain(listOf(it)) }
        layerFilter = filter
        layerChain = chain
        return chain
    }

    override fun translate(dx: Float, dy: Float): Unit = r.translate(dx, dy)
    override fun scale(sx: Float, sy: Float): Unit = r.scale(sx, sy)

    override fun rotate(degrees: Float): Unit = r.rotate(degrees)
    override fun skew(sx: Float, sy: Float): Unit = r.skew(sx, sy)

    override fun concat(matrix: Matrix) {
        val v = matrix.values
        r.translate(v[Matrix.TranslateX], v[Matrix.TranslateY])
        val a = v[Matrix.ScaleX]
        val b = v[Matrix.SkewY]
        val c = v[Matrix.SkewX]
        val d = v[Matrix.ScaleY]
        if (b == 0f && c == 0f) return r.scale(a, d)
        val sx = hypot(a, b)
        if (sx == 0f) return r.scale(0f, 0f)
        if (b != 0f) r.rotate(Math.toDegrees(atan2(b, a).toDouble()).toFloat())
        r.scale(sx, (a * d - b * c) / sx)
        val shear = (a * c + b * d) / (sx * sx)
        if (shear != 0f) r.skew(shear, 0f)
    }

    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp): Unit =
        if (clipOp == ClipOp.Intersect) r.clipRect(left, top, right - left, bottom - top) else r.clipOutRect(left, top, right - left, bottom - top)

    override fun clipPath(path: Path, clipOp: ClipOp): Unit = r.clipTo(path, clipOp == ClipOp.Difference)

    override fun drawLine(p1: Offset, p2: Offset, paint: Paint): Unit = blended(paint, BLEND_STROKE) { line(p1, p2, it) }

    private fun line(p1: Offset, p2: Offset, pp: PolyPaint) {
        if (chromaOf(pp) != null) {
            val sw = strokeWidthOf(pp)
            return chromaLive(pp, min(p1.x, p2.x) - sw, min(p1.y, p2.y) - sw, abs(p1.x - p2.x) + sw * 2f, abs(p1.y - p2.y) + sw * 2f) { c, tp -> c.segment(p1.x, p1.y, p2.x, p2.y, tp) }
        }
        segment(p1.x, p1.y, p2.x, p2.y, pp)
    }

    private fun strokeWidthOf(paint: PolyPaint): Float = if (paint.strokeWidth > 0f) paint.strokeWidth else r.hairlineWidth()

    private fun strokeWidthOf(paint: PolyPaint, dx: Float, dy: Float): Float = if (paint.strokeWidth > 0f) paint.strokeWidth else r.hairlineWidth(dx, dy)

    private fun chromaOf(paint: PolyPaint): ChromaShader? = paint.shader?.platformShader as? ChromaShader

    private fun chromaLive(paint: PolyPaint, x: Float, y: Float, w: Float, h: Float, draw: (PolyCanvas, PolyPaint) -> Unit) {
        val color = chromaOf(paint)?.color ?: return
        val alpha = paint.alpha
        val tinted = paint.copy().also { it.shader = null }
        fun argb(): Int {
            val c = color.argb
            return (((c ushr 24) * alpha).toInt() shl 24) or (c and 0xFFFFFF)
        }
        r.live(x, y, w, h, IntSupplier { argb() }) {
            tinted.color = Color(argb())
            draw(if (it === r) this else PolyCanvas(it), tinted)
        }
    }

    private fun segment(x1: Float, y1: Float, x2: Float, y2: Float, paint: PolyPaint) {
        if (paint.strokeCap == StrokeCap.Butt && !paint.pathEffect.reshapes(true) && paint.shader == null) {
            return r.line(x1, y1, x2, y2, paint.argb(), strokeWidthOf(paint, x2 - x1, y2 - y1))
        }
        strokeContours(listOf(floatArrayOf(x1, y1, x2, y2)), OPEN, paint)
    }

    private fun strokeContours(contours: List<FloatArray>, closed: List<Boolean>, paint: PolyPaint) {
        var cs = contours
        var cl = closed
        val e = paint.pathEffect
        if (e != null && e.reshapes(true)) {
            val out = effected(e, cs, cl, true)
            if (out.filled) return fillContours(out.contours, false, paint)
            cs = out.contours
            cl = out.closed
        }
        if (cs.isEmpty()) return
        val color = paint.argb()
        val sw = strokeWidthOf(paint)
        val cap = when (paint.strokeCap) { StrokeCap.Round -> PolyRenderer.CAP_ROUND; StrokeCap.Square -> PolyRenderer.CAP_SQUARE; else -> PolyRenderer.CAP_BUTT }
        val join = when (paint.strokeJoin) { StrokeJoin.Round -> PolyRenderer.JOIN_ROUND; StrokeJoin.Bevel -> PolyRenderer.JOIN_BEVEL; else -> PolyRenderer.JOIN_MITER }
        val miter = paint.strokeMiterLimit
        val box = if (paint.shader == null) Rect.Zero else bounds(cs).inflate(sw / 2f)
        shaded(paint, box.left, box.top, box.width, box.height) { g ->
            for (i in cs.indices) {
                if (g == null) r.strokePolyline(cs[i], cl[i], color, sw, cap, join, miter)
                else r.strokePolylineGradient(cs[i], cl[i], box.left, box.top, box.width, box.height, g, sw, cap, join, miter)
            }
        }
    }

    private fun fillContours(contours: List<FloatArray>, evenOdd: Boolean, paint: PolyPaint) {
        val poly = PolyPath()
        poly.evenOdd = evenOdd
        for (i in contours.indices) {
            val c = contours[i]
            if (c.size < 6) continue
            poly.moveTo(c[0], c[1])
            for (k in 2 until c.size step 2) poly.lineTo(c[k], c[k + 1])
            poly.close()
        }
        val b = bounds(contours)
        if (b.width <= 0f || b.height <= 0f) return
        shaded(paint, b.left, b.top, b.width, b.height) { g ->
            if (g == null) r.fillPath(poly, paint.argb()) else r.fillPathGradient(poly, b.left, b.top, b.width, b.height, g)
        }
    }

    private var fadePushed = false

    private inline fun shaded(paint: PolyPaint, x: Float, y: Float, w: Float, h: Float, draw: (PolyGradient?) -> Unit) {
        val s = paint.shader?.platformShader
        if (s == null || s is ChromaShader) return draw(null)
        if (paint.alpha < 1f) r.saveLayerAlpha(paint.alpha) else r.save()
        fadePushed = false
        val brush = shaderBrush(s, x, y, w, h, paint.colorFilter)
        val faded = fadePushed
        if (brush != null) draw(brush)
        if (faded) r.restore()
        r.restore()
    }

    private fun shaderBrush(s: PlatformShader, x: Float, y: Float, w: Float, h: Float, filter: ColorFilter?): PolyGradient? {
        when (s) {
            is GradientShader -> if (s.tile == TileMode.Clamp || s.tile == TileMode.Decal && decalClip(s, x, y, w, h)) return s.brushFor(x, y, w, h, filter)
            is MaskedShader -> {
                r.saveLayerFade(s.mask)
                fadePushed = true
                return s.color.brushFor(x, y, w, h, filter)
            }
            is ImageShader -> if (s.direct && filter == null) {
                val b = s.bitmap() ?: return null
                if (imageDecalClip(s, b, x, y, w, h)) return s.fill(b)
            }
            is ClearShader -> return null
        }
        val k = r.deviceScale().coerceIn(MIN_RASTER_SCALE, MAX_RASTER_SCALE)
        return ShaderRaster.fill(s, x, y, w, h, k, k, filter)
    }

    private fun decalClip(s: GradientShader, x: Float, y: Float, w: Float, h: Float): Boolean {
        if (r.deviceTransform() == null) return false
        val g = s.brush
        val c = s.center
        if (g is PolyGradient.Radial && c != null) {
            r.clipRRect(c.x - s.radius, c.y - s.radius, s.radius * 2f, s.radius * 2f, s.radius)
            return true
        }
        if (g !is PolyGradient.Points) return false
        val dx = g.x1 - g.x0
        val dy = g.y1 - g.y0
        val len = hypot(dx, dy)
        if (len <= 0f) return false
        val nx = -dy / len
        val ny = dx / len
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val d = ((if (i and 1 == 0) x else x + w) - g.x0) * nx + ((if (i < 2) y else y + h) - g.y0) * ny
            lo = min(lo, d); hi = max(hi, d)
        }
        lo -= 1f; hi += 1f
        r.clipPolygon(floatArrayOf(g.x0 + nx * lo, g.y0 + ny * lo, g.x1 + nx * lo, g.y1 + ny * lo, g.x1 + nx * hi, g.y1 + ny * hi, g.x0 + nx * hi, g.y0 + ny * hi))
        return true
    }

    private fun imageDecalClip(s: ImageShader, b: PolyBitmap, x: Float, y: Float, w: Float, h: Float): Boolean {
        val decalX = s.tileX == TileMode.Decal
        val decalY = s.tileY == TileMode.Decal
        if (!decalX && !decalY) return true
        if (r.deviceTransform() == null) return false
        val inv = s.inverse ?: IDENTITY
        val fwd = invertAffine(inv) ?: return false
        var lx = Float.MAX_VALUE; var ly = Float.MAX_VALUE; var hx = -Float.MAX_VALUE; var hy = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val cx = if (i and 1 == 0) x else x + w
            val cy = if (i < 2) y else y + h
            val ix = inv[0] * cx + inv[1] * cy + inv[4]
            val iy = inv[2] * cx + inv[3] * cy + inv[5]
            lx = min(lx, ix); hx = max(hx, ix); ly = min(ly, iy); hy = max(hy, iy)
        }
        lx -= 1f; ly -= 1f; hx += 1f; hy += 1f
        if (decalX) { lx = max(lx, 0f); hx = min(hx, b.width.toFloat()) }
        if (decalY) { ly = max(ly, 0f); hy = min(hy, b.height.toFloat()) }
        if (lx >= hx || ly >= hy) {
            r.clipRect(x, y, 0f, 0f)
            return true
        }
        val pts = floatArrayOf(lx, ly, hx, ly, hx, hy, lx, hy)
        for (i in pts.indices step 2) {
            val px = pts[i]
            pts[i] = fwd[0] * px + fwd[1] * pts[i + 1] + fwd[4]
            pts[i + 1] = fwd[2] * px + fwd[3] * pts[i + 1] + fwd[5]
        }
        r.clipPolygon(pts)
        return true
    }

    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint): Unit =
        blended(paint, kindOf(paint)) { shape(left, top, right - left, bottom - top, 0f, it) }

    private fun kindOf(paint: Paint) = if (paint.style == PaintingStyle.Fill) BLEND_FILL else BLEND_STROKE

    private inline fun blended(paint: Paint, kind: Int, draw: (PolyPaint) -> Unit) {
        val p = paint as PolyPaint
        if (p.isAntiAlias) return blendedByMode(p, kind, draw)
        r.antiAlias(false)
        try {
            blendedByMode(p, kind, draw)
        } finally {
            r.antiAlias(true)
        }
    }

    private inline fun blendedByMode(p: PolyPaint, kind: Int, draw: (PolyPaint) -> Unit) {
        val mode = p.blendMode
        if (mode == BlendMode.SrcOver) return draw(p)
        when (mode) {
            BlendMode.Dst -> return
            BlendMode.Clear -> return blendedWith(PolyRenderer.BLEND_DST_OUT, p.solid(1f), draw)
            BlendMode.Src -> if (kind == BLEND_FILL && r.blend(PolyRenderer.BLEND_PLUS) && r.blend(PolyRenderer.BLEND_DST_OUT)) {
                try {
                    draw(p.solid(1f))
                    r.blend(PolyRenderer.BLEND_PLUS)
                    draw(p)
                } finally {
                    r.blend(PolyRenderer.BLEND_SRC_OVER)
                }
                return
            }
            BlendMode.DstIn -> if (kind != BLEND_IMAGE && p.shader == null) {
                return blendedWith(PolyRenderer.BLEND_DST_OUT, p.solid(1f - (p.argb() ushr 24) / 255f), draw)
            }
        }
        val m = blendOf(mode)
        if (m >= 0 && r.blend(m)) return blendedWith(m, p, draw)
        r.blend(PolyRenderer.BLEND_SRC_OVER)
        if (!p.masks()) draw(p)
    }

    internal fun blendedText(mode: BlendMode, alpha: Float, draw: (Int?) -> Unit) {
        if (mode == BlendMode.SrcOver) return draw(null)
        val p = PolyPaint().also {
            it.color = Color.Black.copy(alpha = alpha.coerceIn(0f, 1f))
            it.blendMode = mode
        }
        blended(p, BLEND_FILL) { q -> draw(if (q === p) null else q.argb()) }
    }

    private inline fun blendedWith(mode: Int, paint: PolyPaint, draw: (PolyPaint) -> Unit) {
        if (!r.blend(mode)) return
        try {
            draw(paint)
        } finally {
            r.blend(PolyRenderer.BLEND_SRC_OVER)
        }
    }

    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, radiusX: Float, radiusY: Float, paint: Paint) {
        if (radiusX <= 0f || radiusY <= 0f) return blended(paint, kindOf(paint)) { shape(left, top, right - left, bottom - top, 0f, it) }
        if (abs(radiusX - radiusY) > ELLIPTICAL_EPSILON) {
            val corners = RoundRect(Rect(left, top, right, bottom), CornerRadius(radiusX, radiusY))
            return drawPath(PolyComposePath().apply { addRoundRect(corners, Path.Direction.Clockwise) }, paint)
        }
        blended(paint, kindOf(paint)) { shape(left, top, right - left, bottom - top, radiusX, it) }
    }

    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val w = right - left
        val h = bottom - top
        if (abs(w - h) < 0.5f) blended(paint, kindOf(paint)) { shape(left, top, w, h, min(w, h) / 2f, it) }
        else drawPath(ovalPath(left, top, right, bottom), paint)
    }

    override fun drawCircle(center: Offset, radius: Float, paint: Paint): Unit =
        blended(paint, kindOf(paint)) { shape(center.x - radius, center.y - radius, radius * 2f, radius * 2f, radius, it) }

    private val arcKey = FloatArray(6)
    private var arcCenter = false
    private var arc: PolyComposePath? = null

    override fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
        val k = arcKey
        var p = arc
        if (p == null || useCenter != arcCenter || k[0] != left || k[1] != top || k[2] != right || k[3] != bottom || k[4] != startAngle || k[5] != sweepAngle) {
            p = arcPath(left, top, right, bottom, startAngle, sweepAngle, useCenter)
            k[0] = left; k[1] = top; k[2] = right; k[3] = bottom; k[4] = startAngle; k[5] = sweepAngle
            arcCenter = useCenter
            arc = p
        }
        drawPath(p, paint)
    }

    override fun drawPath(path: Path, paint: Paint): Unit = blended(paint, kindOf(paint)) { fillOrStroke(path as PolyComposePath, it) }

    private fun fillOrStroke(p: PolyComposePath, pp: PolyPaint) {
        val stroke = pp.style == PaintingStyle.Stroke
        val effect = pp.pathEffect
        val reshaped = effect != null && effect.reshapes(stroke)
        if (!reshaped) {
            p.roundRect?.takeIf { it.isSimple }?.let { return shape(it.left, it.top, it.width, it.height, it.topLeftCornerRadius.x, pp) }
            p.plainRect?.let { return shape(it.left, it.top, it.width, it.height, 0f, pp) }
        }
        if (chromaOf(pp) != null) {
            val b = p.getBounds()
            val sw = if (stroke) strokeWidthOf(pp) else 0f
            val copy = PolyComposePath().also { it.fillType = p.fillType; it.addPath(p, Offset.Zero) }
            return chromaLive(pp, b.left - sw, b.top - sw, b.width + sw * 2f, b.height + sw * 2f) { c, tp -> c.fillOrStroke(copy, tp) }
        }
        val contours = p.allContours()
        if (stroke) return strokeContours(contours, List(contours.size) { p.closedAt(it) }, pp)
        if (effect != null && reshaped) {
            return fillContours(effected(effect, contours, List(contours.size) { p.closedAt(it) }, false).contours, p.fillType == PathFillType.EvenOdd, pp)
        }
        val poly = p.polyPath()
        val b = bounds(contours)
        shaded(pp, b.left, b.top, b.width, b.height) { g ->
            if (g == null) r.fillPath(poly, pp.argb()) else r.fillPathGradient(poly, b.left, b.top, b.width, b.height, g)
        }
    }

    private fun shape(x: Float, y: Float, w: Float, h: Float, radius: Float, paint: PolyPaint) {
        if (w <= 0f || h <= 0f) return
        val style = paint.style
        val s = paint.shader?.platformShader
        val blur = if (paint.blur > 0f) paint.blur
        else if (style == PaintingStyle.Fill && s == null) imageBlur.let { if (it > 0.5f) (it - 0.5f) / 0.57735f else 0f }
        else 0f
        if (blur > 0f) {
            r.shadow(x, y, w, h, radius, PolyShadow(PolyColor(paint.argb()), blur, 0f, 0f, 0f))
            return
        }
        (s as? ChromaShader)?.let { return chromaShape(x, y, w, h, radius, paint, it.color) }
        val stroke = style == PaintingStyle.Stroke
        if (paint.pathEffect.reshapes(stroke)) return fillOrStroke(outline(x, y, w, h, radius), paint)
        if (stroke) {
            val cutCorners = radius <= 0f && (paint.strokeJoin != StrokeJoin.Miter || paint.strokeMiterLimit * paint.strokeMiterLimit < 2f)
            if (cutCorners || (s != null && !(s is GradientShader && s.tile == TileMode.Clamp) && s !is MaskedShader)) {
                return strokeContours(outline(x, y, w, h, radius).allContours(), CLOSED, paint)
            }
            val sw = strokeWidthOf(paint)
            return shaded(paint, x, y, w, h) { g ->
                if (g == null) r.rectStroke(x, y, w, h, paint.argb(), sw, radius)
                else r.rectStrokeGradient(x, y, w, h, g, sw, radius)
            }
        }
        shaded(paint, x, y, w, h) { g -> if (g == null) r.rect(x, y, w, h, paint.argb(), radius) else r.rectGradient(x, y, w, h, g, radius) }
    }

    private fun chromaShape(x: Float, y: Float, w: Float, h: Float, radius: Float, paint: PolyPaint, color: PolyColor) {
        val stroke = paint.style == PaintingStyle.Stroke
        val sw = strokeWidthOf(paint)
        val alpha = paint.alpha
        fun tinted(): Int {
            val c = color.argb
            return (((c ushr 24) * alpha).toInt() shl 24) or (c and 0xFFFFFF)
        }
        r.live(x, y, w, h, IntSupplier { tinted() }) {
            if (stroke) it.rectStroke(x, y, w, h, tinted(), sw, radius) else it.rect(x, y, w, h, tinted(), radius)
        }
    }

    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) {
        val w = image.width.toFloat()
        val h = image.height.toFloat()
        if (paint.blendMode == BlendMode.Clear) return drawRect(topLeftOffset.x, topLeftOffset.y, topLeftOffset.x + w, topLeftOffset.y + h, paint)
        blended(paint, BLEND_IMAGE) { image(image, 0f, 0f, w, h, topLeftOffset.x, topLeftOffset.y, w, h, it) }
    }

    override fun drawImageRect(image: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, dstOffset: IntOffset, dstSize: IntSize, paint: Paint) {
        val x = dstOffset.x.toFloat()
        val y = dstOffset.y.toFloat()
        if (paint.blendMode == BlendMode.Clear) return drawRect(x, y, x + dstSize.width, y + dstSize.height, paint)
        blended(paint, BLEND_IMAGE) {
            image(image, srcOffset.x.toFloat(), srcOffset.y.toFloat(), srcSize.width.toFloat(), srcSize.height.toFloat(), x, y, dstSize.width.toFloat(), dstSize.height.toFloat(), it)
        }
    }

    private fun image(image: ImageBitmap, srcX: Float, srcY: Float, srcW: Float, srcH: Float, dstX: Float, dstY: Float, dstW: Float, dstH: Float, paint: PolyPaint) {
        var bmp = (image as? PolyImageBitmap)?.bitmap ?: return
        var sx = srcX; var sy = srcY; var sw = srcW; var sh = srcH
        var x = dstX; var y = dstY; var w = dstW; var h = dstH
        val sigma = imageBlur
        val blur = if (sigma > 0f) placeBlur(bmp, sigma, sx, sy, sw, sh, x, y, w, h) else null
        if (blur != null) {
            bmp = blur.bitmap
            sx = blur.srcX; sy = blur.srcY; sw = blur.srcW; sh = blur.srcH
            x = blur.x; y = blur.y; w = blur.w; h = blur.h
        }
        val alpha = paint.alpha
        var tint = ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        when (val filter = paint.colorFilter?.let { PolyPaint.filterPlatform(it) }) {
            is MatrixFilter -> bmp = recolored(bmp, filter.matrix)
            is TintFilter -> {
                val c = filter.color.toArgb()
                when (filter.blendMode) {
                    BlendMode.Dst -> {}
                    BlendMode.SrcIn -> {
                        if (!isWhite(bmp)) bmp = recolored(bmp, WHITEN)
                        tint = withAlpha(c, alpha)
                    }
                    BlendMode.Modulate -> tint = withAlpha(c, alpha)
                    BlendMode.Multiply -> if (isOpaque(bmp)) tint = multiplied(filter.color, alpha) else bmp = tinted(bmp, c, filter.blendMode)
                    else -> bmp = tinted(bmp, c, filter.blendMode)
                }
            }
        }
        val iw = bmp.width.toFloat()
        val ih = bmp.height.toFloat()
        val fq = when (paint.filterQuality) {
            // a blurred copy is smaller or padded, so nearest sampling would show its pixels
            FilterQuality.None -> if (blur != null) PolyFilterQuality.Low else PolyFilterQuality.None
            FilterQuality.Low -> PolyFilterQuality.Low
            FilterQuality.Medium -> PolyFilterQuality.Medium
            else -> PolyFilterQuality.High
        }
        r.image(bmp, x, y, w, h, sx / iw, sy / ih, (sx + sw) / iw, (sy + sh) / ih, tint, fq)
    }

    private fun withAlpha(c: Int, alpha: Float): Int = (((c ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)

    private fun multiplied(c: Color, alpha: Float): Int {
        val a = c.alpha
        fun ch(v: Float) = ((1f - a + a * v) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return ((alpha * 255f).toInt().coerceIn(0, 255) shl 24) or (ch(c.red) shl 16) or (ch(c.green) shl 8) or ch(c.blue)
    }

    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) {
        val raw = FloatArray(points.size * 2) { if (it % 2 == 0) points[it / 2].x else points[it / 2].y }
        drawRawPoints(pointMode, raw, paint)
    }

    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint): Unit = blended(paint, BLEND_STROKE) { rawPoints(pointMode, points, it) }

    private fun rawPoints(pointMode: PointMode, points: FloatArray, pp: PolyPaint) {
        val sw = strokeWidthOf(pp)
        if (chromaOf(pp) != null) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rt = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (k in 0 until points.size - 1 step 2) { l = min(l, points[k]); rt = max(rt, points[k]); t = min(t, points[k + 1]); b = max(b, points[k + 1]) }
            if (l > rt) return
            val copy = points.copyOf()
            return chromaLive(pp, l - sw, t - sw, rt - l + sw * 2f, b - t + sw * 2f) { c, tp -> c.rawPoints(pointMode, copy, tp) }
        }
        val c = pp.argb()
        when (pointMode) {
            PointMode.Points -> for (k in points.indices step 2) r.rect(points[k] - sw / 2f, points[k + 1] - sw / 2f, sw, sw, c, if (pp.strokeCap == StrokeCap.Round) sw / 2f else 0f)
            PointMode.Lines -> for (k in 0 until points.size - 3 step 4) segment(points[k], points[k + 1], points[k + 2], points[k + 3], pp)
            else -> if (points.size >= 4) strokeContours(listOf(points), OPEN, pp)
        }
    }

    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) {
        val pp = paint as PolyPaint
        val order = triangleOrder(vertices)
        if (order.size < 3) return
        val positions = vertices.positions
        val coords = vertices.textureCoordinates.takeIf { it.size == positions.size } ?: positions
        val shader = pp.shader?.platformShader?.takeIf { blendMode != BlendMode.Dst && it !is ChromaShader }
        val image = (shader as? ImageShader)?.takeIf { it.direct && pp.colorFilter == null }
        val fill = when {
            shader == null -> null
            image != null -> image.bitmap()?.let { image.fill(it) }
            else -> shaderFill(shader, coords, pp.colorFilter)
        }
        if (shader != null && fill == null) return
        val bitmap = fill?.bitmap
        val uvs = fill?.let { f -> FloatArray(coords.size) { if (it % 2 == 0) f.u(coords[it], coords[it + 1]) else f.v(coords[it - 1], coords[it]) } }
        val decalU = image?.tileX == TileMode.Decal
        val decalV = image?.tileY == TileMode.Decal
        val count = positions.size / 2
        val fallback = if (shader != null) -1 else pp.color.toArgb() or -0x1000000
        val shaderOnly = shader != null && blendMode == BlendMode.Src
        blended(paint, BLEND_STROKE) { tp ->
            val colors = IntArray(count) { withAlpha(if (shaderOnly) -1 else vertices.colors.getOrElse(it) { fallback }, tp.alpha) }
            if (uvs != null && (decalU || decalV)) {
                val mesh = decalClipped(positions, colors, uvs, order, decalU, decalV)
                if (mesh.indices.size >= 3) r.triangles(mesh.positions, mesh.colors, mesh.indices, bitmap, mesh.uvs)
            } else {
                r.triangles(positions, colors, order, bitmap, uvs)
            }
        }
    }

    private fun shaderFill(shader: PlatformShader, coords: FloatArray, filter: ColorFilter?): PolyImageFill? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var rt = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (k in 0 until coords.size - 1 step 2) { l = min(l, coords[k]); rt = max(rt, coords[k]); t = min(t, coords[k + 1]); b = max(b, coords[k + 1]) }
        if (l > rt) return null
        val k = r.deviceScale().coerceIn(MIN_RASTER_SCALE, MAX_RASTER_SCALE)
        val w = rt - l
        val h = b - t
        return ShaderRaster.fill(shader, if (w > 0f) l else l - 0.5f, if (h > 0f) t else t - 0.5f, if (w > 0f) w else 1f, if (h > 0f) h else 1f, k, k, filter)
    }

    override fun enableZ() {}
    override fun disableZ() {}
}

private const val ELLIPTICAL_EPSILON = 0.01f

private const val MIN_RASTER_SCALE = 0.25f

private const val MAX_RASTER_SCALE = 8f

private const val MAX_STAMPS = 1000

private val OPEN = listOf(false)

private val CLOSED = listOf(true)

private fun outline(x: Float, y: Float, w: Float, h: Float, radius: Float): PolyComposePath = PolyComposePath().apply {
    if (radius > 0f) addRoundRect(RoundRect(Rect(x, y, x + w, y + h), CornerRadius(radius)), Path.Direction.Clockwise)
    else addRect(Rect(x, y, x + w, y + h), Path.Direction.Clockwise)
}

private class Effected(val contours: List<FloatArray>, val closed: List<Boolean>, val filled: Boolean)

private fun PathEffect?.reshapes(stroke: Boolean): Boolean = this != null && this !== NoPathEffect && (stroke || this !is DashEffect)

private fun effected(e: PathEffect, cs: List<FloatArray>, closed: List<Boolean>, stroke: Boolean): Effected = when (e) {
    is DashEffect -> if (!stroke) Effected(cs, closed, false) else {
        val out = ArrayList<FloatArray>()
        val oc = ArrayList<Boolean>()
        for (i in cs.indices) for (d in dashes(cs[i], closed[i], e)) { out += d; oc += d === cs[i] && closed[i] }
        Effected(out, oc, false)
    }
    is CornerEffect -> Effected(List(cs.size) { rounded(cs[it], closed[it], e.radius) }, closed, false)
    is StampEffect -> {
        val out = ArrayList<FloatArray>()
        val oc = ArrayList<Boolean>()
        for (i in cs.indices) stamp(cs[i], closed[i], e, out, oc)
        Effected(out, oc, true)
    }
    is ChainPathEffect -> {
        val a = effected(e.inner, cs, closed, stroke)
        val b = effected(e.outer, a.contours, a.closed, stroke && !a.filled)
        Effected(b.contours, b.closed, a.filled || b.filled)
    }
    else -> Effected(cs, closed, false)
}

private fun rounded(c: FloatArray, closed: Boolean, radius: Float): FloatArray {
    val pts = FloatArrayList()
    for (k in c.indices step 2) {
        val x = c[k]; val y = c[k + 1]
        if (pts.size >= 2 && pts[pts.size - 2] == x && pts[pts.size - 1] == y) continue
        pts.add(x, y)
    }
    if (closed && pts.size >= 4 && pts[0] == pts[pts.size - 2] && pts[1] == pts[pts.size - 1]) pts.dropPoint()
    val p = pts.toArray()
    val n = p.size / 2
    if (n < 3) return p
    val out = FloatArrayList()
    for (i in 0 until n) {
        val bx = p[i * 2]; val by = p[i * 2 + 1]
        if (!closed && (i == 0 || i == n - 1)) { out.add(bx, by); continue }
        val a = (i + n - 1) % n
        val d = (i + 1) % n
        val ax = p[a * 2]; val ay = p[a * 2 + 1]
        val dx = p[d * 2]; val dy = p[d * 2 + 1]
        val t1 = min(radius / hypot(ax - bx, ay - by), 0.5f)
        val t2 = min(radius / hypot(dx - bx, dy - by), 0.5f)
        val sx = bx + (ax - bx) * t1; val sy = by + (ay - by) * t1
        val ex = bx + (dx - bx) * t2; val ey = by + (dy - by) * t2
        val segs = ceil(sqrt(max(hypot(sx - bx, sy - by) + hypot(ex - bx, ey - by), 1f)) * 1.5f).toInt().coerceIn(1, 16)
        out.add(sx, sy)
        for (k in 1 until segs) {
            val t = k / segs.toFloat(); val u = 1f - t
            out.add(u * u * sx + 2f * u * t * bx + t * t * ex, u * u * sy + 2f * u * t * by + t * t * ey)
        }
        out.add(ex, ey)
    }
    return out.toArray()
}

private fun stamp(c: FloatArray, closed: Boolean, e: StampEffect, out: MutableList<FloatArray>, oc: MutableList<Boolean>) {
    val n = c.size / 2
    if (n < 2) return
    val segs = if (closed) n else n - 1
    val cum = FloatArray(segs + 1)
    for (i in 0 until segs) {
        val j = (i + 1) % n
        cum[i + 1] = cum[i] + hypot(c[j * 2] - c[i * 2], c[j * 2 + 1] - c[i * 2 + 1])
    }
    val length = cum[segs]
    if (length <= 0f) return
    val adv = e.advance
    var dist = if (e.phase < 0f) -e.phase % adv else adv - e.phase % adv
    if (dist >= adv) dist = 0f
    val at = FloatArray(4)
    var count = 0
    while (dist < length && count++ < MAX_STAMPS) {
        if (e.style != StampedPathEffectStyle.Morph) posTan(c, n, cum, segs, dist, at)
        for (k in e.contours.indices) {
            val s = e.contours[k]
            val placed = FloatArray(s.size)
            for (q in s.indices step 2) {
                val sx = s[q]; val sy = s[q + 1]
                when (e.style) {
                    StampedPathEffectStyle.Morph -> {
                        posTan(c, n, cum, segs, dist + sx, at)
                        placed[q] = at[0] - at[3] * sy; placed[q + 1] = at[1] + at[2] * sy
                    }
                    StampedPathEffectStyle.Rotate -> {
                        placed[q] = at[0] + sx * at[2] - sy * at[3]; placed[q + 1] = at[1] + sx * at[3] + sy * at[2]
                    }
                    else -> { placed[q] = at[0] + sx; placed[q + 1] = at[1] + sy }
                }
            }
            out += placed
            oc += e.closed[k]
        }
        dist += adv
    }
}

private fun posTan(c: FloatArray, n: Int, cum: FloatArray, segs: Int, d: Float, out: FloatArray) {
    val dd = d.coerceIn(0f, cum[segs])
    var lo = 0
    var hi = segs - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) ushr 1
        if (cum[mid] <= dd) lo = mid else hi = mid - 1
    }
    val j = (lo + 1) % n
    val ax = c[lo * 2]; val ay = c[lo * 2 + 1]
    val len = cum[lo + 1] - cum[lo]
    val tx = if (len > 0f) (c[j * 2] - ax) / len else 1f
    val ty = if (len > 0f) (c[j * 2 + 1] - ay) / len else 0f
    val t = dd - cum[lo]
    out[0] = ax + tx * t; out[1] = ay + ty * t; out[2] = tx; out[3] = ty
}

private class Corner(val x: Float, val y: Float, val u: Float, val v: Float, val color: Int)

private class VertexMesh(val positions: FloatArray, val colors: IntArray, val uvs: FloatArray, val indices: IntArray)

private fun decalClipped(positions: FloatArray, colors: IntArray, uvs: FloatArray, order: IntArray, decalU: Boolean, decalV: Boolean): VertexMesh {
    val xy = FloatArrayList()
    val st = FloatArrayList()
    val argb = ArrayList<Int>()
    val out = ArrayList<Int>()
    for (t in 0 until order.size / 3) {
        var poly = List(3) {
            val i = order[t * 3 + it]
            Corner(positions[i * 2], positions[i * 2 + 1], uvs[i * 2], uvs[i * 2 + 1], colors[i])
        }
        if (decalU) poly = clipCorners(clipCorners(poly, 0f, true) { it.u }, 1f, false) { it.u }
        if (decalV) poly = clipCorners(clipCorners(poly, 0f, true) { it.v }, 1f, false) { it.v }
        if (poly.size < 3) continue
        val base = argb.size
        for (c in poly) {
            xy.add(c.x, c.y)
            st.add(c.u, c.v)
            argb += c.color
        }
        for (k in 1 until poly.size - 1) { out += base; out += base + k; out += base + k + 1 }
    }
    return VertexMesh(xy.toArray(), argb.toIntArray(), st.toArray(), out.toIntArray())
}

private inline fun clipCorners(poly: List<Corner>, bound: Float, keepAbove: Boolean, value: (Corner) -> Float): List<Corner> {
    if (poly.size < 3) return poly
    val sign = if (keepAbove) 1f else -1f
    val out = ArrayList<Corner>(poly.size + 1)
    for (i in poly.indices) {
        val a = poly[i]
        val b = poly[(i + 1) % poly.size]
        val da = (value(a) - bound) * sign
        val db = (value(b) - bound) * sign
        if (da >= 0f) out += a
        if ((da > 0f && db < 0f) || (da < 0f && db > 0f)) {
            val f = da / (da - db)
            out += Corner(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.u + (b.u - a.u) * f, a.v + (b.v - a.v) * f, PolyGradient.lerpArgb(a.color, b.color, f))
        }
    }
    return out
}

private fun triangleOrder(v: Vertices): IntArray {
    val seq = if (v.indices.isEmpty()) IntArray(v.positions.size / 2) { it } else IntArray(v.indices.size) { v.indices[it].toInt() and 0xFFFF }
    val n = seq.size
    if (n < 3) return IntArray(0)
    return when (v.vertexMode) {
        VertexMode.TriangleStrip -> IntArray((n - 2) * 3) { seq[it / 3 + it % 3] }
        VertexMode.TriangleFan -> IntArray((n - 2) * 3) { if (it % 3 == 0) seq[0] else seq[it / 3 + it % 3] }
        else -> IntArray(n / 3 * 3) { seq[it] }
    }
}

private fun ovalPath(left: Float, top: Float, right: Float, bottom: Float): PolyComposePath =
    PolyComposePath().apply { addOval(Rect(left, top, right, bottom), Path.Direction.Clockwise) }

private fun arcPath(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean): PolyComposePath {
    val p = PolyComposePath()
    val rect = Rect(left, top, right, bottom)
    if (useCenter) { p.moveTo(rect.center.x, rect.center.y); p.arcTo(rect, startAngle, sweepAngle, false); p.close() }
    else p.arcTo(rect, startAngle, sweepAngle, true)
    return p
}

private fun PolyPaint.sameAs(o: PolyPaint): Boolean =
    isAntiAlias == o.isAntiAlias && color == o.color && blendMode == o.blendMode && style == o.style && strokeWidth == o.strokeWidth &&
        strokeCap == o.strokeCap && strokeJoin == o.strokeJoin && strokeMiterLimit == o.strokeMiterLimit && filterQuality == o.filterQuality &&
        shader === o.shader && colorFilter === o.colorFilter && pathEffect === o.pathEffect && blur == o.blur

private fun dashes(c: FloatArray, closed: Boolean, e: DashEffect): List<FloatArray> {
    val iv = e.intervals
    var total = 0f
    for (v in iv) total += v
    val n = c.size / 2
    if (iv.size < 2 || total <= 0f || n < 2) return listOf(c)
    var k = 0
    var pos = ((e.phase % total) + total) % total
    while (pos > iv[k]) { pos -= iv[k]; k = (k + 1) % iv.size }
    var rem = iv[k] - pos
    var on = k % 2 == 0
    val startsOn = on
    val out = ArrayList<FloatArray>()
    var cur = FloatArrayList()
    if (on) cur.add(c[0], c[1])
    for (i in 0 until if (closed) n else n - 1) {
        val j = (i + 1) % n
        val ax = c[i * 2]; val ay = c[i * 2 + 1]
        val bx = c[j * 2]; val by = c[j * 2 + 1]
        val len = hypot(bx - ax, by - ay)
        var t = 0f
        while (len - t > rem) {
            t += rem
            val x = ax + (bx - ax) * t / len; val y = ay + (by - ay) * t / len
            cur.add(x, y)
            if (on) { if (cur.size >= 4) out += cur.toArray(); cur = FloatArrayList() }
            on = !on
            k = (k + 1) % iv.size
            rem = iv[k]
        }
        rem -= len - t
        if (on) cur.add(bx, by)
    }
    if (on && cur.size >= 4) {
        if (closed && startsOn && out.isNotEmpty()) {
            val head = out.removeAt(0)
            for (i in 1 until head.size / 2) cur.add(head[i * 2], head[i * 2 + 1])
        }
        out += cur.toArray()
    }
    return out
}

public class RecordingCanvas : PolyDrawCanvas {
    public val ops: ArrayList<(PolyCanvas) -> Unit> = ArrayList<(PolyCanvas) -> Unit>()

    override fun polyOp(op: (PolyCanvas) -> Unit) { ops += op }

    public fun replay(canvas: PolyCanvas): Unit = replay(canvas, null)

    internal fun replay(canvas: PolyCanvas, dynamic: ((DynamicOp) -> Unit)?) {
        for (i in ops.lastIndex downTo 0) (ops[i] as? MaskOp)?.let { canvas.renderer.saveLayerFade(it.fade) }
        for (i in ops.indices) {
            val op = ops[i]
            if (dynamic != null && op is DynamicOp) dynamic(op) else op(canvas)
        }
    }

    private var lastPaint: PolyPaint? = null

    private fun Paint.snap(): PolyPaint {
        val p = this as PolyPaint
        lastPaint?.let { if (it.sameAs(p)) return it }
        return p.copy().also { lastPaint = it }
    }

    override fun save(): Unit = polyOp { it.save() }
    override fun restore(): Unit = polyOp { it.restore() }
    override fun saveLayer(bounds: Rect, paint: Paint) { val p = paint.snap(); polyOp { it.saveLayer(bounds, p) } }
    override fun translate(dx: Float, dy: Float): Unit = polyOp { it.translate(dx, dy) }
    override fun scale(sx: Float, sy: Float): Unit = polyOp { it.scale(sx, sy) }
    override fun rotate(degrees: Float): Unit = polyOp { it.rotate(degrees) }
    override fun skew(sx: Float, sy: Float): Unit = polyOp { it.skew(sx, sy) }
    override fun concat(matrix: Matrix) { val m = Matrix(matrix.values.copyOf()); polyOp { it.concat(m) } }
    override fun clipRect(left: Float, top: Float, right: Float, bottom: Float, clipOp: ClipOp): Unit = polyOp { it.clipRect(left, top, right, bottom, clipOp) }
    override fun clipPath(path: Path, clipOp: ClipOp) { val p = path.copyPath(); polyOp { it.clipPath(p, clipOp) } }
    override fun drawLine(p1: Offset, p2: Offset, paint: Paint) { val p = paint.snap(); polyOp { it.drawLine(p1, p2, p) } }
    override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        if (paint.masks()) paint.fadeMask()?.let { ops += MaskOp(it); return }
        val p = paint.snap()
        polyOp { it.drawRect(left, top, right, bottom, p) }
    }
    override fun drawRoundRect(left: Float, top: Float, right: Float, bottom: Float, radiusX: Float, radiusY: Float, paint: Paint) { val p = paint.snap(); polyOp { it.drawRoundRect(left, top, right, bottom, radiusX, radiusY, p) } }
    override fun drawOval(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
        val p = paint.snap()
        if (abs((right - left) - (bottom - top)) < 0.5f) return polyOp { it.drawOval(left, top, right, bottom, p) }
        val path = ovalPath(left, top, right, bottom)
        polyOp { it.drawPath(path, p) }
    }
    override fun drawCircle(center: Offset, radius: Float, paint: Paint) { val p = paint.snap(); polyOp { it.drawCircle(center, radius, p) } }
    override fun drawArc(left: Float, top: Float, right: Float, bottom: Float, startAngle: Float, sweepAngle: Float, useCenter: Boolean, paint: Paint) {
        val p = paint.snap()
        val path = arcPath(left, top, right, bottom, startAngle, sweepAngle, useCenter)
        polyOp { it.drawPath(path, p) }
    }
    override fun drawPath(path: Path, paint: Paint) { val p = paint.snap(); val c = path.copyPath(); polyOp { it.drawPath(c, p) } }
    override fun drawImage(image: ImageBitmap, topLeftOffset: Offset, paint: Paint) { val p = paint.snap(); polyOp { it.drawImage(image, topLeftOffset, p) } }
    override fun drawImageRect(image: ImageBitmap, srcOffset: IntOffset, srcSize: IntSize, dstOffset: IntOffset, dstSize: IntSize, paint: Paint) { val p = paint.snap(); polyOp { it.drawImageRect(image, srcOffset, srcSize, dstOffset, dstSize, p) } }
    override fun drawPoints(pointMode: PointMode, points: List<Offset>, paint: Paint) { val p = paint.snap(); val pts = points.toList(); polyOp { it.drawPoints(pointMode, pts, p) } }
    override fun drawRawPoints(pointMode: PointMode, points: FloatArray, paint: Paint) { val p = paint.snap(); val pts = points.copyOf(); polyOp { it.drawRawPoints(pointMode, pts, p) } }
    override fun drawVertices(vertices: Vertices, blendMode: BlendMode, paint: Paint) { val p = paint.snap(); polyOp { it.drawVertices(vertices, blendMode, p) } }
    override fun enableZ() {}
    override fun disableZ() {}

    private fun Path.copyPath(): Path = PolyComposePath().also { it.fillType = fillType; it.addPath(this, Offset.Zero) }
}

