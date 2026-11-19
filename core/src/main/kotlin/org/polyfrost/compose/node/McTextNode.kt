package org.polyfrost.compose.node

import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.RenderContext

public class McTextNode : PolyNode() {
    public var text: String = ""
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
            }
        }
    public var color: PolyColor = PolyColor.WHITE
    public var shadow: Boolean = true
    public var scale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
            }
        }
    public var outlineColor: PolyColor? = null
    public var textProvider: (() -> String)? = null
    public var literal: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
            }
        }
    private var measuredFonts = -1
    private var measureDirty = true

    override val hasLiveContent: Boolean get() = textProvider != null

    override fun measureContent() {
        val provider = textProvider
        if (provider != null) text = provider()
        if (!measureDirty && measuredFonts == FontManager.revision) return
        remeasure()
        contentRevision++
    }

    public fun remeasure() {
        measuredFonts = FontManager.revision
        measureDirty = false
        val lines = text.lines()
        intrinsicWidth = lines.maxOf { McFontQueue.measureTextWidth(it, scale, literal) }
        intrinsicHeight = McFontQueue.measureBlockHeight(lines.size, scale)
    }

    override fun renderContent(ctx: RenderContext) {
        val c = color
        val outline = outlineColor
        val lit = literal
        val x = x + style.padding.left
        val y = y + style.padding.top
        if (c.chroma && outline == null) {
            ctx.mcTextLive(text, x, y, { c.argb }, shadow, scale, lit)
        } else if (c.chroma || outline?.chroma == true) {
            val t = text; val px = x; val py = y; val sh = shadow; val sc = scale
            ctx.renderer.live { draw(it, t, px, py, c, outline, sh, sc, lit) }
        } else {
            draw(ctx.renderer, text, x, y, c, outline, shadow, scale, lit)
        }
    }

    private fun draw(renderer: PolyRenderer, text: String, x: Float, y: Float, color: PolyColor, outline: PolyColor?, shadow: Boolean, scale: Float, literal: Boolean) {
        if (text.isEmpty()) return
        if (outline != null) renderer.mcTextOutline(text, x, y, color.argb, outline.argb, scale, literal)
        else renderer.mcText(text, x, y, color.argb, shadow, scale, literal)
    }
}
