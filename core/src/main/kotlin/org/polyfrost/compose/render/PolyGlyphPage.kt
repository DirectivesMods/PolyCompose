package org.polyfrost.compose.render

import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.annotations.ApiStatus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

public object PolyGlyphPage {
    public interface Rasterizer {
        public fun face(name: String): Face?

        public fun emoji(): Face? = null
    }

    public interface Face {
        public fun glyph(codepoint: Int): Int

        public fun pair(first: Int, second: Int): Int = 0

        public fun sequence(text: CharSequence, start: Int, end: Int): Long {
            val first = Character.codePointAt(text, start)
            val at = start + Character.charCount(first)
            if (at >= end) return 0L
            val second = Character.codePointAt(text, at)
            val glyph = pair(first, second)
            return if (glyph == 0) 0L else (glyph.toLong() shl 32) or (at + Character.charCount(second)).toLong()
        }

        public fun advance(glyph: Int): Float

        public fun raster(glyph: Int, pixelSize: Float): Raster?

        public fun raster(glyph: Int, pixelSize: Float, shiftX: Float): Raster? = raster(glyph, pixelSize)
    }

    public class Raster @JvmOverloads constructor(public val offX: Int, public val offY: Int, public val w: Int, public val h: Int, public val coverage: ByteArray, public val color: Boolean = false)

    public class Glyph @JvmOverloads constructor(public val page: Page, public val offX: Int, public val offY: Int, public val w: Int, public val h: Int, public val u0: Float, public val v0: Float, public val u1: Float, public val v1: Float, public val color: Boolean = false) {
        public fun u(t: Float): Float = u0 + (u1 - u0) * t
        public fun v(t: Float): Float = v0 + (v1 - v0) * t
    }

    public class Page internal constructor() {
        @JvmField
        public val pixels: IntArray = IntArray(SIZE * SIZE).also { px ->
            for (y in 0 until WHITE) for (x in 0 until WHITE) px[(PAD + y) * SIZE + PAD + x] = -1
        }

        public var version: Int = 1
            private set

        public var retired: Boolean = false
            internal set

        public var hasColor: Boolean = false
            private set

        private val rowVersions = IntArray(SIZE).also { it.fill(1, PAD, PAD + WHITE) }

        init {
            revision++
        }

        /** The rows written to since [version] was [since], so only those need uploading again. Empty if there are none. */
        public fun dirtyRows(since: Int): IntRange {
            var first = -1
            var last = -1
            for (y in 0 until SIZE) if (rowVersions[y] > since) {
                if (first < 0) first = y
                last = y
            }
            return if (first < 0) IntRange.EMPTY else first..last
        }

        private var penX = PAD * 2 + WHITE
        private var penY = PAD
        private var rowH = WHITE

        private fun alloc(w: Int, h: Int): Int {
            var x = penX
            var y = penY
            var row = rowH
            if (x + w + PAD > SIZE) { x = PAD; y += row + PAD; row = 0 }
            if (y + h + PAD > SIZE) return -1
            penX = x + w + PAD
            penY = y
            rowH = max(row, h)
            version++
            revision++
            rowVersions.fill(version, y, y + h)
            return (x shl 16) or y
        }

        internal fun place(r: Raster): Glyph? {
            if (r.color) return place(PolyBitmap(r.w, r.h, r.coverage), r.offX, r.offY, true)
            val at = alloc(r.w, r.h)
            if (at < 0) return null
            val x = at ushr 16
            val y = at and 0xFFFF
            for (gy in 0 until r.h) {
                val row = (y + gy) * SIZE + x
                val src = gy * r.w
                for (gx in 0 until r.w) pixels[row + gx] = ((r.coverage[src + gx].toInt() and 0xFF) shl 24) or 0xFFFFFF
            }
            return Glyph(this, r.offX, r.offY, r.w, r.h, x / SIZE.toFloat(), y / SIZE.toFloat(), (x + r.w) / SIZE.toFloat(), (y + r.h) / SIZE.toFloat())
        }

        internal fun place(b: PolyBitmap, offX: Int = 0, offY: Int = 0, color: Boolean = false): Glyph? {
            val w = b.width
            val h = b.height
            val at = alloc(w + 2, h + 2)
            if (at < 0) return null
            hasColor = true
            val x = (at ushr 16) + 1
            val y = (at and 0xFFFF) + 1
            val px = b.pixels
            for (gy in -1..h) for (gx in -1..w) {
                val o = (gy.coerceIn(0, h - 1) * w + gx.coerceIn(0, w - 1)) * 4
                pixels[(y + gy) * SIZE + x + gx] = ((px[o + 3].toInt() and 0xFF) shl 24) or ((px[o + 2].toInt() and 0xFF) shl 16) or
                    ((px[o + 1].toInt() and 0xFF) shl 8) or (px[o].toInt() and 0xFF)
            }
            return Glyph(this, offX, offY, w, h, x / SIZE.toFloat(), y / SIZE.toFloat(), (x + w) / SIZE.toFloat(), (y + h) / SIZE.toFloat(), color)
        }
    }

    public class Atlas internal constructor(internal val face: Face, internal val bucket: Int, internal val lum: Int, internal val outline: Int) {
        private var keys = IntArray(INITIAL_SLOTS).also { it.fill(-1) }
        private var values = arrayOfNulls<Any>(INITIAL_SLOTS)
        private var size = 0

        internal var placed: Int = 0
            private set

        internal fun matches(f: Face, b: Int, l: Int, o: Int): Boolean = bucket == b && lum == l && outline == o && face == f

        internal fun find(slot: Int): Int {
            val mask = keys.size - 1
            var i = mix(slot) and mask
            while (true) {
                val k = keys[i]
                if (k == slot) return i
                if (k < 0) return -1
                i = (i + 1) and mask
            }
        }

        internal fun glyphAt(i: Int): Glyph? = values[i] as? Glyph

        internal fun put(slot: Int, g: Glyph?) {
            if ((size + 1) * 4 > keys.size * 3) rebuild(keys.size * 2, null)
            if (insert(slot, g ?: NONE) && g != null) placed++
        }

        private fun insert(slot: Int, v: Any): Boolean {
            val mask = keys.size - 1
            var i = mix(slot) and mask
            while (keys[i] >= 0 && keys[i] != slot) i = (i + 1) and mask
            val fresh = keys[i] < 0
            if (fresh) size++
            keys[i] = slot
            values[i] = v
            return fresh
        }

        internal fun drop(page: Page) {
            var hit = false
            for (v in values) if (v is Glyph && v.page === page) { hit = true; break }
            if (hit) rebuild(keys.size, page)
        }

        private fun rebuild(capacity: Int, without: Page?) {
            val oldKeys = keys
            val oldValues = values
            keys = IntArray(capacity).also { it.fill(-1) }
            values = arrayOfNulls(capacity)
            size = 0
            placed = 0
            for (i in oldKeys.indices) {
                val k = oldKeys[i]
                if (k < 0) continue
                val v = oldValues[i] ?: continue
                if (v is Glyph && v.page === without) continue
                insert(k, v)
                if (v is Glyph) placed++
            }
        }

        private fun mix(slot: Int): Int {
            val h = slot * -0x61c88647
            return h xor (h ushr 16)
        }
    }

    public const val SIZE: Int = 1024
    private const val PAD = 1
    private const val WHITE = 2
    private const val INITIAL_SLOTS = 32
    private const val MAX_PAGES = 4
    private const val MAX_RASTERS = 512
    private const val RECENT_ATLASES = 4

    private const val MOTION_GAP_NANOS = 8_000_000L
    private const val MOTION_WINDOW_NANOS = 300_000_000L
    private const val MOTION_HOLD_NANOS = 400_000_000L
    private const val MOTION_STEPS = 4

    public const val WHITE_UV: Float = (PAD + WHITE / 2f) / SIZE

    @JvmStatic
    public var rasterizer: Rasterizer? = null

    private val NONE = Any()

    private val live = ArrayList<Page>(MAX_PAGES)

    @JvmStatic
    public val pages: List<Page>
        get() {
            if (live.isEmpty()) live += Page()
            return live
        }

    @JvmStatic
    public var revision: Long = 0L
        private set

    private val atlases = HashMap<Face, ArrayList<Atlas>>()
    private val recent = arrayOfNulls<Atlas>(RECENT_ATLASES)
    private var recentNext = 0

    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun atlas(face: Face, bucket: Int, lum: Int = -1): HashMap<Int, Glyph?> = HashMap()

    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun atlas(face: Face, bucket: Int, outlinePx: Float, round: Boolean, lum: Int = -1): HashMap<Int, Glyph?> = HashMap()

    @JvmName("glyphAtlas")
    public fun atlas(face: Face, bucket: Int, lum: Int = -1): Atlas = find(face, bucket, lum, 0)

    @JvmName("glyphAtlas")
    public fun atlas(face: Face, bucket: Int, outlinePx: Float, round: Boolean, lum: Int = -1): Atlas {
        if (outlinePx <= 0f) return find(face, bucket, lum, 0)
        val q = max(1, (outlinePx * 4f).roundToInt())
        return find(face, bucket, -1, if (round) q else -q)
    }

    private fun find(face: Face, bucket: Int, lum: Int, outline: Int): Atlas {
        applyInvalidations()
        for (a in recent) if (a != null && a.matches(face, bucket, lum, outline)) return a
        val list = atlases.getOrPut(face) { ArrayList(4) }
        var hit: Atlas? = null
        for (a in list) if (a.matches(face, bucket, lum, outline)) { hit = a; break }
        if (hit == null) {
            hit = Atlas(face, bucket, lum, outline)
            if (outline == 0 && list.none { it.bucket == bucket && it.outline == 0 }) moved(face, bucket)
            list += hit
        }
        recent[recentNext] = hit
        recentNext = (recentNext + 1) % RECENT_ATLASES
        return hit
    }

    private fun exists(face: Face, bucket: Int, lum: Int): Boolean {
        for (a in recent) if (a != null && a.matches(face, bucket, lum, 0)) return true
        return atlases[face]?.any { it.matches(face, bucket, lum, 0) } == true
    }

    private class Motion {
        var bucket = -1
        var at = 0L
        var direction = 0
        var steps = 0
        var until = 0L
    }

    private val motion = HashMap<Face, Motion>()

    private fun moved(face: Face, bucket: Int) {
        val m = motion.getOrPut(face) { Motion() }
        val now = System.nanoTime()
        val dt = now - m.at
        if (m.bucket >= 0 && dt < MOTION_GAP_NANOS) return
        val d = Integer.signum(bucket - m.bucket)
        when {
            m.bucket < 0 || dt > MOTION_WINDOW_NANOS -> { m.steps = 1; m.direction = 0 }
            d == 0 -> return
            m.direction == 0 || d == m.direction -> { m.steps++; m.direction = d }
            else -> { m.steps = 2; m.direction = d }
        }
        m.bucket = bucket
        m.at = now
        if (m.steps >= MOTION_STEPS) m.until = now + MOTION_HOLD_NANOS
    }

    internal fun animatedBucket(face: Face, bucket: Int, lum: Int): Int {
        if ((bucket and (SUBPIXELS - 1)) == 0) return bucket
        val m = motion[face] ?: return bucket
        if (m.until == 0L || System.nanoTime() > m.until || exists(face, bucket, lum)) return bucket
        return max(SUBPIXELS, (bucket + SUBPIXELS / 2) / SUBPIXELS * SUBPIXELS)
    }

    public const val SUBPIXELS: Int = 4

    public const val EMOJI_VARIATION: Int = 0xFE0F

    private val REGIONAL = 0x1F1E6..0x1F1FF

    private data class Prekey(val face: Face, val bucket: Int, val slot: Int)

    // Stands in for glyphs without a bitmap, such as spaces, so they're only rasterized once
    private val BLANK = Raster(0, 0, 0, 0, ByteArray(0))

    private val prerastered = ConcurrentHashMap<Prekey, Raster>()
    private val rasters = object : LinkedHashMap<Prekey, Raster>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Prekey, Raster>?) = size > MAX_RASTERS
    }

    @JvmStatic
    public var recorder: ((face: Face, bucket: Int, id: Int, phase: Int) -> Unit)? = null

    @JvmStatic
    public fun isIgnorable(cp: Int): Boolean = cp == 0x200D || cp in 0xFE00..EMOJI_VARIATION || cp in 0xE0001..0xE007F

    private fun joins(cp: Int): Boolean =
        cp == 0x200D || cp == EMOJI_VARIATION || cp == 0x20E3 || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F || cp in REGIONAL

    @JvmStatic
    public fun sequence(face: Face, text: CharSequence, start: Int, end: Int): Long {
        if (start >= end) return 0L
        val at = start + Character.charCount(Character.codePointAt(text, start))
        if (at >= end || !joins(Character.codePointAt(text, at))) return 0L
        val hit = face.sequence(text, start, end)
        return if (hit.toInt() in at + 1..end) hit else 0L
    }

    @JvmStatic
    public fun fallback(cp: Int, next: Int): Face? {
        val r = rasterizer ?: return null
        if (Character.isEmojiPresentation(cp) || (next == EMOJI_VARIATION && Character.isEmoji(cp))) {
            r.emoji()?.let { if (it.glyph(cp) != 0) return it }
        }
        return r.face(PolyTessellator.FALLBACK_FONT)
    }

    @JvmStatic
    public fun isPair(cp: Int, next: Int): Boolean = cp in REGIONAL && next in REGIONAL && fallback(cp, next)?.pair(cp, next).let { it != null && it != 0 }

    private fun raster(face: Face, bucket: Int, id: Int, phase: Int): Raster? =
        if (phase == 0) face.raster(id, bucket / 4f) else face.raster(id, bucket / 4f, phase / SUBPIXELS.toFloat())

    @Volatile
    private var prerasterDropped = false

    @JvmStatic
    public fun dropPrerastered(): Int {
        prerasterDropped = true
        val n = prerastered.size
        prerastered.clear()
        return n
    }

    @JvmStatic
    @ApiStatus.Internal
    public fun invalidate(face: Face) {
        invalidated.add(face)
        invalidatedPending = true
    }

    private val invalidated = ConcurrentHashMap.newKeySet<Face>()

    @Volatile
    private var invalidatedPending = false

    private fun applyInvalidations() {
        if (!invalidatedPending) return
        invalidatedPending = false
        val faces = invalidated.iterator()
        while (faces.hasNext()) {
            val face = faces.next()
            faces.remove()
            atlases.remove(face)
            recent.fill(null)
            synchronized(rasters) { rasters.keys.removeAll { it.face === face } }
            prerastered.keys.removeAll { it.face === face }
            revision++
        }
    }

    @JvmStatic
    public fun prerasterize(face: Face, bucket: Int, id: Int, phase: Int) {
        if (prerasterDropped) return
        val key = Prekey(face, bucket, id * SUBPIXELS + phase)
        if (prerastered.containsKey(key)) return
        prerastered.putIfAbsent(key, raster(face, bucket, id, phase) ?: BLANK)
        if (prerasterDropped) prerastered.remove(key)
    }

    @Deprecated("Binary compatibility", level = DeprecationLevel.HIDDEN)
    public fun glyph(atlas: HashMap<Int, Glyph?>, face: Face, bucket: Int, id: Int, outlinePx: Float = 0f, round: Boolean = false, phase: Int = 0, lum: Int = -1): Glyph? =
        glyph(atlas(face, bucket, outlinePx, round, lum), face, bucket, id, outlinePx, round, phase, lum)

    public fun glyph(atlas: Atlas, face: Face, bucket: Int, id: Int, outlinePx: Float = 0f, round: Boolean = false, phase: Int = 0, lum: Int = -1): Glyph? {
        applyInvalidations()
        val slot = id * SUBPIXELS + phase
        val i = atlas.find(slot)
        if (i >= 0) return atlas.glyphAt(i)
        return newGlyph(face, bucket, id, outlinePx, round, phase, lum).also { atlas.put(slot, it) }
    }

    private fun newGlyph(face: Face, bucket: Int, id: Int, outlinePx: Float, round: Boolean, phase: Int, lum: Int): Glyph? {
        if (outlinePx > 0f) {
            val r = raster(face, bucket, id, phase) ?: return null
            return if (r.color) null else place(dilate(r, max(1, (outlinePx * 4f).roundToInt()) / 4f, round))
        }
        val key = Prekey(face, bucket, id * SUBPIXELS + phase)
        val r = rasters.getOrPut(key) {
            recorder?.invoke(face, bucket, id, phase)
            prerastered.remove(key) ?: raster(face, bucket, id, phase) ?: BLANK
        }
        if (r === BLANK) return null
        return place(if (lum < 0 || r.color) r else PolyMaskGamma.apply(r, lum))
    }

    private fun dilate(r: Raster, radius: Float, round: Boolean): Raster {
        val e = ceil(radius).toInt()
        val w = r.w + e * 2
        val h = r.h + e * 2
        val weights = FloatArray((e * 2 + 1) * (e * 2 + 1)) { k ->
            val dx = abs(k % (e * 2 + 1) - e).toFloat()
            val dy = abs(k / (e * 2 + 1) - e).toFloat()
            (radius + 1f - if (round) sqrt(dx * dx + dy * dy) else max(dx, dy)).coerceIn(0f, 1f)
        }
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0f
            for (ky in 0..e * 2) {
                val sy = y - ky
                if (sy !in 0 until r.h) continue
                for (kx in 0..e * 2) {
                    val sx = x - kx
                    if (sx !in 0 until r.w) continue
                    val v = (r.coverage[sy * r.w + sx].toInt() and 0xFF) * weights[ky * (e * 2 + 1) + kx]
                    if (v > a) a = v
                }
            }
            out[y * w + x] = a.roundToInt().toByte()
        }
        return Raster(r.offX - e, r.offY - e, w, h, out)
    }

    private const val BITMAP_MAX = 256
    private const val BITMAP_AREA = SIZE * SIZE * 2

    private val bitmaps = WeakHashMap<PolyBitmap, Glyph>()
    private var bitmapArea = 0
    private var placedSince = 0L
    private var placed = 0

    @JvmStatic
    public fun bitmap(b: PolyBitmap): Glyph? {
        bitmaps[b]?.let { return it }
        if (b.width > BITMAP_MAX || b.height > BITMAP_MAX) return null
        val now = System.nanoTime()
        if (now - placedSince > 1_000_000_000L) { placedSince = now; placed = 0 }
        if (++placed > 128) return null
        val area = (b.width + 2) * (b.height + 2)
        if (bitmapArea + area > BITMAP_AREA) {
            bitmapArea = bitmapArea()
            if (bitmapArea + area > BITMAP_AREA) return null
        }
        val g = current().place(b) ?: newPage().place(b) ?: return null
        bitmapArea += area
        bitmaps[b] = g
        return g
    }

    private fun bitmapArea(): Int {
        var sum = 0
        for (g in bitmaps.values) sum += (g.w + 2) * (g.h + 2)
        return sum
    }

    private fun place(r: Raster): Glyph? {
        val edge = if (r.color) PAD + 1 else PAD
        if (r.w <= 0 || r.h <= 0 || r.w + edge * 2 > SIZE || r.h + edge * 2 > SIZE) return null
        return current().place(r) ?: newPage().place(r)
    }

    private fun current(): Page = pages.last()

    private fun newPage(): Page {
        if (live.size >= MAX_PAGES) retire(live.removeAt(0))
        return Page().also { live += it }
    }

    private fun retire(page: Page) {
        page.retired = true
        revision++
        recent.fill(null)
        val lists = atlases.values.iterator()
        while (lists.hasNext()) {
            val list = lists.next()
            val it = list.iterator()
            while (it.hasNext()) {
                val a = it.next()
                a.drop(page)
                if (a.placed == 0) it.remove()
            }
            if (list.isEmpty()) lists.remove()
        }
        val placements = bitmaps.values.iterator()
        while (placements.hasNext()) if (placements.next().page === page) placements.remove()
        bitmapArea = bitmapArea()
    }
}
