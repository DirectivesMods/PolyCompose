package org.polyfrost.compose.render

import kotlin.math.hypot
import kotlin.math.sqrt

public sealed class PolyEffect {
    public class Blur(public val radiusX: Float, public val radiusY: Float, public val clampEdges: Boolean) : PolyEffect() {
        public constructor(radius: Float, clampEdges: Boolean) : this(radius, radius, clampEdges)

        public val radius: Float get() = maxOf(radiusX, radiusY)
        public val sigma: Float get() = sigmaOf(radius)
        public val sigmaX: Float get() = if (radiusX > 0f) sigmaOf(radiusX) else 0f
        public val sigmaY: Float get() = if (radiusY > 0f) sigmaOf(radiusY) else 0f

        override fun equals(other: Any?): Boolean = other is Blur && radiusX == other.radiusX && radiusY == other.radiusY && clampEdges == other.clampEdges
        override fun hashCode(): Int = (radiusX.hashCode() * 31 + radiusY.hashCode()) * 31 + clampEdges.hashCode()
    }

    public class ColorMatrix(public val values: FloatArray) : PolyEffect() {
        init { require(values.size == 20) }

        override fun equals(other: Any?): Boolean = other is ColorMatrix && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    public class Tint(public val color: Int) : PolyEffect() {
        override fun equals(other: Any?): Boolean = other is Tint && color == other.color
        override fun hashCode(): Int = color
    }

    public class Refract(public val band: Float, public val strength: Float, public val dispersion: Float) : PolyEffect() {
        override fun equals(other: Any?): Boolean =
            other is Refract && band == other.band && strength == other.strength && dispersion == other.dispersion
        override fun hashCode(): Int = (band.hashCode() * 31 + strength.hashCode()) * 31 + dispersion.hashCode()
    }

    public class Highlight(public val color: Int, public val width: Float, public val angle: Float) : PolyEffect() {
        override fun equals(other: Any?): Boolean = other is Highlight && color == other.color && width == other.width && angle == other.angle
        override fun hashCode(): Int = (color * 31 + width.hashCode()) * 31 + angle.hashCode()
    }

    public class Noise(public val amount: Float, public val scale: Float) : PolyEffect() {
        override fun equals(other: Any?): Boolean = other is Noise && amount == other.amount && scale == other.scale
        override fun hashCode(): Int = amount.hashCode() * 31 + scale.hashCode()
    }

    public class Offset(public val dx: Float, public val dy: Float) : PolyEffect() {
        override fun equals(other: Any?): Boolean = other is Offset && dx == other.dx && dy == other.dy
        override fun hashCode(): Int = dx.hashCode() * 31 + dy.hashCode()
    }

    public class Custom(public val shader: PolyEffectShader, public val params: FloatArray) : PolyEffect() {
        override fun equals(other: Any?): Boolean = other is Custom && shader == other.shader && params.contentEquals(other.params)
        override fun hashCode(): Int = shader.hashCode() * 31 + params.contentHashCode()
    }

    public class Liquid(
        public val thickness: Float,
        public val refraction: Float,
        public val dispersion: Float,
        public val specular: Float,
        public val shapes: FloatArray,
        public val shapeCount: Int,
        public val spacing: Float,
        public val glowX: Float,
        public val glowY: Float,
        public val glowRadius: Float,
        public val glowStrength: Float,
        public val appear: Float,
        public val style: Float,
        public val shadow: Float,
        public val dim: Float,
        public val sizeFactor: Float,
        public val clear: Boolean,
        public val sampleId: Int,
        public val corners: FloatArray?,
    ) : PolyEffect() {
        public constructor(
            thickness: Float, refraction: Float, dispersion: Float, specular: Float, shapes: FloatArray, shapeCount: Int, spacing: Float,
            glowX: Float, glowY: Float, glowRadius: Float, glowStrength: Float, appear: Float, style: Float, shadow: Float, dim: Float,
            sizeFactor: Float, clear: Boolean, sampleId: Int,
        ) : this(
            thickness, refraction, dispersion, specular, shapes, shapeCount, spacing, glowX, glowY, glowRadius, glowStrength,
            appear, style, shadow, dim, sizeFactor, clear, sampleId, null,
        )

        public constructor(
            thickness: Float, refraction: Float, dispersion: Float, specular: Float, shapes: FloatArray, shapeCount: Int, spacing: Float,
            glowX: Float, glowY: Float, glowRadius: Float, glowStrength: Float, appear: Float, style: Float, shadow: Float, dim: Float,
            sizeFactor: Float, clear: Boolean,
        ) : this(
            thickness, refraction, dispersion, specular, shapes, shapeCount, spacing, glowX, glowY, glowRadius, glowStrength,
            appear, style, shadow, dim, sizeFactor, clear, 0, null,
        )

        init { require(shapeCount in 0..MAX_SHAPES && shapes.size >= shapeCount * SHAPE_STRIDE && (corners == null || corners.size >= shapeCount * CORNER_STRIDE)) }

        public val reach: Float
            get() {
                val t = thickness.coerceAtLeast(0f)
                val s = sizeFactor.coerceIn(0f, 1f)
                val shadowReach = if (shadow > 0f) t * (SHADOW_BASE + SHADOW_SIZE * s) else 0f
                return t * refraction.coerceAtLeast(0f) + shadowReach
            }

        override fun equals(other: Any?): Boolean = other is Liquid && thickness == other.thickness && refraction == other.refraction &&
            dispersion == other.dispersion && specular == other.specular && shapeCount == other.shapeCount &&
            shapes.contentEquals(other.shapes) && spacing == other.spacing && glowX == other.glowX && glowY == other.glowY &&
            glowRadius == other.glowRadius && glowStrength == other.glowStrength && appear == other.appear && style == other.style &&
            shadow == other.shadow && dim == other.dim && sizeFactor == other.sizeFactor && clear == other.clear &&
            sampleId == other.sampleId && corners.contentEquals(other.corners)

        override fun hashCode(): Int {
            var h = thickness.hashCode()
            h = h * 31 + refraction.hashCode()
            h = h * 31 + dispersion.hashCode()
            h = h * 31 + specular.hashCode()
            h = h * 31 + shapes.contentHashCode()
            h = h * 31 + shapeCount
            h = h * 31 + spacing.hashCode()
            h = h * 31 + glowX.hashCode()
            h = h * 31 + glowY.hashCode()
            h = h * 31 + glowRadius.hashCode()
            h = h * 31 + glowStrength.hashCode()
            h = h * 31 + appear.hashCode()
            h = h * 31 + style.hashCode()
            h = h * 31 + shadow.hashCode()
            h = h * 31 + dim.hashCode()
            h = h * 31 + sizeFactor.hashCode()
            h = h * 31 + clear.hashCode()
            h = h * 31 + sampleId
            return h * 31 + corners.contentHashCode()
        }

        public companion object {
            public const val MAX_SHAPES: Int = 8
            public const val SHAPE_STRIDE: Int = 5
            public const val CORNER_STRIDE: Int = 4

            private const val SHADOW_BASE = 0.58f
            private const val SHADOW_SIZE = 0.82f
        }
    }

    internal companion object {
        fun sigmaOf(radius: Float): Float = SIGMA_SCALE * radius + SIGMA_BIAS

        fun radiusOf(sigma: Float): Float = (sigma - SIGMA_BIAS) / SIGMA_SCALE

        private const val SIGMA_SCALE = 0.57735f
        private const val SIGMA_BIAS = 0.5f
    }
}

public class PolyEffectShader(public val key: String, public val source: String, public val paramCount: Int) {
    init { require(paramCount in 0..MAX_PARAMS) }

    override fun equals(other: Any?): Boolean = other is PolyEffectShader && key == other.key && source == other.source && paramCount == other.paramCount
    override fun hashCode(): Int = (key.hashCode() * 31 + source.hashCode()) * 31 + paramCount

    public companion object {
        public const val MAX_PARAMS: Int = 16
    }
}

public class PolyEffectChain(public val effects: List<PolyEffect>) {
    public val textureStages: List<PolyEffect>
    public val blurSigma: Float
    public val refract: PolyEffect.Refract?
    public val colorMatrix: FloatArray?
    public val noise: PolyEffect.Noise?
    public val highlight: PolyEffect.Highlight?
    public val liquid: PolyEffect.Liquid?
    public val offsetX: Float
    public val offsetY: Float
    public val outset: Float
    public val isEmpty: Boolean
    public val hasCustom: Boolean

    init {
        val stages = ArrayList<PolyEffect>()
        var refract: PolyEffect.Refract? = null
        var noise: PolyEffect.Noise? = null
        var highlight: PolyEffect.Highlight? = null
        var liquid: PolyEffect.Liquid? = null
        var matrix: FloatArray? = null
        var dx = 0f
        var dy = 0f
        for (e in effects) when (e) {
            is PolyEffect.Blur -> if (e.radius > 0f) {
                val last = stages.lastOrNull()
                if (last is PolyEffect.Blur && last.clampEdges == e.clampEdges) {
                    val x = hypot(last.sigmaX, e.sigmaX)
                    val y = hypot(last.sigmaY, e.sigmaY)
                    stages[stages.lastIndex] = PolyEffect.Blur(if (x > 0f) PolyEffect.radiusOf(x) else 0f, if (y > 0f) PolyEffect.radiusOf(y) else 0f, e.clampEdges)
                } else stages += e
            }
            is PolyEffect.Custom -> stages += e
            is PolyEffect.Refract -> refract = e
            is PolyEffect.Noise -> noise = e
            is PolyEffect.Highlight -> highlight = e
            is PolyEffect.Liquid -> liquid = e
            is PolyEffect.ColorMatrix -> matrix = concat(e.values, matrix)
            is PolyEffect.Tint -> matrix = concat(tintMatrix(e.color), matrix)
            is PolyEffect.Offset -> { dx += e.dx; dy += e.dy }
        }
        var leading = 0f
        var total = 0f
        var custom = false
        var stillLeading = true
        for (s in stages) {
            if (s is PolyEffect.Blur) {
                val sq = s.sigma * s.sigma
                total += sq
                if (stillLeading) leading += sq
            } else {
                stillLeading = false
                custom = true
            }
        }
        val m = matrix?.takeUnless { it.contentEquals(IDENTITY) }
        textureStages = stages
        blurSigma = sqrt(leading)
        this.refract = if (liquid != null) null else refract
        colorMatrix = m
        this.noise = noise
        this.highlight = if (liquid != null) null else highlight
        this.liquid = liquid
        offsetX = dx
        offsetY = dy
        outset = 3f * sqrt(total) + hypot(dx, dy) + (liquid?.reach ?: 0f)
        hasCustom = custom
        isEmpty = stages.isEmpty() && refract == null && liquid == null && m == null && noise == null && highlight == null && dx == 0f && dy == 0f
    }

    override fun equals(other: Any?): Boolean = other is PolyEffectChain && effects == other.effects
    override fun hashCode(): Int = effects.hashCode()

    public companion object {
        public val Empty: PolyEffectChain = PolyEffectChain(emptyList())

        private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

        private fun concat(next: FloatArray, prev: FloatArray?): FloatArray {
            if (prev == null) return next.copyOf()
            val out = FloatArray(20)
            for (i in 0 until 4) {
                for (k in 0 until 5) {
                    var v = if (k == 4) next[i * 5 + 4] else 0f
                    for (j in 0 until 4) v += next[i * 5 + j] * prev[j * 5 + k]
                    out[i * 5 + k] = v
                }
            }
            return out
        }

        private fun tintMatrix(color: Int): FloatArray {
            val a = (color ushr 24) / 255f
            val k = 1f - a
            return floatArrayOf(
                k, 0f, 0f, 0f, a * (color shr 16 and 0xFF),
                0f, k, 0f, 0f, a * (color shr 8 and 0xFF),
                0f, 0f, k, 0f, a * (color and 0xFF),
                0f, 0f, 0f, k, a * 255f,
            )
        }
    }
}

public class PolyBackdrop(
    public val source: Int,
    public val chain: PolyEffectChain,
    public val downscale: Int,
    public val padding: Float,
    public val sample: Boolean,
    public val fallbackColor: Int,
) {
    override fun equals(other: Any?): Boolean = other is PolyBackdrop && source == other.source && chain == other.chain &&
        downscale == other.downscale && padding == other.padding && sample == other.sample && fallbackColor == other.fallbackColor

    override fun hashCode(): Int {
        var h = source
        h = h * 31 + chain.hashCode()
        h = h * 31 + downscale
        h = h * 31 + padding.hashCode()
        h = h * 31 + sample.hashCode()
        return h * 31 + fallbackColor
    }

    public companion object {
        public const val SOURCE_BEHIND: Int = 0
        public const val SOURCE_GAME_FRAME: Int = 1
    }
}

public object PolyLiquidSamples {
    private const val INTERVAL_NANOS = 250_000_000L
    private const val WANTED_NANOS = 1_000_000_000L

    private val slots = HashMap<Int, Slot>()

    public fun due(id: Int): Boolean = synchronized(slots) {
        val slot = slots[id] ?: return false
        val now = System.nanoTime()
        if (now - slot.requestedAt > WANTED_NANOS) return false
        if (slot.version != 0 && now - slot.sampledAt < INTERVAL_NANOS) return false
        slot.sampledAt = now
        true
    }

    public fun publish(id: Int, argb: IntArray) {
        synchronized(slots) {
            val slot = slots[id] ?: return
            slot.pixels = argb
            slot.version++
        }
    }

    internal fun request(id: Int) {
        synchronized(slots) { slots.getOrPut(id) { Slot() }.requestedAt = System.nanoTime() }
    }

    internal fun version(id: Int): Int = synchronized(slots) { slots[id]?.version ?: 0 }

    internal fun pixels(id: Int): IntArray? = synchronized(slots) { slots[id]?.pixels }

    internal fun release(id: Int) {
        synchronized(slots) { slots.remove(id) }
    }

    private class Slot {
        var requestedAt = 0L
        var sampledAt = 0L
        var version = 0
        var pixels: IntArray? = null
    }
}
