package org.polyfrost.compose.minecraft.render

import org.polyfrost.compose.render.PolyEffect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal object EffectParams {
    const val LEGACY_FROST = 2.5f
    const val LEGACY_DISPERSION = 0.12f
    const val LEGACY_REACH = 1.12f
    const val SIGMA_SCALE = 0.57735f
    const val SIGMA_BIAS = 0.5f
    const val COARSE_SIGMA = 4f
    const val MAX_LEVEL = 3
    const val MODE_BACKDROP = 0
    const val MODE_FALLBACK = 1
    const val MODE_LAYER = 2
    const val MODE_LIQUID = 3

    const val LIQUID_FLOATS = 80
    const val LIQUID_RECTS = 8
    const val LIQUID_RADII = 40
    const val LIQUID_GLOW = 72
    const val LIQUID_PARAMS = 76

    fun scaleOf(sx: Float, sy: Float): Float = (abs(sx) + abs(sy)) / 2f

    fun lowEdge(origin: Float, size: Float, scale: Float, translate: Float): Float = min(origin * scale + translate, (origin + size) * scale + translate)

    fun highEdge(origin: Float, size: Float, scale: Float, translate: Float): Float = max(origin * scale + translate, (origin + size) * scale + translate)

    fun blurLevel(sigma: Float): Int = floor(ln(sigma / 2f) / ln(2f)).toInt().coerceIn(0, MAX_LEVEL)

    fun legacyBand(band: Float, scale: Float): Int = (band * scale).coerceIn(1f, 255f).roundToInt()

    fun legacyRadius(radius: Float, scale: Float): Int = (radius * scale).roundToInt().coerceIn(0, 255)

    fun legacyStrength(refraction: Float): Int = (refraction.coerceIn(0f, 1f) * 255f).roundToInt()

    fun legacyPad(band: Int, strength: Int): Float = band * strength / 255f * LEGACY_REACH + LEGACY_FROST

    fun matrixInto(m: FloatArray?, out: FloatArray, at: Int) {
        for (j in 0 until 4) for (i in 0 until 4) out[at + j * 4 + i] = m?.get(i * 5 + j) ?: if (i == j) 1f else 0f
    }

    fun matrixOffsetInto(m: FloatArray, out: FloatArray, at: Int) {
        for (i in 0 until 4) out[at + i] = m[i * 5 + 4] / 255f
    }

    fun highlightInto(h: PolyEffect.Highlight, scale: Float, out: FloatArray, at: Int) {
        val rad = Math.toRadians(h.angle.toDouble())
        out[at] = h.width * scale
        out[at + 1] = cos(rad).toFloat()
        out[at + 2] = sin(rad).toFloat()
    }

    fun noiseInto(n: PolyEffect.Noise, scale: Float, out: FloatArray, at: Int) {
        out[at] = n.amount
        out[at + 1] = n.scale * scale
    }

    fun packLiquid(l: PolyEffect.Liquid, sx: Float, sy: Float, tx: Float, ty: Float, scale: Float): FloatArray {
        val out = FloatArray(LIQUID_FLOATS)
        out[0] = l.thickness * scale; out[1] = l.refraction; out[2] = l.dispersion; out[3] = l.specular
        out[4] = l.spacing * scale; out[5] = l.shapeCount.toFloat(); out[6] = l.sizeFactor; out[7] = if (l.clear) 1f else 0f
        val s = l.shapes
        val k = l.corners
        val flipX = sx < 0f
        val flipY = sy < 0f
        for (i in 0 until l.shapeCount) {
            val o = i * PolyEffect.Liquid.SHAPE_STRIDE
            val r = LIQUID_RECTS + i * 4
            out[r] = lowEdge(s[o], s[o + 2], sx, tx)
            out[r + 1] = lowEdge(s[o + 1], s[o + 3], sy, ty)
            out[r + 2] = highEdge(s[o], s[o + 2], sx, tx)
            out[r + 3] = highEdge(s[o + 1], s[o + 3], sy, ty)
            val q = LIQUID_RADII + i * 4
            if (k == null) {
                val radius = s[o + 4] * scale
                out[q] = radius; out[q + 1] = radius; out[q + 2] = radius; out[q + 3] = radius
            } else {
                val c = i * PolyEffect.Liquid.CORNER_STRIDE
                val top = if (flipY) 2 else 0
                val bottom = 2 - top
                out[q] = k[c + corner(top, flipX)] * scale
                out[q + 1] = k[c + corner(top, !flipX)] * scale
                out[q + 2] = k[c + corner(bottom, !flipX)] * scale
                out[q + 3] = k[c + corner(bottom, flipX)] * scale
            }
        }
        out[LIQUID_GLOW] = l.glowX * sx + tx; out[LIQUID_GLOW + 1] = l.glowY * sy + ty
        out[LIQUID_GLOW + 2] = l.glowRadius * scale; out[LIQUID_GLOW + 3] = l.glowStrength
        out[LIQUID_PARAMS] = l.appear; out[LIQUID_PARAMS + 1] = l.style; out[LIQUID_PARAMS + 2] = l.shadow; out[LIQUID_PARAMS + 3] = l.dim
        return out
    }

    private fun corner(row: Int, right: Boolean): Int = if (row == 0) (if (right) 1 else 0) else (if (right) 2 else 3)

    fun shiftLiquid(src: FloatArray, ox: Float, oy: Float): FloatArray {
        val out = src.copyOf()
        for (i in 0 until src[5].toInt()) {
            val r = LIQUID_RECTS + i * 4
            out[r] -= ox; out[r + 1] -= oy; out[r + 2] -= ox; out[r + 3] -= oy
        }
        out[LIQUID_GLOW] -= ox; out[LIQUID_GLOW + 1] -= oy
        return out
    }
}
