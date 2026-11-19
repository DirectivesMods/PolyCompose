@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.skiko

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.platform.PlatformGraphicsContext
import androidx.compose.ui.graphics.platform.PlatformGraphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.toSize
import kotlin.math.abs
import kotlin.math.cos
import org.polyfrost.compose.backend.ChainEffect
import org.polyfrost.compose.backend.DynamicOp
import org.polyfrost.compose.backend.MatrixFilter
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.backend.PolyPaint
import org.polyfrost.compose.backend.RecordingCanvas
import org.polyfrost.compose.backend.TintFilter
import org.polyfrost.compose.backend.clipTo
import org.polyfrost.compose.backend.imageBlur
import org.polyfrost.compose.effect.Effect
import org.polyfrost.compose.effect.toPoly
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.render.CapturingRenderer
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyShadow
import org.polyfrost.compose.render.PolyTessellator

@Suppress("UNUSED_PARAMETER")
public class SkiaGraphicsContext(measureDrawBounds: Boolean = false, snapshotCache: Boolean = true) : PlatformGraphicsContext() {
    override fun createPlatformGraphicsLayer(): PlatformGraphicsLayer = PolyGraphicsLayer()
}

public class PolyGraphicsLayer : PlatformGraphicsLayer {
    override var compositingStrategy: CompositingStrategy = CompositingStrategy.Auto
    override var pivotOffset: Offset = Offset.Unspecified
    override var alpha: Float = 1f
    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = Color.Black
    override var spotShadowColor: Color = Color.Black
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = 8f
    override var renderEffect: RenderEffect? = null

    private var left = 0f
    private var top = 0f
    private var size = IntSize.Zero
    private var outline: Outline? = null
    private var clip = false
    private val recording = RecordingCanvas()
    private val drawScope = CanvasDrawScope()

    override fun setBounds(topLeft: IntOffset, size: IntSize) {
        left = topLeft.x.toFloat()
        top = topLeft.y.toFloat()
        this.size = size
    }

    override fun setOutline(outline: Outline?, clip: Boolean) {
        this.outline = outline
        this.clip = clip
    }

    private var version = 0
    private var animating = false
    private var lastScaleX = 1f
    private var lastScaleY = 1f
    private var cache: LayerCache? = null
    private val offset = FloatArray(2)

    private var clipMoves = false

    private class LayerCache(val version: Int, val props: Props, val key: Any, val movable: Boolean, val segs: List<Any>, val base: ClipBase? = null)

    private class ClipBase(val log: Any, val key: Any, val lx: Float, val ly: Float, val segs: List<Any>, val ranges: IntArray)

    private class Recut(val log: Any, val captured: Any, val from: Int, val to: Int, val dx: Float, val dy: Float)

    private class Props(l: PolyGraphicsLayer) {
        val lx = l.left + l.translationX
        val ly = l.top + l.translationY
        private val alpha = l.alpha
        private val scaleX = l.scaleX
        private val scaleY = l.scaleY
        private val animating = l.animating
        private val translationX = l.translationX
        private val rotationZ = l.rotationZ
        private val rotationX = l.rotationX
        private val rotationY = l.rotationY
        private val cameraDistance = l.cameraDistance
        private val translationY = l.translationY
        private val left = l.left
        private val top = l.top
        private val size = l.size
        private val outline = l.outline
        private val clip = l.clip
        private val shadowElevation = l.shadowElevation
        private val spotShadowColor = l.spotShadowColor
        private val pivotOffset = l.pivotOffset
        private val renderEffect = l.renderEffect
        private val colorFilter = l.colorFilter

        fun matches(l: PolyGraphicsLayer): Boolean =
            same(translationX, l.translationX) && same(translationY, l.translationY) && same(left, l.left) && same(top, l.top) && matchesMoved(l)

        fun matchesMoved(l: PolyGraphicsLayer): Boolean =
            same(alpha, l.alpha) && same(scaleX, l.scaleX) && same(scaleY, l.scaleY) && animating == l.animating &&
                same(rotationZ, l.rotationZ) && same(rotationX, l.rotationX) && same(rotationY, l.rotationY) &&
                same(cameraDistance, l.cameraDistance) && size == l.size && outline == l.outline && clip == l.clip &&
                same(shadowElevation, l.shadowElevation) && spotShadowColor == l.spotShadowColor && pivotOffset == l.pivotOffset &&
                sameEffect(renderEffect, l.renderEffect) && colorFilter == l.colorFilter

        private fun same(a: Float, b: Float) = a.toBits() == b.toBits()
    }
    private class ChildRef(val layer: PolyGraphicsLayer, val state: Any)
    private class DynamicRef(val op: DynamicOp, val state: Any)
    private class Capture(var mark: Any, var logFrom: Int) {
        val segs = ArrayList<Any>()
        val ranges = ArrayList<Int>()

        fun cut(cr: CapturingRenderer) {
            segs += cr.since(mark)
            ranges += logFrom
            ranges += (cr as? PolyTessellator)?.clipLogEnd() ?: -1
        }

        fun ref(seg: Any) {
            segs += seg
            ranges += -1
            ranges += -1
        }

        fun resume(cr: CapturingRenderer) {
            mark = cr.mark()
            logFrom = (cr as? PolyTessellator)?.clipLogStart() ?: -1
        }
    }

    override fun record(density: Density, layoutDirection: LayoutDirection, layer: GraphicsLayer, block: DrawScope.() -> Unit) {
        version++
        if (FrameProfiler.enabled) {
            redrawPending = true
            redrawHost = FrameProfiler.active
        }
        this.density = density
        recording.ops.clear()
        drawScope.draw(density, layoutDirection, recording, size.toSize(), block)
    }

    override fun draw(canvas: Canvas) {
        if (canvas is RecordingCanvas) { canvas.polyOp(::drawNow); return }
        if (canvas is PolyCanvas) drawNow(canvas)
    }

    private var drawParent: PolyGraphicsLayer? = null
    private var redrawPending = false
    private var redrawHost: ProfileHost? = null

    private fun drawNow(c: PolyCanvas) {
        if (!FrameProfiler.enabled) return drawBody(c)
        val previous = drawing
        drawParent = previous
        drawing = this
        if (redrawPending) reportRedraw(c.renderer.deviceTransform())
        try {
            drawBody(c)
        } finally {
            drawing = previous
        }
    }

    private fun reportRedraw(device: FloatArray?) {
        redrawPending = false
        var x0 = 0f
        var y0 = 0f
        var x1 = size.width.toFloat()
        var y1 = size.height.toFloat()
        var layer: PolyGraphicsLayer? = this
        while (layer != null) {
            val l = layer
            val p = if (l.pivotOffset.isUnspecified) Offset(l.size.width / 2f, l.size.height / 2f) else l.pivotOffset
            val dx = l.left + l.translationX + p.x
            val dy = l.top + l.translationY + p.y
            x0 = dx + (x0 - p.x) * l.scaleX
            x1 = dx + (x1 - p.x) * l.scaleX
            y0 = dy + (y0 - p.y) * l.scaleY
            y1 = dy + (y1 - p.y) * l.scaleY
            layer = if (device != null) null else l.drawParent
        }
        if (device != null) {
            x0 = device[2] + x0 * device[0]
            x1 = device[2] + x1 * device[0]
            y0 = device[3] + y0 * device[1]
            y1 = device[3] + y1 * device[1]
        }
        FrameProfiler.redraw(redrawHost, minOf(x0, x1), minOf(y0, y1), abs(x1 - x0), abs(y1 - y0))
        redrawHost = null
    }

    private fun drawBody(c: PolyCanvas) {
        animating = (scaleX != 1f || scaleY != 1f) && (scaleX != lastScaleX || scaleY != lastScaleY)
        lastScaleX = scaleX
        lastScaleY = scaleY
        val cr = c.renderer as? CapturingRenderer
        if (cr == null) return drawLive(c)
        val parent = capturing.lastOrNull()
        if (parent != null) {
            parent.cut(cr)
            parent.ref(ChildRef(this, cr.saveState()))
        }
        try {
            drawCached(c, cr)
        } finally {
            parent?.resume(cr)
        }
    }

    private fun drawCached(c: PolyCanvas, cr: CapturingRenderer) {
        if (alpha <= 0f) return
        if (alpha >= 1f || effectLayer(c.renderer) != null) return drawCachedInLayer(c, cr)
        c.renderer.saveLayerAlpha(alpha)
        try { drawCachedInLayer(c, cr) } finally { c.renderer.restore() }
    }

    private fun drawCachedInLayer(c: PolyCanvas, cr: CapturingRenderer) {
        val hit = cache
        val segs = if (hit != null && hit.version == version) reusable(hit, cr) else null
        if (segs != null) {
            replay(c, cr, segs)
            return
        }
        val props = Props(this)
        val pt = cr as? PolyTessellator
        val movableKey = pt?.movableKey()
        val key = movableKey ?: cr.stateKey()
        val log = pt?.clipLogPush(movableKey != null && clipMoves)
        val cap = Capture(cr.mark(), pt?.clipLogStart() ?: -1)
        capturing += cap
        try {
            drawLive(c, layered = true)
            cap.cut(cr)
        } finally {
            capturing.removeLast()
            pt?.clipLogPop()
        }
        val base = if (pt != null && log != null && movableKey != null) clipBase(pt, log, movableKey, props, cap) else null
        cache = LayerCache(version, props, key, movableKey != null, cap.segs, base)
    }

    private fun clipBase(pt: PolyTessellator, log: Any, key: Any, props: Props, cap: Capture): ClipBase? {
        val segs = cap.segs
        val ranges = IntArray(segs.size * 2) { cap.ranges[it] }
        for (i in segs.indices) {
            val seg = segs[i]
            if (seg !is ChildRef && seg !is DynamicRef && !pt.clipLogReady(log, seg, ranges[i * 2], ranges[i * 2 + 1])) return null
        }
        return ClipBase(log, key, props.lx, props.ly, segs, ranges)
    }

    private fun reusable(hit: LayerCache, cr: CapturingRenderer): List<Any>? {
        if (!hit.movable) return if (cr.sameState(hit.key) && hit.props.matches(this) && hit.segs.allValid(cr)) hit.segs else null
        val pt = cr as? PolyTessellator ?: return null
        if (!hit.props.matchesMoved(this)) return null
        return shifted(hit, pt, cr) ?: recut(hit, pt, cr)
    }

    private fun recut(hit: LayerCache, pt: PolyTessellator, cr: CapturingRenderer): List<Any>? {
        val o = offset
        val base = hit.base
        if (base == null) {
            if (pt.offsetFrom(hit.key, left + translationX - hit.props.lx, top + translationY - hit.props.ly, o, true)) clipMoves = true
            return null
        }
        if (!pt.offsetFrom(base.key, left + translationX - base.lx, top + translationY - base.ly, o, true) || !base.segs.allValid(cr)) return null
        val dx = o[0]
        val dy = o[1]
        val src = base.segs
        val out = ArrayList<Any>(src.size)
        for (i in src.indices) {
            out += when (val seg = src[i]) {
                is ChildRef -> ChildRef(seg.layer, pt.clipShiftedState(base.log, seg.state, dx, dy) ?: return null)
                is DynamicRef -> DynamicRef(seg.op, pt.clipShiftedState(base.log, seg.state, dx, dy) ?: return null)
                else -> Recut(base.log, seg, base.ranges[i * 2], base.ranges[i * 2 + 1], dx, dy)
            }
        }
        cache = LayerCache(version, Props(this), pt.movableKey() ?: return null, true, out, base)
        return out
    }

    private fun shifted(hit: LayerCache, pt: PolyTessellator, cr: CapturingRenderer): List<Any>? {
        val o = offset
        if (!pt.offsetFrom(hit.key, left + translationX - hit.props.lx, top + translationY - hit.props.ly, o) || !hit.segs.allValid(cr)) return null
        val dx = o[0]
        val dy = o[1]
        if (dx == 0f && dy == 0f) {
            if (hit.props.matches(this)) return hit.segs
            cache = LayerCache(version, Props(this), pt.movableKey() ?: return null, true, hit.segs, hit.base)
            return hit.segs
        }
        val src = hit.segs
        val moved = ArrayList<Any>(src.size)
        for (i in src.indices) {
            moved += when (val seg = src[i]) {
                is ChildRef -> ChildRef(seg.layer, pt.shiftedState(seg.state, dx, dy) ?: return null)
                is DynamicRef -> DynamicRef(seg.op, pt.shiftedState(seg.state, dx, dy) ?: return null)
                else -> pt.shiftedCapture(seg, dx, dy) ?: return null
            }
        }
        cache = LayerCache(version, Props(this), pt.movableKey() ?: return null, true, moved, hit.base)
        return moved
    }

    private fun replay(c: PolyCanvas, cr: CapturingRenderer, segs: List<Any>) {
        val pt = cr as? PolyTessellator
        capturing += null
        pt?.clipLogPush(false)
        try {
            for (i in segs.indices) {
                val seg = segs[i]
                val state = when (seg) {
                    is ChildRef -> seg.state
                    is DynamicRef -> seg.state
                    is Recut -> {
                        val mark = cr.mark()
                        pt?.clipRecut(seg.log, seg.captured, seg.from, seg.to, seg.dx, seg.dy)
                        (segs as? MutableList<Any>)?.set(i, cr.since(mark))
                        continue
                    }
                    else -> { cr.append(seg); continue }
                }
                val saved = if (pt != null) pt.swapState(state) else cr.saveState().also { cr.restoreState(state) }
                try {
                    if (seg is ChildRef) seg.layer.drawNow(c) else (seg as DynamicRef).op(c)
                } finally {
                    if (pt != null) pt.putState(saved) else cr.restoreState(saved)
                }
            }
        } finally {
            capturing.removeLast()
            pt?.clipLogPop()
        }
    }

    private fun List<Any>.allValid(cr: CapturingRenderer): Boolean {
        for (i in indices) {
            val seg = this[i]
            if (seg !is ChildRef && seg !is DynamicRef && (seg is Recut || !cr.valid(seg))) return false
        }
        return true
    }

    private fun drawLive(c: PolyCanvas, layered: Boolean = false) {
        if (alpha <= 0f) return
        val r = c.renderer
        val effect = effectLayer(r)
        if (alpha < 1f && !layered && effect == null) r.saveLayerAlpha(alpha) else r.save()
        r.translate(left + translationX, top + translationY)
        if (scaleX != 1f || scaleY != 1f || rotationZ != 0f || rotationX != 0f || rotationY != 0f) {
            val p = if (pivotOffset.isUnspecified) Offset(size.width / 2f, size.height / 2f) else pivotOffset
            r.translate(p.x, p.y)
            if (rotationX != 0f || rotationY != 0f) rotate3d(r)
            if (rotationZ != 0f) r.rotate(rotationZ)
            if (scaleX != 1f || scaleY != 1f) { if (animating) r.animatedScale(scaleX, scaleY) else r.scale(scaleX, scaleY) }
            r.translate(-p.x, -p.y)
        }
        val o = outline
        if (shadowElevation > 0f && o != null) {
            val b = o.bounds
            r.shadow(b.left, b.top + shadowElevation / 2f, b.width, b.height, radiusOf(o), PolyShadow(PolyColor(spotShadowColor.copy(alpha = spotShadowColor.alpha * 0.25f).toArgb()), shadowElevation, 0f, 0f, 0f))
        }
        if (effect != null) r.saveLayerEffect(0f, 0f, size.width.toFloat(), size.height.toFloat(), o?.let(::radiusOf) ?: 0f, effect, alpha)
        applyClip(r)
        val cap = capturing.lastOrNull()
        val cr = r as? CapturingRenderer
        val prevBlur = imageBlur
        if (effect == null) effectChain().blurSigma.let { if (it > 0f) imageBlur = it }
        try {
            if (cap == null || cr == null) recording.replay(c)
            else recording.replay(c) { op ->
                cap.cut(cr)
                cap.ref(DynamicRef(op, cr.saveState()))
                try { op(c) } finally { cap.resume(cr) }
            }
        } finally {
            imageBlur = prevBlur
        }
        if (effect != null) r.restore()
        r.restore()
    }

    private fun rotate3d(r: PolyRenderer) {
        if (r is PolyTessellator) return r.rotate3d(rotationX, rotationY, cameraDistance)
        r.scale(cos(Math.toRadians(rotationY.toDouble())).toFloat(), cos(Math.toRadians(rotationX.toDouble())).toFloat())
    }

    private fun applyClip(r: PolyRenderer) {
        if (!clip) return
        when (val o = outline) {
            null -> r.clipRect(0f, 0f, size.width.toFloat(), size.height.toFloat())
            // Rounded outlines whose corners differ also arrive as paths
            is Outline.Generic -> r.clipTo(o.path)
            else -> { val b = o.bounds; r.clipRRect(b.left, b.top, b.width, b.height, radiusOf(o)) }
        }
    }

    internal fun replayUncached(c: PolyCanvas) {
        val r = c.renderer
        r.save()
        applyClip(r)
        val pt = r as? PolyTessellator
        capturing += null
        pt?.clipLogPush(false)
        try {
            recording.replay(c)
        } finally {
            capturing.removeLast()
            pt?.clipLogPop()
            r.restore()
        }
    }

    private fun effectLayer(r: PolyRenderer): PolyEffectChain? {
        val chain = effectChain()
        return if (chain.isEmpty || r.effectSupport() and PolyRenderer.EFFECT_LAYER == 0) null else chain
    }

    private fun effectChain(): PolyEffectChain {
        val effect = renderEffect
        val filter = colorFilter
        if (effect == null && filter == null) return PolyEffectChain.Empty
        if (sameEffect(effect, chainEffect) && filter == chainFilter && density == chainDensity) return chain
        val list = ArrayList<PolyEffect>()
        val items = (effect?.platformRenderEffect as? ChainEffect)?.items
        if (items != null) {
            val d = density ?: Density(1f)
            for (item in items) when (item) {
                is PolyEffect -> list += item
                is Effect -> item.toPoly(d)?.let { list += it }
            }
        }
        if (filter != null) filterEffect(filter)?.let { list += it }
        chainEffect = effect
        chainFilter = filter
        chainDensity = density
        chain = if (list.isEmpty()) PolyEffectChain.Empty else PolyEffectChain(list)
        return chain
    }

    private fun radiusOf(o: Outline) = (o as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f

    override fun discardDisplayList() {
        version++
        cache = null
        recording.ops.clear()
    }
    override fun setOutsets(left: Int, top: Int, right: Int, bottom: Int) {}

    private var density: Density? = null
    private var chainEffect: RenderEffect? = null
    private var chainFilter: ColorFilter? = null
    private var chainDensity: Density? = null
    private var chain = PolyEffectChain.Empty

    private companion object {
        val capturing = ArrayList<Capture?>()
        var drawing: PolyGraphicsLayer? = null

        fun sameEffect(a: RenderEffect?, b: RenderEffect?): Boolean =
            a == b || a != null && b != null && a.javaClass == b.javaClass && a.platformRenderEffect.let { it is ChainEffect && it == b.platformRenderEffect }

        fun filterEffect(filter: ColorFilter): PolyEffect? = when (val p = PolyPaint.filterPlatform(filter)) {
            is MatrixFilter -> PolyEffect.ColorMatrix(p.matrix.values.copyOf())
            is TintFilter -> {
                val c = p.color
                when (p.blendMode) {
                    BlendMode.SrcOver -> PolyEffect.Tint(c.toArgb())
                    BlendMode.Modulate -> PolyEffect.ColorMatrix(
                        floatArrayOf(c.red, 0f, 0f, 0f, 0f, 0f, c.green, 0f, 0f, 0f, 0f, 0f, c.blue, 0f, 0f, 0f, 0f, 0f, c.alpha, 0f),
                    )
                    BlendMode.SrcIn, BlendMode.SrcAtop -> PolyEffect.ColorMatrix(
                        floatArrayOf(0f, 0f, 0f, 0f, c.red * 255f, 0f, 0f, 0f, 0f, c.green * 255f, 0f, 0f, 0f, 0f, c.blue * 255f, 0f, 0f, 0f, c.alpha, 0f),
                    )
                    else -> null
                }
            }
            else -> null
        }
    }
}
