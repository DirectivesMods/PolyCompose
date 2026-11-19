package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.minecraft.render.EffectParams
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain

internal class BackdropSpec(
    val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    val radius: Float,
    val pad: Float,
    val alpha: Float,
    val backdrop: PolyBackdrop,
    val scale: Float,
    val frost: Float,
    val clip: FloatArray?,
    val liquid: FloatArray?,
    val guiScale: Float,
) {
    var sampleKey: BackdropSample.Region? = BackdropSample.current
        private set

    fun shifted(dx: Int, dy: Int): BackdropSpec {
        val ox = dx * guiScale
        val oy = dy * guiScale
        val c = clip?.let { floatArrayOf(it[0] - ox, it[1] - oy, it[2] - ox, it[3] - oy) }
        return BackdropSpec(x0 - ox, y0 - oy, x1 - ox, y1 - oy, radius, pad, alpha, backdrop, scale, frost, c, liquid?.let { EffectParams.shiftLiquid(it, ox, oy) }, guiScale)
            .also { it.sampleKey = sampleKey }
    }

    fun faded(a: Float) = BackdropSpec(x0, y0, x1, y1, radius, pad, alpha * a, backdrop, scale, frost, clip, liquid, guiScale).also { it.sampleKey = sampleKey }

    companion object {
        fun packLiquid(l: PolyEffect.Liquid, sx: Float, sy: Float, tx: Float, ty: Float, scale: Float): FloatArray =
            EffectParams.packLiquid(l, sx, sy, tx, ty, scale)

        fun legacy(
            x0: Float, y0: Float, x1: Float, y1: Float, band: Float, refraction: Float, radius: Float, scale: Float, alpha: Int, guiScale: Float,
        ): BackdropSpec {
            val bandPx = EffectParams.legacyBand(band, scale)
            val r = EffectParams.legacyRadius(radius, scale)
            val k = EffectParams.legacyStrength(refraction)
            val chain = PolyEffectChain(listOf(PolyEffect.Refract(bandPx.toFloat(), k / 255f, EffectParams.LEGACY_DISPERSION)))
            val backdrop = PolyBackdrop(PolyBackdrop.SOURCE_BEHIND, chain, 1, 0f, false, 0)
            val pad = EffectParams.legacyPad(bandPx, k) + 2f
            return BackdropSpec(x0, y0, x1, y1, r.toFloat(), pad, alpha / 255f, backdrop, 1f, EffectParams.LEGACY_FROST, null, null, guiScale)
        }
    }
}

internal class LayerEffect(
    val chain: PolyEffectChain, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val radius: Float, val scale: Float,
) {
    val passthrough: Boolean get() = chain.textureStages.isEmpty() && chain.colorMatrix == null && chain.refract == null &&
        chain.highlight == null && chain.noise == null && chain.liquid == null

    fun local(ox: Float, oy: Float) = LayerEffect(chain, x0 - ox, y0 - oy, x1 - ox, y1 - oy, radius, scale)
}
//?}
