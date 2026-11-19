package org.polyfrost.compose.minecraft.warmup

import kotlin.math.roundToInt
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.PolyGlyphPage

internal object GlyphWarmup {
    private const val FIRST = ' '
    private const val LAST = '~'

    fun run(density: Float): Int {
        val face = PolyGlyphPage.rasterizer?.face(FontManager.DEFAULT_KEY) ?: return 0
        val profile = WarmupProfile.current
        val sizes = profile.glyphSizes
        val phases = if (profile.allSubpixelPhases) PolyGlyphPage.SUBPIXELS else 1
        val ascii = (FIRST..LAST).joinToString("")
        for (size in sizes) PolyFont(FontManager.DEFAULT_KEY, size * density).measureTextWidth(ascii)
        var count = 0
        for (size in sizes) {
            val bucket = (size * density * 4f).roundToInt()
            for (c in FIRST..LAST) {
                val id = face.glyph(c.code)
                for (phase in 0 until phases) PolyGlyphPage.prerasterize(face, bucket, id, phase)
                count += phases
            }
        }
        return count
    }
}
