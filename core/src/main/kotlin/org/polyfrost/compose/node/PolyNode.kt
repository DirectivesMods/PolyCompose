package org.polyfrost.compose.node

import org.polyfrost.compose.layout.PolyLayoutStyle
import org.polyfrost.compose.render.CapturingRenderer
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.RenderContext

public open class PolyNode {
    public val children: MutableList<PolyNode> = mutableListOf<PolyNode>()
    public val style: PolyLayoutStyle = PolyLayoutStyle()
    public var parent: PolyNode? = null
    public var x: Float = 0f
    public var y: Float = 0f
    public var width: Float = 0f
    public var height: Float = 0f

    internal var intrinsicWidth: Float = -1f
    internal var intrinsicHeight: Float = -1f
    internal var contentRevision: Int = 0
    internal open val hasLiveContent: Boolean get() = false

    private var decoration: Decoration? = null
    private var liveScratch: RenderContext? = null

    internal fun liveContext(renderer: PolyRenderer): RenderContext {
        val cached = liveScratch
        if (cached != null && cached.renderer === renderer) return cached
        return RenderContext(renderer).also { liveScratch = it }
    }

    public open fun measureContent() {}

    protected open fun renderContent(ctx: RenderContext) {}

    public open fun render(ctx: RenderContext) {
        val s = style
        decorate(ctx)

        if (s.clipToBounds) {
            ctx.save()
            if (s.radius > 0f) ctx.clipRRect(x, y, width, height, s.radius) else ctx.clipRect(x, y, width, height)
        }
        renderContent(ctx)
        for (i in children.indices) {
            val child = children[i]
            val fade = child.style.fade
            val staticAlpha = child.style.alpha
            if (fade == null) {
                if (staticAlpha >= 1f) {
                    child.render(ctx)
                } else if (staticAlpha > 0f) {
                    ctx.saveLayerAlpha(staticAlpha)
                    child.render(ctx)
                    ctx.restore()
                }
            } else {
                ctx.renderer.live { r ->
                    val a = fade() * staticAlpha
                    if (a > 0f) {
                        val scratch = child.liveContext(r)
                        scratch.saveLayerAlpha(a)
                        child.render(scratch)
                        scratch.restore()
                    }
                }
            }
        }
        if (s.clipToBounds) ctx.restore()
    }

    private fun decorate(ctx: RenderContext) {
        val s = style
        val bg = s.background
        val border = s.borderColor
        val stroke = s.borderWidth
        if (bg.chroma || stroke > 0f && border.chroma) {
            val px = x; val py = y; val w = width; val h = height; val r = s.radius
            ctx.renderer.live { drawDecoration(it, px, py, w, h, r, bg.argb, if (stroke > 0f) border.argb else 0, stroke) }
            return
        }
        val bgArgb = bg.argb
        val borderArgb = if (stroke > 0f) border.argb else 0
        if (bgArgb ushr 24 == 0 && borderArgb ushr 24 == 0) return
        val cr = if (ctx.cacheNodes) ctx.renderer as? CapturingRenderer else null
        if (cr == null) return drawDecoration(ctx.renderer, x, y, width, height, s.radius, bgArgb, borderArgb, stroke)
        val d = decoration
        if (d != null && d.x == x && d.y == y && d.w == width && d.h == height && d.radius == s.radius && d.bg == bgArgb &&
            d.border == borderArgb && d.stroke == stroke && cr.sameState(d.key) && cr.valid(d.captured)
        ) {
            cr.append(d.captured)
            return
        }
        val key = cr.stateKey()
        val mark = cr.mark()
        drawDecoration(ctx.renderer, x, y, width, height, s.radius, bgArgb, borderArgb, stroke)
        decoration = Decoration(x, y, width, height, s.radius, bgArgb, borderArgb, stroke, key, cr.since(mark))
    }

    private class Decoration(
        val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float,
        val bg: Int, val border: Int, val stroke: Float, val key: Any, val captured: Any,
    )
}

private fun drawDecoration(renderer: PolyRenderer, x: Float, y: Float, w: Float, h: Float, radius: Float, bg: Int, border: Int, stroke: Float) {
    if (bg ushr 24 != 0) renderer.rect(x, y, w, h, bg, radius)
    if (stroke > 0f && border ushr 24 != 0) renderer.rectStroke(x, y, w, h, border, stroke, radius)
}
