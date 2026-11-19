@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.effect

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.isSpecified
import org.polyfrost.compose.backend.DynamicOp
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.backend.PolyDrawCanvas
import org.polyfrost.compose.backend.clipTo
import org.polyfrost.compose.backend.imageBlur
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.compose.render.alphaByte
import org.polyfrost.compose.skiko.PolyGraphicsLayer
import kotlin.math.abs
import kotlin.math.min

public fun Modifier.backdrop(backdrop: Backdrop, shape: Shape = RectangleShape, alpha: Float = 1f): Modifier =
    this then BackdropElement(backdrop, shape, alpha)

public fun DrawScope.drawBackdrop(
    backdrop: Backdrop,
    shape: Shape = RectangleShape,
    topLeft: Offset = Offset.Zero,
    size: Size = this.size,
    alpha: Float = 1f,
) {
    if (size.width <= 0f || size.height <= 0f || alpha <= 0f) return
    val key = DrawKey(backdrop, shape, size, layoutDirection, density, fontScale, settingsStamp())
    val resolved = drawCache[key] ?: ResolvedBackdrop.of(backdrop, shape, size, layoutDirection, this, null).also { drawCache[key] = it }
    resolved.draw(this, topLeft, alpha)
}

private data class DrawKey(
    val backdrop: Backdrop,
    val shape: Shape,
    val size: Size,
    val direction: LayoutDirection,
    val density: Float,
    val fontScale: Float,
    val settings: Int,
)

private const val DRAW_CACHE_SIZE = 32

private val drawCache = object : LinkedHashMap<DrawKey, ResolvedBackdrop>(DRAW_CACHE_SIZE, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<DrawKey, ResolvedBackdrop>?): Boolean = size > DRAW_CACHE_SIZE
}

private fun settingsStamp(): Int {
    val s = LiquidGlassSettings
    return (if (s.reduceTransparency) 1 else 0) or (if (s.increaseContrast) 2 else 0) or (if (s.reduceMotion) 4 else 0)
}

private class BackdropElement(val backdrop: Backdrop, val shape: Shape, val alpha: Float) : ModifierNodeElement<BackdropNode>() {
    override fun create(): BackdropNode = BackdropNode(backdrop, shape, alpha)

    override fun update(node: BackdropNode) = node.update(backdrop, shape, alpha)

    override fun equals(other: Any?): Boolean = other is BackdropElement && backdrop == other.backdrop && shape == other.shape && alpha == other.alpha

    override fun hashCode(): Int = (backdrop.hashCode() * 31 + shape.hashCode()) * 31 + alpha.hashCode()
}

private class BackdropNode(private var backdrop: Backdrop, private var shape: Shape, private var alpha: Float) : Modifier.Node(), DrawModifierNode {
    private val region = BackdropSample.Region()
    private var resolved: ResolvedBackdrop? = null
    private var resolvedSize = Size.Unspecified
    private var resolvedDirection = LayoutDirection.Ltr
    private var resolvedDensity = 0f
    private var resolvedFontScale = 0f
    private var resolvedSettings = -1

    fun update(backdrop: Backdrop, shape: Shape, alpha: Float) {
        if (backdrop != this.backdrop || shape != this.shape) {
            this.backdrop = backdrop
            this.shape = shape
            resolved = null
            invalidateDraw()
        }
        if (alpha != this.alpha) {
            this.alpha = alpha
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val settings = settingsStamp()
        var r = resolved
        if (r == null || !matches(settings)) {
            r = ResolvedBackdrop.of(backdrop, shape, size, layoutDirection, this, region)
            resolved = r
            resolvedSize = size
            resolvedDirection = layoutDirection
            resolvedDensity = density
            resolvedFontScale = fontScale
            resolvedSettings = settings
        }
        r.draw(this, Offset.Zero, alpha)
        drawContent()
    }

    private fun ContentDrawScope.matches(settings: Int): Boolean {
        if (resolvedSize != size || resolvedDirection != layoutDirection) return false
        return resolvedDensity == density && resolvedFontScale == fontScale && resolvedSettings == settings
    }
}

internal class ResolvedBackdrop private constructor(
    private val source: BackdropSource,
    private val x: Float,
    private val y: Float,
    private val w: Float,
    private val h: Float,
    private val radius: Float,
    private val clip: Path?,
    private val effects: Array<PolyEffect?>,
    private val autoAlpha: FloatArray?,
    private val base: PolyBackdrop,
    private val liquid: LiquidBinding?,
    region: BackdropSample.Region?,
) {
    private val live = base.sample
    private val tintOnly = autoAlpha != null && liquid == null
    private val fixed = region?.let { Slot(it) }
    private val slots = ArrayList<Slot>(1)

    private inner class Slot(val region: BackdropSample.Region) {
        var x = 0f
        var y = 0f
        var usedAt = 0L
        var sampledVersion = -1
        var sampled = base
        val key: () -> Int = {
            region.request()
            region.version
        }

        init {
            region.tintOnly = tintOnly
        }
    }

    private fun slotAt(r: PolyRenderer, dx: Float, dy: Float): Slot {
        fixed?.let { return it }
        val t = r.deviceTransform()
        val px = if (t == null) x + dx else x * t[0] + t[2]
        val py = if (t == null) y + dy else y * t[1] + t[3]
        val now = System.nanoTime()
        var oldest: Slot? = null
        var recent: Slot? = null
        for (s in slots) {
            if (abs(s.x - px) < SLOT_TOLERANCE && abs(s.y - py) < SLOT_TOLERANCE) {
                s.usedAt = now
                return s
            }
            if (oldest == null || s.usedAt < oldest.usedAt) oldest = s
            if (recent == null || s.usedAt > recent.usedAt) recent = s
        }
        val slot = if (slots.size >= MAX_SLOTS && oldest != null) oldest
        else Slot(BackdropSample.Region()).also { fresh ->
            recent?.let { fresh.sampled = it.sampled }
            slots += fresh
        }
        slot.x = px
        slot.y = py
        slot.usedAt = now
        return slot
    }

    fun draw(scope: DrawScope, topLeft: Offset, alpha: Float) {
        if (w <= 0f || h <= 0f || alpha <= 0f) return
        scope.drawIntoCanvas { c ->
            val pc = c as? PolyDrawCanvas ?: return@drawIntoCanvas
            val layer = (source as? BackdropSource.Layer)?.layer?.platformGraphicsLayer as? PolyGraphicsLayer
            if (layer != null) pc.polyOp(DynamicOp(LayerReplay(this, layer, topLeft.x, topLeft.y, alpha)))
            else if (source !is BackdropSource.Layer) pc.polyOp { drawSource(it.renderer, topLeft.x, topLeft.y, alpha) }
        }
    }

    private fun drawSource(r: PolyRenderer, dx: Float, dy: Float, alpha: Float) {
        r.save()
        r.translate(dx, dy)
        if (clip != null) r.clipTo(clip)
        if (live) {
            val slot = slotAt(r, dx, dy)
            r.live(x, y, w, h, slot.key) {
                val previous = BackdropSample.current
                BackdropSample.current = slot.region
                try {
                    it.backdropEffect(x, y, w, h, radius, current(slot), alpha)
                } finally {
                    BackdropSample.current = previous
                }
            }
        }
        else r.backdropEffect(x, y, w, h, radius, base, alpha)
        r.restore()
    }

    private fun drawLayer(r: PolyRenderer, canvas: PolyCanvas, layer: PolyGraphicsLayer, dx: Float, dy: Float, alpha: Float) {
        r.save()
        r.translate(dx, dy)
        if (clip != null) r.clipTo(clip) else r.clipRRect(x, y, w, h, radius)
        val chain = base.chain
        val gpu = !chain.isEmpty && r.effectSupport() and PolyRenderer.EFFECT_LAYER != 0
        val prevBlur = imageBlur
        if (gpu) r.saveLayerEffect(x, y, w, h, radius, chain, alpha)
        else {
            r.saveLayerAlpha(alpha)
            if (chain.blurSigma > 0f) imageBlur = chain.blurSigma
        }
        try {
            layer.replayUncached(canvas)
        } finally {
            imageBlur = prevBlur
            r.restore()
            r.restore()
        }
    }

    private fun current(slot: Slot): PolyBackdrop {
        val v = slot.region.version
        if (v == slot.sampledVersion || v == 0) return slot.sampled
        slot.sampledVersion = v
        val auto = autoAlpha
        val pixels = slot.region.pixels
        val tint = if (auto != null) averageOf(pixels) else 0
        val adapt = liquid != null && liquid.adapts(pixels)
        if ((auto == null || tint == 0) && !adapt) {
            slot.sampled = base
            return base
        }
        val list = ArrayList<PolyEffect>(effects.size + LIQUID_EXTRA)
        for (i in effects.indices) {
            val e = effects[i]
            if (liquid != null && i == liquid.index) liquid.params.addEffects(list, liquid.density)
            else if (e != null) list += e
            else if (auto != null && tint != 0) {
                val a = alphaByte(auto[i])
                if (a != 0) list += PolyEffect.Tint(a shl 24 or (tint and 0xFFFFFF))
            }
        }
        val out = PolyBackdrop(base.source, PolyEffectChain(list), base.downscale, base.padding, base.sample, base.fallbackColor)
        slot.sampled = out
        return out
    }

    private class LayerReplay(
        private val owner: ResolvedBackdrop,
        private val layer: PolyGraphicsLayer,
        private val dx: Float,
        private val dy: Float,
        private val alpha: Float,
    ) : (RenderContext) -> Unit {
        private var canvas: PolyCanvas? = null

        override fun invoke(ctx: RenderContext) {
            val r = ctx.renderer
            val c = canvas?.takeIf { it.renderer === r } ?: PolyCanvas(r).also { canvas = it }
            owner.drawLayer(r, c, layer, dx, dy, alpha)
        }
    }

    companion object {
        private const val PAD_PER_REFRACT = 1.12f
        private const val LIQUID_EXTRA = 3
        private const val MAX_SLOTS = 8
        private const val SLOT_TOLERANCE = 0.5f

        fun of(
            backdrop: Backdrop,
            shape: Shape,
            size: Size,
            layoutDirection: LayoutDirection,
            density: Density,
            region: BackdropSample.Region?,
        ): ResolvedBackdrop {
            val outline = shape.createOutline(size, layoutDirection, density)
            val src = backdrop.effects
            val liquidIndex = src.indexOfLast { it is Effect.Liquid }
            var clip: Path? = null
            var radius = 0f
            val b = outline.bounds
            when (outline) {
                is Outline.Rectangle -> Unit
                is Outline.Rounded -> {
                    val rr = outline.roundRect
                    val c = rr.topLeftCornerRadius
                    if (c.x == c.y && rr.topRightCornerRadius == c && rr.bottomRightCornerRadius == c && rr.bottomLeftCornerRadius == c) radius = c.x
                    else if (liquidIndex < 0) clip = Path().apply { addRoundRect(rr) }
                }
                is Outline.Generic -> clip = outline.path
            }
            val layer = backdrop.source is BackdropSource.Layer
            val effects = arrayOfNulls<PolyEffect>(src.size)
            var autoAlpha: FloatArray? = null
            val known = ArrayList<PolyEffect>(src.size + LIQUID_EXTRA)
            val liquid = if (liquidIndex >= 0) {
                val corners = FloatArray(PolyEffect.Liquid.CORNER_STRIDE)
                LiquidMaterial.corners(outline, corners)
                LiquidBinding.of(src[liquidIndex] as Effect.Liquid, liquidIndex, b.left, b.top, b.width, b.height, corners, density)
            } else {
                null
            }
            for (i in src.indices) {
                val e = src[i]
                if (i == liquidIndex && liquid != null) {
                    liquid.params.addEffects(known, density.density)
                    continue
                }
                val p = e.toPoly(density)
                effects[i] = p
                if (p != null) known += p
                else if (!layer && e is Effect.Tint) (autoAlpha ?: FloatArray(src.size).also { autoAlpha = it })[i] = e.alpha
            }
            val chain = if (known.isEmpty()) PolyEffectChain.Empty else PolyEffectChain(known)
            val padding = if (backdrop.padding.isSpecified) with(density) { backdrop.padding.toPx() }
            else chain.outset + (chain.refract?.let { it.band * it.strength * PAD_PER_REFRACT } ?: 0f)
            val sourceId = if (backdrop.source is BackdropSource.GameFrame) PolyBackdrop.SOURCE_GAME_FRAME else PolyBackdrop.SOURCE_BEHIND
            val fallback = if (backdrop.fallback.isSpecified) backdrop.fallback.toArgb() else 0
            val sample = !layer && (backdrop.sample || autoAlpha != null || liquid?.auto == true)
            val base = PolyBackdrop(sourceId, chain, backdrop.downscale, padding, sample, fallback)
            return ResolvedBackdrop(backdrop.source, b.left, b.top, b.width, b.height, radius, clip, effects, autoAlpha, base, liquid, region)
        }

        private fun averageOf(pixels: IntArray): Int {
            if (pixels.isEmpty()) return 0
            var r = 0L
            var g = 0L
            var b = 0L
            for (p in pixels) {
                r += p shr 16 and 0xFF
                g += p shr 8 and 0xFF
                b += p and 0xFF
            }
            val n = pixels.size
            return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
        }
    }
}

internal class LiquidBinding private constructor(
    val params: LiquidParams,
    val index: Int,
    val density: Float,
    private val autoStyle: Boolean,
    private val autoShadow: Boolean,
    private val minDp: Float,
) {
    private val estimate = LiquidEstimate()

    val auto: Boolean get() = !params.clear && (autoStyle || autoShadow)

    fun adapts(pixels: IntArray): Boolean {
        if (!auto || pixels.isEmpty()) return false
        estimate.measure(pixels)
        if (autoStyle) params.style = estimate.style(minDp)
        if (autoShadow) params.shadow = estimate.shadow(params.sizeFactor, false)
        return true
    }

    companion object {
        fun of(e: Effect.Liquid, index: Int, x: Float, y: Float, w: Float, h: Float, corners: FloatArray, density: Density): LiquidBinding {
            val d = density.density
            val minDp = min(w, h) / d
            val p = LiquidParams()
            p.thickness = with(density) { e.thickness.toPx() }
            p.refraction = e.refraction
            p.dispersion = e.dispersion
            p.specular = e.specular
            p.clear = e.variant == LiquidVariant.Clear
            p.tint = e.tint
            p.blur = if (e.blur.isSpecified) with(density) { e.blur.toPx() } else Float.NaN
            p.dim = e.dim
            p.addShape(x, y, w, h, corners[0], corners[1], corners[2], corners[3])
            if (e.glowCenter.isSpecified) {
                p.glowX = x + e.glowCenter.x
                p.glowY = y + e.glowCenter.y
                p.glowRadius = with(density) { e.glowRadius.toPx() }
                p.glowStrength = e.glowStrength
            }
            p.appear = e.appear.coerceIn(0f, 1f)
            p.sizeFactor = LiquidMaterial.sizeFactor(minDp)
            p.style = if (e.style.isNaN()) 0f else e.style
            p.shadow = if (e.shadow.isNaN()) LiquidEstimate().shadow(p.sizeFactor, p.clear) else e.shadow
            return LiquidBinding(p, index, d, e.style.isNaN(), e.shadow.isNaN(), minDp)
        }
    }
}
