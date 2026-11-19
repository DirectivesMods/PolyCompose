package org.polyfrost.compose.text

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.render.PolyFont

public val MinecraftFontFamily: FontFamily = FontFamily(McFonts.Source(McFonts.REGULAR, FontWeight.Normal), McFonts.Source(McFonts.BOLD, FontWeight.Bold))

public val MinecraftTextShadow: Shadow = Shadow(Color.Unspecified, Offset.Zero, McFonts.SHADOW_MARK)

public fun minecraftFontSize(scale: Float = 1f): TextUnit = (McFonts.CELL * scale).sp

internal object McFonts {
    const val REGULAR = "polycompose:minecraft"
    const val BOLD = "polycompose:minecraft-bold"
    const val CELL = 8f
    const val ASCENT = 7f
    const val SHADOW_MARK = -8f
    private const val DESCENT = 2f
    private const val STRIKEOUT = 3.5f

    class Source(val key: String, override val weight: FontWeight) : Font {
        override val style: FontStyle get() = FontStyle.Normal

        override fun equals(other: Any?): Boolean = other is Source && other.key == key && other.weight == weight
        override fun hashCode(): Int = key.hashCode() * 31 + weight.hashCode()
        override fun toString(): String = "MinecraftFont($key)"
    }

    fun isMinecraft(name: String): Boolean = name == REGULAR || name == BOLD

    fun scale(font: PolyFont): Float = font.size / CELL

    fun width(font: PolyFont, text: String): Float {
        val scale = scale(font)
        if (font.name == BOLD && '§' !in text) return McFontQueue.measureTextWidth("§l$text", scale, false)
        return McFontQueue.measureTextWidth(text, scale, true)
    }

    fun metrics(font: PolyFont): PolyFont.Metrics {
        val s = scale(font)
        return PolyFont.Metrics(-ASCENT * s, DESCENT * s, 0f, s, s, -STRIKEOUT * s, s)
    }

    fun isShadow(shadow: Shadow?): Boolean = shadow != null && !shadow.color.isSpecified && shadow.blurRadius == SHADOW_MARK

    fun shadowArgb(argb: Int): Int = (argb and ALPHA) or ((argb and SHADOW_RGB) ushr 2)

    private const val ALPHA = 0xFF000000.toInt()
    private const val SHADOW_RGB = 0xFCFCFC
}
