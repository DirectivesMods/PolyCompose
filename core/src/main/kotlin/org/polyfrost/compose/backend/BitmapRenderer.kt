package org.polyfrost.compose.backend

import androidx.compose.ui.graphics.ImageBitmapConfig
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyTessellator
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Rasterizes the tessellator's output into a [PolyImageBitmap] on the CPU, backing `Canvas(ImageBitmap)`.
 * Compose uses it to cache vector images, which are only redrawn when they change.
 */
internal class BitmapRenderer(private val target: PolyImageBitmap) : PolyTessellator() {
    // Alpha8 images are drawn tinted, which the backend does by multiplying, so coverage is stored as white
    private val mask = target.config == ImageBitmapConfig.Alpha8
    private var depth = 0

    // The texture being sampled: an atlas page's packed pixels, or a bitmap's bytes when there is no page
    private var texPage: IntArray? = null
    private var texBytes = ByteArray(0)
    private var texW = 0
    private var texH = 0
    private var texNearest = false

    private val src = FloatArray(4)

    override fun blendSupported(mode: Int): Boolean = mode in PolyRenderer.BLEND_SRC_OVER..PolyRenderer.BLEND_SCREEN

    private var requestedBlend = PolyRenderer.BLEND_SRC_OVER
    private var requestedAntiAlias = true

    override fun blend(mode: Int): Boolean {
        requestedBlend = mode
        return super.blend(mode)
    }

    override fun antiAlias(enabled: Boolean) {
        requestedAntiAlias = enabled
        super.antiAlias(enabled)
    }

    override fun layerBlendSupported(mode: Int): Boolean = blendSupported(mode)

    private class Offscreen {
        var pixels = EMPTY
        var ox = 0
        var oy = 0
        var ow = 0
        var oh = 0
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = Int.MIN_VALUE
        var y1 = Int.MIN_VALUE
    }

    private val offscreens = ArrayList<Offscreen>()
    private val spare = ArrayList<ByteArray>()

    private var dstOx = 0
    private var dstOy = 0
    private var dstStride = 0
    private var dstX0 = 0
    private var dstY0 = 0
    private var dstX1 = 0
    private var dstY1 = 0

    override val layered: Boolean get() = true

    override val sharedAtlas: Boolean get() = false

    override fun layerMark(): Any = Offscreen().also { offscreens += it }

    override fun layer(mark: Any, alpha: Float) {
        val o = mark as Offscreen
        if (offscreens.lastOrNull() !== o) return
        offscreens.removeAt(offscreens.lastIndex)
        if (o.x1 >= o.x0 && o.y1 >= o.y0) {
            val parent = offscreens.lastOrNull()
            if (parent != null) {
                cover(parent, o.x0, o.y0, o.x1, o.y1)
                parent.grow(o)
                composite(o, parent.pixels, parent.ox, parent.oy, parent.ow, alpha)
            } else {
                composite(o, target.pixelsForDrawing(), 0, 0, target.width, alpha)
            }
        }
        release(o)
    }

    override fun effectSupport(): Int = PolyRenderer.EFFECT_LAYER

    override fun layer(mark: Any, alpha: Float, chain: PolyEffectChain, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, scale: Float) {
        val o = mark as Offscreen
        if (offscreens.lastOrNull() === o && o.x1 >= o.x0 && o.y1 >= o.y0) applyEffect(o, chain, x0, y0, x1, y1, radius, scale)
        layer(mark, alpha)
    }

    override fun fade(mark: Any, alpha: Float): Boolean {
        layer(mark, alpha)
        return true
    }

    private fun applyEffect(o: Offscreen, chain: PolyEffectChain, bx0: Float, by0: Float, bx1: Float, by1: Float, radius: Float, scale: Float) {
        val stages = chain.textureStages
        val clampFirst = (stages.firstOrNull() as? PolyEffect.Blur)?.clampEdges != false
        val matrix = chain.colorMatrix
        val passless = stages.isEmpty() && chain.refract == null && chain.highlight == null && matrix == null && chain.noise == null
        val dx = (chain.offsetX * scale).roundToInt()
        val dy = (chain.offsetY * scale).roundToInt()
        if (passless && dx == 0 && dy == 0) return
        val pad = if (passless) 0f else chain.outset * scale
        val l = max(0, floor((if (passless) o.x0.toFloat() else min(o.x0.toFloat(), bx0)) - pad).toInt())
        val t = max(0, floor((if (passless) o.y0.toFloat() else min(o.y0.toFloat(), by0)) - pad).toInt())
        val r = min(target.width - 1, ceil((if (passless) o.x1 + 1f else max(o.x1 + 1f, bx1)) + pad).toInt() - 1)
        val b = min(target.height - 1, ceil((if (passless) o.y1 + 1f else max(o.y1 + 1f, by1)) + pad).toInt() - 1)
        if (l > r || t > b) return
        val w = r - l + 1
        val h = b - t + 1
        var buf = effectBuffer(true, w * h * 4)
        loadPremultiplied(o, buf, l, t, w)
        if (stages.isNotEmpty()) {
            var tmp = effectBuffer(false, w * h * 4)
            val clamp = if (clampFirst) clampBox(bx0 - l, by0 - t, bx1 - l, by1 - t, w, h) else null
            if (clamp != null) extendEdges(buf, tmp, w, h, clamp)
            for (i in stages.indices) {
                val s = stages[i] as? PolyEffect.Blur ?: continue
                val decal = i == 0 && !clampFirst
                for (horizontal in BLUR_AXES) {
                    val radius = if (horizontal) s.radiusX else s.radiusY
                    if (radius <= 0f) continue
                    val out = blurPass(buf, tmp, w, h, PolyEffect.sigmaOf(radius * scale), horizontal, decal)
                    if (out !== buf) {
                        tmp = buf
                        buf = out
                    }
                }
            }
        }
        if (!passless) {
            val bleed = !clampFirst && chain.refract == null && chain.highlight == null && radius <= 0f
            finishPixels(buf, w, h, l, t, matrix, if (bleed) null else floatArrayOf(bx0, by0, bx1, by1, radius))
        }
        storeStraight(o, buf, l, t, w, h, dx, dy)
    }

    private fun loadPremultiplied(o: Offscreen, buf: FloatArray, l: Int, t: Int, w: Int) {
        val px = o.pixels
        for (y in o.y0..o.y1) for (x in o.x0..o.x1) {
            val s = ((y - o.oy) * o.ow + x - o.ox) * 4
            val a = (px[s + 3].toInt() and 0xFF) / 255f
            if (a <= 0f) continue
            val d = ((y - t) * w + x - l) * 4
            for (ch in 0..2) buf[d + ch] = (px[s + ch].toInt() and 0xFF) / 255f * a
            buf[d + 3] = a
        }
    }

    private fun clampBox(x0: Float, y0: Float, x1: Float, y1: Float, w: Int, h: Int): IntArray? {
        val l = x0.coerceIn(0f, w.toFloat()).roundToInt()
        val t = y0.coerceIn(0f, h.toFloat()).roundToInt()
        val r = x1.coerceIn(0f, w.toFloat()).roundToInt() - 1
        val b = y1.coerceIn(0f, h.toFloat()).roundToInt() - 1
        return if (r >= l && b >= t) intArrayOf(l, t, r, b) else null
    }

    private fun extendEdges(buf: FloatArray, src: FloatArray, w: Int, h: Int, c: IntArray) {
        System.arraycopy(buf, 0, src, 0, w * h * 4)
        for (y in 0 until h) {
            val sy = y.coerceIn(c[1], c[3])
            for (x in 0 until w) System.arraycopy(src, (sy * w + x.coerceIn(c[0], c[2])) * 4, buf, (y * w + x) * 4, 4)
        }
    }

    private var effectA = EMPTY_FLOATS
    private var effectB = EMPTY_FLOATS
    private var columnSums = DoubleArray(0)

    private fun effectBuffer(first: Boolean, size: Int): FloatArray {
        if (size > KEEP_FLOATS) return FloatArray(size)
        val kept = if (first) effectA else effectB
        if (kept.size >= size) {
            if (first) kept.fill(0f, 0, size)
            return kept
        }
        val fresh = FloatArray(size)
        if (first) effectA = fresh else effectB = fresh
        return fresh
    }

    private fun blurPass(buf: FloatArray, src: FloatArray, w: Int, h: Int, sigma: Float, horizontal: Boolean, decal: Boolean): FloatArray {
        if (sigma >= BOX_SIGMA) return boxBlur(buf, src, w, h, sigma, horizontal, decal)
        val rad = ceil(sigma * 3f).toInt().coerceAtLeast(1)
        val k = FloatArray(rad * 2 + 1) { i -> exp(-((i - rad) * (i - rad)) / (2f * sigma * sigma)) }
        val sum = k.sum()
        for (i in k.indices) k[i] /= sum
        System.arraycopy(buf, 0, src, 0, w * h * 4)
        if (!horizontal) {
            gaussianColumns(buf, src, w, h, k, rad, decal)
            return buf
        }
        for (line in 0 until h) {
            val base = line * w * 4
            for (i in 0 until w) {
                var r = 0f; var g = 0f; var b = 0f; var a = 0f
                for (j in k.indices) {
                    var at = i + j - rad
                    if (at < 0 || at >= w) {
                        if (decal) continue
                        at = at.coerceIn(0, w - 1)
                    }
                    val s = base + at * 4
                    val kw = k[j]
                    r += src[s] * kw; g += src[s + 1] * kw; b += src[s + 2] * kw; a += src[s + 3] * kw
                }
                val d = base + i * 4
                buf[d] = r; buf[d + 1] = g; buf[d + 2] = b; buf[d + 3] = a
            }
        }
        return buf
    }

    private fun gaussianColumns(buf: FloatArray, src: FloatArray, w: Int, h: Int, k: FloatArray, rad: Int, decal: Boolean) {
        val row = w * 4
        for (i in 0 until h) {
            val d = i * row
            buf.fill(0f, d, d + row)
            for (j in k.indices) {
                var at = i + j - rad
                if (at < 0 || at >= h) {
                    if (decal) continue
                    at = at.coerceIn(0, h - 1)
                }
                val s = at * row
                val kw = k[j]
                for (e in 0 until row) buf[d + e] += src[s + e] * kw
            }
        }
    }

    private fun boxBlur(buf: FloatArray, tmp: FloatArray, w: Int, h: Int, sigma: Float, horizontal: Boolean, decal: Boolean): FloatArray {
        val ideal = 12.0 * sigma * sigma
        var lower = floor(sqrt(ideal / BOX_PASSES + 1)).toInt()
        if (lower % 2 == 0) lower--
        val lowerPasses = ((ideal - BOX_PASSES * lower * lower - 4 * BOX_PASSES * lower - 3 * BOX_PASSES) / (-4.0 * lower - 4)).roundToInt()
        var from = buf
        var to = tmp
        for (pass in 0 until BOX_PASSES) {
            val radius = if (pass < lowerPasses) (lower - 1) / 2 else (lower + 1) / 2
            if (horizontal) boxRows(from, to, w, h, radius, decal) else boxColumns(from, to, w, h, radius, decal)
            val swap = from
            from = to
            to = swap
        }
        return from
    }

    private fun boxRows(from: FloatArray, to: FloatArray, w: Int, h: Int, r: Int, decal: Boolean) {
        val inv = 1.0 / (2 * r + 1)
        for (y in 0 until h) {
            val base = y * w * 4
            var s0 = 0.0; var s1 = 0.0; var s2 = 0.0; var s3 = 0.0
            for (k in -r..r) {
                if (decal && (k < 0 || k >= w)) continue
                val i = base + k.coerceIn(0, w - 1) * 4
                s0 += from[i]; s1 += from[i + 1]; s2 += from[i + 2]; s3 += from[i + 3]
            }
            for (x in 0 until w) {
                val d = base + x * 4
                to[d] = (s0 * inv).toFloat(); to[d + 1] = (s1 * inv).toFloat(); to[d + 2] = (s2 * inv).toFloat(); to[d + 3] = (s3 * inv).toFloat()
                val add = x + r + 1
                if (!decal || add < w) {
                    val i = base + min(add, w - 1) * 4
                    s0 += from[i]; s1 += from[i + 1]; s2 += from[i + 2]; s3 += from[i + 3]
                }
                val sub = x - r
                if (!decal || sub >= 0) {
                    val i = base + max(sub, 0) * 4
                    s0 -= from[i]; s1 -= from[i + 1]; s2 -= from[i + 2]; s3 -= from[i + 3]
                }
            }
        }
    }

    private fun boxColumns(from: FloatArray, to: FloatArray, w: Int, h: Int, r: Int, decal: Boolean) {
        val row = w * 4
        if (columnSums.size < row) columnSums = DoubleArray(row)
        val acc = columnSums
        acc.fill(0.0, 0, row)
        val inv = 1.0 / (2 * r + 1)
        for (k in -r..r) {
            if (decal && (k < 0 || k >= h)) continue
            val s = k.coerceIn(0, h - 1) * row
            for (j in 0 until row) acc[j] += from[s + j]
        }
        for (y in 0 until h) {
            val d = y * row
            for (j in 0 until row) to[d + j] = (acc[j] * inv).toFloat()
            val add = y + r + 1
            if (!decal || add < h) {
                val s = min(add, h - 1) * row
                for (j in 0 until row) acc[j] += from[s + j]
            }
            val sub = y - r
            if (!decal || sub >= 0) {
                val s = max(sub, 0) * row
                for (j in 0 until row) acc[j] -= from[s + j]
            }
        }
    }

    private fun finishPixels(buf: FloatArray, w: Int, h: Int, l: Int, t: Int, m: FloatArray?, box: FloatArray?) {
        var ix0 = 0
        var ix1 = -1
        var iy0 = 0
        var iy1 = -1
        if (box != null) {
            val hw = (box[2] - box[0]) / 2f
            val hh = (box[3] - box[1]) / 2f
            if (hw > 0f && hh > 0f) {
                val inset = min(max(box[4], 0f), min(hw, hh)) + 1f
                ix0 = ceil(box[0] + inset - l - 0.5f).toInt(); ix1 = floor(box[2] - inset - l - 0.5f).toInt()
                iy0 = ceil(box[1] + inset - t - 0.5f).toInt(); iy1 = floor(box[3] - inset - t - 0.5f).toInt()
            }
        }
        for (y in 0 until h) for (x in 0 until w) {
            val d = (y * w + x) * 4
            val inside = box == null || y in iy0..iy1 && x in ix0..ix1
            if (inside && m == null) continue
            val cov = if (box == null || inside) 1f else coverage(l + x + 0.5f, t + y + 0.5f, box)
            if (cov <= 0f) {
                buf[d] = 0f; buf[d + 1] = 0f; buf[d + 2] = 0f; buf[d + 3] = 0f
                continue
            }
            if (m != null) {
                val a = buf[d + 3]
                val u0 = if (a > 0f) buf[d] / a else 0f
                val u1 = if (a > 0f) buf[d + 1] / a else 0f
                val u2 = if (a > 0f) buf[d + 2] / a else 0f
                val oa = (m[15] * u0 + m[16] * u1 + m[17] * u2 + m[18] * a + m[19] / 255f).coerceIn(0f, 1f)
                for (ch in 0..2) {
                    val i = ch * 5
                    buf[d + ch] = (m[i] * u0 + m[i + 1] * u1 + m[i + 2] * u2 + m[i + 3] * a + m[i + 4] / 255f).coerceIn(0f, 1f) * oa
                }
                buf[d + 3] = oa
            }
            if (cov < 1f) for (ch in 0..3) buf[d + ch] *= cov
        }
    }

    private fun coverage(px: Float, py: Float, box: FloatArray): Float {
        val hw = (box[2] - box[0]) / 2f
        val hh = (box[3] - box[1]) / 2f
        if (hw <= 0f || hh <= 0f) return 0f
        val rad = min(max(box[4], 0f), min(hw, hh))
        val qx = abs(px - (box[0] + hw)) - hw + rad
        val qy = abs(py - (box[1] + hh)) - hh + rad
        val ex = max(qx, 0f)
        val ey = max(qy, 0f)
        val outside = if (ex == 0f) ey else if (ey == 0f) ex else hypot(ex, ey)
        val d = outside + min(max(qx, qy), 0f) - rad
        return (0.5f - d).coerceIn(0f, 1f)
    }

    private fun storeStraight(o: Offscreen, buf: FloatArray, l: Int, t: Int, w: Int, h: Int, dx: Int, dy: Int) {
        clear(o)
        o.x0 = Int.MAX_VALUE; o.y0 = Int.MAX_VALUE; o.x1 = Int.MIN_VALUE; o.y1 = Int.MIN_VALUE
        val nl = max(0, l + dx)
        val nt = max(0, t + dy)
        val nr = min(target.width - 1, l + w - 1 + dx)
        val nb = min(target.height - 1, t + h - 1 + dy)
        if (nl > nr || nt > nb) return
        cover(o, nl, nt, nr, nb)
        val px = o.pixels
        for (y in nt..nb) for (x in nl..nr) {
            val s = ((y - dy - t) * w + x - dx - l) * 4
            val a = buf[s + 3]
            if (a <= 0f) continue
            val d = ((y - o.oy) * o.ow + x - o.ox) * 4
            for (ch in 0..2) px[d + ch] = (buf[s + ch] / a * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
            px[d + 3] = (a * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        o.x0 = nl; o.y0 = nt; o.x1 = nr; o.y1 = nb
    }

    private fun Offscreen.grow(o: Offscreen) {
        x0 = min(x0, o.x0); y0 = min(y0, o.y0); x1 = max(x1, o.x1); y1 = max(y1, o.y1)
    }

    private fun cover(o: Offscreen, l: Int, t: Int, r: Int, b: Int) {
        if (o.ow > 0 && l >= o.ox && t >= o.oy && r < o.ox + o.ow && b < o.oy + o.oh) return
        var nl = l
        var nt = t
        var nr = r
        var nb = b
        if (o.ow > 0) {
            nl = min(nl, o.ox); nt = min(nt, o.oy); nr = max(nr, o.ox + o.ow - 1); nb = max(nb, o.oy + o.oh - 1)
            val sx = max(LAYER_SLACK, (nr - nl + 1) / 4)
            val sy = max(LAYER_SLACK, (nb - nt + 1) / 4)
            nl = max(0, nl - sx); nt = max(0, nt - sy)
            nr = min(target.width - 1, nr + sx); nb = min(target.height - 1, nb + sy)
        }
        val nw = nr - nl + 1
        val px = take(nw * (nb - nt + 1) * 4)
        if (o.x1 >= o.x0) for (y in o.y0..o.y1) {
            System.arraycopy(o.pixels, ((y - o.oy) * o.ow + o.x0 - o.ox) * 4, px, ((y - nt) * nw + o.x0 - nl) * 4, (o.x1 - o.x0 + 1) * 4)
        }
        clear(o)
        give(o.pixels)
        o.pixels = px
        o.ox = nl; o.oy = nt; o.ow = nw; o.oh = nb - nt + 1
    }

    private fun clear(o: Offscreen) {
        if (o.x1 < o.x0 || o.y1 < o.y0) return
        for (y in o.y0..o.y1) {
            val row = (y - o.oy) * o.ow - o.ox
            o.pixels.fill(0, (row + o.x0) * 4, (row + o.x1 + 1) * 4)
        }
    }

    private fun release(o: Offscreen) {
        clear(o)
        give(o.pixels)
        o.pixels = EMPTY
        o.ow = 0; o.oh = 0
        o.x0 = Int.MAX_VALUE; o.y0 = Int.MAX_VALUE; o.x1 = Int.MIN_VALUE; o.y1 = Int.MIN_VALUE
    }

    private fun take(size: Int): ByteArray {
        var best = -1
        for (i in spare.indices) if (spare[i].size >= size && (best < 0 || spare[i].size < spare[best].size)) best = i
        return if (best >= 0) spare.removeAt(best) else ByteArray(size)
    }

    private fun give(px: ByteArray) {
        if (px.isEmpty()) return
        if (spare.size < MAX_SPARE) {
            spare += px
            return
        }
        var smallest = 0
        for (i in spare.indices) if (spare[i].size < spare[smallest].size) smallest = i
        if (spare[smallest].size < px.size) spare[smallest] = px
    }

    private fun composite(o: Offscreen, dst: ByteArray, dx: Int, dy: Int, stride: Int, alpha: Float) {
        if (blendMode == PolyRenderer.BLEND_SRC_OVER) {
            if (alpha == 1f) compositeOpaque(o, dst, dx, dy, stride) else compositeFaded(o, dst, dx, dy, stride, alpha)
            return
        }
        val src = o.pixels
        for (y in o.y0..o.y1) for (x in o.x0..o.x1) {
            val s = ((y - o.oy) * o.ow + x - o.ox) * 4
            val i = ((y - dy) * stride + x - dx) * 4
            val sa = (src[s + 3].toInt() and 0xFF) / 255f * alpha
            for (ch in 0..2) this.src[ch] = (src[s + ch].toInt() and 0xFF) / 255f * sa
            this.src[3] = sa
            blendPixel(dst, i)
        }
    }

    private fun compositeOpaque(o: Offscreen, dst: ByteArray, dx: Int, dy: Int, stride: Int) {
        val src = o.pixels
        for (y in o.y0..o.y1) {
            var s = ((y - o.oy) * o.ow + o.x0 - o.ox) * 4
            var i = ((y - dy) * stride + o.x0 - dx) * 4
            var x = o.x0
            while (x <= o.x1) {
                val a = src[s + 3].toInt() and 0xFF
                if (a == 255) {
                    var n = 1
                    while (x + n <= o.x1 && src[s + n * 4 + 3].toInt() == -1) n++
                    System.arraycopy(src, s, dst, i, n * 4)
                    x += n
                    s += n * 4
                    i += n * 4
                    continue
                }
                if (a != 0) {
                    val k = a * 255L
                    over(dst, i, (src[s].toInt() and 0xFF) * k * 255, (src[s + 1].toInt() and 0xFF) * k * 255, (src[s + 2].toInt() and 0xFF) * k * 255, k, FULL)
                }
                x++
                s += 4
                i += 4
            }
        }
    }

    private fun compositeFaded(o: Offscreen, dst: ByteArray, dx: Int, dy: Int, stride: Int, alpha: Float) {
        val src = o.pixels
        for (y in o.y0..o.y1) {
            var s = ((y - o.oy) * o.ow + o.x0 - o.ox) * 4
            var i = ((y - dy) * stride + o.x0 - dx) * 4
            for (x in o.x0..o.x1) {
                val sa = (src[s + 3].toInt() and 0xFF) / 255f * alpha
                if (sa > 0f) {
                    val da = dst[i + 3].toInt() and 0xFF
                    if (da == 0) {
                        dst[i] = src[s]; dst[i + 1] = src[s + 1]; dst[i + 2] = src[s + 2]
                        dst[i + 3] = (sa * 255f + 0.5f).toInt().toByte()
                    } else {
                        overFloat(src, s, dst, i, sa, da / 255f)
                    }
                }
                s += 4
                i += 4
            }
        }
    }

    private fun overFloat(src: ByteArray, s: Int, dst: ByteArray, i: Int, sa: Float, da: Float) {
        val outA = sa + da * (1f - sa)
        for (ch in 0..2) {
            val sc = (src[s + ch].toInt() and 0xFF) / 255f * sa
            val d = (dst[i + ch].toInt() and 0xFF) / 255f * da
            dst[i + ch] = ((sc + d * (1f - sa)) / outA * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        dst[i + 3] = (outA * 255f + 0.5f).toInt().toByte()
    }

    private fun over(px: ByteArray, o: Int, s0: Long, s1: Long, s2: Long, sa: Long, full: Long) {
        val da = px[o + 3].toInt() and 0xFF
        val k: Long
        val q: Long
        when (da) {
            0 -> {
                k = 0L
                q = sa * 255
            }
            255 -> {
                k = 255 * (full - sa)
                q = 255 * full
            }
            else -> {
                k = da * (full - sa)
                q = sa * 255 + k
            }
        }
        px[o] = ratio(s0 + (px[o].toInt() and 0xFF) * k, q)
        px[o + 1] = ratio(s1 + (px[o + 1].toInt() and 0xFF) * k, q)
        px[o + 2] = ratio(s2 + (px[o + 2].toInt() and 0xFF) * k, q)
        px[o + 3] = ((2 * q + full) / (2 * full)).toInt().toByte()
    }

    private fun ratio(p: Long, q: Long): Byte = min(255L, (2 * p + q) / (2 * q)).toInt().toByte()

    override fun bitmapTexture(bitmap: PolyBitmap): Any = bitmap
    override fun live(draw: (PolyRenderer) -> Unit) = draw(this)

    override fun save() {
        depth++
        super.save()
    }

    override fun restore() {
        depth--
        super.restore()
    }

    /** Rasterizes pending draws; called whenever the target's pixels are read. */
    fun sync() {
        packedFrom.fill(null)
        packedUse.fill(0L)
        if (drawBoxMark == 0) return
        flush()
        // Per-draw bookkeeping would otherwise grow with every redraw; the transform is kept for callers that never save
        if (depth == 0) {
            val kept = state
            resetTessellator()
            state = kept
            super.blend(requestedBlend)
            super.antiAlias(requestedAntiAlias)
        }
    }

    private companion object {
        const val MAX_SPARE = 4
        const val LAYER_SLACK = 64
        const val PACK_MAX = 256 * 256
        const val PACK_SLOTS = 4
        const val FULL = 255L * 255L
        const val BOX_SIGMA = 2f
        const val BOX_PASSES = 3
        const val KEEP_FLOATS = 1 shl 20
        const val SPAN_LIMIT = 1e15f
        const val TEXEL_SLACK = 5e-4
        const val OFFSET_LIMIT = 1e9
        const val TIE_SLACK = 1e-5
        val EMPTY = ByteArray(0)
        val EMPTY_FLOATS = FloatArray(0)
        val EMPTY_INTS = IntArray(0)
        val BLUR_AXES = booleanArrayOf(true, false)
    }

    private val packedPixels = Array(PACK_SLOTS) { EMPTY_INTS }
    private val packedFrom = arrayOfNulls<PolyBitmap>(PACK_SLOTS)
    private val packedUse = LongArray(PACK_SLOTS)
    private var packedClock = 0L

    private fun packed(b: PolyBitmap): IntArray {
        var slot = 0
        for (i in 0 until PACK_SLOTS) {
            if (packedFrom[i] === b) {
                packedUse[i] = ++packedClock
                return packedPixels[i]
            }
            if (packedUse[i] < packedUse[slot]) slot = i
        }
        val n = b.width * b.height
        if (packedPixels[slot].size < n) packedPixels[slot] = IntArray(n)
        val out = packedPixels[slot]
        val p = b.pixels
        for (i in 0 until n) {
            out[i] = ((p[i * 4 + 3].toInt() and 0xFF) shl 24) or ((p[i * 4 + 2].toInt() and 0xFF) shl 16) or
                ((p[i * 4 + 1].toInt() and 0xFF) shl 8) or (p[i * 4].toInt() and 0xFF)
        }
        packedFrom[slot] = b
        packedUse[slot] = ++packedClock
        return out
    }

    override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {
        val layer = offscreens.lastOrNull()
        val px: ByteArray
        if (layer != null) {
            val l = max(0, floor(bounds[0]).toInt())
            val t = max(0, floor(bounds[1]).toInt())
            val r = min(target.width - 1, ceil(bounds[2]).toInt())
            val b = min(target.height - 1, ceil(bounds[3]).toInt())
            if (l > r || t > b) return
            cover(layer, l, t, r, b)
            layer.x0 = min(layer.x0, l); layer.y0 = min(layer.y0, t)
            layer.x1 = max(layer.x1, r); layer.y1 = max(layer.y1, b)
            px = layer.pixels
            dstOx = layer.ox; dstOy = layer.oy; dstStride = layer.ow
            dstX0 = l; dstY0 = t; dstX1 = r; dstY1 = b
        } else {
            px = target.pixelsForDrawing()
            dstOx = 0; dstOy = 0; dstStride = target.width
            dstX0 = 0; dstY0 = 0; dstX1 = target.width - 1; dstY1 = target.height - 1
        }
        if (texture is PolyGlyphPage.Page) {
            texPage = texture.pixels
            texW = PolyGlyphPage.SIZE
            texH = PolyGlyphPage.SIZE
        } else {
            val b = texture as PolyBitmap
            texPage = if (b.width * b.height <= PACK_MAX) packed(b) else null
            texBytes = b.pixels
            texW = b.width
            texH = b.height
        }
        texNearest = nearest
        for (q in colors.indices step 4) {
            if (rect(px, verts, colors, q, premultiplied)) continue
            triangle(px, verts, colors, q, q + 1, q + 2, premultiplied)
            triangle(px, verts, colors, q, q + 2, q + 3, premultiplied)
        }
    }

    private fun triangle(px: ByteArray, v: FloatArray, c: IntArray, a: Int, b0: Int, d0: Int, premultiplied: Boolean) {
        val flip = edge(v, a, b0, v[d0 * 4], v[d0 * 4 + 1]) < 0f
        val b = if (flip) d0 else b0
        val d = if (flip) b0 else d0
        val area = edge(v, a, b, v[d * 4], v[d * 4 + 1])
        if (!(area > 0f)) return
        val ax = v[a * 4]; val ay = v[a * 4 + 1]
        val bx = v[b * 4]; val by = v[b * 4 + 1]
        val dx = v[d * 4]; val dy = v[d * 4 + 1]
        val au = v[a * 4 + 2]; val av = v[a * 4 + 3]
        val bu = v[b * 4 + 2]; val bv = v[b * 4 + 3]
        val du = v[d * 4 + 2]; val dv = v[d * 4 + 3]
        // Solid fills all sample the white texel, so it is only looked up once
        val flat = au == bu && au == du && av == bv && av == dv
        val flatTexel = if (flat) sample(au, av) else 0
        val x0 = max(dstX0, ceil(min(ax, min(bx, dx)) - 0.5f).toInt())
        val x1 = min(dstX1, floor(max(ax, max(bx, dx)) - 0.5f).toInt())
        val y0 = max(dstY0, ceil(min(ay, min(by, dy)) - 0.5f).toInt())
        val y1 = min(dstY1, floor(max(ay, max(by, dy)) - 0.5f).toInt())
        val tlA = topLeft(dx - bx, dy - by)
        val tlB = topLeft(ax - dx, ay - dy)
        val tlD = topLeft(bx - ax, by - ay)
        val ox = dstOx
        val oy = dstOy
        val stride = dstStride
        val narrow = abs(ax) < SPAN_LIMIT && abs(ay) < SPAN_LIMIT && abs(bx) < SPAN_LIMIT && abs(by) < SPAN_LIMIT &&
            abs(dx) < SPAN_LIMIT && abs(dy) < SPAN_LIMIT
        val color = c[a]
        val fast = color == c[b] && color == c[d] && blendMode == PolyRenderer.BLEND_SRC_OVER
        val solid = fast && flat && narrow
        if (solid) {
            prepareSolid(color, flatTexel, premultiplied)
            if (solidAlpha == 0L) return
        }
        for (y in y0..y1) {
            val sy = y + 0.5f
            var lo = x0
            var hi = x1
            if (narrow) {
                if (!rowSpan(v, a, b, d, sy, tlA, tlB, tlD, x0, x1)) continue
                lo = spanLo
                hi = spanHi
                while (lo <= hi && !covers(v, a, b, d, lo + 0.5f, sy, tlA, tlB, tlD)) lo++
                if (lo > hi) continue
                while (hi > lo && !covers(v, a, b, d, hi + 0.5f, sy, tlA, tlB, tlD)) hi--
            }
            val row = (y - oy) * stride - ox
            if (solid) {
                solidSpan(px, (row + lo) * 4, (row + hi) * 4)
                continue
            }
            for (x in lo..hi) triangleTexel(px, v, c, a, b, d, x, y, row, area, tlA, tlB, tlD, flat, flatTexel, fast, premultiplied)
        }
    }

    private var spanLo = 0
    private var spanHi = 0

    private fun rowSpan(v: FloatArray, a: Int, b: Int, d: Int, sy: Float, tlA: Boolean, tlB: Boolean, tlD: Boolean, x0: Int, x1: Int): Boolean {
        spanLo = x0
        spanHi = x1
        return clipSpan(v, b, d, sy, tlA) && clipSpan(v, d, a, sy, tlB) && clipSpan(v, a, b, sy, tlD) && spanLo <= spanHi
    }

    private fun clipSpan(v: FloatArray, i: Int, j: Int, sy: Float, tl: Boolean): Boolean {
        val ix = v[i * 4].toDouble()
        val iy = v[i * 4 + 1].toDouble()
        val dy = v[j * 4 + 1] - iy
        if (dy == 0.0) {
            val w = edge(v, i, j, spanLo + 0.5f, sy)
            return !(w < 0f || w == 0f && !tl)
        }
        val cross = ix + (v[j * 4] - ix) * (sy - iy) / dy
        if (dy > 0.0) {
            val hi = ceil(cross - 0.5) + 1.0
            if (hi < spanHi) spanHi = hi.toInt()
        } else {
            val lo = floor(cross - 0.5) - 1.0
            if (lo > spanLo) spanLo = lo.toInt()
        }
        return true
    }

    private fun covers(v: FloatArray, a: Int, b: Int, d: Int, sx: Float, sy: Float, tlA: Boolean, tlB: Boolean, tlD: Boolean): Boolean {
        val wa = edge(v, b, d, sx, sy)
        if (wa < 0f || wa == 0f && !tlA) return false
        val wb = edge(v, d, a, sx, sy)
        if (wb < 0f || wb == 0f && !tlB) return false
        val wd = edge(v, a, b, sx, sy)
        return !(wd < 0f || wd == 0f && !tlD)
    }

    private fun triangleTexel(
        px: ByteArray, v: FloatArray, c: IntArray, a: Int, b: Int, d: Int, x: Int, y: Int, row: Int, area: Float,
        tlA: Boolean, tlB: Boolean, tlD: Boolean, flat: Boolean, flatTexel: Int, fast: Boolean, premultiplied: Boolean,
    ) {
        val sx = x + 0.5f
        val sy = y + 0.5f
        val wa = edge(v, b, d, sx, sy)
        if (wa < 0f || wa == 0f && !tlA) return
        val wb = edge(v, d, a, sx, sy)
        if (wb < 0f || wb == 0f && !tlB) return
        val wd = edge(v, a, b, sx, sy)
        if (wd < 0f || wd == 0f && !tlD) return
        val t = if (flat) {
            flatTexel
        } else {
            sample((wa * v[a * 4 + 2] + wb * v[b * 4 + 2] + wd * v[d * 4 + 2]) / area, (wa * v[a * 4 + 3] + wb * v[b * 4 + 3] + wd * v[d * 4 + 3]) / area)
        }
        val o = (row + x) * 4
        if (fast) {
            texelOver(px, o, c[a], t, premultiplied)
            return
        }
        val sa = mix(c[a], c[b], c[d], wa, wb, wd, area, 24) / 255f * (t ushr 24) / 255f
        if (blendMode != PolyRenderer.BLEND_SRC_OVER) {
            for (ch in 0..2) {
                src[ch] = if (mask) sa
                else mix(c[a], c[b], c[d], wa, wb, wd, area, 16 - ch * 8) / 255f * (t ushr ch * 8 and 0xFF) / 255f * (if (premultiplied) 1f else sa)
            }
            src[3] = sa
            blendPixel(px, o)
            return
        }
        if (sa <= 0f) return
        val da = (px[o + 3].toInt() and 0xFF) / 255f
        val outA = sa + da * (1f - sa)
        for (ch in 0..2) {
            val s = if (mask) sa
            else mix(c[a], c[b], c[d], wa, wb, wd, area, 16 - ch * 8) / 255f * (t ushr ch * 8 and 0xFF) / 255f * (if (premultiplied) 1f else sa)
            val dc = (px[o + ch].toInt() and 0xFF) / 255f * da
            px[o + ch] = ((s + dc * (1f - sa)) / outA * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        px[o + 3] = (outA * 255f + 0.5f).toInt().toByte()
    }

    private var solidAlpha = 0L
    private var solid0 = 0L
    private var solid1 = 0L
    private var solid2 = 0L
    private val solidBytes = ByteArray(4)

    private fun prepareSolid(color: Int, t: Int, premultiplied: Boolean) {
        val sa = ((color ushr 24) * (t ushr 24)).toLong()
        solidAlpha = sa
        if (sa == 0L) return
        solid0 = sourceTerm(color shr 16 and 0xFF, t and 0xFF, sa, premultiplied)
        solid1 = sourceTerm(color shr 8 and 0xFF, t shr 8 and 0xFF, sa, premultiplied)
        solid2 = sourceTerm(color and 0xFF, t shr 16 and 0xFF, sa, premultiplied)
        val q = sa * 255
        solidBytes[0] = ratio(solid0, q)
        solidBytes[1] = ratio(solid1, q)
        solidBytes[2] = ratio(solid2, q)
        solidBytes[3] = ((2 * q + FULL) / (2 * FULL)).toInt().toByte()
    }

    private fun sourceTerm(color: Int, texel: Int, sa: Long, premultiplied: Boolean): Long = when {
        mask -> sa * FULL
        premultiplied -> color * texel * FULL
        else -> color * texel * sa
    }

    private fun solidSpan(px: ByteArray, from: Int, to: Int) {
        val bytes = solidBytes
        if (solidAlpha == FULL) {
            System.arraycopy(bytes, 0, px, from, 4)
            var filled = 4
            val total = to - from + 4
            while (filled < total) {
                val n = min(filled, total - filled)
                System.arraycopy(px, from, px, from + filled, n)
                filled += n
            }
            return
        }
        var o = from
        while (o <= to) {
            if (px[o + 3].toInt() == 0) {
                px[o] = bytes[0]; px[o + 1] = bytes[1]; px[o + 2] = bytes[2]; px[o + 3] = bytes[3]
            } else {
                over(px, o, solid0, solid1, solid2, solidAlpha, FULL)
            }
            o += 4
        }
    }

    private fun texelOver(px: ByteArray, o: Int, color: Int, t: Int, premultiplied: Boolean) {
        val sa = (color ushr 24) * (t ushr 24)
        if (sa == 0) return
        val cr = color shr 16 and 0xFF
        val cg = color shr 8 and 0xFF
        val cb = color and 0xFF
        if (sa.toLong() == FULL) {
            if (mask) {
                px[o] = -1; px[o + 1] = -1; px[o + 2] = -1
            } else {
                px[o] = mul8(cr, t and 0xFF).toByte(); px[o + 1] = mul8(cg, t shr 8 and 0xFF).toByte(); px[o + 2] = mul8(cb, t shr 16 and 0xFF).toByte()
            }
            px[o + 3] = -1
            return
        }
        val s = sa.toLong()
        over(px, o, sourceTerm(cr, t and 0xFF, s, premultiplied), sourceTerm(cg, t shr 8 and 0xFF, s, premultiplied), sourceTerm(cb, t shr 16 and 0xFF, s, premultiplied), s, FULL)
    }

    private fun mul8(a: Int, b: Int): Int = (a * b + 127) / 255

    private fun rect(px: ByteArray, v: FloatArray, c: IntArray, q: Int, premultiplied: Boolean): Boolean {
        val color = c[q]
        if (c[q + 1] != color || c[q + 2] != color || c[q + 3] != color) return false
        val p = q * 4
        val x0 = v[p]; val y0 = v[p + 1]
        val x2 = v[p + 8]; val y2 = v[p + 9]
        val turn = v[p + 4] == x0 && v[p + 5] == y2 && v[p + 12] == x2 && v[p + 13] == y0
        if (!turn && !(v[p + 4] == x2 && v[p + 5] == y0 && v[p + 12] == x0 && v[p + 13] == y2)) return false
        val l = min(x0, x2); val r = max(x0, x2)
        val t = min(y0, y2); val b = max(y0, y2)
        if (!(r - l > 0f && b - t > 0f && r - l < Float.POSITIVE_INFINITY && b - t < Float.POSITIVE_INFINITY)) return false
        val u0 = v[p + 2]; val w0 = v[p + 3]
        val u2 = v[p + 10]; val w2 = v[p + 11]
        if (v[p + 6] != (if (turn) u0 else u2) || v[p + 7] != (if (turn) w2 else w0)) return false
        if (v[p + 14] != (if (turn) u2 else u0) || v[p + 15] != (if (turn) w0 else w2)) return false
        val flat = u0 == u2 && w0 == w2
        var offX = 0
        var offY = 0
        if (!flat) {
            val fx = u0.toDouble() * texW - x0
            val fy = w0.toDouble() * texH - y0
            if (!(abs(fx) < OFFSET_LIMIT && abs(fy) < OFFSET_LIMIT)) return false
            offX = fx.roundToInt()
            offY = fy.roundToInt()
            if (abs(fx - offX) > TEXEL_SLACK || abs(u2.toDouble() * texW - x2 - offX) > TEXEL_SLACK) return false
            if (abs(fy - offY) > TEXEL_SLACK || abs(w2.toDouble() * texH - y2 - offY) > TEXEL_SLACK) return false
        }
        val slack = TIE_SLACK * max(r - l, b - t)
        if (onCenter(r) && (nearAbove(t, slack) || nearBelow(b, slack)) || onCenter(b) && (nearAbove(l, slack) || nearBelow(r, slack))) return false
        val xs = max(dstX0, ceil(l.toDouble() - 0.5).toInt())
        val xe = min(dstX1, ceil(r.toDouble() - 0.5).toInt() - 1)
        val ys = max(dstY0, ceil(t.toDouble() - 0.5).toInt())
        val ye = min(dstY1, ceil(b.toDouble() - 0.5).toInt() - 1)
        if (xs > xe || ys > ye) return true
        if (blendMode != PolyRenderer.BLEND_SRC_OVER) {
            rectBlend(px, color, flat, u0, w0, offX, offY, xs, xe, ys, ye, premultiplied)
            return true
        }
        if (flat) {
            prepareSolid(color, sample(u0, w0), premultiplied)
            if (solidAlpha == 0L) return true
            for (y in ys..ye) {
                val row = (y - dstOy) * dstStride - dstOx
                solidSpan(px, (row + xs) * 4, (row + xe) * 4)
            }
            return true
        }
        val copy = !mask && color == -1 && texPage == null && xs + offX >= 0 && xe + offX < texW
        for (y in ys..ye) {
            val ty = (y + offY).coerceIn(0, texH - 1) * texW
            val row = (y - dstOy) * dstStride - dstOx
            if (copy) copyRow(px, row, ty + offX, xs, xe) else texelRow(px, row, ty, offX, xs, xe, color, premultiplied)
        }
        return true
    }

    private fun texelRow(px: ByteArray, row: Int, ty: Int, offX: Int, xs: Int, xe: Int, color: Int, premultiplied: Boolean) {
        val page = texPage
        for (x in xs..xe) {
            val i = ty + (x + offX).coerceIn(0, texW - 1)
            texelOver(px, (row + x) * 4, color, page?.get(i) ?: byteTexel(i), premultiplied)
        }
    }

    private fun copyRow(px: ByteArray, row: Int, tx: Int, xs: Int, xe: Int) {
        val bytes = texBytes
        var x = xs
        while (x <= xe) {
            if (bytes[(tx + x) * 4 + 3].toInt() != -1) {
                texelOver(px, (row + x) * 4, -1, byteTexel(tx + x), false)
                x++
                continue
            }
            var end = x + 1
            while (end <= xe && bytes[(tx + end) * 4 + 3].toInt() == -1) end++
            System.arraycopy(bytes, (tx + x) * 4, px, (row + x) * 4, (end - x) * 4)
            x = end
        }
    }

    private fun rectBlend(px: ByteArray, color: Int, flat: Boolean, u: Float, v: Float, offX: Int, offY: Int, xs: Int, xe: Int, ys: Int, ye: Int, premultiplied: Boolean) {
        val flatTexel = if (flat) sample(u, v) else 0
        val page = texPage
        for (y in ys..ye) {
            val ty = (y + offY).coerceIn(0, texH - 1) * texW
            val row = (y - dstOy) * dstStride - dstOx
            for (x in xs..xe) {
                val t = if (flat) {
                    flatTexel
                } else {
                    val i = ty + (x + offX).coerceIn(0, texW - 1)
                    page?.get(i) ?: byteTexel(i)
                }
                val sa = (color ushr 24).toFloat() / 255f * (t ushr 24) / 255f
                for (ch in 0..2) {
                    src[ch] = if (mask) sa
                    else (color shr 16 - ch * 8 and 0xFF).toFloat() / 255f * (t ushr ch * 8 and 0xFF) / 255f * (if (premultiplied) 1f else sa)
                }
                src[3] = sa
                blendPixel(px, (row + x) * 4)
            }
        }
    }

    private fun onCenter(z: Float): Boolean {
        val f = z.toDouble() - 0.5
        return f == floor(f)
    }

    private fun nearAbove(z: Float, slack: Double): Boolean {
        val gap = ceil(z.toDouble() - 0.5) + 0.5 - z
        return gap > 0.0 && gap < slack
    }

    private fun nearBelow(z: Float, slack: Double): Boolean = z - (ceil(z.toDouble() - 0.5) - 0.5) < slack

    private fun blendPixel(px: ByteArray, o: Int) {
        val sa = src[3]
        val da = (px[o + 3].toInt() and 0xFF) / 255f
        val outA = blended(sa, da, sa, da)
        for (ch in 0..2) {
            val out = blended(src[ch], (px[o + ch].toInt() and 0xFF) / 255f * da, sa, da)
            px[o + ch] = if (outA <= 0f) 0 else (out / outA * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        px[o + 3] = (outA * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    private fun blended(s: Float, d: Float, sa: Float, da: Float): Float = when (blendMode) {
        PolyRenderer.BLEND_SRC -> s
        PolyRenderer.BLEND_DST_OUT -> d * (1f - sa)
        PolyRenderer.BLEND_DST_IN -> d * sa
        PolyRenderer.BLEND_PLUS -> min(1f, s + d)
        PolyRenderer.BLEND_SRC_IN -> s * da
        PolyRenderer.BLEND_SRC_OUT -> s * (1f - da)
        PolyRenderer.BLEND_SRC_ATOP -> s * da + d * (1f - sa)
        PolyRenderer.BLEND_DST_OVER -> d + s * (1f - da)
        PolyRenderer.BLEND_DST_ATOP -> d * sa + s * (1f - da)
        PolyRenderer.BLEND_XOR -> s * (1f - da) + d * (1f - sa)
        PolyRenderer.BLEND_MODULATE -> s * d
        PolyRenderer.BLEND_SCREEN -> s + d - s * d
        else -> s + d * (1f - sa)
    }

    // Bilinear unless nearest, clamped to the edge like the GPU's samplers; packed like atlas pages, with red in the low byte
    private fun sample(u: Float, v: Float): Int {
        if (texNearest) return texel(floor(u * texW).toInt(), floor(v * texH).toInt())
        val fx = u * texW - 0.5f
        val fy = v * texH - 0.5f
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val ax = fx - x0
        val ay = fy - y0
        val c00 = texel(x0, y0); val c10 = texel(x0 + 1, y0)
        val c01 = texel(x0, y0 + 1); val c11 = texel(x0 + 1, y0 + 1)
        var out = 0
        var shift = 0
        while (shift <= 24) {
            val top = lerp(c00, c10, shift, ax)
            val bottom = lerp(c01, c11, shift, ax)
            out = out or ((top + (bottom - top) * ay + 0.5f).toInt() shl shift)
            shift += 8
        }
        return out
    }

    private fun lerp(a: Int, b: Int, shift: Int, t: Float): Float {
        val ca = a ushr shift and 0xFF
        return ca + ((b ushr shift and 0xFF) - ca) * t
    }

    private fun texel(x: Int, y: Int): Int {
        val i = y.coerceIn(0, texH - 1) * texW + x.coerceIn(0, texW - 1)
        texPage?.let { return it[i] }
        return byteTexel(i)
    }

    private fun byteTexel(i: Int): Int {
        val p = texBytes
        return ((p[i * 4 + 3].toInt() and 0xFF) shl 24) or ((p[i * 4 + 2].toInt() and 0xFF) shl 16) or
            ((p[i * 4 + 1].toInt() and 0xFF) shl 8) or (p[i * 4].toInt() and 0xFF)
    }

    private fun mix(ca: Int, cb: Int, cd: Int, wa: Float, wb: Float, wd: Float, area: Float, shift: Int) =
        (wa * (ca ushr shift and 0xFF) + wb * (cb ushr shift and 0xFF) + wd * (cd ushr shift and 0xFF)) / area

    // Pixels exactly on an edge shared by two triangles belong to the one where it is a top or left edge, like on the GPU
    private fun topLeft(dx: Float, dy: Float) = dy < 0f || dy == 0f && dx > 0f

    // Evaluated in a canonical endpoint order so both triangles sharing an edge agree exactly on which side a pixel is
    private fun edge(v: FloatArray, i: Int, j: Int, x: Float, y: Float): Float {
        val ax = v[i * 4]; val ay = v[i * 4 + 1]
        val bx = v[j * 4]; val by = v[j * 4 + 1]
        return if (ax < bx || ax == bx && ay < by) (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        else -((ax - bx) * (y - by) - (ay - by) * (x - bx))
    }
}
