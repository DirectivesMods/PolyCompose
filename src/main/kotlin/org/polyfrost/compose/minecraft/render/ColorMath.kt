package org.polyfrost.compose.minecraft.render

import kotlin.math.roundToInt

internal object ColorMath {
    fun alphaByte(alpha: Float): Int = (alpha * 255f).roundToInt().coerceIn(0, 255)

    fun alphaMul(color: Int, alpha: Float): Int {
        if (alpha >= 1f) return color
        val a = ((color ushr 24) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    fun fadeColor(color: Int, alpha: Float, premultiplied: Boolean): Int {
        if (!premultiplied) return alphaMul(color, alpha)
        var out = 0
        var shift = 0
        while (shift < 32) {
            out = out or ((((color ushr shift) and 0xFF) * alpha).roundToInt().coerceIn(0, 255) shl shift)
            shift += 8
        }
        return out
    }

    fun premultiplyInto(argb: Int, out: FloatArray, at: Int) {
        val a = (argb ushr 24) / 255f
        out[at] = (argb shr 16 and 0xFF) / 255f * a
        out[at + 1] = (argb shr 8 and 0xFF) / 255f * a
        out[at + 2] = (argb and 0xFF) / 255f * a
        out[at + 3] = a
    }
}
