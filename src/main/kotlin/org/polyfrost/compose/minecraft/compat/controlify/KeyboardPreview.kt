package org.polyfrost.compose.minecraft.compat.controlify

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.max
import kotlin.math.min
import org.polyfrost.compose.backend.PolyText
import org.polyfrost.compose.text.MinecraftFontFamily
import org.polyfrost.compose.text.minecraftFontSize

internal class KeyboardPreview {
    private val measurer by lazy {
        TextMeasurer(PolyText.createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    }

    private val panel = Paint().apply { color = Color(0f, 0f, 0f, PANEL_ALPHA) }
    private val border = Paint().apply { color = Color(1f, 1f, 1f, BORDER_ALPHA) }
    private val caret = Paint().apply { color = Color.White }
    private val highlight = Paint().apply { color = Color(1f, 1f, 1f, HIGHLIGHT_ALPHA) }

    fun draw(canvas: Canvas, keyboard: Rect, unit: Float, field: FieldPreview) {
        val height = PANEL_HEIGHT * unit
        val bottom = keyboard.top - GAP * unit
        val top = bottom - height
        if (top < 0f) return
        val left = keyboard.left
        val right = keyboard.right
        val radius = RADIUS * unit
        canvas.drawRoundRect(left - unit, top - unit, right + unit, bottom + unit, radius + unit, radius + unit, border)
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, panel)

        val pad = PADDING * unit
        val innerLeft = left + pad
        val innerRight = right - pad
        val innerWidth = innerRight - innerLeft
        if (innerWidth <= 0f) return
        val shown = if (field.password) MASK.toString().repeat(field.text.length) else field.text
        val empty = shown.isEmpty()
        val label = if (empty) field.hint.orEmpty() else shown
        val layout = if (label.isEmpty()) null else measure(label, unit, if (empty) HINT_ALPHA else 1f)
        val textHeight = layout?.size?.height?.toFloat() ?: (minecraftFontSize(unit).value)
        val textTop = top + (height - textHeight) / 2f

        val selection = if (empty) null else field.selection
        val caretIndex = selection?.end?.coerceIn(0, shown.length) ?: shown.length
        val caretX = if (empty || layout == null) 0f else layout.getHorizontalPosition(caretIndex, true)
        val contentWidth = layout?.size?.width?.toFloat() ?: 0f
        val caretWidth = max(unit, 1f)
        val scroll = if (empty || contentWidth + caretWidth <= innerWidth) 0f
        else (caretX + caretWidth - innerWidth).coerceIn(0f, contentWidth + caretWidth - innerWidth)

        canvas.save()
        canvas.clipRect(innerLeft, top, innerRight, bottom, ClipOp.Intersect)
        canvas.translate(innerLeft - scroll, textTop)
        if (selection != null && !selection.collapsed && layout != null) {
            val start = layout.getHorizontalPosition(selection.min.coerceIn(0, shown.length), true)
            val end = layout.getHorizontalPosition(selection.max.coerceIn(0, shown.length), true)
            canvas.drawRect(min(start, end), 0f, max(start, end), textHeight, highlight)
        }
        if (layout != null) TextPainter.paint(canvas, layout)
        canvas.drawRect(caretX, 0f, caretX + caretWidth, textHeight, caret)
        canvas.restore()
    }

    private fun measure(text: String, unit: Float, alpha: Float): TextLayoutResult = measurer.measure(
        text,
        TextStyle(
            color = Color.White.copy(alpha = alpha),
            fontFamily = MinecraftFontFamily,
            fontSize = minecraftFontSize(unit),
        ),
        overflow = TextOverflow.Clip,
        softWrap = false,
        maxLines = 1,
    )

    companion object {
        const val PANEL_HEIGHT = 18f
        const val GAP = 4f
        private const val RADIUS = 4f
        private const val PADDING = 6f
        private const val PANEL_ALPHA = 0.72f
        private const val BORDER_ALPHA = 0.18f
        private const val HIGHLIGHT_ALPHA = 0.3f
        private const val HINT_ALPHA = 0.45f
        private const val MASK = '*'
    }
}
