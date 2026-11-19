package org.polyfrost.compose.effect

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.polyfrost.compose.backend.PolyComposePath
import org.polyfrost.compose.backend.PolyDrawCanvas
import org.polyfrost.compose.backend.clipTo
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyLiquidSamples
import org.polyfrost.compose.render.PolyRenderer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal class LiquidParams {
    var thickness = 0f
    var refraction = 1f
    var dispersion = LiquidMaterial.DISPERSION
    var specular = 1f
    var clear = false
    var tint: Color = Color.Unspecified
    var blur = Float.NaN
    var dim = 0f
    val shapes = FloatArray(PolyEffect.Liquid.MAX_SHAPES * PolyEffect.Liquid.SHAPE_STRIDE)
    val corners = FloatArray(PolyEffect.Liquid.MAX_SHAPES * PolyEffect.Liquid.CORNER_STRIDE)
    var count = 0
    var cornered = false
        private set
    var spacing = 0f
    var glowX = 0f
    var glowY = 0f
    var glowRadius = 0f
    var glowStrength = 0f
    var appear = 1f
    var style = 0f
    var shadow = 0f
    var sizeFactor = 0f
    var sampleId = 0

    val full: Boolean get() = count >= PolyEffect.Liquid.MAX_SHAPES

    fun clearShapes() {
        count = 0
        cornered = false
    }

    fun addShape(x: Float, y: Float, w: Float, h: Float, topLeft: Float, topRight: Float, bottomRight: Float, bottomLeft: Float) {
        if (count >= PolyEffect.Liquid.MAX_SHAPES || w <= 0f || h <= 0f) return
        val i = count * PolyEffect.Liquid.SHAPE_STRIDE
        val limit = min(w, h) * 0.5f
        val tl = topLeft.coerceIn(0f, limit)
        val tr = topRight.coerceIn(0f, limit)
        val br = bottomRight.coerceIn(0f, limit)
        val bl = bottomLeft.coerceIn(0f, limit)
        shapes[i] = x
        shapes[i + 1] = y
        shapes[i + 2] = w
        shapes[i + 3] = h
        shapes[i + 4] = tl
        val c = count * PolyEffect.Liquid.CORNER_STRIDE
        corners[c] = tl
        corners[c + 1] = tr
        corners[c + 2] = br
        corners[c + 3] = bl
        if (tr != tl || br != tl || bl != tl) cornered = true
        count++
    }

    fun effects(density: Float): PolyEffectChain {
        val list = ArrayList<PolyEffect>(LIST_CAPACITY)
        addEffects(list, density)
        return PolyEffectChain(list)
    }

    fun addEffects(list: MutableList<PolyEffect>, density: Float) {
        val settings = LiquidGlassSettings
        val frosty = settings.reduceTransparency
        val contrast = settings.increaseContrast
        val s = sizeFactor
        var blurDp = when {
            !blur.isNaN() -> blur / density
            clear -> LiquidMaterial.CLEAR_BLUR_DP * s
            else -> LiquidMaterial.lerp(LiquidMaterial.BLUR_SMALL_DP, LiquidMaterial.BLUR_LARGE_DP, s)
        }
        if (frosty) blurDp = blurDp * LiquidMaterial.FROSTY_BLUR_SCALE + LiquidMaterial.FROSTY_BLUR_ADD_DP
        val blurPx = blurDp * density * appear
        if (blurPx > MIN_BLUR_PX) list += PolyEffect.Blur(blurPx, true)
        if (tint.isSpecified && tint.alpha > 0f) list += PolyEffect.ColorMatrix(LiquidMaterial.tintMatrix(tint, tint.alpha * appear))
        val dark = style >= 0.5f
        if (contrast) list += PolyEffect.Tint(LiquidMaterial.fill(dark, LiquidMaterial.CONTRAST_FILL * appear))
        else if (frosty) list += PolyEffect.Tint(LiquidMaterial.fill(dark, LiquidMaterial.FROSTY_FILL * appear))
        var bend = refraction
        if (frosty) bend *= LiquidMaterial.FROSTY_REFRACTION
        if (settings.reduceMotion) bend *= LiquidMaterial.CALM_REFRACTION
        list += PolyEffect.Liquid(
            thickness, bend, if (frosty || contrast) 0f else dispersion, if (contrast) 0f else specular,
            shapes.copyOf(count * PolyEffect.Liquid.SHAPE_STRIDE), count, spacing,
            glowX, glowY, glowRadius, glowStrength, appear,
            if (contrast) (if (dark) 1f else 0f) else style, shadow, dim, s, clear, sampleId,
            if (cornered) corners.copyOf(count * PolyEffect.Liquid.CORNER_STRIDE) else null,
        )
    }

    fun draw(scope: DrawScope, fallback: Color, alpha: Float) {
        if (count == 0 || appear <= MIN_APPEAR || alpha <= 0f) return
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (i in 0 until count) {
            val o = i * PolyEffect.Liquid.SHAPE_STRIDE
            x0 = min(x0, shapes[o])
            y0 = min(y0, shapes[o + 1])
            x1 = max(x1, shapes[o] + shapes[o + 2])
            y1 = max(y1, shapes[o + 1] + shapes[o + 3])
        }
        val chain = effects(scope.density)
        val fill = if (fallback.isSpecified) fallback.toArgb() else LiquidMaterial.fill(style >= 0.5f, LiquidMaterial.FALLBACK_FILL)
        val backdrop = PolyBackdrop(PolyBackdrop.SOURCE_BEHIND, chain, 1, chain.outset, !clear, fill)
        val outline = shapes.copyOf(count * PolyEffect.Liquid.SHAPE_STRIDE)
        val n = count
        val paths = if (cornered) Array(n) { Path().apply { addRoundRect(roundRect(it)) } } else null
        scope.drawIntoCanvas { c ->
            (c as? PolyDrawCanvas)?.polyOp {
                val r = it.renderer
                if (r.effectSupport() and PolyRenderer.EFFECT_BEHIND != 0) r.backdropEffect(x0, y0, x1 - x0, y1 - y0, 0f, backdrop, alpha)
                else for (i in 0 until n) {
                    val o = i * PolyEffect.Liquid.SHAPE_STRIDE
                    val color = LiquidMaterial.scaled(fill, alpha * appear)
                    if (paths == null) {
                        r.rect(outline[o], outline[o + 1], outline[o + 2], outline[o + 3], color, outline[o + 4])
                    } else {
                        r.save()
                        r.clipTo(paths[i])
                        r.rect(outline[o], outline[o + 1], outline[o + 2], outline[o + 3], color, 0f)
                        r.restore()
                    }
                }
            }
        }
        if (LiquidGlassSettings.increaseContrast) {
            val border = (if (style >= 0.5f) Color.White else Color.Black).copy(alpha = alpha * appear)
            val stroke = Stroke(scope.density)
            for (i in 0 until n) {
                if (paths != null) {
                    scope.drawPath(paths[i], border, style = stroke)
                    continue
                }
                val o = i * PolyEffect.Liquid.SHAPE_STRIDE
                val r = outline[o + 4]
                scope.drawRoundRect(border, Offset(outline[o], outline[o + 1]), Size(outline[o + 2], outline[o + 3]), CornerRadius(r, r), style = stroke)
            }
        }
    }

    private fun roundRect(i: Int): RoundRect {
        val o = i * PolyEffect.Liquid.SHAPE_STRIDE
        val c = i * PolyEffect.Liquid.CORNER_STRIDE
        return RoundRect(
            shapes[o], shapes[o + 1], shapes[o] + shapes[o + 2], shapes[o + 1] + shapes[o + 3],
            CornerRadius(corners[c]), CornerRadius(corners[c + 1]), CornerRadius(corners[c + 2]), CornerRadius(corners[c + 3]),
        )
    }

    private companion object {
        const val LIST_CAPACITY = 4
        const val MIN_BLUR_PX = 0.05f
        const val MIN_APPEAR = 0.001f
    }
}

internal class LiquidEstimate {
    var luma = Float.NaN
        private set
    var busy = LiquidMaterial.DEFAULT_BUSY
        private set
    private var dark = false

    fun measure(pixels: IntArray) {
        val n = pixels.size
        if (n == 0) return
        val cols = BackdropSample.columns
        val lum = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val p = pixels[i]
            val l = (LiquidMaterial.LUMA_R * (p shr 16 and 0xFF) + LiquidMaterial.LUMA_G * (p shr 8 and 0xFF) + LiquidMaterial.LUMA_B * (p and 0xFF)) / 255f
            lum[i] = l
            sum += l
        }
        val mean = sum / n
        var variance = 0f
        var edge = 0f
        var edges = 0
        for (i in 0 until n) {
            val d = lum[i] - mean
            variance += d * d
            if (i % cols != cols - 1 && i + 1 < n) {
                edge += abs(lum[i + 1] - lum[i])
                edges++
            }
            if (i + cols < n) {
                edge += abs(lum[i + cols] - lum[i])
                edges++
            }
        }
        val std = sqrt(variance / n)
        val e = if (edges > 0) edge / edges else 0f
        luma = mean
        busy = (std * LiquidMaterial.BUSY_STD + e * LiquidMaterial.BUSY_EDGE).coerceIn(0f, 1f)
        if (mean < LiquidMaterial.DARK_BELOW) dark = true else if (mean > LiquidMaterial.LIGHT_ABOVE) dark = false
    }

    fun style(minDp: Float): Float {
        val flip = if (dark) 1f else 0f
        if (luma.isNaN() || LiquidGlassSettings.increaseContrast) return flip
        val shift = (0.5f + (0.5f - luma) * LiquidMaterial.LARGE_SHIFT).coerceIn(LiquidMaterial.LARGE_MIN_STYLE, LiquidMaterial.LARGE_MAX_STYLE)
        return LiquidMaterial.lerp(flip, shift, LiquidMaterial.largeWeight(minDp))
    }

    fun shadow(sizeFactor: Float, clear: Boolean): Float =
        if (clear) LiquidMaterial.CLEAR_SHADOW * LiquidMaterial.shadowSize(sizeFactor)
        else (LiquidMaterial.SHADOW_BASE + LiquidMaterial.SHADOW_BUSY * busy) * LiquidMaterial.shadowSize(sizeFactor)
}

internal class LiquidAdaptation {
    val id: Int = ids.incrementAndGet()
    val style = Animatable(0f)
    val shadow = Animatable(LiquidMaterial.DEFAULT_SHADOW)
    private val estimate = LiquidEstimate()
    private var globalVersion = -1
    private var keyed = false
    private var styleTarget = 0f
    private var shadowTarget = LiquidMaterial.DEFAULT_SHADOW

    suspend fun track(minDimDp: () -> Float, clear: () -> Boolean, enabled: () -> Boolean): Unit = coroutineScope {
        var interval = LiquidMaterial.POLL_MILLIS
        var lastMinDp = Float.NaN
        var lastClear = false
        try {
            while (isActive) {
                if (!enabled()) {
                    interval = LiquidMaterial.POLL_MILLIS
                    delay(interval)
                    continue
                }
                if (!clear()) {
                    request()
                    delay(LiquidMaterial.SAMPLE_LEAD_MILLIS)
                    if (!enabled()) continue
                }
                val c = clear()
                if (!c) read()
                val minDp = minDimDp()
                var changed = minDp != lastMinDp || c != lastClear
                lastMinDp = minDp
                lastClear = c
                val st = if (c || estimate.luma.isNaN()) styleTarget else estimate.style(minDp)
                val sh = estimate.shadow(LiquidMaterial.sizeFactor(minDp), c)
                if (abs(st - styleTarget) > HYSTERESIS) {
                    val q = quantize(st)
                    styleTarget = q
                    changed = true
                    launch { style.animateTo(q, tween(LiquidMaterial.STYLE_MILLIS)) }
                }
                if (abs(sh - shadowTarget) > HYSTERESIS) {
                    val q = quantize(sh)
                    shadowTarget = q
                    changed = true
                    launch { shadow.animateTo(q, tween(LiquidMaterial.SHADOW_MILLIS)) }
                }
                interval = if (changed) LiquidMaterial.POLL_MILLIS else min(interval * 2, LiquidMaterial.MAX_POLL_MILLIS)
                delay(max(interval - (if (c) 0L else LiquidMaterial.SAMPLE_LEAD_MILLIS), 0L))
            }
        } finally {
            PolyLiquidSamples.release(id)
        }
    }

    private fun request() {
        PolyLiquidSamples.request(id)
        if (!keyed) BackdropSample.request()
    }

    private fun read() {
        if (PolyLiquidSamples.version(id) != 0) {
            keyed = true
            PolyLiquidSamples.pixels(id)?.let { estimate.measure(it) }
            PolyLiquidSamples.release(id)
            return
        }
        if (keyed) return
        val gv = BackdropSample.version
        if (gv != globalVersion && gv != 0) {
            globalVersion = gv
            estimate.measure(BackdropSample.pixels)
        }
    }

    private fun quantize(v: Float): Float = (v / QUANTUM).roundToInt() * QUANTUM

    private companion object {
        const val QUANTUM = 0.05f
        const val HYSTERESIS = 0.06f
        val ids = AtomicInteger()
    }
}

internal object LiquidMaterial {
    const val THICKNESS_DP = 20f
    const val DISPERSION = 0.1f
    const val BLUR_SMALL_DP = 2f
    const val SPECULAR_BOOST = 0.3f
    const val BLUR_LARGE_DP = 8f
    const val CLEAR_BLUR_DP = 0.5f
    const val FROSTY_BLUR_SCALE = 3f
    const val FROSTY_BLUR_ADD_DP = 4f
    const val FROSTY_FILL = 0.6f
    const val CONTRAST_FILL = 0.88f
    const val FALLBACK_FILL = 0.6f
    const val FROSTY_REFRACTION = 0.25f
    const val CALM_REFRACTION = 0.6f
    const val DEFAULT_SHADOW = 0.3f
    const val DEFAULT_BUSY = 0.35f
    const val CLEAR_SHADOW = 0.25f
    const val SHADOW_BASE = 0.2f
    const val SHADOW_BUSY = 0.6f
    const val BUSY_STD = 2.2f
    const val BUSY_EDGE = 2.5f
    const val DARK_BELOW = 0.4f
    const val LIGHT_ABOVE = 0.6f
    const val LARGE_SHIFT = 0.5f
    const val LARGE_MIN_STYLE = 0.25f
    const val LARGE_MAX_STYLE = 0.75f
    const val LUMA_R = 0.2126f
    const val LUMA_G = 0.7152f
    const val LUMA_B = 0.0722f
    const val STYLE_MILLIS = 260
    const val SHADOW_MILLIS = 400
    const val POLL_MILLIS = 250L
    const val MAX_POLL_MILLIS = 2000L
    const val SAMPLE_LEAD_MILLIS = 120L

    private const val SMALL_DP = 24f
    private const val ARC_GAP = 0.41421357f
    private const val LARGE_DP = 200f
    private const val FLIP_DP = 64f
    private const val SHADOW_MIN = 0.55f
    private const val SHADOW_RANGE = 0.45f
    private const val TINT_MAPPED = 0.75f
    private const val TINT_MIN_LUMA = 0.15f
    private const val LIGHT_FILL = 0xF2F2F4
    private const val DARK_FILL = 0x1C1C1E

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun corners(outline: Outline, out: FloatArray) {
        when (outline) {
            is Outline.Rectangle -> out.fill(0f, 0, PolyEffect.Liquid.CORNER_STRIDE)
            is Outline.Rounded -> {
                val rr = outline.roundRect
                out[0] = min(rr.topLeftCornerRadius.x, rr.topLeftCornerRadius.y)
                out[1] = min(rr.topRightCornerRadius.x, rr.topRightCornerRadius.y)
                out[2] = min(rr.bottomRightCornerRadius.x, rr.bottomRightCornerRadius.y)
                out[3] = min(rr.bottomLeftCornerRadius.x, rr.bottomLeftCornerRadius.y)
            }
            is Outline.Generic -> estimateCorners(outline, out)
        }
    }

    private fun estimateCorners(outline: Outline.Generic, out: FloatArray) {
        out.fill(0f, 0, PolyEffect.Liquid.CORNER_STRIDE)
        val contours = (outline.path as? PolyComposePath)?.allContours() ?: return
        val b = outline.bounds
        val limit = min(b.width, b.height) * 0.5f
        if (limit <= 0f) return
        for (k in 0 until PolyEffect.Liquid.CORNER_STRIDE) {
            val cx = if (k == 1 || k == 2) b.right else b.left
            val cy = if (k >= 2) b.bottom else b.top
            var best = Float.MAX_VALUE
            for (pts in contours) {
                val m = pts.size / 2
                for (i in 0 until m) {
                    val j = (i + 1) % m
                    best = min(best, segmentDistance(cx, cy, pts[i * 2], pts[i * 2 + 1], pts[j * 2], pts[j * 2 + 1]))
                }
            }
            if (best != Float.MAX_VALUE) out[k] = (best / ARC_GAP).coerceIn(0f, limit)
        }
    }

    private fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0f) (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
        return hypot(px - (ax + dx * t), py - (ay + dy * t))
    }

    fun sizeFactor(minDimDp: Float): Float = ((minDimDp - SMALL_DP) / (LARGE_DP - SMALL_DP)).coerceIn(0f, 1f)

    fun largeWeight(minDimDp: Float): Float {
        val t = ((minDimDp - FLIP_DP) / (LARGE_DP - FLIP_DP)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun shadowSize(sizeFactor: Float): Float = SHADOW_MIN + SHADOW_RANGE * sizeFactor

    fun fill(dark: Boolean, alpha: Float): Int = ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24) or (if (dark) DARK_FILL else LIGHT_FILL)

    fun scaled(argb: Int, alpha: Float): Int = ((((argb ushr 24) * alpha.coerceIn(0f, 1f)).toInt()) shl 24) or (argb and 0xFFFFFF)

    fun tintMatrix(tint: Color, strength: Float): FloatArray {
        val s = strength.coerceIn(0f, 1f)
        val lt = max(LUMA_R * tint.red + LUMA_G * tint.green + LUMA_B * tint.blue, TINT_MIN_LUMA)
        val keep = 1f - s
        val m = FloatArray(20)
        val channels = floatArrayOf(tint.red, tint.green, tint.blue)
        for (row in 0 until 3) {
            val k = s * TINT_MAPPED * channels[row] / lt
            val o = row * 5
            m[o] = k * LUMA_R
            m[o + 1] = k * LUMA_G
            m[o + 2] = k * LUMA_B
            m[o + row] += keep
            m[o + 4] = s * (1f - TINT_MAPPED) * channels[row] * 255f
        }
        m[18] = 1f
        return m
    }
}
