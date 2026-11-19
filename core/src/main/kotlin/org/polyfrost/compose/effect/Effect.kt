@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.effect

import androidx.compose.runtime.Immutable
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.polyfrost.compose.backend.ChainEffect
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectShader
import androidx.compose.ui.graphics.ColorMatrix as ComposeColorMatrix

@Immutable
public sealed class Effect {
    public class Blur(public val radius: Dp, public val edgeTreatment: TileMode = TileMode.Clamp) : Effect() {
        override fun equals(other: Any?): Boolean = other is Blur && radius == other.radius && edgeTreatment == other.edgeTreatment
        override fun hashCode(): Int = radius.hashCode() * 31 + edgeTreatment.hashCode()
    }

    public class ColorMatrix(public val matrix: ComposeColorMatrix) : Effect() {
        override fun equals(other: Any?): Boolean = other is ColorMatrix && matrix == other.matrix
        override fun hashCode(): Int = matrix.hashCode()
    }

    public class Tint(public val color: Color = Color.Unspecified, public val alpha: Float = 1f) : Effect() {
        override fun equals(other: Any?): Boolean = other is Tint && color == other.color && alpha == other.alpha
        override fun hashCode(): Int = color.hashCode() * 31 + alpha.hashCode()
    }

    public class Refract(public val band: Dp, public val strength: Float, public val dispersion: Float = 0.12f) : Effect() {
        override fun equals(other: Any?): Boolean =
            other is Refract && band == other.band && strength == other.strength && dispersion == other.dispersion
        override fun hashCode(): Int = (band.hashCode() * 31 + strength.hashCode()) * 31 + dispersion.hashCode()
    }

    public class Highlight(public val color: Color = Color.White.copy(alpha = 0.3f), public val width: Dp = 1.dp, public val angle: Float = -45f) : Effect() {
        override fun equals(other: Any?): Boolean = other is Highlight && color == other.color && width == other.width && angle == other.angle
        override fun hashCode(): Int = (color.hashCode() * 31 + width.hashCode()) * 31 + angle.hashCode()
    }

    public class Noise(public val amount: Float, public val scale: Dp = 1.dp) : Effect() {
        override fun equals(other: Any?): Boolean = other is Noise && amount == other.amount && scale == other.scale
        override fun hashCode(): Int = amount.hashCode() * 31 + scale.hashCode()
    }

    public class Custom(public val shader: EffectShader, public val params: FloatArray = FloatArray(0)) : Effect() {
        init { require(params.size <= shader.paramCount) }

        override fun equals(other: Any?): Boolean = other is Custom && shader == other.shader && params.contentEquals(other.params)
        override fun hashCode(): Int = shader.hashCode() * 31 + params.contentHashCode()
    }

    public class Liquid(
        public val thickness: Dp = LiquidMaterial.THICKNESS_DP.dp,
        public val refraction: Float = 1f,
        public val dispersion: Float = LiquidMaterial.DISPERSION,
        public val specular: Float = 1f,
        public val variant: LiquidVariant = LiquidVariant.Regular,
        public val tint: Color = Color.Unspecified,
        public val blur: Dp = Dp.Unspecified,
        public val style: Float = Float.NaN,
        public val shadow: Float = Float.NaN,
        public val dim: Float = 0f,
        public val appear: Float = 1f,
        public val glowCenter: Offset = Offset.Unspecified,
        public val glowRadius: Dp = 0.dp,
        public val glowStrength: Float = 0f,
    ) : Effect() {
        override fun equals(other: Any?): Boolean = other is Liquid && thickness == other.thickness &&
            refraction.toBits() == other.refraction.toBits() && dispersion.toBits() == other.dispersion.toBits() &&
            specular.toBits() == other.specular.toBits() && variant == other.variant && tint == other.tint && blur == other.blur &&
            style.toBits() == other.style.toBits() && shadow.toBits() == other.shadow.toBits() && dim.toBits() == other.dim.toBits() &&
            appear.toBits() == other.appear.toBits() && glowCenter == other.glowCenter && glowRadius == other.glowRadius &&
            glowStrength.toBits() == other.glowStrength.toBits()

        override fun hashCode(): Int {
            var h = thickness.hashCode()
            h = h * 31 + refraction.toBits()
            h = h * 31 + dispersion.toBits()
            h = h * 31 + specular.toBits()
            h = h * 31 + variant.hashCode()
            h = h * 31 + tint.hashCode()
            h = h * 31 + blur.hashCode()
            h = h * 31 + style.toBits()
            h = h * 31 + shadow.toBits()
            h = h * 31 + dim.toBits()
            h = h * 31 + appear.toBits()
            h = h * 31 + glowCenter.hashCode()
            h = h * 31 + glowRadius.hashCode()
            return h * 31 + glowStrength.toBits()
        }
    }
}

@Immutable
public class EffectShader(public val key: String, public val source: String, public val paramCount: Int = 0) {
    internal val poly = PolyEffectShader(key, source, paramCount)

    override fun equals(other: Any?): Boolean = other is EffectShader && poly == other.poly
    override fun hashCode(): Int = poly.hashCode()
}

public fun Effect.asRenderEffect(): RenderEffect = ChainEffect(listOf(this)).asComposeRenderEffect()

public fun List<Effect>.asRenderEffect(): RenderEffect = ChainEffect(toList()).asComposeRenderEffect()

internal fun Effect.toPoly(density: Density): PolyEffect? = with(density) {
    when (val e = this@toPoly) {
        is Effect.Blur -> PolyEffect.Blur(e.radius.toPx(), e.edgeTreatment != TileMode.Decal)
        is Effect.ColorMatrix -> PolyEffect.ColorMatrix(e.matrix.values.copyOf())
        is Effect.Tint -> if (e.color.isSpecified) PolyEffect.Tint(e.color.copy(alpha = (e.color.alpha * e.alpha).coerceIn(0f, 1f)).toArgb()) else null
        is Effect.Refract -> PolyEffect.Refract(e.band.toPx(), e.strength.coerceIn(0f, 1f), e.dispersion)
        is Effect.Highlight -> PolyEffect.Highlight(e.color.toArgb(), e.width.toPx(), e.angle)
        is Effect.Noise -> PolyEffect.Noise(e.amount.coerceIn(0f, 1f), e.scale.toPx())
        is Effect.Custom -> PolyEffect.Custom(e.shader.poly, e.params)
        is Effect.Liquid -> null
    }
}
