package org.polyfrost.compose.render

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

public sealed class PolyGradient {
    public abstract val colors: List<PolyColor>
    public abstract val stops: List<Float>?

    public abstract fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float

    public open val affine: Boolean get() = false

    public open val linear: Boolean get() = false

    public fun colorAt(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Int = sample(position(px, py, x, y, w, h))

    public fun sample(t: Float): Int {
        val c = argbs()
        val n = c.size
        if (n == 0) return 0
        if (n == 1) return c[0]
        val tc = t.coerceIn(0f, 1f)
        val s = stopArray()
        var i = 0
        while (i < n - 2 && tc > s[i + 1]) i++
        val a = s[i]
        val b = s[i + 1]
        val f = if (b > a) ((tc - a) / (b - a)).coerceIn(0f, 1f) else 0f
        return lerpArgb(c[i], c[i + 1], f)
    }

    private var argbCache: IntArray? = null
    private var stopCache: FloatArray? = null
    private var chromaAt = 0L
    private var chromaLive = false
    private var version = 0
    private var rampVersion = 0
    private var boxVersion = 0

    internal var rampBitmap: PolyBitmap? = null
        get() {
            val b = field ?: return null
            argbs()
            if (rampVersion == version) return b
            return rasterize(256, 1) { x, _ -> sample(x / 255f) }.also {
                field = it
                rampVersion = version
            }
        }
        set(value) {
            field = value
            rampVersion = version
        }

    internal var boxBitmap: PolyBitmap? = null
        get() {
            val b = field ?: return null
            argbs()
            if (boxVersion == version) return b
            val a = boxAspect / 16f
            return rasterize(b.width, b.height) { x, y -> colorAt((x + 0.5f) / b.width * a, (y + 0.5f) / b.height, 0f, 0f, a, 1f) }.also {
                field = it
                boxVersion = version
            }
        }
        set(value) {
            field = value
            boxVersion = version
        }

    internal var boxAspect: Int = 0

    private fun argbs(): IntArray {
        val c = colors
        val cached = argbCache
        if (cached != null && cached.size == c.size && fresh(c, cached)) return cached
        val out = if (cached != null && cached.size == c.size) cached else IntArray(c.size)
        var live = false
        for (i in c.indices) {
            val color = c[i]
            if (color.chroma) live = true
            out[i] = color.argb
        }
        chromaLive = live
        if (live) chromaAt = System.nanoTime()
        if (cached != null) version++
        argbCache = out
        return out
    }

    private fun fresh(c: List<PolyColor>, cached: IntArray): Boolean {
        var live = false
        for (i in c.indices) {
            val color = c[i]
            if (color.chroma) live = true
            else if (color.rawArgb != cached[i]) return false
        }
        if (live != chromaLive) return false
        return !live || System.nanoTime() - chromaAt < CHROMA_REFRESH_NANOS
    }

    private inline fun rasterize(w: Int, h: Int, argb: (Int, Int) -> Int): PolyBitmap {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val c = argb(x, y)
            val o = (y * w + x) * 4
            px[o] = (c shr 16).toByte(); px[o + 1] = (c shr 8).toByte(); px[o + 2] = c.toByte(); px[o + 3] = (c ushr 24).toByte()
        }
        return PolyBitmap(w, h, px)
    }

    internal fun positions(): FloatArray = stopArray()

    private fun stopArray(): FloatArray {
        stopCache?.let { return it }
        val s = stops
        val n = colors.size
        var prev = 0f
        return FloatArray(n) {
            val raw = s?.getOrNull(it) ?: (it.toFloat() / (n - 1))
            prev = if (raw.isNaN()) prev else raw.coerceIn(prev, 1f)
            prev
        }.also { stopCache = it }
    }

    public data class Linear(
        override val colors: List<PolyColor>,
        override val stops: List<Float>? = null,
        val angle: Float = 0f,
    ) : PolyGradient() {
        override val affine: Boolean get() = colors.size <= 2 && stops == null
        override val linear: Boolean get() = true

        private val cosA = cos(Math.toRadians(angle.toDouble()))
        private val sinA = sin(Math.toRadians(angle.toDouble()))

        override fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float {
            val dx = (w / 2f * cosA).toFloat() * 2f
            val dy = (h / 2f * sinA).toFloat() * 2f
            val len2 = dx * dx + dy * dy
            if (len2 <= 0f) return 0f
            val sx = x + w / 2f - dx / 2f
            val sy = y + h / 2f - dy / 2f
            return ((px - sx) * dx + (py - sy) * dy) / len2
        }
    }

    public data class Radial(
        override val colors: List<PolyColor>,
        override val stops: List<Float>? = null,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
        val radius: Float = 0.5f,
    ) : PolyGradient() {
        override fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float {
            val r = radius * minOf(w, h)
            if (r <= 0f) return 1f
            return hypot(px - (x + centerX * w), py - (y + centerY * h)) / r
        }
    }

    public data class Sweep(
        override val colors: List<PolyColor>,
        override val stops: List<Float>? = null,
        val centerX: Float = 0.5f,
        val centerY: Float = 0.5f,
    ) : PolyGradient() {
        override fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float {
            val t = atan2(py - (y + centerY * h), px - (x + centerX * w)) / (2f * Math.PI.toFloat())
            return if (t < 0f) t + 1f else t
        }
    }

    public data class Points(
        override val colors: List<PolyColor>,
        override val stops: List<Float>?,
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
    ) : PolyGradient() {
        override val affine: Boolean get() = colors.size <= 2 && stops == null
        override val linear: Boolean get() = true

        override fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float {
            val dx = x1 - x0
            val dy = y1 - y0
            val len2 = dx * dx + dy * dy
            if (len2 <= 0f) return 0f
            return ((px - x0) * dx + (py - y0) * dy) / len2
        }
    }

    public companion object {
        private const val CHROMA_REFRESH_NANOS = 33_000_000L

        public fun horizontal(vararg colors: PolyColor): Linear = Linear(colors.toList(), angle = 0f)
        public fun vertical(vararg colors: PolyColor): Linear = Linear(colors.toList(), angle = 90f)
        public fun diagonal(vararg colors: PolyColor): Linear = Linear(colors.toList(), angle = 45f)
        public fun radial(vararg colors: PolyColor): Radial = Radial(colors.toList())

        public fun horizontal(vararg colors: Int): Linear = horizontal(*colors.map { PolyColor(it) }.toTypedArray())
        public fun vertical(vararg colors: Int): Linear = vertical(*colors.map { PolyColor(it) }.toTypedArray())

        @JvmStatic
        public fun lerpArgb(a: Int, b: Int, t: Float): Int {
            if (a == b) return a
            val aa = a ushr 24
            val ab = b ushr 24
            if (aa != ab) return lerpPremultiplied(a, b, aa, ab, t.coerceIn(0f, 1f))
            var out = 0
            var shift = 0
            while (shift <= 24) {
                val ca = (a ushr shift) and 0xFF
                val cb = (b ushr shift) and 0xFF
                out = out or (((ca + (cb - ca) * t).roundToInt().coerceIn(0, 255)) shl shift)
                shift += 8
            }
            return out
        }

        private fun lerpPremultiplied(a: Int, b: Int, aa: Int, ab: Int, t: Float): Int {
            val wa = aa * (1f - t)
            val wb = ab * t
            val oa = wa + wb
            if (oa <= 0f) return (if (aa == 0) b else a) and 0xFFFFFF
            var out = oa.roundToInt().coerceIn(0, 255) shl 24
            var shift = 0
            while (shift <= 16) {
                val ca = (a ushr shift) and 0xFF
                val cb = (b ushr shift) and 0xFF
                out = out or (((ca * wa + cb * wb) / oa).roundToInt().coerceIn(0, 255) shl shift)
                shift += 8
            }
            return out
        }
    }
}
