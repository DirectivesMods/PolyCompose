package org.polyfrost.compose.minecraft.font

import org.lwjgl.stb.STBTTBitmap
import org.lwjgl.stb.STBTTFontinfo
import org.lwjgl.stb.STBTTVertex
import org.lwjgl.stb.STBTruetype.stbtt_FindGlyphIndex
import org.lwjgl.stb.STBTruetype.stbtt_FreeSDF
import org.lwjgl.stb.STBTruetype.stbtt_FreeShape
import org.lwjgl.stb.STBTruetype.stbtt_GetFontOffsetForIndex
import org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics
import org.lwjgl.stb.STBTruetype.stbtt_GetGlyphBitmapBoxSubpixel
import org.lwjgl.stb.STBTruetype.stbtt_GetGlyphHMetrics
import org.lwjgl.stb.STBTruetype.stbtt_GetGlyphKernAdvance
import org.lwjgl.stb.STBTruetype.stbtt_GetGlyphSDF
import org.lwjgl.stb.STBTruetype.stbtt_GetGlyphShape
import org.lwjgl.stb.STBTruetype.stbtt_InitFont
import org.lwjgl.stb.STBTruetype.stbtt_MakeGlyphBitmapSubpixel
import org.lwjgl.stb.STBTruetype.stbtt_Rasterize
import org.lwjgl.stb.STBTruetype.stbtt_ScaleForMappingEmToPixels
import org.lwjgl.system.MemoryUtil
import org.polyfrost.compose.render.CustomEmoji
import org.polyfrost.compose.render.EmojiFace
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyTessellator
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal object StbFonts : PolyFont.Engine, PolyGlyphPage.Rasterizer {
    private class PagedInts {
        private val pages = arrayOfNulls<IntArray>(CACHE_PAGES)

        fun get(key: Int): Int {
            if (key < 0 || key ushr CACHE_PAGE_BITS >= CACHE_PAGES) return 0
            return pages[key ushr CACHE_PAGE_BITS]?.get(key and CACHE_PAGE_MASK) ?: 0
        }

        fun put(key: Int, value: Int) {
            if (key < 0 || key ushr CACHE_PAGE_BITS >= CACHE_PAGES) return
            val page = pages[key ushr CACHE_PAGE_BITS] ?: IntArray(CACHE_PAGE_MASK + 1).also { pages[key ushr CACHE_PAGE_BITS] = it }
            page[key and CACHE_PAGE_MASK] = value
        }
    }

    class Face private constructor(val bytes: ByteArray?, private val data: ByteBuffer, private val owned: Boolean) : PolyGlyphPage.Face {
        constructor(bytes: ByteArray) : this(bytes, MemoryUtil.memAlloc(bytes.size).put(bytes).flip(), true)

        constructor(mapped: ByteBuffer) : this(null, mapped, false)

        @Volatile
        private var freed = false

        val info: STBTTFontinfo = STBTTFontinfo.malloc()
        val emScale: Float
        private val unitMetrics: FloatArray
        private val glyphIds = PagedInts()
        private val advances = PagedInts()

        init {
            try {
                val fontOffset = stbtt_GetFontOffsetForIndex(data, 0)
                check(stbtt_InitFont(info, data, fontOffset)) { "stb_truetype can't read font" }
                emScale = stbtt_ScaleForMappingEmToPixels(info, 1f)
                val asc = IntArray(1); val desc = IntArray(1); val gap = IntArray(1)
                stbtt_GetFontVMetrics(info, asc, desc, gap)
                val post = table(data, fontOffset, "post", 12)
                val ulPos = if (post < 0) Float.NaN else -data.s16(post + 8) * emScale
                val ulThick = if (post < 0) Float.NaN else data.s16(post + 10) * emScale
                val os2 = table(data, fontOffset, "OS/2", 30)
                val soThick = if (os2 < 0) Float.NaN else data.s16(os2 + 26) * emScale
                val soPos = if (os2 < 0) Float.NaN else -data.s16(os2 + 28) * emScale
                unitMetrics = floatArrayOf(-asc[0] * emScale, -desc[0] * emScale, gap[0] * emScale, ulPos, ulThick, soPos, soThick)
            } catch (t: Throwable) {
                info.free()
                if (owned) MemoryUtil.memFree(data)
                throw t
            }
        }

        override fun glyph(codepoint: Int): Int {
            val cached = glyphIds.get(codepoint)
            if (cached != 0) return cached - 1
            val id = findGlyph(codepoint)
            glyphIds.put(codepoint, id + 1)
            return id
        }

        private fun findGlyph(codepoint: Int): Int = synchronized(this) {
            if (freed) 0 else stbtt_FindGlyphIndex(info, if (codepoint == '\t'.code) ' '.code else codepoint)
        }

        override fun advance(glyph: Int): Float = advanceUnits(glyph) * emScale

        fun advanceUnits(glyph: Int): Int {
            val cached = advances.get(glyph)
            if (cached != 0) return cached - 1
            val adv = findAdvance(glyph)
            advances.put(glyph, adv + 1)
            return adv
        }

        private fun findAdvance(glyph: Int): Int {
            val adv = IntArray(1); val lsb = IntArray(1)
            synchronized(this) {
                if (freed) return 0
                stbtt_GetGlyphHMetrics(info, glyph, adv, lsb)
            }
            return adv[0]
        }

        fun free() {
            synchronized(this) {
                if (freed) return
                freed = true
                info.free()
                if (owned) MemoryUtil.memFree(data)
            }
        }

        fun metrics(size: Float) = PolyFont.Metrics(
            unitMetrics[0] * size, unitMetrics[1] * size, unitMetrics[2] * size,
            if (unitMetrics[3].isNaN()) size * 0.08f else unitMetrics[3] * size,
            if (unitMetrics[4].isNaN()) size * 0.06f else unitMetrics[4] * size,
            if (unitMetrics[5].isNaN() || unitMetrics[6] <= 0f) unitMetrics[0] * size / 3f else unitMetrics[5] * size,
            if (unitMetrics[6].isNaN() || unitMetrics[6] <= 0f) (if (unitMetrics[4].isNaN()) size * 0.06f else unitMetrics[4] * size) else unitMetrics[6] * size,
        )

        override fun raster(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? = raster(glyph, pixelSize, 0f)

        override fun raster(glyph: Int, pixelSize: Float, shiftX: Float): PolyGlyphPage.Raster? = synchronized(this) {
            if (freed) return null
            val scale = stbtt_ScaleForMappingEmToPixels(info, pixelSize)
            if (DILATE > 0f) rasterDilated(glyph, scale, pixelSize, shiftX) else rasterExact(glyph, scale, shiftX)
        }

        private fun rasterExact(id: Int, scale: Float, shiftX: Float): PolyGlyphPage.Raster? {
            val x0 = IntArray(1); val y0 = IntArray(1); val x1 = IntArray(1); val y1 = IntArray(1)
            stbtt_GetGlyphBitmapBoxSubpixel(info, id, scale, scale, shiftX, 0f, x0, y0, x1, y1)
            val w = x1[0] - x0[0]
            val h = y1[0] - y0[0]
            if (w <= 0 || h <= 0) return null
            val buf = MemoryUtil.memAlloc(w * h)
            try {
                if (!scaledComponents(id, 0) || !rasterComponents(id, buf, w, h, scale, shiftX, x0[0], y0[0])) {
                    stbtt_MakeGlyphBitmapSubpixel(info, buf, w, h, w, scale, scale, shiftX, 0f, id)
                }
                return PolyGlyphPage.Raster(x0[0], y0[0], w, h, ByteArray(w * h).also { buf.get(it) })
            } finally {
                MemoryUtil.memFree(buf)
            }
        }

        private val directory = stbtt_GetFontOffsetForIndex(data, 0)
        private val glyf = tableOffset("glyf")
        private val loca = tableOffset("loca")
        private val longLoca = tableOffset("head").let { it >= 0 && data.u16(it + 50) == 1 }
        private val glyphCount = tableOffset("maxp").let { if (it < 0) 0 else data.u16(it + 4) }
        private val kerned = tableOffset("kern") >= 0 || tableOffset("GPOS") >= 0
        private val kerns = AtomicLongArray(KERN_SLOTS)

        override fun kern(left: Int, right: Int): Float {
            if (!kerned || left !in 1..MAX_GLYPH || right !in 1..MAX_GLYPH) return 0f
            val key = (left shl 16) or right
            val slot = (key * KERN_HASH) ushr (Int.SIZE_BITS - KERN_BITS)
            val cached = kerns.get(slot)
            if ((cached ushr 32).toInt() == key) return cached.toInt() * emScale
            val units = synchronized(this) { if (freed) 0 else stbtt_GetGlyphKernAdvance(info, left, right) }
            kerns.set(slot, (key.toLong() shl 32) or (units.toLong() and 0xFFFFFFFFL))
            return units * emScale
        }

        private fun f2dot14(o: Int) = data.s16(o) / 16384f

        private fun tableOffset(tag: String): Int {
            if (directory < 0) return -1
            for (i in 0 until data.u16(directory + 4)) {
                val r = directory + 12 + i * 16
                if (r + 16 > data.limit()) return -1
                if ((0 until 4).all { (data.get(r + it).toInt() and 0xFF) == tag[it].code }) return data.u32(r + 8)
            }
            return -1
        }

        private fun glyphAt(id: Int): Int {
            if (glyf < 0 || loca < 0 || id !in 0 until glyphCount) return -1
            val start = if (longLoca) data.u32(loca + id * 4) else data.u16(loca + id * 2) * 2
            val end = if (longLoca) data.u32(loca + id * 4 + 4) else data.u16(loca + id * 2 + 2) * 2
            return if (end > start && glyf + end <= data.limit()) glyf + start else -1
        }

        private fun componentSize(flags: Int) = 4 + (if (flags and WORD_ARGS != 0) 4 else 2) + when {
            flags and UNIFORM_SCALE != 0 -> 2
            flags and XY_SCALE != 0 -> 4
            flags and TWO_BY_TWO != 0 -> 8
            else -> 0
        }

        private fun scaledComponents(id: Int, depth: Int): Boolean {
            val at = glyphAt(id)
            if (at < 0 || data.s16(at) >= 0 || depth > MAX_COMPONENT_DEPTH) return false
            var c = at + 10
            while (true) {
                val flags = data.u16(c)
                if (flags and (UNIFORM_SCALE or XY_SCALE or TWO_BY_TWO) != 0 || scaledComponents(data.u16(c + 2), depth + 1)) return true
                if (flags and MORE_COMPONENTS == 0) return false
                c += componentSize(flags)
            }
        }

        private fun collect(id: Int, m: FloatArray, out: ArrayList<FloatArray>, depth: Int): Boolean {
            val at = glyphAt(id)
            if (at < 0) return true
            if (data.s16(at) >= 0) {
                val shape = stbtt_GetGlyphShape(info, id) ?: return true
                try {
                    for (i in 0 until shape.remaining()) {
                        val v = shape.get(i)
                        out += floatArrayOf(
                            v.type().toFloat(),
                            m[0] * v.x() + m[2] * v.y() + m[4], m[1] * v.x() + m[3] * v.y() + m[5],
                            m[0] * v.cx() + m[2] * v.cy() + m[4], m[1] * v.cx() + m[3] * v.cy() + m[5],
                            m[0] * v.cx1() + m[2] * v.cy1() + m[4], m[1] * v.cx1() + m[3] * v.cy1() + m[5],
                        )
                    }
                } finally {
                    stbtt_FreeShape(info, shape)
                }
                return true
            }
            if (depth > MAX_COMPONENT_DEPTH) return false
            var c = at + 10
            while (true) {
                val flags = data.u16(c)
                if (flags and XY_VALUES == 0) return false
                val words = flags and WORD_ARGS != 0
                var dx = (if (words) data.s16(c + 4) else data.get(c + 4).toInt()).toFloat()
                var dy = (if (words) data.s16(c + 6) else data.get(c + 5).toInt()).toFloat()
                val t = c + if (words) 8 else 6
                val local = when {
                    flags and UNIFORM_SCALE != 0 -> f2dot14(t).let { floatArrayOf(it, 0f, 0f, it) }
                    flags and XY_SCALE != 0 -> floatArrayOf(f2dot14(t), 0f, 0f, f2dot14(t + 2))
                    flags and TWO_BY_TWO != 0 -> floatArrayOf(f2dot14(t), f2dot14(t + 2), f2dot14(t + 4), f2dot14(t + 6))
                    else -> floatArrayOf(1f, 0f, 0f, 1f)
                }
                if (flags and SCALED_OFFSET != 0) {
                    val x = dx
                    dx = local[0] * x + local[2] * dy
                    dy = local[1] * x + local[3] * dy
                }
                val child = floatArrayOf(
                    m[0] * local[0] + m[2] * local[1], m[1] * local[0] + m[3] * local[1],
                    m[0] * local[2] + m[2] * local[3], m[1] * local[2] + m[3] * local[3],
                    m[0] * dx + m[2] * dy + m[4], m[1] * dx + m[3] * dy + m[5],
                )
                if (!collect(data.u16(c + 2), child, out, depth + 1)) return false
                if (flags and MORE_COMPONENTS == 0) return true
                c += componentSize(flags)
            }
        }

        private fun rasterComponents(id: Int, buf: ByteBuffer, w: Int, h: Int, scale: Float, shiftX: Float, x0: Int, y0: Int): Boolean {
            val points = ArrayList<FloatArray>()
            if (!collect(id, floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f), points, 0) || points.isEmpty()) return false
            val vertices = STBTTVertex.calloc(points.size)
            val bitmap = STBTTBitmap.calloc()
            try {
                for ((i, p) in points.withIndex()) {
                    val address = vertices.get(i).address()
                    MemoryUtil.memPutByte(address + STBTTVertex.TYPE, p[0].toInt().toByte())
                    MemoryUtil.memPutShort(address + STBTTVertex.X, p[1].roundToInt().toShort())
                    MemoryUtil.memPutShort(address + STBTTVertex.Y, p[2].roundToInt().toShort())
                    MemoryUtil.memPutShort(address + STBTTVertex.CX, p[3].roundToInt().toShort())
                    MemoryUtil.memPutShort(address + STBTTVertex.CY, p[4].roundToInt().toShort())
                    MemoryUtil.memPutShort(address + STBTTVertex.CX1, p[5].roundToInt().toShort())
                    MemoryUtil.memPutShort(address + STBTTVertex.CY1, p[6].roundToInt().toShort())
                }
                bitmap.set(w, h, w, buf)
                stbtt_Rasterize(bitmap, FLATNESS, vertices, scale, scale, shiftX, 0f, x0, y0, true)
                return true
            } finally {
                vertices.free()
                bitmap.free()
            }
        }

        private fun rasterDilated(id: Int, scale: Float, pixelSize: Float, shiftX: Float): PolyGlyphPage.Raster? {
            val w = IntArray(1); val h = IntArray(1); val xo = IntArray(1); val yo = IntArray(1)
            val sdf = stbtt_GetGlyphSDF(info, scale, id, SDF_PAD, SDF_EDGE.toByte(), SDF_SCALE, w, h, xo, yo) ?: return null
            try {
                val bias = 0.5f + DILATE * sqrt(pixelSize)
                val sw = w[0]
                val sh = h[0]
                fun dist(x: Int, y: Int) = if (x < 0 || x >= sw) -SDF_EDGE / SDF_SCALE else ((sdf.get(y * sw + x).toInt() and 0xFF) - SDF_EDGE) / SDF_SCALE
                val ow = if (shiftX > 0f) sw + 1 else sw
                val out = ByteArray(ow * sh)
                var i = 0
                for (y in 0 until sh) {
                    for (x in 0 until ow) {
                        val d = if (shiftX > 0f) dist(x - 1, y) * shiftX + dist(x, y) * (1f - shiftX) else dist(x, y)
                        out[i++] = ((d + bias).coerceIn(0f, 1f) * 255f).roundToInt().toByte()
                    }
                }
                return PolyGlyphPage.Raster(xo[0], yo[0], ow, h[0], out)
            } finally {
                stbtt_FreeSDF(sdf)
            }
        }
    }

    private val faces = ConcurrentHashMap<String, Face>()
    private val failed = HashMap<String, ByteArray>()

    override fun face(name: String): PolyGlyphPage.Face? =
        if (name == PolyTessellator.FALLBACK_FONT) CustomEmoji.fallback(stbFace(name)) else stbFace(name)

    private fun stbFace(name: String): Face? {
        val bytes = FontManager.fontData(name) ?: return null
        val current = faces[name]
        if (current != null && current.bytes === bytes) return current
        return loadFace(name)
    }

    @Synchronized
    private fun loadFace(name: String): Face? {
        val bytes = FontManager.fontData(name) ?: return null
        val previous = faces[name]
        if (previous != null && previous.bytes === bytes) return previous
        if (failed[name] === bytes) return null
        val face = try {
            Face(bytes)
        } catch (_: Exception) {
            failed[name] = bytes
            return null
        }
        failed.remove(name)
        faces[name] = face
        previous?.let(PolyGlyphPage::invalidate)
        previous?.free()
        return face
    }

    private val emoji by lazy { runCatching { EmojiFace.systemMapped(::Face) }.getOrNull() }

    override fun emoji() = emoji

    internal fun warmEmoji() {
        runCatching { emoji?.pair(FLAG_PROBE, FLAG_PROBE + 1) }
    }

    fun prerasterize(line: String): Boolean {
        if (PolyGlyphPage.rasterizer !== this) return false
        val parts = line.split('\t')
        if (parts.size != 4) return false
        if (parts[0].startsWith("res:") && FontManager.fontData(parts[0]) == null) {
            FontManager.loadFromResource(parts[0].substring(4), parts[0], StbFonts::class.java.classLoader)
        }
        val face = face(parts[0]) ?: return false
        PolyGlyphPage.prerasterize(face, parts[1].toInt(), parts[2].toInt(), parts[3].toInt())
        return true
    }

    override fun measureTextWidth(font: PolyFont, text: String): Float {
        val face = stbFace(font.name) ?: return 0f
        var units = 0L
        var extra = 0f
        val emoji = emoji
        var kernGlyph = 0
        var i = 0
        while (i < text.length) {
            val at = i
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (PolyGlyphPage.isIgnorable(cp)) continue
            val sequence = if (emoji != null && i < text.length) PolyGlyphPage.sequence(emoji, text, at, text.length) else 0L
            if (sequence != 0L && emoji != null) {
                extra += emoji.advance((sequence ushr 32).toInt())
                kernGlyph = 0
                i = sequence.toInt()
                continue
            }
            val id = face.glyph(cp)
            val fallback = if (id == 0 && !Character.isWhitespace(cp)) PolyGlyphPage.fallback(cp, if (i < text.length) text.codePointAt(i) else 0) else null
            val fid = fallback?.glyph(cp) ?: 0
            if (fid != 0) extra += fallback!!.advance(fid) else units += face.advanceUnits(id)
            val kernable = fid == 0 && id != 0 && PolyGlyphPage.kernable(cp)
            if (kernable && kernGlyph != 0) extra += face.kern(kernGlyph, id)
            kernGlyph = if (kernable) id else 0
        }
        return (units * face.emScale + extra) * font.size
    }

    override fun metrics(font: PolyFont) = stbFace(font.name)?.metrics(font.size) ?: PolyFont.Metrics(0f, 0f, 0f, 0f, 0f)

    private val DILATE = System.getProperty("polycompose.hud.textDilate")?.toFloat() ?: if (System.getProperty("os.name").startsWith("Mac")) 0.068f else 0f
    private const val SDF_PAD = 2
    private const val MAX_GLYPH = 0xFFFF
    private const val KERN_BITS = 12
    private const val KERN_SLOTS = 1 shl KERN_BITS
    private const val KERN_HASH = -0x61c88647
    private const val CACHE_PAGE_BITS = 8
    private const val CACHE_PAGE_MASK = (1 shl CACHE_PAGE_BITS) - 1
    private const val CACHE_PAGES = 0x110000 ushr CACHE_PAGE_BITS
    private const val FLAG_PROBE = 0x1F1E6
    private const val SDF_EDGE = 128
    private const val SDF_SCALE = 32f
    private const val FLATNESS = 0.35f
    private const val MAX_COMPONENT_DEPTH = 8
    private const val WORD_ARGS = 0x0001
    private const val XY_VALUES = 0x0002
    private const val UNIFORM_SCALE = 0x0008
    private const val MORE_COMPONENTS = 0x0020
    private const val XY_SCALE = 0x0040
    private const val TWO_BY_TWO = 0x0080
    private const val SCALED_OFFSET = 0x0800

    private fun ByteBuffer.u16(o: Int) = (get(o).toInt() and 0xFF shl 8) or (get(o + 1).toInt() and 0xFF)
    private fun ByteBuffer.s16(o: Int) = u16(o).toShort().toInt()
    private fun ByteBuffer.u32(o: Int) = (u16(o) shl 16) or u16(o + 2)

    // tables too short for the fields read from them are treated as missing
    private fun table(b: ByteBuffer, directory: Int, tag: String, minLength: Int): Int {
        if (directory < 0 || directory + 12 > b.limit()) return -1
        for (i in 0 until b.u16(directory + 4)) {
            val r = directory + 12 + i * 16
            if (r + 16 > b.limit()) return -1
            if ((0 until 4).any { (b.get(r + it).toInt() and 0xFF) != tag[it].code }) continue
            val offset = b.u32(r + 8)
            return if (offset >= 0 && b.u32(r + 12) >= minLength && offset + minLength <= b.limit()) offset else -1
        }
        return -1
    }
}
