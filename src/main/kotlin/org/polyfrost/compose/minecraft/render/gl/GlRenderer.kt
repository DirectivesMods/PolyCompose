package org.polyfrost.compose.minecraft.render.gl

import com.mojang.blaze3d.pipeline.RenderTarget
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL33
import org.polyfrost.compose.render.BitmapTextureCache
import org.polyfrost.compose.render.CapturingRenderer
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyTessellator
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.profile.GlGpuTimer
import org.polyfrost.compose.minecraft.profile.RenderCounters
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.minecraft.render.ColorMath
import org.polyfrost.compose.minecraft.render.EffectParams
import org.polyfrost.compose.minecraft.render.RenderTargetFbo
import org.polyfrost.compose.minecraft.render.TextureRenderer
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.IntBuffer
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

//? if = 1.21.1 {
/*import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.BufferUploader
import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexFormat
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.renderer.GameRenderer
import org.joml.Matrix4f
import org.lwjgl.system.MemoryUtil
*///?}

class GlRenderer private constructor(private val inLive: Boolean) : PolyTessellator(), TextureRenderer, CapturingRenderer {
    constructor() : this(false)

    private val batches = ArrayList<GlProgram.Batch>()

    private class Live(
        val at: Int, val state: State, val draw: (PolyRenderer) -> Unit, val key: (() -> Int)? = null, val box: FloatArray? = null,
        private val memo: Memo = Memo(),
    ) {
        class Memo {
            var key = 0
            var out: List<GlProgram.Batch>? = null
        }

        fun moved(at: Int) = Live(at, state, draw, key, box, memo)

        val stamp: Int get() = memo.key

        fun changed(since: Int): Boolean {
            val k = key ?: return true
            return memo.out == null || k() != since
        }

        fun cached(): List<GlProgram.Batch>? {
            val k = key ?: return null
            val value = k()
            val out = memo.out
            if (out != null && value == memo.key && out.all { GlTextures.alive(it.texture) }) return out
            memo.key = value
            memo.out = null
            return null
        }

        fun keep(out: List<GlProgram.Batch>) {
            if (key != null) memo.out = out
        }
    }
    private val lives = ArrayList<Live>()

    private val screenW = McWindow.viewportWidth()
    private val screenH = McWindow.viewportHeight()
    private val guiW = McWindow.guiWidth()

    var usesGameFrame = false
        private set

    override fun bitmapTexture(bitmap: PolyBitmap): Any = GlTextures.bitmap(bitmap)

    private class Captured(val batches: List<GlProgram.Batch>, val lives: List<Live>, val boxes: FloatArray, val movable: Boolean)

    private var screenReads = 0

    override fun mark(): Any {
        flush()
        return intArrayOf(batches.size, lives.size, drawBoxMark, screenReads)
    }

    override fun since(mark: Any): Any {
        flush()
        val m = mark as IntArray
        val captured = lives.subList(m[1], lives.size).map { it.moved(it.at - m[0]) }
        return Captured(ArrayList(batches.subList(m[0], batches.size)), captured, drawBoxesSince(m[2]), m[3] == screenReads && captured.isEmpty())
    }

    override fun valid(captured: Any): Boolean {
        val batches = (captured as Captured).batches
        for (i in batches.indices) if (!GlTextures.alive(batches[i].texture)) return false
        return true
    }

    override fun shiftedCapture(captured: Any, dx: Float, dy: Float): Any? {
        val c = captured as Captured
        if (!c.movable) return null
        val out = ArrayList<GlProgram.Batch>(c.batches.size)
        for (b in c.batches) {
            if (b.texture is Group || b.texture is GlGlass.Key) return null
            out += b.shifted(dx, dy)
        }
        return Captured(out, c.lives, shiftedBoxes(c.boxes, dx, dy), true)
    }

    override fun recuttable(captured: Any): Boolean {
        val c = captured as Captured
        return c.movable && c.batches.none { it.texture is Group || it.texture is GlGlass.Key }
    }

    override fun append(captured: Any) {
        flush()
        val c = captured as Captured
        if (!c.movable) screenReads++
        for (l in c.lives) lives.add(l.moved(l.at + batches.size))
        batches.addAll(c.batches)
        appendDrawBoxes(c.boxes)
    }

    override fun writeStateKey() {
        super.writeStateKey()
        keyAdd(screenW.toFloat()); keyAdd(screenH.toFloat()); keyAdd(guiW.toFloat())
    }

    fun matches(): Boolean =
        screenW == McWindow.viewportWidth() && screenH == McWindow.viewportHeight() && guiW == McWindow.guiWidth()

    fun reset() {
        GlProgram.forget(batches)
        batches.clear()
        lives.clear()
        usesGameFrame = false
        resetTessellator()
    }

    fun isEmpty(): Boolean {
        flush()
        return batches.isEmpty() && lives.isEmpty()
    }

    fun drawnBounds(out: IntArray, w: Int, h: Int): Boolean {
        flush()
        if (lives.isNotEmpty() && lives.any { it.box == null }) {
            out[0] = 0; out[1] = 0; out[2] = w; out[3] = h
            return true
        }
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (b in batches) {
            val v = b.verts
            for (i in b.colors.indices) {
                val vx = v[i * 4]; val vy = v[i * 4 + 1]
                if (vx < box[0]) box[0] = vx
                if (vy < box[1]) box[1] = vy
                if (vx > box[2]) box[2] = vx
                if (vy > box[3]) box[3] = vy
            }
        }
        for (l in lives) {
            val b = l.box ?: continue
            box[0] = min(box[0], b[0]); box[1] = min(box[1], b[1]); box[2] = max(box[2], b[2]); box[3] = max(box[3], b[3])
        }
        if (box[0] > box[2] || box[1] > box[3]) return false
        out[0] = max(floor(box[0]).toInt(), 0); out[1] = max(floor(box[1]).toInt(), 0)
        out[2] = min(ceil(box[2]).toInt(), w); out[3] = min(ceil(box[3]).toInt(), h)
        return out[2] > out[0] && out[3] > out[1]
    }

    override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {
        RenderCounters.op()
        batches.add(GlProgram.Batch(texture, premultiplied, nearest, verts, colors, blendMode))
    }

    override val layered: Boolean get() = !GlHost.vanillaPipeline

    override fun blendSupported(mode: Int): Boolean = !GlHost.vanillaPipeline && mode in PolyRenderer.BLEND_SRC_OVER..PolyRenderer.BLEND_SCREEN

    override fun layerBlendSupported(mode: Int): Boolean = blendSupported(mode)

    override fun layerMark(): Any = intArrayOf(batches.size, lives.size)

    override fun layer(mark: Any, alpha: Float): Unit = split(mark, alpha, null)

    override fun layer(mark: Any, alpha: Float, chain: PolyEffectChain, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, scale: Float): Unit =
        split(mark, alpha, GlEffects.Spec.layer(x0, y0, x1, y1, radius, scale, chain))

    private fun split(mark: Any, alpha: Float, effect: GlEffects.Spec?) {
        val m = mark as IntArray
        val sub = batches.subList(m[0], batches.size)
        val subLives = lives.subList(m[1], lives.size)
        if (sub.isEmpty() && subLives.isEmpty()) return
        val out = ArrayList<GlProgram.Batch>()
        var start = 0
        var li = 0
        for (i in 0..sub.size) {
            if (i < sub.size && sub[i].texture !is GlGlass.Key) continue
            val run = ArrayList<Live>()
            while (li < subLives.size && subLives[li].at - m[0] <= i) {
                val l = subLives[li++]
                run.add(l.moved(l.at - m[0] - start))
            }
            grouped(ArrayList(sub.subList(start, i)), run, alpha, effect)?.let { out.add(it.batch.blended(blendMode)) }
            if (i < sub.size) out.add(sub[i].faded(alpha))
            start = i + 1
        }
        sub.clear()
        subLives.clear()
        batches.addAll(out)
    }

    private fun grouped(run: List<GlProgram.Batch>, runLives: List<Live>, alpha: Float, effect: GlEffects.Spec?): Group? {
        if (run.isEmpty() && runLives.isEmpty()) return null
        val keys = IntArray(runLives.size)
        val live = if (runLives.isEmpty()) null else ({ segments(runLives).also { for (i in runLives.indices) keys[i] = runLives[i].stamp } })
        return Group.of(run, live, { anyChanged(runLives, keys) }, liveBox(runLives), alpha, screenW, screenH, effect)
    }

    private fun anyChanged(lives: List<Live>, keys: IntArray): Boolean {
        for (i in lives.indices) if (lives[i].changed(keys[i])) return true
        return false
    }

    private fun liveBox(lives: List<Live>): FloatArray? {
        if (lives.isEmpty()) return null
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (l in lives) {
            val b = l.box ?: return null
            out[0] = min(out[0], b[0]); out[1] = min(out[1], b[1]); out[2] = max(out[2], b[2]); out[3] = max(out[3], b[3])
        }
        return out
    }

    private fun extend(box: FloatArray, m: FloatArray?, px: Float, py: Float) {
        val rx = if (m == null) px else m[0] * px + m[1] * py + m[4]
        val ry = if (m == null) py else m[2] * px + m[3] * py + m[5]
        box[0] = min(box[0], rx); box[1] = min(box[1], ry); box[2] = max(box[2], rx); box[3] = max(box[3], ry)
    }

    override fun fade(mark: Any, alpha: Float): Boolean {
        if (alpha >= 1f) return true
        val m = mark as IntArray
        for (i in m[0] until batches.size) batches[i] = batches[i].faded(alpha)
        for (i in m[1] until lives.size) {
            val l = lives[i]
            lives[i] = Live(l.at, l.state.copy().also { it.alpha *= alpha }, l.draw, l.key, l.box)
        }
        return true
    }

    override fun live(draw: (PolyRenderer) -> Unit) {
        if (inLive) return draw(this)
        flush()
        beginDraw()
        touchAll()
        lives.add(Live(batches.size, state.copy(), draw))
    }

    override fun live(x: Float, y: Float, w: Float, h: Float, key: () -> Int, draw: (PolyRenderer) -> Unit) {
        if (inLive) return draw(this)
        flush()
        beginDraw()
        val s = state
        val x0 = x * s.sx + s.tx
        val x1 = (x + w) * s.sx + s.tx
        val y0 = y * s.sy + s.ty
        val y1 = (y + h) * s.sy + s.ty
        val m = s.rot
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        extend(box, m, x0, y0)
        extend(box, m, x1, y0)
        extend(box, m, x1, y1)
        extend(box, m, x0, y1)
        touch(box[0], box[1], box[2], box[3])
        lives.add(Live(batches.size, s.copy(), draw, key, box))
    }

    override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) {
        if (inLive || '§' in text) return super.mcTextLive(text, x, y, color, shadow, scale)
        flush()
        val start = batches.size
        mcText(text, x, y, -1, shadow, scale)
        flush()
        if (batches.size == start) return
        val tail = batches.subList(start, batches.size)
        val baked = ArrayList(tail)
        tail.clear()
        val base = baked.map { it.colors.copyOf() }
        val box = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (b in baked) for (i in b.colors.indices) {
            val vx = b.verts[i * 4]; val vy = b.verts[i * 4 + 1]
            box[0] = min(box[0], vx); box[1] = min(box[1], vy); box[2] = max(box[2], vx); box[3] = max(box[3], vy)
        }
        lives.add(Live(batches.size, state.copy(), { r ->
            val alpha = (r as GlRenderer).state.alpha
            val c = mulAlpha(color(), alpha)
            if (c ushr 24 != 0) {
                val sc = (c and -0x1000000) or ((c and 0xFCFCFC) shr 2)
                for (k in baked.indices) {
                    val dst = baked[k].colors
                    val src = base[k]
                    for (i in dst.indices) dst[i] = if (src[i] and 0xFFFFFF == 0xFFFFFF) c else sc
                }
                r.batches.addAll(baked)
            }
        }, color, box))
    }

    override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) {
        beginDraw()
        val a = ColorMath.alphaByte(alpha * state.alpha)
        if (a == 0) return
        begin(GlTextures.external(RenderTargetFbo.getColorTexId(target)), true)
        val x0 = x * state.sx + state.tx
        val y0 = y * state.sy + state.ty
        quadPx(x0, y0, x0 + w * state.sx, y0 + h * state.sy, u0, v0, u1, v1, (a shl 24) or (a shl 16) or (a shl 8) or a)
    }

    override fun backdropGlass(x: Float, y: Float, w: Float, h: Float, radius: Float, band: Float, refraction: Float, alpha: Float) {
        beginDraw()
        if (GlHost.vanillaPipeline) return
        val s = state
        if (s.rot != null) return
        val a = ColorMath.alphaByte(alpha * s.alpha)
        if (a == 0) return
        val x0 = EffectParams.lowEdge(x, w, s.sx, s.tx)
        val x1 = EffectParams.highEdge(x, w, s.sx, s.tx)
        val y0 = EffectParams.lowEdge(y, h, s.sy, s.ty)
        val y1 = EffectParams.highEdge(y, h, s.sy, s.ty)
        if (x1 <= x0 || y1 <= y0) return
        val scale = EffectParams.scaleOf(s.sx, s.sy)
        val bandPx = EffectParams.legacyBand(band, scale)
        val r = EffectParams.legacyRadius(radius, scale)
        val k = EffectParams.legacyStrength(refraction)
        effectQuad(GlEffects.Spec.legacy(x0, y0, x1, y1, r, bandPx, k), a)
    }

    override fun effectSupport(): Int =
        if (GlHost.vanillaPipeline) 0 else PolyRenderer.EFFECT_BEHIND or PolyRenderer.EFFECT_GAME_FRAME or PolyRenderer.EFFECT_LAYER or PolyRenderer.EFFECT_CUSTOM

    override fun backdropEffect(x: Float, y: Float, w: Float, h: Float, radius: Float, backdrop: PolyBackdrop, alpha: Float) {
        beginDraw()
        val s = state
        if (GlHost.vanillaPipeline || s.rot != null) {
            val f = backdrop.fallbackColor
            val fa = ((f ushr 24) * alpha.coerceIn(0f, 1f)).roundToInt()
            if (fa != 0) rect(x, y, w, h, (fa shl 24) or (f and 0xFFFFFF), radius)
            return
        }
        val a = ColorMath.alphaByte(alpha * s.alpha)
        if (a == 0) return
        val x0 = EffectParams.lowEdge(x, w, s.sx, s.tx)
        val x1 = EffectParams.highEdge(x, w, s.sx, s.tx)
        val y0 = EffectParams.lowEdge(y, h, s.sy, s.ty)
        val y1 = EffectParams.highEdge(y, h, s.sy, s.ty)
        if (x1 <= x0 || y1 <= y0) return
        if (backdrop.source == PolyBackdrop.SOURCE_GAME_FRAME) usesGameFrame = true
        val scale = EffectParams.scaleOf(s.sx, s.sy)
        effectQuad(GlEffects.Spec.backdrop(x0, y0, x1, y1, max(radius, 0f) * scale, s.sx, s.sy, s.tx, s.ty, backdrop), a)
    }

    private fun effectQuad(spec: GlEffects.Spec, alpha: Int) {
        begin(GlGlass.Key(spec), false)
        quadPx(spec.gx0, spec.gy0, spec.gx1, spec.gy1, 0f, 0f, 1f, 1f, (alpha shl 24) or 0xFFFFFF)
        flush()
    }

    override fun backdropSource(): BackdropSource? {
        val id = GlBlur.backdropTexture
        if (id == 0 || !GlHost.backdropFresh) return null
        screenReads++
        return BackdropSource(GlTextures.external(id), GlBlur.backdropWidth.toFloat(), GlBlur.backdropHeight.toFloat(), false)
    }

    fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean = false): Unit = draw(fbo, w, h, topDown, clear, kept, null)

    internal fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, kept: Boolean, vbo: GlProgram.Vbo?) {
        val profile = FrameProfiler.active
        if (profile == null) {
            flush()
            drawBatches(fbo, w, h, topDown, clear, batches, segments(lives), kept, false, vbo)
            return
        }
        profile.begin(ProfilePhase.Upload)
        val query = GlGpuTimer.begin(profile)
        try {
            flush()
            drawBatches(fbo, w, h, topDown, clear, batches, segments(lives), kept, false, vbo)
        } finally {
            GlGpuTimer.end(query)
            profile.end()
        }
    }

    //? if = 1.21.1 {
    /*fun drawVanilla(w: Int, h: Int, topDown: Boolean) {
        flush()
        val segments = segments(lives)
        if (batches.isNotEmpty() || segments.isNotEmpty()) VanillaProgram.draw(batches, w, h, topDown, segments)
    }
    *///?}

    fun stale(): Boolean {
        for (i in batches.indices) if (!GlTextures.alive(batches[i].texture)) return true
        return false
    }

    private fun flushPending() = flush()

    private fun replay(lives: List<Live>): List<GlProgram.Segment> = lives.map { l ->
        GlProgram.Segment(l.at, l.cached() ?: run {
            reset()
            state = l.state.copy()
            l.draw(this)
            flush()
            ArrayList(batches).also(l::keep)
        })
    }

    internal class Layer(private val coalesce: Boolean = false) {
        private var batches: List<GlProgram.Batch> = emptyList()
        private var lives: List<Live> = emptyList()
        private var raw: List<GlProgram.Batch> = emptyList()
        private var rawLives: List<Live> = emptyList()
        private var stable = 0

        fun take(renderer: GlRenderer) {
            renderer.flushPending()
            val next = renderer.batches
            val nextLives = renderer.lives
            if (coalesce && same(next, raw) && sameLives(nextLives, rawLives)) {
                idle()
                return
            }
            raw = ArrayList(next)
            rawLives = ArrayList(nextLives)
            batches = raw
            lives = rawLives
            stable = 0
        }

        val isEmpty: Boolean get() = batches.isEmpty() && lives.isEmpty()

        private fun idle() {
            if (++stable == COALESCE_FRAMES) coalesce()
            if (batches !== raw && batches.any { !GlTextures.alive(it.texture) }) {
                batches = raw
                lives = rawLives
            }
        }

        private fun same(a: List<GlProgram.Batch>, b: List<GlProgram.Batch>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) if (a[i] !== b[i]) return false
            return true
        }

        private fun sameLives(a: List<Live>, b: List<Live>): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) {
                val x = a[i]
                val y = b[i]
                if (x.at != y.at || x.state !== y.state || x.draw !== y.draw) return false
            }
            return true
        }

        private fun coalesce() {
            val out = ArrayList<GlProgram.Batch>()
            val outLives = ArrayList<Live>()
            val run = ArrayList<GlProgram.Batch>()
            fun emit() {
                if (run.size == 1) out.add(run[0]) else if (run.isNotEmpty()) out.add(GlProgram.Batch.merge(run))
                run.clear()
            }
            var li = 0
            for (i in 0..raw.size) {
                if (li < rawLives.size && rawLives[li].at == i) emit()
                while (li < rawLives.size && rawLives[li].at == i) {
                    val l = rawLives[li++]
                    outLives.add(l.moved(out.size))
                }
                if (i == raw.size) break
                val b = raw[i]
                if (run.isNotEmpty() && !run[0].sameKey(b)) emit()
                run.add(b)
            }
            emit()
            if (out.size == raw.size) return
            batches = out
            lives = outLives
        }

        companion object {
            private val all = ArrayList<GlProgram.Batch>()
            private val allLives = ArrayList<Live>()

            fun draw(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, first: Layer, second: Layer) {
                try {
                    first.appendTo(all, allLives)
                    second.appendTo(all, allLives)
                    drawBatches(fbo, w, h, topDown, clear, all, segments(allLives), false, true)
                } finally {
                    all.clear()
                    allLives.clear()
                }
            }
        }

        private fun appendTo(all: ArrayList<GlProgram.Batch>, allLives: ArrayList<Live>) {
            val offset = all.size
            for (l in lives) allLives.add(if (offset == 0) l else l.moved(l.at + offset))
            all.addAll(batches)
        }
    }

    internal class Group private constructor(
        private val batches: List<GlProgram.Batch>, private val live: (() -> List<GlProgram.Segment>)?, private val changed: () -> Boolean,
        alpha: Float, private val x: Int, private val y: Int, private val w: Int, private val h: Int, private val effect: GlEffects.Spec?,
    ) {
        private var target: IntArray? = null
        private var output: GlEffects.Target? = null
        private var rendered = false
        private var lastUsed = 0L

        val tex: Int get() = output?.tex ?: target?.get(1) ?: 0

        val restsNearest: Boolean get() = output == null && target != null

        val batch: GlProgram.Batch = run {
            val l = x + (effect?.offsetX ?: 0f)
            val t = y + (effect?.offsetY ?: 0f)
            GlProgram.Batch(
                this, true, true,
                floatArrayOf(l, t, 0f, 0f, l, t + h, 0f, 1f, l + w, t + h, 1f, 1f, l + w, t, 1f, 0f),
                IntArray(4) { ColorMath.alphaByte(alpha) * 0x01010101 },
            )
        }

        private fun stale(): Boolean =
            (live != null && changed()) || effect?.pending == true || batches.any { (it.texture as? Group)?.stale() == true }

        private fun render(composite: GlProgram.Batch, transient: ArrayList<Group>?, sink: ArrayList<Group>) {
            lastUsed = frame
            transient?.add(this)
            if (rendered && target != null && !stale()) return
            prepare(batches, transient, sink)
            val segs = live?.invoke() ?: emptyList()
            for (s in segs) prepare(s.batches, sink, sink)
            val t = target?.takeIf { it[2] >= w && it[3] >= h } ?: acquire(w, h).also { next ->
                target?.let(::recycle) ?: active.add(this)
                target = next
            }
            GlFbo.bind(GL30.GL_FRAMEBUFFER, t[0])
            GL11.glViewport(0, 0, t[2], t[3])
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            GL11.glColorMask(true, true, true, true)
            val previousClear = clearColor
            previousClear.clear()
            GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previousClear)
            GL11.glClearColor(0f, 0f, 0f, 0f)
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            GL11.glClearColor(previousClear.get(0), previousClear.get(1), previousClear.get(2), previousClear.get(3))
            if (batches.isNotEmpty() || segs.isNotEmpty()) GlProgram.draw(batches, t[2], t[3], true, segs, false, x.toFloat(), y.toFloat())
            if (effect != null) {
                val out = GlEffects.layer(effect, t[1], t[2], t[3], x, y, w, h, output)
                if (out == null) output?.let(GlEffects::recycle)
                output = out
            }
            val o = output
            val u1 = w.toFloat() / (o?.w ?: t[2])
            val v1 = h.toFloat() / (o?.h ?: t[3])
            val v = composite.verts
            if (v[10] != u1 || v[11] != v1) {
                v[7] = v1; v[10] = u1; v[11] = v1; v[14] = u1
                GlProgram.invalidate(composite)
            }
            rendered = true
        }

        fun release() {
            active.remove(this)
            target?.let(::recycle)
            target = null
            output?.let(GlEffects::recycle)
            output = null
            rendered = false
        }

        companion object {
            private const val MAX_POOLED = 4
            private const val RELEASE_FRAMES = 8
            private val pool = ArrayList<IntArray>()
            private val active = ArrayList<Group>()
            private val clearColor = BufferUtils.createFloatBuffer(16)
            private var frame = 0L

            init {
                GlFrames.ensure()
            }

            fun of(
                batches: List<GlProgram.Batch>, live: (() -> List<GlProgram.Segment>)?, changed: () -> Boolean, box: FloatArray?,
                alpha: Float, screenW: Int, screenH: Int, effect: GlEffects.Spec?,
            ): Group? {
                var l = 0; var t = 0; var r = screenW; var b = screenH
                if (live == null || box != null) {
                    var x0 = box?.get(0) ?: Float.MAX_VALUE; var y0 = box?.get(1) ?: Float.MAX_VALUE
                    var x1 = box?.get(2) ?: -Float.MAX_VALUE; var y1 = box?.get(3) ?: -Float.MAX_VALUE
                    for (batch in batches) for (i in batch.colors.indices) {
                        val vx = batch.verts[i * 4]; val vy = batch.verts[i * 4 + 1]
                        x0 = min(x0, vx); y0 = min(y0, vy); x1 = max(x1, vx); y1 = max(y1, vy)
                    }
                    if (x0 > x1) return null
                    if (effect != null) {
                        val pad = effect.pad
                        x0 = min(x0, effect.x0) - pad; y0 = min(y0, effect.y0) - pad
                        x1 = max(x1, effect.x1) + pad; y1 = max(y1, effect.y1) + pad
                    }
                    l = max(floor(x0).toInt(), 0); t = max(floor(y0).toInt(), 0)
                    r = min(ceil(x1).toInt(), screenW); b = min(ceil(y1).toInt(), screenH)
                }
                if (r <= l || b <= t) return null
                return Group(batches, live, changed, alpha, l, t, r - l, b - t, effect)
            }

            fun prepare(batches: List<GlProgram.Batch>, transient: ArrayList<Group>?, sink: ArrayList<Group>) {
                for (b in batches) (b.texture as? Group)?.render(b, transient, sink)
            }

            fun endFrame() {
                frame++
                var i = 0
                while (i < active.size) {
                    val g = active[i]
                    if (frame - g.lastUsed > RELEASE_FRAMES) g.release() else i++
                }
            }

            private fun recycle(t: IntArray) {
                if (pool.size < MAX_POOLED) pool.add(t) else delete(t)
            }

            private fun acquire(w: Int, h: Int): IntArray {
                var fit = -1
                for (i in pool.indices) {
                    val p = pool[i]
                    if (p[2] >= w && p[3] >= h && (fit < 0 || p[2] * p[3] < pool[fit][2] * pool[fit][3])) fit = i
                }
                if (fit >= 0) return pool.removeAt(fit)
                val t = pool.removeLastOrNull() ?: intArrayOf(GlFbo.gen(), GL11.glGenTextures(), 0, 0)
                t[2] = PolyTessellator.layerTargetSize(0, w); t[3] = PolyTessellator.layerTargetSize(0, h)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, t[1])
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, t[2], t[3], 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
                GlFbo.bind(GL30.GL_FRAMEBUFFER, t[0])
                GlFbo.attachColor(t[1])
                return t
            }

            private fun delete(t: IntArray) {
                GlFbo.delete(t[0])
                GL11.glDeleteTextures(t[1])
            }
        }
    }

    private companion object {
        private const val COALESCE_FRAMES = 60
        private var liveRenderer: GlRenderer? = null

        fun segments(lives: List<Live>): List<GlProgram.Segment> {
            if (lives.isEmpty()) return emptyList()
            return (liveRenderer ?: GlRenderer(true).also { liveRenderer = it }).replay(lives)
        }

        private val transientGroups = ArrayList<Group>()

        fun drawBatches(
            fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, batches: List<GlProgram.Batch>, segments: List<GlProgram.Segment>, kept: Boolean, arena: Boolean,
            vbo: GlProgram.Vbo? = null,
        ) {
            val transient = transientGroups
            transient.clear()
            Group.prepare(batches, null, transient)
            for (s in segments) Group.prepare(s.batches, transient, transient)
            GlFbo.bind(GL30.GL_FRAMEBUFFER, fbo)
            GL11.glViewport(0, 0, w, h)
            GL11.glDisable(GL11.GL_SCISSOR_TEST)
            if (clear) {
                GL11.glColorMask(true, true, true, true)
                GL11.glClearColor(0f, 0f, 0f, 0f)
                GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
            }
            if (batches.isNotEmpty() || segments.isNotEmpty()) {
                if (arena) GlProgram.drawArena(batches, w, h, topDown, segments, glass = fbo) else GlProgram.draw(batches, w, h, topDown, segments, kept, glass = fbo, target = vbo)
            }
            for (g in transient) g.release()
            transient.clear()
        }
    }
}

internal object GlFrames {
    init {
        HostEvents.onFrameEnd { end() }
    }

    fun ensure() = Unit

    private fun end() {
        GlProgram.endFrame()
        GlRenderer.Group.endFrame()
        GlTextures.endFrame()
    }
}

//? if = 1.21.1 {
/*internal object VanillaProgram {
    fun draw(batches: List<GlProgram.Batch>, w: Int, h: Int, topDown: Boolean, live: List<GlProgram.Segment>) {
        RenderSystem.backupProjectionMatrix()
        val bottom = if (topDown) 0f else h.toFloat()
        RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, w.toFloat(), bottom, h - bottom, -1f, 1f), VertexSorting.ORTHOGRAPHIC_Z)
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushMatrix()
        modelView.identity()
        RenderSystem.applyModelViewMatrix()
        RenderSystem.disableCull()
        RenderSystem.disableDepthTest()
        RenderSystem.disableScissor()
        RenderSystem.colorMask(true, true, true, true)
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader)
        try {
            var seg = 0
            for (i in 0..batches.size) {
                while (seg < live.size && live[seg].at == i) live[seg++].batches.forEach(::drawBatch)
                if (i == batches.size) break
                drawBatch(batches[i])
            }
        } finally {
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableBlend()
            RenderSystem.enableDepthTest()
            RenderSystem.enableCull()
            modelView.popMatrix()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.restoreProjectionMatrix()
        }
    }

    private fun drawBatch(b: GlProgram.Batch) {
        if (b.colors.isEmpty()) return
        RenderCounters.batch(b.colors.size)
        val src = if (b.premultiplied) GlStateManager.SourceFactor.ONE else GlStateManager.SourceFactor.SRC_ALPHA
        RenderSystem.blendFuncSeparate(src, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA)
        RenderSystem.setShaderTexture(0, GlTextures.id(b.texture))
        val buf = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR)
        for (i in b.colors.indices) {
            val o = i * 4
            buf.addVertex(b.verts[o], b.verts[o + 1], 0f).setUv(b.verts[o + 2], b.verts[o + 3]).setColor(b.colors[i])
        }
        BufferUploader.drawWithShader(buf.buildOrThrow())
    }
}
*///?}

internal object GlProgram {
    class Batch(
        val texture: Any, val premultiplied: Boolean, val nearest: Boolean, val verts: FloatArray, val colors: IntArray,
        val blend: Int = PolyRenderer.BLEND_SRC_OVER,
    ) {
        @JvmField var arenaAt = 0
        @JvmField var arenaGen = -1
        @JvmField var seen = -1L

        fun sameKey(o: Batch) = texture === o.texture && premultiplied == o.premultiplied && nearest == o.nearest && blend == o.blend

        fun blended(mode: Int): Batch = if (mode == blend) this else Batch(texture, premultiplied, nearest, verts, colors, mode)

        fun shifted(dx: Float, dy: Float): Batch {
            val v = verts.copyOf()
            for (i in colors.indices) {
                v[i * 4] += dx
                v[i * 4 + 1] += dy
            }
            return Batch(texture, premultiplied, nearest, v, colors, blend)
        }

        fun faded(alpha: Float) = Batch(texture, premultiplied, nearest, verts, blend = blend, colors = IntArray(colors.size) { i ->
            val c = colors[i]
            if (premultiplied) {
                var out = 0
                var shift = 0
                while (shift < 32) {
                    out = out or ((((c ushr shift) and 0xFF) * alpha).roundToInt().coerceIn(0, 255) shl shift)
                    shift += 8
                }
                out
            } else {
                (((c ushr 24) * alpha).roundToInt().coerceIn(0, 255) shl 24) or (c and 0xFFFFFF)
            }
        })

        companion object {
            fun merge(list: List<Batch>): Batch {
                var n = 0
                for (b in list) n += b.colors.size
                val verts = FloatArray(n * 4)
                val colors = IntArray(n)
                var o = 0
                for (b in list) {
                    System.arraycopy(b.verts, 0, verts, o * 4, b.colors.size * 4)
                    System.arraycopy(b.colors, 0, colors, o, b.colors.size)
                    o += b.colors.size
                }
                val first = list[0]
                return Batch(first.texture, first.premultiplied, first.nearest, verts, colors, first.blend)
            }
        }
    }
    class Segment(val at: Int, val batches: List<Batch>)

    private val LOG = LoggerFactory.getLogger(GlProgram::class.java)
    private const val STRIDE = 20

    private var program = 0
    private var pending: GlShaderJob? = null
    private var core = false
    private var alphaTest = false
    private var vao = 0
    class Vbo {
        var id = 0
        var uploaded: List<Batch>? = null
        var size = 0
    }
    private val retained = ArrayList<Vbo>()
    private val keptVbo = Vbo()
    private val scratchVbo = Vbo()
    private val liveVbo = Vbo()
    private val arenaVbo = Vbo()
    private var arenaUsed = 0
    private var arenaGen = 0
    private var arenaFrame = 0L
    private var arenaChecked = -1L
    private var arenaLive = 0
    private var arenaPrevLive = 0
    private val arenaAdds = ArrayList<Batch>()
    private var boundVbo = -1
    private const val MIN_ARENA = 1 shl 14
    private const val NO_GEN = Int.MIN_VALUE
    private var ibo = 0
    private var iboQuads = 0
    private var uScale = 0
    private var uOffset = 0
    private var uPremul = 0
    var scaleX = 0f
        private set
    var scaleY = 0f
        private set
    var offsetX = 0f
        private set
    var offsetY = 0f
        private set
    val frag = FloatArray(4)
    private var drawW = 0
    private var drawH = 0
    private var drawTopDown = false
    private var drawOx = 0f
    private var drawOy = 0f
    private var drawVbo = 0
    private var boundTex = -1
    private var boundPremul = -1
    private var boundBlend = PolyRenderer.BLEND_SRC_OVER
    private val blendFactors = intArrayOf(
        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
        GL11.GL_ONE, GL11.GL_ZERO,
        GL11.GL_ZERO, GL11.GL_ONE_MINUS_SRC_ALPHA,
        GL11.GL_ZERO, GL11.GL_SRC_ALPHA,
        GL11.GL_ONE, GL11.GL_ONE,
        GL11.GL_DST_ALPHA, GL11.GL_ZERO,
        GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_ZERO,
        GL11.GL_DST_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
        GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_ONE,
        GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_SRC_ALPHA,
        GL11.GL_ONE_MINUS_DST_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
        GL11.GL_ZERO, GL11.GL_SRC_COLOR,
        GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_COLOR,
    )
    private var vertexData: ByteBuffer = BufferUtils.createByteBuffer(1 shl 16)
    private var vertexInts: IntBuffer = vertexData.asIntBuffer()
    private var scratch = IntArray(vertexData.capacity() / 4)
    private val LITTLE = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN

    init {
        GlFrames.ensure()
    }

    fun endFrame() {
        arenaFrame++
        arenaPrevLive = arenaLive
        arenaLive = 0
    }

    fun warm(): Boolean {
        if (program != 0) return true
        val job = pending
        if (job == null) {
            pending = startJob()
            return false
        }
        if (!job.ready) return false
        val previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            init()
        } finally {
            GL20.glUseProgram(previous)
        }
        return true
    }

    private fun startJob(): GlShaderJob {
        core = isCoreGlsl(glslVersion())
        return GlShaderJob("GL", if (core) VS_150 else VS_120, if (core) FS_150 else FS_120, "aPos", "aUv", "aColor")
    }

    private fun init() {
        val glsl = glslVersion()
        val job = pending ?: startJob()
        pending = null
        alphaTest = hasAlphaTest()
        val p = job.finish()
        uScale = GL20.glGetUniformLocation(p, "uScale")
        uOffset = GL20.glGetUniformLocation(p, "uOffset")
        uPremul = GL20.glGetUniformLocation(p, "uPremul")
        GL20.glUseProgram(p)
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "uTex"), 0)
        keptVbo.id = GL15.glGenBuffers()
        scratchVbo.id = GL15.glGenBuffers()
        liveVbo.id = GL15.glGenBuffers()
        arenaVbo.id = GL15.glGenBuffers()
        ibo = GL15.glGenBuffers()
        if (core) vao = GL30.glGenVertexArrays()
        program = p
        LOG.info("PolyCompose GL renderer ready (GLSL {}, {})", glsl, if (core) "150" else "120")
    }

    fun glslVersion(): String = GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION) ?: "1.20"

    fun isCoreGlsl(glsl: String): Boolean =
        glsl.substringBefore(' ').split('.').let { it[0].toInt() * 100 + it.getOrElse(1) { "0" }.take(2).padEnd(2, '0').toInt() } >= 150

    fun hasAlphaTest(): Boolean = GL.getCapabilities().let { it.GL_ARB_compatibility || !it.OpenGL31 }

    fun invalidate(b: Batch) {
        b.arenaGen = -1
        b.seen = -1L
        keptVbo.uploaded = null
        scratchVbo.uploaded = null
        for (i in retained.indices) retained[i].uploaded = null
    }

    fun forget(batches: List<Batch>) {
        if (keptVbo.uploaded === batches) keptVbo.uploaded = null
        if (scratchVbo.uploaded === batches) scratchVbo.uploaded = null
        for (i in retained.indices) if (retained[i].uploaded === batches) retained[i].uploaded = null
    }

    fun retain(): Vbo = Vbo().also(retained::add)

    fun release(vbo: Vbo) {
        retained.remove(vbo)
        if (vbo.id != 0) GL15.glDeleteBuffers(vbo.id)
        vbo.id = 0
        vbo.uploaded = null
        vbo.size = 0
    }

    fun draw(
        batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment> = emptyList(), kept: Boolean = false, ox: Float = 0f, oy: Float = 0f, glass: Int = -1,
        target: Vbo? = null,
    ) {
        if (program == 0) init()
        var staticVerts = 0
        for (b in batches) staticVerts += b.colors.size
        var liveVerts = 0
        for (s in live) for (b in s.batches) liveVerts += b.colors.size

        GL20.glUseProgram(program)
        if (core) GL30.glBindVertexArray(vao)
        val vbo = target ?: if (kept) keptVbo else scratchVbo
        if (vbo.id == 0) vbo.id = GL15.glGenBuffers()
        if (staticVerts > 0 && (batches !== vbo.uploaded || staticVerts > vbo.size)) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo.id)
            vbo.size = staticVerts
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vbo.size.toLong() * STRIDE, GL15.GL_STREAM_DRAW)
            upload(0, batches)
            vbo.uploaded = batches
        }
        if (liveVerts > 0) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, liveVbo.id)
            liveVbo.size = maxOf(liveVerts, liveVbo.size)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, liveVbo.size.toLong() * STRIDE, GL15.GL_STREAM_DRAW)
            var at = 0
            for (s in live) if (s.batches.isNotEmpty()) at += upload(at, s.batches)
        }
        val first = if (staticVerts > 0 || liveVerts == 0) vbo.id else liveVbo.id
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, first)
        boundVbo = first
        drawVbo = first
        GL20.glEnableVertexAttribArray(0)
        GL20.glEnableVertexAttribArray(1)
        GL20.glEnableVertexAttribArray(2)
        attribPointers()
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo)
        ensureIndices(maxOf(staticVerts, liveVerts) / 4)
        setupState(w, h, topDown, ox, oy)
        GlGlass.begin(glass, w, h)

        try {
            var at = 0
            var liveAt = 0
            var seg = 0
            for (i in 0..batches.size) {
                while (seg < live.size && live[seg].at == i) {
                    val segBatches = live[seg++].batches
                    if (segBatches.isEmpty()) continue
                    use(liveVbo.id)
                    for (b in segBatches) { drawBatch(b, liveAt); liveAt += b.colors.size / 4 }
                }
                if (i == batches.size) break
                use(vbo.id)
                drawBatch(batches[i], at)
                at += batches[i].colors.size / 4
            }
        } finally {
            GlGlass.end()
        }
        GL20.glDisableVertexAttribArray(0)
        GL20.glDisableVertexAttribArray(1)
        GL20.glDisableVertexAttribArray(2)
        if (core) GL30.glBindVertexArray(0)
    }

    fun drawArena(batches: List<Batch>, w: Int, h: Int, topDown: Boolean, live: List<Segment>, glass: Int = -1) {
        if (program == 0) init()
        val frame = arenaFrame
        val gen = arenaGen
        var inArena = 0
        var adding = 0
        for (b in batches) {
            if (b.arenaGen == gen) inArena += b.colors.size
            else if (b.seen == frame - 1) adding += b.colors.size
        }
        val firstThisFrame = arenaChecked != frame
        arenaChecked = frame
        val garbage = firstThisFrame && arenaUsed - arenaPrevLive > maxOf(arenaPrevLive, inArena, MIN_ARENA)
        val rebuild = arenaUsed + adding > arenaVbo.size || garbage
        if (rebuild) {
            arenaGen++
            arenaLive = 0
        }
        val newGen = arenaGen
        val adds = arenaAdds
        adds.clear()
        for (b in batches) {
            if (b.arenaGen == newGen) continue
            if (b.arenaGen == gen || b.seen == frame - 1) {
                b.arenaGen = newGen
                adds.add(b)
            }
        }
        var scratchVerts = 0
        for (b in batches) {
            if (b.arenaGen != newGen) scratchVerts += b.colors.size
            else if (b.seen != frame) arenaLive += b.colors.size
            b.seen = frame
        }

        GL20.glUseProgram(program)
        if (core) GL30.glBindVertexArray(vao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arenaVbo.id)
        if (rebuild) {
            var need = 0
            for (b in adds) need += b.colors.size
            val others = if (firstThisFrame) 0 else arenaPrevLive
            arenaVbo.size = maxOf(Integer.highestOneBit(maxOf(need + others, 1)) shl 2, MIN_ARENA)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, arenaVbo.size.toLong() * STRIDE, GL15.GL_DYNAMIC_DRAW)
            arenaUsed = 0
        }
        if (adds.isNotEmpty()) {
            var at = arenaUsed
            for (b in adds) { b.arenaAt = at; at += b.colors.size }
            upload(arenaUsed, adds)
            arenaUsed = at
            adds.clear()
        }

        var liveVerts = 0
        for (seg in live) for (b in seg.batches) liveVerts += b.colors.size
        val scratchSize = scratchVerts + liveVerts
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, scratchVbo.id)
        if (scratchSize > 0) {
            scratchVbo.size = maxOf(scratchSize, scratchVbo.size)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, scratchVbo.size.toLong() * STRIDE, GL15.GL_STREAM_DRAW)
            scratchVbo.uploaded = null
            var at = if (scratchVerts > 0) upload(0, batches, newGen) else 0
            for (seg in live) if (seg.batches.isNotEmpty()) at += upload(at, seg.batches)
        }

        GL20.glEnableVertexAttribArray(0)
        GL20.glEnableVertexAttribArray(1)
        GL20.glEnableVertexAttribArray(2)
        GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, ibo)
        ensureIndices(maxOf(arenaVbo.size, scratchVbo.size) / 4)
        setupState(w, h, topDown)
        GlGlass.begin(glass, w, h)

        try {
            boundVbo = -1
            var scratchFirst = 0
            var liveFirst = scratchVerts / 4
            var seg = 0
            for (i in 0..batches.size) {
                while (seg < live.size && live[seg].at == i) {
                    use(scratchVbo.id)
                    for (b in live[seg++].batches) { drawBatch(b, liveFirst); liveFirst += b.colors.size / 4 }
                }
                if (i == batches.size) break
                val b = batches[i]
                if (b.arenaGen == newGen) {
                    use(arenaVbo.id)
                    drawBatch(b, b.arenaAt / 4)
                } else {
                    use(scratchVbo.id)
                    drawBatch(b, scratchFirst)
                    scratchFirst += b.colors.size / 4
                }
            }
        } finally {
            GlGlass.end()
        }
        GL20.glDisableVertexAttribArray(0)
        GL20.glDisableVertexAttribArray(1)
        GL20.glDisableVertexAttribArray(2)
        if (core) GL30.glBindVertexArray(0)
    }

    private fun use(id: Int) {
        if (boundVbo == id) return
        boundVbo = id
        drawVbo = id
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, id)
        attribPointers()
    }

    private fun attribPointers() {
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, STRIDE, 0L)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, STRIDE, 8L)
        GL20.glVertexAttribPointer(2, 4, GL11.GL_UNSIGNED_BYTE, true, STRIDE, 16L)
    }

    fun rebind(fbo: Int) {
        GlFbo.bind(GL30.GL_FRAMEBUFFER, fbo)
        GL11.glViewport(0, 0, drawW, drawH)
        GL20.glUseProgram(program)
        if (core) {
            GL30.glBindVertexArray(vao)
        } else {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, drawVbo)
            GL20.glEnableVertexAttribArray(0)
            GL20.glEnableVertexAttribArray(1)
            GL20.glEnableVertexAttribArray(2)
            attribPointers()
        }
        setupState(drawW, drawH, drawTopDown, drawOx, drawOy)
    }

    private fun setupState(w: Int, h: Int, topDown: Boolean, ox: Float = 0f, oy: Float = 0f) {
        drawW = w
        drawH = h
        drawTopDown = topDown
        drawOx = ox
        drawOy = oy
        frag[0] = ox
        frag[1] = if (topDown) oy else h + oy
        frag[2] = 1f
        frag[3] = if (topDown) 1f else -1f
        scaleX = 2f / w
        scaleY = if (topDown) 2f / h else -2f / h
        offsetX = -1f - ox * 2f / w
        offsetY = if (topDown) -1f - oy * 2f / h else 1f + oy * 2f / h
        GL20.glUniform2f(uScale, scaleX, scaleY)
        GL20.glUniform2f(uOffset, offsetX, offsetY)
        GL11.glDisable(GL11.GL_CULL_FACE)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        if (alphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glColorMask(true, true, true, true)
        GL11.glEnable(GL11.GL_BLEND)
        GL14.glBlendEquation(GL14.GL_FUNC_ADD)
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        boundTex = -1
        boundPremul = -1
        boundBlend = PolyRenderer.BLEND_SRC_OVER
    }

    private fun drawBatch(b: Batch, first: Int) {
        RenderCounters.batch(b.colors.size)
        val key = b.texture
        if (key is GlGlass.Key) {
            boundTex = -1
            if (GlEffects.prepare(key.spec)) {
                GL11.glDrawElements(GL11.GL_TRIANGLES, b.colors.size / 4 * 6, GL11.GL_UNSIGNED_INT, first * 6L * 4L)
                GlEffects.finish()
            }
            GL20.glUseProgram(program)
            boundBlend = -1
            return
        }
        if (b.blend != boundBlend) {
            val s = blendFactors[b.blend * 2]
            val d = blendFactors[b.blend * 2 + 1]
            GL14.glBlendFuncSeparate(s, d, s, d)
            boundBlend = b.blend
        }
        val tex = GlTextures.id(b.texture)
        if (tex != boundTex) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
            boundTex = tex
        }
        val premul = if (b.premultiplied) 1 else 0
        if (premul != boundPremul) {
            GL20.glUniform1i(uPremul, premul)
            boundPremul = premul
        }
        val swap = !GlTextures.filter(key, b.nearest) && b.nearest
        if (swap) filter(GL11.GL_NEAREST)
        GL11.glDrawElements(GL11.GL_TRIANGLES, b.colors.size / 4 * 6, GL11.GL_UNSIGNED_INT, first * 6L * 4L)
        if (swap) filter(GL11.GL_LINEAR)
    }

    private fun filter(mode: Int) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mode)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mode)
    }

    private fun upload(at: Int, batches: List<Batch>, skipGen: Int = NO_GEN): Int {
        var vertices = 0
        for (b in batches) if (b.arenaGen != skipGen) vertices += b.colors.size
        val ints = vertices * (STRIDE / 4)
        if (vertexData.capacity() < vertices * STRIDE) {
            vertexData = BufferUtils.createByteBuffer(Integer.highestOneBit(vertices * STRIDE) shl 1)
            vertexInts = vertexData.asIntBuffer()
            scratch = IntArray(vertexData.capacity() / 4)
        }
        val out = scratch
        var n = 0
        for (b in batches) {
            if (b.arenaGen == skipGen) continue
            val v = b.verts
            val cs = b.colors
            for (i in cs.indices) {
                val o = i * 4
                out[n] = v[o].toRawBits()
                out[n + 1] = v[o + 1].toRawBits()
                out[n + 2] = v[o + 2].toRawBits()
                out[n + 3] = v[o + 3].toRawBits()
                val c = cs[i]
                out[n + 4] = if (LITTLE) (c and 0xFF00FF00.toInt()) or ((c shr 16) and 0xFF) or ((c and 0xFF) shl 16) else (c shl 8) or (c ushr 24)
                n += 5
            }
        }
        vertexInts.clear()
        vertexInts.put(out, 0, ints)
        val buf = vertexData
        buf.clear()
        buf.limit(vertices * STRIDE)
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, at.toLong() * STRIDE, buf)
        return vertices
    }

    private fun ensureIndices(quads: Int) {
        if (quads <= iboQuads) return
        val n = Integer.highestOneBit(quads.coerceAtLeast(256)) shl 1
        val idx = BufferUtils.createIntBuffer(n * 6)
        for (q in 0 until n) {
            val v = q * 4
            idx.put(v).put(v + 1).put(v + 2).put(v).put(v + 2).put(v + 3)
        }
        idx.flip()
        GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, idx, GL15.GL_STATIC_DRAW)
        iboQuads = n
    }

    const val VS_150 = """#version 150
in vec2 aPos; in vec2 aUv; in vec4 aColor;
uniform vec2 uScale; uniform vec2 uOffset;
out vec2 vUv; out vec4 vColor;
void main() { vUv = aUv; vColor = aColor; gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }
"""
    private const val FS_150 = """#version 150
in vec2 vUv; in vec4 vColor;
uniform sampler2D uTex; uniform int uPremul;
out vec4 fragColor;
void main() { vec4 c = texture(uTex, vUv) * vColor; fragColor = uPremul == 1 ? c : vec4(c.rgb * c.a, c.a); }
"""
    const val VS_120 = """#version 120
attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor;
uniform vec2 uScale; uniform vec2 uOffset;
varying vec2 vUv; varying vec4 vColor;
void main() { vUv = aUv; vColor = aColor; gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0); }
"""
    private const val FS_120 = """#version 120
varying vec2 vUv; varying vec4 vColor;
uniform sampler2D uTex; uniform int uPremul;
void main() { vec4 c = texture2D(uTex, vUv) * vColor; gl_FragColor = uPremul == 1 ? c : vec4(c.rgb * c.a, c.a); }
"""
}

internal object GlTextures {
    class Tex(val bitmap: PolyBitmap?, var id: Int) {
        var filter = GL11.GL_LINEAR
        internal var stale = false
    }

    private class PageTex(val id: Int, var lastUsed: Long, var mask: Boolean) {
        var version = 0
        var filter = GL11.GL_LINEAR
    }

    private val bitmaps = BitmapTextureCache<Tex>(RELEASE_FRAMES)
    private val external = HashMap<Int, Tex>()
    private val pages = IdentityHashMap<PolyGlyphPage.Page, PageTex>()
    private var uploadBytes: ByteBuffer? = null

    private val maskFormat: Int by lazy {
        val caps = GL.getCapabilities()
        when {
            GlHost.isVulkanModInstalled -> MASK_NONE
            caps.GL_ARB_compatibility || !caps.OpenGL31 -> MASK_LUMINANCE
            caps.OpenGL33 || caps.GL_ARB_texture_swizzle -> MASK_SWIZZLE
            else -> MASK_NONE
        }
    }
    private var frame = 0L
    private var scanAt = 0L

    init {
        GlFrames.ensure()
    }

    fun filter(key: Any, nearest: Boolean): Boolean {
        val mode = if (nearest) GL11.GL_NEAREST else GL11.GL_LINEAR
        when (key) {
            is GlRenderer.Group -> return nearest && key.restsNearest
            is Tex -> {
                val tex = current(key, key.bitmap ?: return false)
                if (tex.filter != mode) {
                    tex.filter = mode
                    applyFilter(mode)
                }
                return true
            }
            is PolyGlyphPage.Page -> {
                val tex = pages[key] ?: return false
                if (tex.filter != mode) {
                    tex.filter = mode
                    applyFilter(mode)
                }
                return true
            }
            else -> return false
        }
    }

    private fun applyFilter(mode: Int) {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, mode)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, mode)
    }

    fun bitmap(bitmap: PolyBitmap): Tex = bitmaps.get(bitmap)?.texture ?: adopt(bitmap) ?: Tex(bitmap, 0).also { bitmaps.add(bitmap, it) }

    private fun adopt(bitmap: PolyBitmap): Tex? {
        val e = bitmaps.adopt(bitmap) ?: return null
        val old = e.texture
        val tex = Tex(bitmap, old.id)
        tex.filter = old.filter
        tex.stale = old.id != 0
        old.id = 0
        old.stale = false
        e.texture = tex
        return tex
    }

    private fun current(key: Tex, bitmap: PolyBitmap): Tex {
        bitmaps.get(bitmap)?.let { return it.texture }
        key.id = 0
        key.stale = false
        bitmaps.add(bitmap, key)
        return key
    }

    fun external(id: Int): Tex = external.getOrPut(id) { Tex(null, id) }

    fun alive(key: Any): Boolean = key !is Tex || key.bitmap.let { it == null || bitmaps.peek(it)?.texture === key }

    fun id(key: Any): Int = when (key) {
        is PolyGlyphPage.Page -> glyphPage(key)
        is GlRenderer.Group -> key.tex
        is Tex -> key.bitmap?.let { textureId(current(key, it), it) } ?: key.id
        else -> 0
    }

    private fun textureId(tex: Tex, bitmap: PolyBitmap): Int {
        if (tex.id == 0) tex.id = upload(bitmap)
        else if (tex.stale) reupload(tex.id, bitmap)
        tex.stale = false
        return tex.id
    }

    private fun glyphPage(page: PolyGlyphPage.Page): Int {
        val tex = pages.getOrPut(page) {
            scanAt = min(scanAt, frame + RELEASE_FRAMES + 1)
            PageTex(newTexture(), frame, maskFormat != MASK_NONE && !page.hasColor).also { if (it.mask) maskSwizzle(true) }
        }
        tex.lastUsed = frame
        if (tex.mask && page.hasColor) {
            tex.mask = false
            tex.version = 0
            bind(tex.id)
            maskSwizzle(false)
        }
        if (tex.version != page.version) {
            val size = PolyGlyphPage.SIZE
            val rows = if (tex.version == 0) 0 until size else page.dirtyRows(tex.version)
            val h = rows.last - rows.first + 1
            tex.version = page.version
            bind(tex.id)
            if (tex.mask) {
                uploadMask(page, rows.first, h)
            } else {
                val px = scratchBytes(h * size * 4).asIntBuffer()
                px.put(page.pixels, rows.first * size, h * size).flip()
                unpacked(size) { if (h == size) texImage(size, size, px) else texSubImage(rows.first, size, h, px) }
            }
        }
        return tex.id
    }

    private fun uploadMask(page: PolyGlyphPage.Page, first: Int, h: Int) {
        val size = PolyGlyphPage.SIZE
        val n = h * size
        val buf = scratchBytes(n * 2)
        val px = page.pixels
        val start = first * size
        for (i in 0 until n) {
            val p = px[start + i]
            buf.put(i * 2, (p ushr 16).toByte())
            buf.put(i * 2 + 1, (p ushr 24).toByte())
        }
        buf.limit(n * 2)
        val format = if (maskFormat == MASK_SWIZZLE) GL30.GL_RG else GL11.GL_LUMINANCE_ALPHA
        RenderCounters.upload(n * 2L)
        unpacked(size) {
            if (h == size) {
                val internal = if (maskFormat == MASK_SWIZZLE) GL30.GL_RG8 else GL11.GL_LUMINANCE8_ALPHA8
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, internal, size, size, 0, format, GL11.GL_UNSIGNED_BYTE, buf)
            } else {
                GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, first, size, h, format, GL11.GL_UNSIGNED_BYTE, buf)
            }
        }
    }

    private fun maskSwizzle(mask: Boolean) {
        if (maskFormat != MASK_SWIZZLE) return
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_R, GL11.GL_RED)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_G, if (mask) GL11.GL_RED else GL11.GL_GREEN)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_B, if (mask) GL11.GL_RED else GL11.GL_BLUE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL33.GL_TEXTURE_SWIZZLE_A, if (mask) GL11.GL_GREEN else GL11.GL_ALPHA)
    }

    private fun reupload(id: Int, bitmap: PolyBitmap) {
        bind(id)
        val px = scratchBytes(bitmap.pixels.size).put(bitmap.pixels)
        px.flip()
        unpacked(bitmap.width) { texSubImage(0, bitmap.width, bitmap.height, px.asIntBuffer()) }
    }

    private fun upload(bitmap: PolyBitmap): Int {
        val id = newTexture()
        val px = scratchBytes(bitmap.pixels.size).put(bitmap.pixels)
        px.flip()
        unpacked(bitmap.width) { texImage(bitmap.width, bitmap.height, px.asIntBuffer()) }
        return id
    }

    private fun scratchBytes(size: Int): ByteBuffer {
        if (size > MAX_SCRATCH_BYTES) return BufferUtils.createByteBuffer(size)
        val buf = uploadBytes?.takeIf { it.capacity() >= size }
            ?: BufferUtils.createByteBuffer(min(max(Integer.highestOneBit(size) shl 1, MIN_SCRATCH_BYTES), MAX_SCRATCH_BYTES)).also { uploadBytes = it }
        buf.clear()
        return buf
    }

    private inline fun unpacked(rowLength: Int, upload: () -> Unit) {
        val alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT)
        val previousRowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH)
        val skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS)
        val skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS)
        try {
            //? if = 1.21.1
            //if (GlHost.vanillaPipeline) return run { unpackDefaults(rowLength); upload() }
            unpackDefaults(rowLength)
            upload()
        } finally {
            pixelStore(GL11.GL_UNPACK_ALIGNMENT, alignment)
            pixelStore(GL11.GL_UNPACK_ROW_LENGTH, previousRowLength)
            pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels)
            pixelStore(GL11.GL_UNPACK_SKIP_ROWS, skipRows)
        }
    }

    private fun unpackDefaults(rowLength: Int) {
        pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4)
        // VulkanMod sizes uploads by the row length, so 0 (tightly packed) uploads nothing there
        pixelStore(GL11.GL_UNPACK_ROW_LENGTH, rowLength)
        pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0)
        pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0)
    }

    private fun newTexture(): Int {
        val id = genTexture()
        bind(id)
        texParameter(GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        texParameter(GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        texParameter(GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        texParameter(GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        return id
    }

    private fun genTexture(): Int {
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._genTexture()
        return GL11.glGenTextures()
    }

    private fun bind(id: Int) {
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._bindTexture(id)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id)
    }

    private fun texParameter(name: Int, value: Int) {
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._texParameter(GL11.GL_TEXTURE_2D, name, value)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, name, value)
    }

    private fun pixelStore(name: Int, value: Int) {
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._pixelStore(name, value)
        GL11.glPixelStorei(name, value)
    }

    private fun texImage(w: Int, h: Int, px: IntBuffer) {
        RenderCounters.upload(w.toLong() * h * 4)
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px)
    }

    private fun texSubImage(y: Int, w: Int, h: Int, px: IntBuffer) {
        RenderCounters.upload(w.toLong() * h * 4)
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._texSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, MemoryUtil.memAddress(px))
        GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px)
    }

    private fun deleteTexture(id: Int) {
        //? if = 1.21.1
        //if (GlHost.vanillaPipeline) return GlStateManager._deleteTexture(id)
        GL11.glDeleteTextures(id)
    }

    fun endFrame() {
        frame++
        bitmaps.endFrame(::release)
        if (external.size > 64) external.clear()
        if (frame < scanAt) return
        var next = Long.MAX_VALUE
        val pit = pages.entries.iterator()
        while (pit.hasNext()) {
            val (page, t) = pit.next()
            if (!page.retired) next = min(next, frame + RELEASE_FRAMES + 1)
            else if (frame - t.lastUsed > RELEASE_FRAMES) {
                deleteTexture(t.id)
                pit.remove()
            } else next = min(next, t.lastUsed + RELEASE_FRAMES + 1)
        }
        scanAt = next
    }

    private fun release(tex: Tex) {
        if (tex.id != 0) deleteTexture(tex.id)
        tex.id = 0
        tex.stale = false
    }

    private const val RELEASE_FRAMES = 600L
    private const val MIN_SCRATCH_BYTES = 1 shl 16
    private const val MAX_SCRATCH_BYTES = 1 shl 22
    private const val MASK_NONE = 0
    private const val MASK_LUMINANCE = 1
    private const val MASK_SWIZZLE = 2
}
