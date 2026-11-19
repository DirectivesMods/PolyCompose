package org.polyfrost.compose.render

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

public class EmojiFace private constructor(
    private val font: ByteBuffer,
    outlines: ((ByteArray) -> PolyGlyphPage.Face)?,
    mappedOutlines: ((ByteBuffer) -> PolyGlyphPage.Face)?,
) : PolyGlyphPage.Face {
    public constructor(font: ByteBuffer, outlines: ((ByteArray) -> PolyGlyphPage.Face)? = null) : this(font, outlines, null)

    private val tables = HashMap<String, Int>()
    private val unitsPerEm: Int
    private val metricCount: Int
    private val glyphCount: Int
    private val cmap: Int
    private val layers: PolyGlyphPage.Face?

    init {
        val base = if (tag(0) == "ttcf") u32(12) else 0
        for (i in 0 until u16(base + 4)) {
            val r = base + 12 + i * 16
            tables[tag(r)] = u32(r + 8)
            tables[tag(r) + ".length"] = u32(r + 12)
        }
        unitsPerEm = u16(table("head") + 18)
        metricCount = u16(table("hhea") + 34)
        glyphCount = u16(table("maxp") + 4)
        val map = table("cmap")
        cmap = (0 until u16(map + 2)).map { map + u32(map + 8 + it * 8) }.firstOrNull { u16(it) == 12 } ?: error("no format 12 cmap")
        layers = when {
            "COLR" !in tables -> null
            mappedOutlines != null && font.isDirect -> mappedOutlines(font.duplicate())
            outlines != null -> outlines(ByteArray(font.capacity()).also { font.get(0, it) })
            else -> null
        }
        require("sbix" in tables || "CBDT" in tables || layers != null) { "no supported colour table" }
    }

    override fun glyph(codepoint: Int): Int {
        if (purge) {
            purge = false
            invalidator?.invoke(this)
        }
        var lo = 0
        var hi = u32(cmap + 12) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val group = cmap + 16 + mid * 12
            when {
                codepoint < u32(group) -> hi = mid - 1
                codepoint > u32(group + 4) -> lo = mid + 1
                else -> return u32(group + 8) + codepoint - u32(group)
            }
        }
        return 0
    }

    private val sequences by lazy { runCatching { buildSequences() }.getOrDefault(Sequences.EMPTY) }

    override fun pair(first: Int, second: Int): Int = sequences.pair(first, second)

    override fun sequence(text: CharSequence, start: Int, end: Int): Long = sequences.match(text, start, end)

    private fun buildSequences(): Sequences {
        val builder = SequenceBuilder()
        postSequences(builder)
        gsubSequences(builder)
        return builder.build()
    }

    private fun flagOf(code: String) = intArrayOf(REGIONAL.first + code[0].code - 'A'.code, REGIONAL.first + code[1].code - 'A'.code)

    private fun postSequences(builder: SequenceBuilder) {
        val post = tables["post"] ?: return
        if (u32(post) != 0x00020000) return
        val count = u16(post + 32)
        val names = ArrayList<String>()
        var at = post + 34 + count * 2
        val end = post + tables.getValue("post.length")
        while (at < end) {
            val length = u8(at)
            names += String(CharArray(length) { u8(at + 1 + it).toChar() })
            at += length + 1
        }
        for (id in 0 until count) {
            val name = names.getOrNull(u16(post + 34 + id * 2) - STANDARD_MAC_NAMES) ?: continue
            if (!SEQUENCE_NAME.matches(name)) continue
            builder.put(name.split('_').map { it.removePrefix("u").toInt(16) }.toIntArray(), id)
        }
        for ((region, flag) in FLAG_ALIASES) {
            val glyph = builder.get(flagOf(flag))
            if (glyph != 0) builder.put(flagOf(region), glyph)
        }
    }

    private fun reverseCmap(): HashMap<Int, IntArray> {
        val out = HashMap<Int, IntArray>()
        for (i in 0 until u32(cmap + 12)) {
            val group = cmap + 16 + i * 12
            val first = u32(group)
            val glyph = u32(group + 8)
            for (cp in first..u32(group + 4)) {
                val id = glyph + cp - first
                val known = out[id]
                if (known == null || (!Character.isEmoji(known[0]) && Character.isEmoji(cp))) out[id] = intArrayOf(cp)
            }
        }
        return out
    }

    private fun featureLookups(gsub: Int): Set<Int>? {
        if (u16(gsub + 6) == 0) return null
        val list = gsub + u16(gsub + 6)
        val out = HashSet<Int>()
        for (i in 0 until u16(list)) {
            val feature = list + u16(list + 2 + i * 6 + 4)
            for (j in 0 until u16(feature + 2)) out += u16(feature + 4 + j * 2)
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun gsubSequences(builder: SequenceBuilder) {
        val gsub = tables["GSUB"] ?: return
        val used = featureLookups(gsub)
        val codepoints = reverseCmap()
        val list = gsub + u16(gsub + 8)
        for (i in 0 until u16(list)) {
            if (used != null && i !in used) continue
            val lookup = list + u16(list + 2 + i * 2)
            for (j in 0 until u16(lookup + 4)) {
                var sub = lookup + u16(lookup + 6 + j * 2)
                var type = u16(lookup)
                if (type == EXTENSION) {
                    type = u16(sub + 2)
                    sub += u32(sub + 4)
                }
                if (type != LIGATURE || u16(sub) != 1) continue
                coverage(sub + u16(sub + 2)).forEachIndexed { index, glyph ->
                    val first = codepoints[glyph] ?: return@forEachIndexed
                    val set = sub + u16(sub + 6 + index * 2)
                    for (k in 0 until u16(set)) {
                        val ligature = set + u16(set + 2 + k * 2)
                        var cps: IntArray? = first
                        for (c in 1 until u16(ligature + 2)) {
                            val part = codepoints[u16(ligature + 2 + c * 2)]
                            cps = if (part == null) null else cps?.plus(part)
                        }
                        val output = u16(ligature)
                        if (cps == null || !builder.put(cps, output)) continue
                        codepoints.putIfAbsent(output, cps)
                    }
                }
            }
        }
    }

    private fun coverage(at: Int): List<Int> =
        if (u16(at) == 1) List(u16(at + 2)) { u16(at + 4 + it * 2) }
        else (0 until u16(at + 2)).flatMap { (u16(at + 4 + it * 6)..u16(at + 6 + it * 6)).toList() }

    override fun advance(glyph: Int): Float = u16(table("hmtx") + min(glyph, metricCount - 1) * 4) / unitsPerEm.toFloat()

    override fun raster(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? = runCatching {
        when {
            "sbix" in tables -> sbix(glyph, pixelSize)
            "CBDT" in tables -> cbdt(glyph, pixelSize)
            else -> colr(glyph, pixelSize)
        }
    }.getOrNull()

    private val sbixStrikes by lazy {
        val sbix = table("sbix")
        (0 until u32(sbix + 4)).map { sbix + u32(sbix + 8 + it * 4) }.sortedBy { u16(it) }
    }

    private val cblcSizes by lazy {
        val cblc = table("CBLC")
        (0 until u32(cblc + 4)).map { cblc + 8 + it * 48 }.sortedBy { u8(it + 45) }
    }

    private val decoded = object : LinkedHashMap<Int, PolyBitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, PolyBitmap>?) = size > MAX_DECODED
    }

    private fun sbix(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? {
        val strikes = sbixStrikes
        val strike = strikes.firstOrNull { u16(it) >= pixelSize } ?: strikes.last()
        var id = glyph
        repeat(2) {
            val start = u32(strike + 4 + id * 4)
            val size = u32(strike + 8 + id * 4) - start - 8
            if (size <= 0 || id >= glyphCount) return null
            val at = strike + start
            when (tag(at + 4)) {
                "dupe" -> id = u16(at + 8)
                "png " -> return image(at + 8, size, pixelSize / u16(strike), s16(at), s16(at + 2), bottom = true)
                else -> return null
            }
        }
        return null
    }

    private fun cbdt(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? {
        val cblc = table("CBLC")
        val sizes = cblcSizes
        val size = sizes.firstOrNull { u8(it + 45) >= pixelSize } ?: sizes.last()
        val array = cblc + u32(size)
        for (i in 0 until u32(size + 8)) {
            val entry = array + i * 8
            if (glyph !in u16(entry)..u16(entry + 2)) continue
            val sub = array + u32(entry + 4)
            if (u16(sub) != 1 || u16(sub + 2) != 17) return null
            val offsets = sub + 8 + (glyph - u16(entry)) * 4
            if (u32(offsets) == u32(offsets + 4)) return null
            val at = table("CBDT") + u32(sub + 4) + u32(offsets)
            return image(at + 9, u32(at + 5), pixelSize / u8(size + 45), font.get(at + 2).toInt(), font.get(at + 3).toInt(), bottom = false)
        }
        return null
    }

    private data class Key(val at: Int, val scale: Int)

    private val ready = object : LinkedHashMap<Key, PolyGlyphPage.Raster>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PolyGlyphPage.Raster>?) = size > MAX_READY
    }

    private val pending = HashSet<Key>()

    private var async = false

    @Volatile
    private var purge = false

    private fun image(at: Int, size: Int, scale: Float, x: Int, y: Int, bottom: Boolean): PolyGlyphPage.Raster? {
        if (!async || invalidator == null) return render(at, size, scale, x, y, bottom)
        val key = Key(at, scale.toBits())
        val done = synchronized(ready) { ready[key] }
        if (done != null) return if (done === FAILED) null else done
        val placeholder = placeholder(at, scale, x, y, bottom) ?: return render(at, size, scale, x, y, bottom)
        val submit = synchronized(pending) {
            when {
                key in pending -> false
                pending.size >= MAX_PENDING -> return render(at, size, scale, x, y, bottom)
                else -> pending.add(key)
            }
        }
        if (submit) {
            try {
                EXECUTOR.execute { load(key, size, scale, x, y, bottom) }
            } catch (_: RejectedExecutionException) {
                synchronized(pending) { pending.remove(key) }
                return render(at, size, scale, x, y, bottom)
            }
        }
        return placeholder
    }

    private fun load(key: Key, size: Int, scale: Float, x: Int, y: Int, bottom: Boolean) {
        val raster = runCatching { render(key.at, size, scale, x, y, bottom) }.getOrNull()
        synchronized(ready) { ready[key] = raster ?: FAILED }
        synchronized(pending) { pending.remove(key) }
        if (!QUEUE.isEmpty()) {
            purge = true
            return
        }
        purge = false
        invalidator?.invoke(this)
        FontManager.invalidateMetrics()
    }

    private fun placeholder(at: Int, scale: Float, x: Int, y: Int, bottom: Boolean): PolyGlyphPage.Raster? {
        if (u32(at) != PNG_MAGIC) return null
        val iw = u32(at + 16)
        val ih = u32(at + 20)
        if (iw <= 0 || ih <= 0) return null
        val w = max(1, (iw * scale).roundToInt())
        val h = max(1, (ih * scale).roundToInt())
        return PolyGlyphPage.Raster((x * scale).roundToInt(), -((y + if (bottom) ih else 0) * scale).roundToInt(), w, h, ByteArray(w * h * 4), true)
    }

    private fun render(at: Int, size: Int, scale: Float, x: Int, y: Int, bottom: Boolean): PolyGlyphPage.Raster? {
        val bitmap = synchronized(decoded) { decoded[at] } ?: (PolyBitmap.decode(ByteArray(size).also { font.get(at, it) }) ?: return null)
            .also { synchronized(decoded) { decoded[at] = it } }
        val w = max(1, (bitmap.width * scale).roundToInt())
        val h = max(1, (bitmap.height * scale).roundToInt())
        return PolyGlyphPage.Raster((x * scale).roundToInt(), -((y + if (bottom) bitmap.height else 0) * scale).roundToInt(), w, h, scaled(bitmap, w, h), true)
    }

    private fun colr(glyph: Int, pixelSize: Float): PolyGlyphPage.Raster? {
        val outlines = layers ?: return null
        val colr = table("COLR")
        val records = colr + u32(colr + 4)
        var lo = 0
        var hi = u16(colr + 2) - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val id = u16(records + mid * 6)
            if (id < glyph) { lo = mid + 1; continue }
            if (id > glyph) { hi = mid - 1; continue }
            val first = colr + u32(colr + 8) + u16(records + mid * 6 + 2) * 4
            val parts = (0 until u16(records + mid * 6 + 4)).mapNotNull { i ->
                outlines.raster(u16(first + i * 4), pixelSize)?.let { it to u16(first + i * 4 + 2) }
            }
            return composite(parts)
        }
        return null
    }

    private fun composite(parts: List<Pair<PolyGlyphPage.Raster, Int>>): PolyGlyphPage.Raster? {
        if (parts.isEmpty()) return null
        val x0 = parts.minOf { it.first.offX }
        val y0 = parts.minOf { it.first.offY }
        val w = parts.maxOf { it.first.offX + it.first.w } - x0
        val h = parts.maxOf { it.first.offY + it.first.h } - y0
        val out = FloatArray(w * h * 4)
        val cpal = table("CPAL")
        val colors = cpal + u32(cpal + 8) + u16(cpal + 12) * 4
        for ((r, palette) in parts) {
            val c = colors + palette * 4
            val red = if (palette == FOREGROUND) 1f else u8(c + 2) / 255f
            val green = if (palette == FOREGROUND) 1f else u8(c + 1) / 255f
            val blue = if (palette == FOREGROUND) 1f else u8(c) / 255f
            val alpha = if (palette == FOREGROUND) 1f else u8(c + 3) / 255f
            for (y in 0 until r.h) for (x in 0 until r.w) {
                val a = (r.coverage[y * r.w + x].toInt() and 0xFF) / 255f * alpha
                val o = ((y + r.offY - y0) * w + x + r.offX - x0) * 4
                out[o] = red * a + out[o] * (1f - a)
                out[o + 1] = green * a + out[o + 1] * (1f - a)
                out[o + 2] = blue * a + out[o + 2] * (1f - a)
                out[o + 3] = a + out[o + 3] * (1f - a)
            }
        }
        return PolyGlyphPage.Raster(x0, y0, w, h, straight(out), true)
    }

    private class SequenceBuilder {
        private val children = HashMap<Long, Int>()
        private val glyphs = ArrayList<Int>().also { it += 0 }

        fun get(cps: IntArray): Int {
            var node = 0
            for (cp in cps) {
                if (Sequences.isVariation(cp)) continue
                node = children[Sequences.key(node, cp)] ?: return 0
            }
            return glyphs[node]
        }

        fun put(cps: IntArray, glyph: Int): Boolean {
            if (glyph == 0 || cps.count { !Sequences.isVariation(it) } < 2) return false
            var node = 0
            for (cp in cps) {
                if (Sequences.isVariation(cp)) continue
                node = children.getOrPut(Sequences.key(node, cp)) { glyphs.size.also { glyphs += 0 } }
            }
            glyphs[node] = glyph
            return true
        }

        fun build(): Sequences {
            var capacity = 2
            while (capacity < children.size * 2) capacity *= 2
            val keys = LongArray(capacity) { -1L }
            val values = IntArray(capacity)
            for ((key, child) in children) {
                var i = Sequences.slot(key, capacity - 1)
                while (keys[i] >= 0) i = (i + 1) and (capacity - 1)
                keys[i] = key
                values[i] = child
            }
            return Sequences(keys, values, glyphs.toIntArray())
        }
    }

    private class Sequences(private val keys: LongArray, private val children: IntArray, private val glyphs: IntArray) {
        private fun child(node: Int, cp: Int): Int {
            val key = key(node, cp)
            val mask = keys.size - 1
            var i = slot(key, mask)
            while (true) {
                val k = keys[i]
                if (k == key) return children[i]
                if (k < 0) return -1
                i = (i + 1) and mask
            }
        }

        fun pair(first: Int, second: Int): Int {
            val a = child(0, first)
            val b = if (a < 0) -1 else child(a, second)
            return if (b < 0) 0 else glyphs[b]
        }

        fun match(text: CharSequence, start: Int, end: Int): Long {
            var node = 0
            var i = start
            var best = 0L
            while (i < end) {
                val cp = Character.codePointAt(text, i)
                val n = Character.charCount(cp)
                if (node != 0 && isVariation(cp)) {
                    i += n
                    continue
                }
                node = child(node, cp)
                if (node < 0) break
                i += n
                val glyph = glyphs[node]
                if (glyph != 0) best = (glyph.toLong() shl 32) or i.toLong()
            }
            return best
        }

        companion object {
            private const val CP_BITS = 21
            val EMPTY = Sequences(LongArray(1) { -1L }, IntArray(1), IntArray(1))

            fun isVariation(cp: Int) = cp == 0xFE0E || cp == PolyGlyphPage.EMOJI_VARIATION

            fun key(node: Int, cp: Int) = (node.toLong() shl CP_BITS) or cp.toLong()

            fun slot(key: Long, mask: Int) = ((key * -0x61c8864680b583ebL) ushr 32).toInt() and mask
        }
    }

    private fun table(name: String) = tables[name] ?: error("no $name table")
    private fun tag(o: Int) = String(CharArray(4) { (font.get(o + it).toInt() and 0xFF).toChar() })
    private fun u8(o: Int) = font.u8(o)
    private fun u16(o: Int) = font.u16(o)
    private fun s16(o: Int) = font.s16(o)
    private fun u32(o: Int) = font.u32(o)

    public companion object {
        private const val FOREGROUND = 0xFFFF
        private const val LIGATURE = 4
        private const val EXTENSION = 7
        private const val STANDARD_MAC_NAMES = 258
        private const val MAX_DECODED = 8
        private const val PNG_MAGIC = 0x89504E47.toInt()
        private const val MAX_READY = 128
        private const val MAX_PENDING = 64
        private val FAILED = PolyGlyphPage.Raster(0, 0, 0, 0, ByteArray(0))
        private val QUEUE = LinkedBlockingQueue<Runnable>(MAX_PENDING)
        private val EXECUTOR by lazy {
            ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, QUEUE) { r ->
                Thread(r, "PolyCompose Emoji").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }

        @Volatile
        @JvmStatic
        internal var invalidator: ((PolyGlyphPage.Face) -> Unit)? = PolyGlyphPage::invalidate
        private val REGIONAL = 0x1F1E6..0x1F1FF
        private val FLAG_ALIASES = mapOf("BV" to "NO", "SJ" to "NO", "HM" to "AU", "MF" to "FR", "UM" to "US")
        private val SEQUENCE_NAME = Regex("u[0-9A-F]{4,6}(?:_u?[0-9A-F]{4,6})+")

        internal fun scaled(b: PolyBitmap, w: Int, h: Int): ByteArray {
            val out = FloatArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val sx0 = x * b.width / w
                val sy0 = y * b.height / h
                val sx1 = max((x + 1) * b.width / w, sx0 + 1)
                val sy1 = max((y + 1) * b.height / h, sy0 + 1)
                val o = (y * w + x) * 4
                for (sy in sy0 until sy1) for (sx in sx0 until sx1) {
                    val i = (sy * b.width + sx) * 4
                    val a = (b.pixels[i + 3].toInt() and 0xFF) / 255f
                    for (c in 0..2) out[o + c] += (b.pixels[i + c].toInt() and 0xFF) / 255f * a
                    out[o + 3] += a
                }
                val n = ((sx1 - sx0) * (sy1 - sy0)).toFloat()
                for (c in 0..3) out[o + c] /= n
            }
            return straight(out)
        }

        private fun straight(premultiplied: FloatArray) = ByteArray(premultiplied.size) { i ->
            val a = premultiplied[i or 3]
            val v = if (i and 3 == 3) a else if (a > 0f) premultiplied[i] / a else 0f
            (v.coerceIn(0f, 1f) * 255f).roundToInt().toByte()
        }

        private fun systemFont(): File? {
            val os = System.getProperty("os.name")
            return when {
                os.startsWith("Mac") -> File("/System/Library/Fonts/Apple Color Emoji.ttc")
                os.startsWith("Windows") -> File(System.getenv("WINDIR") ?: "C:\\Windows", "Fonts\\seguiemj.ttf")
                else -> File("/usr/share/fonts").walkTopDown().maxDepth(4).firstOrNull { it.name == "NotoColorEmoji.ttf" }
            }?.takeIf { it.isFile }
        }

        @JvmStatic
        public fun system(outlines: (ByteArray) -> PolyGlyphPage.Face): EmojiFace? {
            val file = systemFont() ?: return null
            val mapped = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            return EmojiFace(mapped, outlines).also { it.async = true }
        }

        @JvmStatic
        public fun systemMapped(outlines: (ByteBuffer) -> PolyGlyphPage.Face): EmojiFace? {
            val file = systemFont() ?: return null
            val mapped = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
            return EmojiFace(mapped, null, outlines).also { it.async = true }
        }
    }
}
