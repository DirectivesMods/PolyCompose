@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.render

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSimple
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.unit.Dp
import org.polyfrost.compose.backend.PolyComposePath
import org.polyfrost.compose.backend.PolyDrawCanvas
import org.polyfrost.compose.backend.clipTo
import java.util.function.IntSupplier
import kotlin.math.max

public fun chromaBrush(speed: Float = 1f, base: Color = Color.Red): Brush = ChromaBrush(base.toArgb(), speed)

public fun PolyColor.toComposeBrush(): Brush = if (chroma) ChromaBrush(rawArgb, chromaSpeed) else SolidColor(Color(rawArgb))

public fun Modifier.chromaBackground(speed: Float = 1f, shape: Shape = RectangleShape, base: Color = Color.Red): Modifier =
    this then ChromaElement(base.toArgb(), true, speed, shape, null)

public fun Modifier.chromaBackground(color: PolyColor, shape: Shape = RectangleShape): Modifier =
    this then ChromaElement(color.rawArgb, color.chroma, color.chromaSpeed, shape, null)

public fun Modifier.chromaBorder(width: Dp, speed: Float = 1f, shape: Shape = RectangleShape, base: Color = Color.Red): Modifier =
    this then ChromaElement(base.toArgb(), true, speed, shape, width)

public fun Modifier.chromaBorder(width: Dp, color: PolyColor, shape: Shape = RectangleShape): Modifier =
    this then ChromaElement(color.rawArgb, color.chroma, color.chromaSpeed, shape, width)

internal class ChromaShader(val color: PolyColor) : PlatformShader

internal class ChromaBrush(val base: Int, val speed: Float) : ShaderBrush() {
    private val color = PolyColor(base, true, speed)

    fun argb(): Int = color.argb

    override fun createShader(size: Size): Shader = Shader(ChromaShader(color))

    override fun equals(other: Any?): Boolean = other is ChromaBrush && other.base == base && other.speed == speed
    override fun hashCode(): Int = base * 31 + speed.hashCode()
    override fun toString(): String = "ChromaBrush(${Integer.toHexString(base)}, $speed)"
}

private data class ChromaElement(val argb: Int, val chroma: Boolean, val speed: Float, val shape: Shape, val stroke: Dp?) : ModifierNodeElement<ChromaNode>() {
    override fun create(): ChromaNode = ChromaNode(PolyColor(argb, chroma, speed), shape, stroke)

    override fun update(node: ChromaNode) {
        node.color = PolyColor(argb, chroma, speed)
        node.shape = shape
        node.stroke = stroke
    }
}

private class ChromaNode(var color: PolyColor, var shape: Shape, var stroke: Dp?) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        if (stroke == null) paint()
        drawContent()
        if (stroke != null) paint()
    }

    private var traced: Outline? = null
    private var tracedWidth = 0f
    private var contours: List<FloatArray>? = null

    private fun DrawScope.paint() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val sw = stroke?.toPx()?.coerceAtLeast(1f) ?: 0f
        val c = color
        val w = size.width
        val h = size.height
        val lines = if (sw > 0f) borderContours(outline, sw) else null
        drawIntoCanvas { canvas ->
            (canvas as? PolyDrawCanvas)?.polyOp { pc ->
                val r = pc.renderer
                if (!c.chroma) fill(r, outline, c.rawArgb, sw, lines)
                else r.live(-sw, -sw, w + sw * 2f, h + sw * 2f, IntSupplier { c.argb }) { fill(it, outline, c.argb, sw, lines) }
            }
        }
    }

    private fun borderContours(outline: Outline, sw: Float): List<FloatArray>? {
        val simple = when (outline) {
            is Outline.Rectangle -> true
            is Outline.Rounded -> outline.roundRect.isSimple
            is Outline.Generic -> false
        }
        if (simple) return null
        if (traced == outline && tracedWidth == sw) return contours
        val half = sw / 2f
        val path = when (outline) {
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect.inset(half)) }
            else -> (outline as Outline.Generic).path
        }
        return (path as? PolyComposePath ?: return null).allContours().also {
            traced = outline
            tracedWidth = sw
            contours = it
        }
    }

    private fun RoundRect.inset(d: Float): RoundRect {
        fun corner(c: CornerRadius) = CornerRadius(max(0f, c.x - d), max(0f, c.y - d))
        return RoundRect(
            left + d, top + d, max(left + d, right - d), max(top + d, bottom - d),
            corner(topLeftCornerRadius), corner(topRightCornerRadius), corner(bottomRightCornerRadius), corner(bottomLeftCornerRadius),
        )
    }

    private fun fill(r: PolyRenderer, outline: Outline, argb: Int, sw: Float, lines: List<FloatArray>?) {
        if (argb ushr 24 == 0) return
        if (lines != null) return strokeContours(r, outline, argb, sw, lines)
        val b = outline.bounds
        val radius = when (outline) {
            is Outline.Rectangle -> 0f
            is Outline.Rounded -> outline.roundRect.let { rr ->
                val x = rr.topLeftCornerRadius.x
                if (rr.topRightCornerRadius.x == x && rr.bottomLeftCornerRadius.x == x && rr.bottomRightCornerRadius.x == x && rr.topLeftCornerRadius.y == x) x else -1f
            }
            is Outline.Generic -> -1f
        }
        if (sw > 0f) {
            val half = sw / 2f
            val rr = if (radius >= 0f) radius else (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius?.x ?: 0f
            return r.rectStroke(b.left + half, b.top + half, b.width - sw, b.height - sw, argb, sw, max(0f, rr - half))
        }
        if (radius >= 0f) return r.rect(b.left, b.top, b.width, b.height, argb, radius)
        val path = when (outline) {
            is Outline.Generic -> outline.path
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Rectangle -> return r.rect(b.left, b.top, b.width, b.height, argb, 0f)
        }
        r.save()
        r.clipTo(path)
        r.rect(b.left, b.top, b.width, b.height, argb, 0f)
        r.restore()
    }

    private fun strokeContours(r: PolyRenderer, outline: Outline, argb: Int, sw: Float, lines: List<FloatArray>) {
        if (outline !is Outline.Generic) {
            for (i in lines.indices) r.strokePolyline(lines[i], true, argb, sw, PolyRenderer.CAP_BUTT, PolyRenderer.JOIN_MITER)
            return
        }
        r.save()
        r.clipTo(outline.path)
        val width = sw * 2f
        for (i in lines.indices) r.strokePolyline(lines[i], true, argb, width, PolyRenderer.CAP_BUTT, PolyRenderer.JOIN_MITER)
        r.restore()
    }
}
