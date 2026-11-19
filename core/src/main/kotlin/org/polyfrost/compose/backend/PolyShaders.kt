@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.backend

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.platform.PlatformShader
import org.polyfrost.compose.render.ChromaShader
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyFade
import org.polyfrost.compose.render.PolyGradient
import org.polyfrost.compose.render.PolyImageFill
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal abstract class PolyShader : PlatformShader {
    open fun prepare() {}
    abstract fun color(x: Float, y: Float): Int
}

internal class GradientShader(val brush: PolyGradient, val center: Offset?, val radius: Float, val tile: TileMode = TileMode.Clamp) : PolyShader() {
    fun brushFor(x: Float, y: Float, w: Float, h: Float): PolyGradient {
        val c = center ?: return brush
        val m = min(w, h)
        if (w <= 0f || h <= 0f || m <= 0f) return brush
        return when (brush) {
            is PolyGradient.Radial -> brush.copy(centerX = (c.x - x) / w, centerY = (c.y - y) / h, radius = radius / m)
            is PolyGradient.Sweep -> brush.copy(centerX = (c.x - x) / w, centerY = (c.y - y) / h)
            else -> brush
        }
    }

    override fun color(x: Float, y: Float): Int {
        val c = center
        val t = when {
            brush is PolyGradient.Radial && c != null -> if (radius <= 0f) 1f else hypot(x - c.x, y - c.y) / radius
            brush is PolyGradient.Sweep && c != null -> (atan2(y - c.y, x - c.x) / (2f * Math.PI.toFloat())).let { if (it < 0f) it + 1f else it }
            else -> brush.position(x, y, 0f, 0f, 0f, 0f)
        }
        val tt = tiled(t, tile)
        return if (tt.isNaN()) 0 else brush.sample(tt)
    }

    fun transformed(m: FloatArray): GradientShader? {
        val a = m[0]; val b = m[1]; val c = m[2]; val d = m[3]
        val det = a * d - b * c
        if (det == 0f) return null
        val g = brush
        if (g is PolyGradient.Points) {
            val dx = g.x1 - g.x0; val dy = g.y1 - g.y0
            val len2 = dx * dx + dy * dy
            if (len2 <= 0f) return null
            val gx = (d * dx - c * dy) / det / len2
            val gy = (-b * dx + a * dy) / det / len2
            val g2 = gx * gx + gy * gy
            if (g2 <= 0f) return null
            val x0 = a * g.x0 + b * g.y0 + m[4]
            val y0 = c * g.x0 + d * g.y0 + m[5]
            return GradientShader(g.copy(x0 = x0, y0 = y0, x1 = x0 + gx / g2, y1 = y0 + gy / g2), null, 0f, tile)
        }
        val ctr = center ?: return null
        val moved = Offset(a * ctr.x + b * ctr.y + m[4], c * ctr.x + d * ctr.y + m[5])
        val eps = 1e-5f * (abs(a) + abs(b) + abs(c) + abs(d))
        return when {
            g is PolyGradient.Radial && (abs(a - d) <= eps && abs(b + c) <= eps || abs(a + d) <= eps && abs(b - c) <= eps) ->
                GradientShader(g, moved, radius * sqrt(abs(det)), tile)
            g is PolyGradient.Sweep && abs(b) <= eps && abs(c) <= eps && abs(a - d) <= eps && a > 0f -> GradientShader(g, moved, radius, tile)
            else -> null
        }
    }
}

internal class MaskedShader(val color: GradientShader, val mask: PolyFade, val source: PlatformShader) : PolyShader() {
    override fun prepare() = prepareShader(source)
    override fun color(x: Float, y: Float): Int = blendArgb(shaderColor(source, x, y), color.color(x, y), BlendMode.DstIn)
}

internal class ImageShader(val image: ImageBitmap, val tileX: TileMode, val tileY: TileMode, val inverse: FloatArray? = null) : PolyShader() {
    private var fill: PolyImageFill? = null
    private var sampled: PolyBitmap? = null

    val direct: Boolean get() = tileX.edgeOnly && tileY.edgeOnly

    fun bitmap(): PolyBitmap? = (image as? PolyImageBitmap)?.bitmap

    fun fill(b: PolyBitmap): PolyImageFill {
        fill?.let { if (it.bitmap === b) return it }
        val iw = b.width.toFloat(); val ih = b.height.toFloat()
        val m = inverse ?: IDENTITY
        return PolyImageFill(b, m[0] / iw, m[1] / iw, m[4] / iw, m[2] / ih, m[3] / ih, m[5] / ih).also { fill = it }
    }

    override fun prepare() { sampled = bitmap() }

    override fun color(x: Float, y: Float): Int {
        val b = sampled ?: return 0
        val m = inverse
        val px = if (m == null) x else m[0] * x + m[1] * y + m[4]
        val py = if (m == null) y else m[2] * x + m[3] * y + m[5]
        return sampleBitmap(b, px - 0.5f, py - 0.5f, tileX, tileY)
    }
}

internal class CompositeShader(private val dst: PlatformShader, private val src: PlatformShader, private val mode: BlendMode) : PolyShader() {
    override fun prepare() { prepareShader(dst); prepareShader(src) }
    override fun color(x: Float, y: Float): Int = blendArgb(shaderColor(src, x, y), shaderColor(dst, x, y), mode)
}

internal class TransformedShader(val inner: PlatformShader, val inverse: FloatArray) : PolyShader() {
    override fun prepare() = prepareShader(inner)
    override fun color(x: Float, y: Float): Int {
        val m = inverse
        return shaderColor(inner, m[0] * x + m[1] * y + m[4], m[2] * x + m[3] * y + m[5])
    }
}

internal object ClearShader : PolyShader() {
    override fun color(x: Float, y: Float): Int = 0
}

internal val IDENTITY = floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)

private val TileMode.edgeOnly: Boolean get() = this == TileMode.Clamp || this == TileMode.Decal

internal fun prepareShader(s: PlatformShader) { (s as? PolyShader)?.prepare() }

internal fun shaderColor(s: PlatformShader, x: Float, y: Float): Int = when (s) {
    is PolyShader -> s.color(x, y)
    is ChromaShader -> s.color.argb
    else -> 0
}

internal fun affineOf(matrix: Matrix): FloatArray {
    val v = matrix.values
    return floatArrayOf(v[Matrix.ScaleX], v[Matrix.SkewX], v[Matrix.SkewY], v[Matrix.ScaleY], v[Matrix.TranslateX], v[Matrix.TranslateY])
}

internal fun invertAffine(m: FloatArray): FloatArray? {
    val det = m[0] * m[3] - m[1] * m[2]
    if (det == 0f || det.isNaN()) return null
    val a = m[3] / det; val b = -m[1] / det; val c = -m[2] / det; val d = m[0] / det
    return floatArrayOf(a, b, c, d, -(a * m[4] + b * m[5]), -(c * m[4] + d * m[5]))
}

internal fun concatAffine(outer: FloatArray, inner: FloatArray): FloatArray = floatArrayOf(
    outer[0] * inner[0] + outer[1] * inner[2], outer[0] * inner[1] + outer[1] * inner[3],
    outer[2] * inner[0] + outer[3] * inner[2], outer[2] * inner[1] + outer[3] * inner[3],
    outer[0] * inner[4] + outer[1] * inner[5] + outer[4], outer[2] * inner[4] + outer[3] * inner[5] + outer[5],
)

internal fun transformedShader(s: PlatformShader, m: FloatArray, inv: FloatArray): PlatformShader = when (s) {
    is GradientShader -> s.transformed(m) ?: TransformedShader(s, inv)
    is ImageShader -> ImageShader(s.image, s.tileX, s.tileY, concatAffine(s.inverse ?: IDENTITY, inv))
    is TransformedShader -> TransformedShader(s.inner, concatAffine(s.inverse, inv))
    is ClearShader -> s
    else -> TransformedShader(s, inv)
}

private fun tiled(t: Float, mode: TileMode): Float = when (mode) {
    TileMode.Repeated -> t - floor(t)
    TileMode.Mirror -> (t - floor(t / 2f) * 2f).let { if (it > 1f) 2f - it else it }
    TileMode.Decal -> if (t < 0f || t > 1f) Float.NaN else t
    else -> t.coerceIn(0f, 1f)
}

private fun tiledIndex(i: Int, size: Int, mode: TileMode): Int = when (mode) {
    TileMode.Repeated -> Math.floorMod(i, size)
    TileMode.Mirror -> Math.floorMod(i, size * 2).let { if (it >= size) size * 2 - 1 - it else it }
    TileMode.Decal -> if (i < 0 || i >= size) -1 else i
    else -> i.coerceIn(0, size - 1)
}

private fun sampleBitmap(b: PolyBitmap, fx: Float, fy: Float, tx: TileMode, ty: TileMode): Int {
    val w = b.width; val h = b.height
    if (w <= 0 || h <= 0) return 0
    val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
    val ax = fx - x0; val ay = fy - y0
    val px = b.pixels
    var r = 0f; var g = 0f; var bl = 0f; var a = 0f
    for (k in 0 until 4) {
        val ix = tiledIndex(x0 + (k and 1), w, tx)
        val iy = tiledIndex(y0 + (k shr 1), h, ty)
        if (ix < 0 || iy < 0) continue
        val wt = (if (k and 1 == 0) 1f - ax else ax) * (if (k shr 1 == 0) 1f - ay else ay)
        if (wt <= 0f) continue
        val o = (iy * w + ix) * 4
        val pa = (px[o + 3].toInt() and 0xFF) * wt
        a += pa
        r += (px[o].toInt() and 0xFF) * pa
        g += (px[o + 1].toInt() and 0xFF) * pa
        bl += (px[o + 2].toInt() and 0xFF) * pa
    }
    if (a <= 0f) return 0
    return ((a + 0.5f).toInt().coerceIn(0, 255) shl 24) or ((r / a + 0.5f).toInt().coerceIn(0, 255) shl 16) or
        ((g / a + 0.5f).toInt().coerceIn(0, 255) shl 8) or (bl / a + 0.5f).toInt().coerceIn(0, 255)
}

internal fun blendArgb(src: Int, dst: Int, mode: BlendMode): Int {
    when (mode) {
        BlendMode.Clear -> return 0
        BlendMode.Src -> return src
        BlendMode.Dst -> return dst
        BlendMode.Hue, BlendMode.Saturation, BlendMode.Color, BlendMode.Luminosity -> return nonSeparable(src, dst, mode)
        else -> {}
    }
    val sa = (src ushr 24) / 255f
    val da = (dst ushr 24) / 255f
    val oa = when (mode) {
        BlendMode.SrcOver, BlendMode.DstOver -> sa + da - sa * da
        BlendMode.SrcIn, BlendMode.DstIn, BlendMode.Modulate -> sa * da
        BlendMode.SrcOut -> sa * (1f - da)
        BlendMode.DstOut -> da * (1f - sa)
        BlendMode.SrcAtop -> da
        BlendMode.DstAtop -> sa
        BlendMode.Xor -> sa * (1f - da) + da * (1f - sa)
        BlendMode.Plus -> min(1f, sa + da)
        else -> sa + da - sa * da
    }
    if (oa <= 0f) return 0
    var out = (oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
    var shift = 16
    while (shift >= 0) {
        val cs = ((src shr shift) and 0xFF) / 255f
        val cd = ((dst shr shift) and 0xFF) / 255f
        val ps = cs * sa
        val pd = cd * da
        val c = when (mode) {
            BlendMode.SrcOver -> ps + pd * (1f - sa)
            BlendMode.DstOver -> pd + ps * (1f - da)
            BlendMode.SrcIn -> ps * da
            BlendMode.DstIn -> pd * sa
            BlendMode.SrcOut -> ps * (1f - da)
            BlendMode.DstOut -> pd * (1f - sa)
            BlendMode.SrcAtop -> ps * da + pd * (1f - sa)
            BlendMode.DstAtop -> pd * sa + ps * (1f - da)
            BlendMode.Xor -> ps * (1f - da) + pd * (1f - sa)
            BlendMode.Plus -> min(1f, ps + pd)
            BlendMode.Modulate -> ps * pd
            BlendMode.Screen -> ps + pd - ps * pd
            else -> (1f - da) * ps + (1f - sa) * pd + sa * da * separable(cs, cd, mode)
        }
        out = out or ((c / oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl shift)
        shift -= 8
    }
    return out
}

private fun nonSeparable(src: Int, dst: Int, mode: BlendMode): Int {
    val sa = (src ushr 24) / 255f
    val da = (dst ushr 24) / 255f
    val oa = sa + da - sa * da
    if (oa <= 0f) return 0
    val s = FloatArray(3) { ((src shr (16 - it * 8)) and 0xFF) / 255f }
    val d = FloatArray(3) { ((dst shr (16 - it * 8)) and 0xFF) / 255f }
    val mixed = FloatArray(3)
    when (mode) {
        BlendMode.Hue -> setLum(setSat(s, sat(d)), lum(d), mixed)
        BlendMode.Saturation -> setLum(setSat(d, sat(s)), lum(d), mixed)
        BlendMode.Color -> setLum(s, lum(d), mixed)
        else -> setLum(d, lum(s), mixed)
    }
    var out = (oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl 24
    for (i in 0..2) {
        val c = (1f - da) * s[i] * sa + (1f - sa) * d[i] * da + sa * da * mixed[i]
        out = out or ((c / oa * 255f + 0.5f).toInt().coerceIn(0, 255) shl (16 - i * 8))
    }
    return out
}

private fun separable(cs: Float, cd: Float, mode: BlendMode): Float = when (mode) {
    BlendMode.Multiply -> cs * cd
    BlendMode.Overlay -> hardLight(cd, cs)
    BlendMode.Darken -> min(cs, cd)
    BlendMode.Lighten -> max(cs, cd)
    BlendMode.ColorDodge -> when {
        cd <= 0f -> 0f
        cs >= 1f -> 1f
        else -> min(1f, cd / (1f - cs))
    }
    BlendMode.ColorBurn -> when {
        cd >= 1f -> 1f
        cs <= 0f -> 0f
        else -> 1f - min(1f, (1f - cd) / cs)
    }
    BlendMode.Hardlight -> hardLight(cs, cd)
    BlendMode.Softlight -> if (cs <= 0.5f) cd - (1f - 2f * cs) * cd * (1f - cd)
    else cd + (2f * cs - 1f) * ((if (cd <= 0.25f) ((16f * cd - 12f) * cd + 4f) * cd else sqrt(cd)) - cd)
    BlendMode.Difference -> abs(cs - cd)
    BlendMode.Exclusion -> cs + cd - 2f * cs * cd
    else -> cs
}

private fun hardLight(cs: Float, cd: Float): Float = if (cs <= 0.5f) cd * 2f * cs else { val t = 2f * cs - 1f; cd + t - cd * t }

private fun lum(c: FloatArray): Float = 0.3f * c[0] + 0.59f * c[1] + 0.11f * c[2]

private fun sat(c: FloatArray): Float = max(c[0], max(c[1], c[2])) - min(c[0], min(c[1], c[2]))

private fun setSat(c: FloatArray, s: Float): FloatArray {
    val mx = max(c[0], max(c[1], c[2]))
    val mn = min(c[0], min(c[1], c[2]))
    return FloatArray(3) { if (mx > mn) (c[it] - mn) * s / (mx - mn) else 0f }
}

private fun setLum(c: FloatArray, l: Float, out: FloatArray) {
    val dl = l - lum(c)
    for (i in 0..2) out[i] = c[i] + dl
    val lv = lum(out)
    val mn = min(out[0], min(out[1], out[2]))
    val mx = max(out[0], max(out[1], out[2]))
    for (i in 0..2) {
        if (mn < 0f) out[i] = lv + (out[i] - lv) * lv / (lv - mn)
        if (mx > 1f) out[i] = lv + (out[i] - lv) * (1f - lv) / (mx - lv)
    }
}

internal object ShaderRaster {
    private const val MAX_SIDE = 2048
    private const val MAX_PIXELS = 1 shl 20

    private class Entry(val fill: PolyImageFill, val x: Float, val y: Float, val w: Float, val h: Float, val kx: Float, val ky: Float)

    private val cache = WeakHashMap<PlatformShader, Entry>()

    fun fill(shader: PlatformShader, x: Float, y: Float, w: Float, h: Float, kx: Float, ky: Float): PolyImageFill? {
        if (!(w > 0f && h > 0f) || w.isInfinite() || h.isInfinite()) return null
        synchronized(cache) {
            cache[shader]?.let { e ->
                val covers = e.x <= x && e.y <= y && e.x + e.w >= x + w && e.y + e.h >= y + h
                if (covers && e.kx >= kx * 0.9f && e.ky >= ky * 0.9f && e.kx <= kx * 2f && e.ky <= ky * 2f) return e.fill
            }
        }
        var pw = ceil(w * kx).toInt().coerceIn(1, MAX_SIDE)
        var ph = ceil(h * ky).toInt().coerceIn(1, MAX_SIDE)
        if (pw.toLong() * ph > MAX_PIXELS) {
            val f = sqrt(MAX_PIXELS.toDouble() / (pw.toLong() * ph))
            pw = (pw * f).toInt().coerceAtLeast(1)
            ph = (ph * f).toInt().coerceAtLeast(1)
        }
        prepareShader(shader)
        val px = ByteArray(pw * ph * 4)
        for (j in 0 until ph) {
            val ly = y + (j + 0.5f) / ph * h
            for (i in 0 until pw) {
                val c = shaderColor(shader, x + (i + 0.5f) / pw * w, ly)
                val o = (j * pw + i) * 4
                px[o] = (c shr 16).toByte(); px[o + 1] = (c shr 8).toByte(); px[o + 2] = c.toByte(); px[o + 3] = (c ushr 24).toByte()
            }
        }
        val fill = PolyImageFill(PolyBitmap(pw, ph, px), 1f / w, 0f, -x / w, 0f, 1f / h, -y / h)
        synchronized(cache) { cache[shader] = Entry(fill, x, y, w, h, pw / w, ph / h) }
        return fill
    }
}
