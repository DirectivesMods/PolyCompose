package org.polyfrost.compose.effect

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
public sealed class BackdropSource {
    public object Behind : BackdropSource()

    public object GameFrame : BackdropSource()

    public class Layer(public val layer: GraphicsLayer) : BackdropSource() {
        override fun equals(other: Any?): Boolean = other is Layer && layer === other.layer
        override fun hashCode(): Int = System.identityHashCode(layer)
    }
}

@Immutable
public class Backdrop(
    public val source: BackdropSource = BackdropSource.Behind,
    public val effects: List<Effect> = emptyList(),
    public val downscale: Int = 1,
    public val padding: Dp = Dp.Unspecified,
    public val sample: Boolean = false,
    public val fallback: Color = Color.Unspecified,
) {
    init { require(downscale == 1 || downscale == 2 || downscale == 4) }

    override fun equals(other: Any?): Boolean = other is Backdrop && source == other.source && effects == other.effects &&
        downscale == other.downscale && padding == other.padding && sample == other.sample && fallback == other.fallback

    override fun hashCode(): Int {
        var h = source.hashCode()
        h = h * 31 + effects.hashCode()
        h = h * 31 + downscale
        h = h * 31 + padding.hashCode()
        h = h * 31 + sample.hashCode()
        return h * 31 + fallback.hashCode()
    }

    public companion object {
        public fun glass(
            band: Dp = 12.dp,
            refraction: Float = 0.5f,
            blur: Dp = 2.dp,
            tintAlpha: Float = 0.12f,
            highlight: Color = Color.White.copy(alpha = 0.3f),
            fallback: Color = Color.Unspecified,
        ): Backdrop = Backdrop(
            BackdropSource.Behind,
            listOf(Effect.Blur(blur), Effect.Refract(band, refraction), Effect.Tint(Color.Unspecified, tintAlpha), Effect.Highlight(highlight)),
            sample = true,
            fallback = fallback,
        )

        public fun liquid(
            variant: LiquidVariant = LiquidVariant.Regular,
            tint: Color = Color.Unspecified,
            fallback: Color = Color.Unspecified,
        ): Backdrop = Backdrop(
            BackdropSource.Behind,
            listOf(Effect.Liquid(variant = variant, tint = tint)),
            sample = variant == LiquidVariant.Regular,
            fallback = fallback,
        )

        public fun frosted(tint: Color = Color.Unspecified, tintAlpha: Float = 0f, fallback: Color = Color.Unspecified): Backdrop = Backdrop(
            BackdropSource.GameFrame,
            if (tintAlpha > 0f) listOf(Effect.Tint(tint, tintAlpha)) else emptyList(),
            fallback = fallback,
        )
    }
}
