package org.polyfrost.compose.render

import java.nio.IntBuffer
import java.util.WeakHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import org.jetbrains.annotations.ApiStatus
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
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

        public fun kern(left: Int, right: Int): Float = 0f

        public fun raster(glyph: Int, pixelSize: Float): Raster?

        public fun raster(glyph: Int, pixelSize: Float, shiftX: Float): Raster? = raster(glyph, pixelSize)
    }

    public class Raster @JvmOverloads constructor(public val offX: Int, public val offY: Int, public val w: Int, public val h: Int, public val coverage: ByteArray, public val color: Boolean = false)

    public class Glyph @JvmOverloads constructor(public val page: Page, public val offX: Int, public val offY: Int, public val w: Int, public val h: Int, public val u0: Float, public val v0: Float, public val u1: Float, public val v1: Float, public val color: Boolean = false) {
        public fun u(t: Float): Float = u0 + (u1 - u0) * t
        public fun v(t: Float): Float = v0 + (v1 - v0) * t
    }

    public class Page internal constructor(private val shared: Boolean = true) {
        private val full: IntArray? = if (shared) null else IntArray(SIZE * SIZE)
        private val alpha = arrayOfNulls<ByteArray>(if (shared) STRIPS else 0)
        private val inked = arrayOfNulls<LongArray>(if (shared) STRIPS else 0)
        private val argb = arrayOfNulls<IntArray>(if (shared) STRIPS else 0)

        public val pixels: IntArray
            get() = full ?: IntArray(SIZE * SIZE).also { out -> for (y in 0 until SIZE) readRow(y, out, y * SIZE) }

        @ApiStatus.Internal
        public fun readRow(y: Int, out: IntArray, offset: Int) {
            val full = full
            if (full != null) {
                full.copyInto(out, offset, y * SIZE, y * SIZE + SIZE)
                return
            }
            val s = y / STRIP_ROWS
            val at = (y - s * STRIP_ROWS) * SIZE
            val ints = argb[s]
            if (ints != null) {
                ints.copyInto(out, offset, at, at + SIZE)
                return
            }
            val bytes = alpha[s]
            val ink = inked[s]
            if (bytes == null || ink == null) {
                out.fill(0, offset, offset + SIZE)
                return
            }
            for (x in 0 until SIZE) {
                val i = at + x
                out[offset + x] = if ((ink[i ushr 6] and (1L shl i)) != 0L) ((bytes[i].toInt() and 0xFF) shl 24) or 0xFFFFFF else 0
            }
        }

        @ApiStatus.Internal
        public fun readRows(first: Int, count: Int, out: IntBuffer) {
            val full = full
            if (full != null) {
                out.put(full, first * SIZE, count * SIZE)
                return
            }
            var row: IntArray? = null
            for (y in first until first + count) {
                val s = y / STRIP_ROWS
                val ints = argb[s]
                if (ints != null) {
                    out.put(ints, (y - s * STRIP_ROWS) * SIZE, SIZE)
                } else {
                    var buf = row
                    if (buf == null) {
                        buf = IntArray(SIZE)
                        row = buf
                    }
                    readRow(y, buf, 0)
                    out.put(buf)
                }
            }
        }

        private fun writeCoverage(x: Int, y: Int, src: ByteArray, from: Int, w: Int) {
            val full = full
            if (full != null) {
                val row = y * SIZE + x
                for (gx in 0 until w) full[row + gx] = ((src[from + gx].toInt() and 0xFF) shl 24) or 0xFFFFFF
                return
            }
            val s = y / STRIP_ROWS
            val at = (y - s * STRIP_ROWS) * SIZE + x
            val ints = argb[s]
            if (ints != null) {
                for (gx in 0 until w) ints[at + gx] = ((src[from + gx].toInt() and 0xFF) shl 24) or 0xFFFFFF
                return
            }
            val bytes = alpha[s] ?: ByteArray(STRIP_ROWS * SIZE).also { alpha[s] = it }
            val ink = inked[s] ?: LongArray(STRIP_ROWS * SIZE / 64).also { inked[s] = it }
            src.copyInto(bytes, at, from, from + w)
            for (i in at until at + w) ink[i ushr 6] = ink[i ushr 6] or (1L shl i)
        }

        private fun writeArgb(x: Int, y: Int, src: IntArray, w: Int) {
            val full = full
            if (full != null) {
                src.copyInto(full, y * SIZE + x, 0, w)
                return
            }
            val s = y / STRIP_ROWS
            src.copyInto(argbStrip(s), (y - s * STRIP_ROWS) * SIZE + x, 0, w)
        }

        private fun argbStrip(s: Int): IntArray {
            argb[s]?.let { return it }
            val ints = IntArray(STRIP_ROWS * SIZE)
            val bytes = alpha[s]
            val ink = inked[s]
            if (bytes != null && ink != null) {
                for (i in ints.indices) if ((ink[i ushr 6] and (1L shl i)) != 0L) ints[i] = ((bytes[i].toInt() and 0xFF) shl 24) or 0xFFFFFF
            }
            argb[s] = ints
            alpha[s] = null
            inked[s] = null
            return ints
        }

        public var version: Int = 1
            private set

        public var retired: Boolean = false
            internal set

        public var hasColor: Boolean = false
            private set

        private val rowVersions = IntArray(SIZE).also { it.fill(1, PAD, PAD + WHITE) }

        init {
            if (shared) revision++
            val white = ByteArray(WHITE) { -1 }
            for (y in 0 until WHITE) writeCoverage(PAD, PAD + y, white, 0, WHITE)
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
            if (shared) revision++
            rowVersions.fill(version, y, y + h)
            return (x shl 16) or y
        }

        internal fun place(r: Raster): Glyph? {
            if (r.color) return place(PolyBitmap(r.w, r.h, r.coverage), r.offX, r.offY, true)
            val at = alloc(r.w, r.h)
            if (at < 0) return null
            val x = at ushr 16
            val y = at and 0xFFFF
            for (gy in 0 until r.h) writeCoverage(x, y + gy, r.coverage, gy * r.w, r.w)
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
            val row = IntArray(w + 2)
            for (gy in -1..h) {
                for (gx in -1..w) {
                    val o = (gy.coerceIn(0, h - 1) * w + gx.coerceIn(0, w - 1)) * 4
                    row[gx + 1] = ((px[o + 3].toInt() and 0xFF) shl 24) or ((px[o + 2].toInt() and 0xFF) shl 16) or
                        ((px[o + 1].toInt() and 0xFF) shl 8) or (px[o].toInt() and 0xFF)
                }
                writeArgb(x - 1, y + gy, row, w + 2)
            }
            return Glyph(this, offX, offY, w, h, x / SIZE.toFloat(), y / SIZE.toFloat(), (x + w) / SIZE.toFloat(), (y + h) / SIZE.toFloat(), color)
        }
    }

    public class Atlas internal constructor(
        internal val face: Face, internal val bucket: Int, internal val lum: Int, internal val outline: Int, internal val ring: Boolean = false,
    ) {
        private var keys = IntArray(INITIAL_SLOTS).also { it.fill(-1) }
        private var values = arrayOfNulls<Any>(INITIAL_SLOTS)
        private var size = 0

        internal var placed: Int = 0
            private set

        internal fun matches(f: Face, b: Int, l: Int, o: Int, r: Boolean = false): Boolean = bucket == b && lum == l && outline == o && ring == r && face == f

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
    private const val STRIP_ROWS = 32
    private const val STRIPS = SIZE / STRIP_ROWS
    private const val PAD = 1
    private const val WHITE = 2
    private const val INITIAL_SLOTS = 32
    private const val MAX_PAGES = 4
    private const val MAX_RASTERS = 512
    private const val KERN_LIMIT = 0x0590
    private const val RECENT_ATLASES = 4
    private const val DENSE_RADIUS = 4

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

    internal fun ringAtlas(face: Face, bucket: Int, radiusPx: Float): Atlas = find(face, bucket, -1, quarters(radiusPx), true)

    private fun quarters(px: Float): Int = max(1, (px * 4f).roundToInt())

    private fun find(face: Face, bucket: Int, lum: Int, outline: Int, ring: Boolean = false): Atlas {
        applyInvalidations()
        for (a in recent) if (a != null && a.matches(face, bucket, lum, outline, ring)) return a
        val list = atlases.getOrPut(face) { ArrayList(4) }
        var hit: Atlas? = null
        for (i in list.indices) {
            val a = list[i]
            if (a.matches(face, bucket, lum, outline, ring)) { hit = a; break }
        }
        if (hit == null) {
            hit = Atlas(face, bucket, lum, outline, ring)
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

    @JvmStatic
    public fun kernable(cp: Int): Boolean = cp < KERN_LIMIT && !Character.isWhitespace(cp)

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
        cpuInvalidated.add(face)
        cpuEpoch.incrementAndGet()
        cpuInvalidatedPending = true
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
        return newGlyph(face, bucket, id, outlinePx, round, phase, lum, atlas.ring).also { atlas.put(slot, it) }
    }

    private fun newGlyph(face: Face, bucket: Int, id: Int, outlinePx: Float, round: Boolean, phase: Int, lum: Int, ring: Boolean): Glyph? {
        if (outlinePx > 0f) {
            val r = raster(face, bucket, id, phase) ?: return null
            if (r.color) return if (ring) place(r) else null
            if (!fits(r, quarters(outlinePx) / 4f)) return null
            return place(if (ring) hollow(r, quarters(outlinePx) / 4f) else dilate(r, quarters(outlinePx) / 4f, round))
        }
        val key = Prekey(face, bucket, id * SUBPIXELS + phase)
        val r = rasters.getOrPut(key) {
            recorder?.invoke(face, bucket, id, phase)
            prerastered.remove(key) ?: raster(face, bucket, id, phase) ?: BLANK
        }
        if (r === BLANK) return null
        return place(if (lum < 0 || r.color) r else PolyMaskGamma.apply(r, lum))
    }

    private fun kernel(radius: Float, e: Int, round: Boolean): FloatArray = FloatArray((e * 2 + 1) * (e * 2 + 1)) { k ->
        val dx = abs(k % (e * 2 + 1) - e).toFloat()
        val dy = abs(k / (e * 2 + 1) - e).toFloat()
        (radius + 1f - if (round) sqrt(dx * dx + dy * dy) else max(dx, dy)).coerceIn(0f, 1f)
    }

    private class Spans(radius: Float, val e: Int, round: Boolean) {
        val span = e * 2 + 1
        val weights = kernel(radius, e, round)
        val flatLo = IntArray(span)
        val flatHi = IntArray(span)
        val outerLo = IntArray(span)
        val outerHi = IntArray(span)
        val fringe: IntArray
        val fringeWeights: FloatArray

        init {
            var n = 0
            for (ky in 0 until span) {
                flatLo[ky] = span; flatHi[ky] = -1; outerLo[ky] = span; outerHi[ky] = -1
                for (kx in 0 until span) {
                    val w = weights[ky * span + kx]
                    if (w <= 0f) continue
                    outerLo[ky] = min(outerLo[ky], kx); outerHi[ky] = kx
                    if (w >= 1f) { flatLo[ky] = min(flatLo[ky], kx); flatHi[ky] = kx } else n++
                }
            }
            fringe = IntArray(n)
            fringeWeights = FloatArray(n)
            var i = 0
            for (k in weights.indices) {
                val w = weights[k]
                if (w > 0f && w < 1f) { fringe[i] = k; fringeWeights[i++] = w }
            }
        }
    }

    private class RangeMax(values: IntArray, private val w: Int, h: Int) {
        private val area = w * h
        private val table: IntArray

        init {
            var levels = 1
            while (1 shl levels <= w) levels++
            table = IntArray(area * levels)
            values.copyInto(table)
            for (k in 1 until levels) {
                val half = 1 shl (k - 1)
                val at = k * area
                val prev = at - area
                for (y in 0 until h) {
                    val row = y * w
                    for (x in 0..w - (1 shl k)) table[at + row + x] = max(table[prev + row + x], table[prev + row + x + half])
                }
            }
        }

        fun query(y: Int, l: Int, r: Int): Int {
            val k = 31 - Integer.numberOfLeadingZeros(r - l + 1)
            val at = k * area + y * w
            return max(table[at + l], table[at + r - (1 shl k) + 1])
        }
    }

    private fun fits(r: Raster, radius: Float): Boolean {
        val e = ceil(radius).toInt()
        return r.w + e * 2 + PAD * 2 <= SIZE && r.h + e * 2 + PAD * 2 <= SIZE
    }

    private fun dilate(r: Raster, radius: Float, round: Boolean): Raster = if (ceil(radius).toInt() > DENSE_RADIUS) spanDilate(r, radius, round) else denseDilate(r, radius, round)

    private fun hollow(r: Raster, radius: Float): Raster = if (ceil(radius).toInt() > DENSE_RADIUS) spanHollow(r, radius) else denseHollow(r, radius)

    private fun denseDilate(r: Raster, radius: Float, round: Boolean): Raster {
        val e = ceil(radius).toInt()
        val w = r.w + e * 2
        val h = r.h + e * 2
        val weights = kernel(radius, e, round)
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

    private fun denseHollow(r: Raster, radius: Float): Raster {
        val outer = dilate(r, radius, true)
        val e = ceil(radius).toInt()
        val span = e * 2 + 1
        val weights = kernel(radius, e, true)
        val out = outer.coverage
        for (y in 0 until r.h) for (x in 0 until r.w) {
            var gap = 0f
            for (ky in 0 until span) {
                val sy = y + ky - e
                for (kx in 0 until span) {
                    val sx = x + kx - e
                    val c = if (sy in 0 until r.h && sx in 0 until r.w) r.coverage[sy * r.w + sx].toInt() and 0xFF else 0
                    val v = (255 - c) * weights[ky * span + kx]
                    if (v > gap) gap = v
                }
            }
            val o = (y + e) * outer.w + x + e
            out[o] = max(0, (out[o].toInt() and 0xFF) - (255f - gap).roundToInt()).toByte()
        }
        return outer
    }

    private fun spanDilate(r: Raster, radius: Float, round: Boolean): Raster {
        val k = Spans(radius, ceil(radius).toInt(), round)
        val e = k.e
        val span = k.span
        val w = r.w + e * 2
        val h = r.h + e * 2
        val out = ByteArray(w * h)
        if (r.w <= 0 || r.h <= 0) return Raster(r.offX - e, r.offY - e, w, h, out)
        val rows = RangeMax(IntArray(r.w * r.h) { r.coverage[it].toInt() and 0xFF }, r.w, r.h)
        for (y in 0 until h) for (x in 0 until w) {
            var flat = 0
            var outer = 0
            for (ky in max(0, y - r.h + 1)..min(span - 1, y)) {
                val sy = y - ky
                val oL = max(0, x - k.outerHi[ky])
                val oR = min(r.w - 1, x - k.outerLo[ky])
                if (oL > oR) continue
                outer = max(outer, rows.query(sy, oL, oR))
                val fL = max(0, x - k.flatHi[ky])
                val fR = min(r.w - 1, x - k.flatLo[ky])
                if (fL <= fR) flat = max(flat, rows.query(sy, fL, fR))
            }
            var a = flat.toFloat()
            if (outer > flat) {
                for (i in k.fringe.indices) {
                    val sy = y - k.fringe[i] / span
                    val sx = x - k.fringe[i] % span
                    if (sy !in 0 until r.h || sx !in 0 until r.w) continue
                    val v = (r.coverage[sy * r.w + sx].toInt() and 0xFF) * k.fringeWeights[i]
                    if (v > a) a = v
                }
            }
            out[y * w + x] = a.roundToInt().toByte()
        }
        return Raster(r.offX - e, r.offY - e, w, h, out)
    }

    private fun spanHollow(r: Raster, radius: Float): Raster {
        val outer = dilate(r, radius, true)
        if (r.w <= 0 || r.h <= 0) return outer
        val k = Spans(radius, ceil(radius).toInt(), true)
        val e = k.e
        val span = k.span
        val gaps = RangeMax(IntArray(r.w * r.h) { 255 - (r.coverage[it].toInt() and 0xFF) }, r.w, r.h)
        val out = outer.coverage
        for (y in 0 until r.h) for (x in 0 until r.w) {
            var flat = 0
            var wide = 0
            for (ky in 0 until span) {
                if (k.outerLo[ky] > k.outerHi[ky]) continue
                val sy = y + ky - e
                val inside = sy in 0 until r.h
                val oL = x + k.outerLo[ky] - e
                val oR = x + k.outerHi[ky] - e
                wide = max(wide, if (inside && oL >= 0 && oR < r.w) gaps.query(sy, oL, oR) else 255)
                if (k.flatLo[ky] > k.flatHi[ky]) continue
                val fL = x + k.flatLo[ky] - e
                val fR = x + k.flatHi[ky] - e
                flat = max(flat, if (inside && fL >= 0 && fR < r.w) gaps.query(sy, fL, fR) else 255)
                if (flat == 255) break
            }
            var gap = flat.toFloat()
            if (wide > flat) {
                for (i in k.fringe.indices) {
                    val sy = y + k.fringe[i] / span - e
                    val sx = x + k.fringe[i] % span - e
                    val c = if (sy in 0 until r.h && sx in 0 until r.w) r.coverage[sy * r.w + sx].toInt() and 0xFF else 0
                    val v = (255 - c) * k.fringeWeights[i]
                    if (v > gap) gap = v
                }
            }
            val o = (y + e) * outer.w + x + e
            out[o] = max(0, (out[o].toInt() and 0xFF) - (255f - gap).roundToInt()).toByte()
        }
        return outer
    }

    private const val BITMAP_MAX = 256
    private const val BITMAP_AREA = SIZE * SIZE * 2

    private val bitmaps = WeakHashMap<PolyBitmap, Glyph>()
    private val placedLineages = WeakHashMap<Any, Boolean>()
    private var bitmapArea = 0
    private var placedSince = 0L
    private var placed = 0

    @JvmStatic
    public fun bitmap(b: PolyBitmap): Glyph? {
        bitmaps[b]?.let { return it }
        if (b.width > BITMAP_MAX || b.height > BITMAP_MAX) return null
        val lineage = b.lineage
        if (lineage != null && placedLineages.containsKey(lineage)) return null
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
        if (lineage != null) placedLineages[lineage] = true
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

    private const val MAX_CPU_PAGES = 2
    private const val MAX_CPU_PAGES_GROWN = 8
    private const val GHOST_SHARE = 8

    private data class CpuKey(var face: Face?, var bucket: Int, var lum: Int, var outline: Int, var slot: Int, var ring: Boolean)

    private val cpuGlyphs = ConcurrentHashMap<CpuKey, Any>()
    private val cpuProbe = ThreadLocal.withInitial { CpuKey(null, 0, 0, 0, 0, false) }
    private val cpuLock = Any()
    private val cpuPages = ArrayList<Page>(MAX_CPU_PAGES_GROWN)
    private val cpuPending = HashMap<CpuKey, CompletableFuture<Glyph?>>()
    private val cpuGhosts = HashSet<CpuKey>()
    private var cpuGhostHits = 0
    private var cpuPageLimit = MAX_CPU_PAGES
    private val cpuEpoch = AtomicLong()

    @Volatile
    private var cpuCurrent: Page? = null

    private val cpuInvalidated = ConcurrentHashMap.newKeySet<Face>()

    @Volatile
    private var cpuInvalidatedPending = false

    internal fun cpuWhite(): Page = cpuCurrent ?: synchronized(cpuLock) { cpuCurrent ?: cpuNewPage() }

    internal fun cpuGlyph(face: Face, bucket: Int, id: Int, outlinePx: Float, round: Boolean, phase: Int, lum: Int, ring: Boolean = false): Glyph? {
        if (cpuInvalidatedPending) cpuApplyInvalidations()
        val q = if (outlinePx <= 0f) 0 else quarters(outlinePx)
        val probe = cpuProbe.get()
        probe.face = face; probe.bucket = bucket; probe.lum = if (q == 0) lum else -1
        probe.outline = if (round || ring) q else -q; probe.slot = id * SUBPIXELS + phase; probe.ring = ring && q != 0
        cpuGlyphs[probe]?.let { probe.face = null; return it as? Glyph }
        val key = probe.copy()
        probe.face = null
        val mine = CompletableFuture<Glyph?>()
        val other: CompletableFuture<Glyph?>?
        val epoch: Long
        synchronized(cpuLock) {
            cpuGlyphs[key]?.let { return it as? Glyph }
            other = cpuPending[key]
            if (other == null) {
                cpuPending[key] = mine
                if (cpuGhosts.remove(key)) cpuGhostHits++
            }
            epoch = cpuEpoch.get()
        }
        if (other != null) return other.join()
        var g: Glyph? = null
        var published = false
        try {
            g = cpuPublish(key, mine, cpuRaster(face, bucket, id, outlinePx, round, phase, lum, ring), epoch)
            published = true
        } finally {
            if (!published) synchronized(cpuLock) { cpuPending.remove(key, mine) }
            mine.complete(g)
        }
        return g
    }

    private fun cpuPublish(key: CpuKey, pending: CompletableFuture<Glyph?>, r: Raster?, epoch: Long): Glyph? = synchronized(cpuLock) {
        cpuPending.remove(key, pending)
        val g = r?.let(::cpuPlace)
        if (cpuEpoch.get() == epoch) cpuGlyphs[key] = g ?: NONE
        g
    }

    private fun cpuRaster(face: Face, bucket: Int, id: Int, outlinePx: Float, round: Boolean, phase: Int, lum: Int, ring: Boolean): Raster? {
        val r = raster(face, bucket, id, phase) ?: return null
        if (outlinePx > 0f) {
            if (r.color) return if (ring) r else null
            if (!fits(r, quarters(outlinePx) / 4f)) return null
            return if (ring) hollow(r, quarters(outlinePx) / 4f) else dilate(r, quarters(outlinePx) / 4f, round)
        }
        return if (lum < 0 || r.color) r else PolyMaskGamma.apply(r, lum)
    }

    private fun cpuPlace(r: Raster): Glyph? {
        val edge = if (r.color) PAD + 1 else PAD
        if (r.w <= 0 || r.h <= 0 || r.w + edge * 2 > SIZE || r.h + edge * 2 > SIZE) return null
        return (cpuCurrent ?: cpuNewPage()).place(r) ?: cpuNewPage().place(r)
    }

    private fun cpuNewPage(): Page {
        if (cpuPages.size >= cpuPageLimit) {
            val thrashing = cpuGhosts.isNotEmpty() && cpuGhostHits * GHOST_SHARE >= cpuGhosts.size
            if (thrashing && cpuPageLimit < MAX_CPU_PAGES_GROWN) {
                cpuPageLimit++
            } else {
                if (!thrashing && cpuPageLimit > MAX_CPU_PAGES) cpuPageLimit--
                cpuGhosts.clear()
                while (cpuPages.size >= cpuPageLimit) cpuEvict(cpuPages.removeAt(0))
            }
            cpuGhostHits = 0
        }
        return Page(false).also {
            cpuPages += it
            cpuCurrent = it
        }
    }

    private fun cpuEvict(page: Page) {
        val entries = cpuGlyphs.entries.iterator()
        while (entries.hasNext()) {
            val e = entries.next()
            val g = e.value
            if (g is Glyph && g.page === page) {
                cpuGhosts += e.key
                entries.remove()
            }
        }
    }

    private fun cpuApplyInvalidations() {
        synchronized(cpuLock) {
            if (!cpuInvalidatedPending) return
            cpuInvalidatedPending = false
            val faces = cpuInvalidated.iterator()
            while (faces.hasNext()) {
                val face = faces.next()
                faces.remove()
                cpuGlyphs.keys.removeIf { it.face === face }
                cpuGhosts.removeIf { it.face === face }
                cpuPending.keys.removeIf { it.face === face }
            }
        }
    }
}
