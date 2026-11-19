package org.polyfrost.compose.render

import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.max
import kotlin.math.roundToInt

public fun interface EmojiSource {
    public fun emoji(codepoint: Int): PolyBitmap?

    public companion object {
        @JvmStatic
        public fun atlas(bitmap: PolyBitmap, cell: Int, index: (codepoint: Int) -> Int): EmojiSource =
            atlas(bitmap, cell, cell, index)

        @JvmStatic
        public fun atlas(bitmap: PolyBitmap, cellWidth: Int, cellHeight: Int, index: (codepoint: Int) -> Int): EmojiSource {
            require(cellWidth > 0 && cellHeight > 0) { "bad emoji cell ${cellWidth}x$cellHeight" }
            val columns = bitmap.width / cellWidth
            val rows = bitmap.height / cellHeight
            val cells = ConcurrentHashMap<Int, PolyBitmap>()
            return EmojiSource { cp ->
                val i = index(cp)
                if (i < 0 || columns == 0 || i >= columns * rows) null
                else cells.getOrPut(i) { crop(bitmap, (i % columns) * cellWidth, (i / columns) * cellHeight, cellWidth, cellHeight) }
            }
        }

        private fun crop(b: PolyBitmap, x: Int, y: Int, w: Int, h: Int): PolyBitmap {
            val out = ByteArray(w * h * 4)
            for (row in 0 until h) {
                val start = ((y + row) * b.width + x) * 4
                b.pixels.copyInto(out, row * w * 4, start, start + w * 4)
            }
            return PolyBitmap(w, h, out)
        }
    }
}

public object CustomEmoji {
    private val sources = CopyOnWriteArrayList<EmojiSource>()

    @Volatile
    private var serial = 0

    @Volatile
    private var wrapped: Wrapped? = null

    @JvmStatic
    public fun register(source: EmojiSource): AutoCloseable {
        sources.add(0, source)
        changed()
        return AutoCloseable { if (sources.remove(source)) changed() }
    }

    @JvmStatic
    public fun has(codepoint: Int): Boolean = find(codepoint) != null

    @ApiStatus.Internal
    @JvmStatic
    public fun fallback(base: PolyGlyphPage.Face?): PolyGlyphPage.Face? {
        if (sources.isEmpty()) return base
        val w = wrapped
        if (w != null && w.base === base && w.serial == serial) return w
        return Wrapped(base, serial).also { wrapped = it }
    }

    private fun changed() {
        serial = (serial + 1) and SERIAL_MASK
        wrapped?.let(PolyGlyphPage::invalidate)
        wrapped = null
        FontManager.invalidateMetrics()
    }

    private fun find(cp: Int): PolyBitmap? {
        for (s in sources) {
            val b = runCatching { s.emoji(cp) }.getOrNull()
            if (b != null) return b
        }
        return null
    }

    private class Wrapped(val base: PolyGlyphPage.Face?, val serial: Int) : PolyGlyphPage.Face {
        private val bitmaps = ConcurrentHashMap<Int, Any>()

        private fun bitmap(cp: Int): PolyBitmap? = bitmaps.getOrPut(cp) { find(cp) ?: NONE } as? PolyBitmap

        override fun glyph(codepoint: Int): Int {
            if (codepoint in 0..Character.MAX_CODE_POINT && bitmap(codepoint) != null) return CUSTOM or (serial shl CP_BITS) or codepoint
            return base?.glyph(codepoint) ?: 0
        }

        override fun pair(first: Int, second: Int): Int = base?.pair(first, second) ?: 0

        override fun sequence(text: CharSequence, start: Int, end: Int): Long = base?.sequence(text, start, end) ?: 0L

        override fun advance(glyph: Int): Float {
            if (glyph and CUSTOM == 0) return base?.advance(glyph) ?: 0f
            val b = bitmap(glyph and CP_MASK) ?: return 0f
            return b.width / b.height.toFloat() * EM + GAP
        }

        override fun raster(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? = raster(glyph, pixelSize, 0f)

        override fun raster(glyph: Int, pixelSize: Float, shiftX: Float): PolyGlyphPage.Raster? {
            if (glyph and CUSTOM == 0) return base?.raster(glyph, pixelSize, shiftX)
            val b = bitmap(glyph and CP_MASK) ?: return null
            val h = max(1, (pixelSize * EM).roundToInt())
            val w = max(1, (h * b.width / b.height.toFloat()).roundToInt())
            return PolyGlyphPage.Raster((pixelSize * GAP / 2f).roundToInt(), -(pixelSize * RISE).roundToInt(), w, h, EmojiFace.scaled(b, w, h), true)
        }
    }

    private val NONE = Any()
    private const val CUSTOM = 0x40000000
    private const val CP_BITS = 21
    private const val CP_MASK = (1 shl CP_BITS) - 1
    private const val SERIAL_MASK = 0xFF
    private const val EM = 1f
    private const val GAP = 0.125f
    private const val RISE = 0.88f
}
