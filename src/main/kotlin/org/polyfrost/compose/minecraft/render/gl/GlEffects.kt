package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL30
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.minecraft.profile.RenderCounters
import org.polyfrost.compose.minecraft.render.ColorMath
import org.polyfrost.compose.minecraft.render.EffectParams
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyEffectShader
import org.polyfrost.compose.render.PolyLiquidSamples
import org.polyfrost.compose.render.PolyTessellator
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object GlEffects {
    private val LOG = LoggerFactory.getLogger(GlEffects::class.java)

    class Liquid(
        val a: FloatArray, val b: FloatArray, val rects: FloatArray, val radii: FloatArray, val glow: FloatArray, val c: FloatArray,
        val clear: Boolean,
        val sampleId: Int,
    ) {
        companion object {
            fun of(packed: FloatArray, clear: Boolean, sampleId: Int): Liquid = Liquid(
                packed.copyOfRange(0, 4), packed.copyOfRange(4, 8),
                packed.copyOfRange(EffectParams.LIQUID_RECTS, EffectParams.LIQUID_RADII),
                packed.copyOfRange(EffectParams.LIQUID_RADII, EffectParams.LIQUID_GLOW),
                packed.copyOfRange(EffectParams.LIQUID_GLOW, EffectParams.LIQUID_PARAMS),
                packed.copyOfRange(EffectParams.LIQUID_PARAMS, EffectParams.LIQUID_FLOATS),
                clear, sampleId,
            )
        }
    }

    class Spec private constructor(
        val x0: Float, val y0: Float, val x1: Float, val y1: Float,
        val radius: Float,
        val scale: Float,
        val source: Int,
        val downscale: Int,
        val sample: Boolean,
        val stages: List<PolyEffect>,
        val frost: Float,
        val band: Float, val strength: Float, val dispersion: Float,
        val matrix: FloatArray, val matrixOffset: FloatArray,
        val highlightColor: FloatArray, val highlight: FloatArray,
        val noise: FloatArray,
        val fallback: FloatArray,
        val liquid: Liquid?,
        val pad: Float,
        val offsetX: Float, val offsetY: Float,
        val clampFirst: Boolean,
        val bleed: Boolean,
        val passless: Boolean,
    ) {
        val sampleKey: BackdropSample.Region? = BackdropSample.current

        private var settled = false

        init {
            precompile(stages)
        }

        val pending: Boolean
            get() {
                if (settled) return false
                for (i in stages.indices) {
                    val e = stages[i]
                    if (e is PolyEffect.Custom && !customs.containsKey(e.shader.key)) return true
                }
                settled = true
                return false
            }

        fun settle() {
            settled = true
        }

        val hasFallback: Boolean get() = fallback[3] > 0f
        val gx0: Float get() = if (liquid != null) x0 - pad else x0
        val gy0: Float get() = if (liquid != null) y0 - pad else y0
        val gx1: Float get() = if (liquid != null) x1 + pad else x1
        val gy1: Float get() = if (liquid != null) y1 + pad else y1

        companion object {
            fun backdrop(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, sx: Float, sy: Float, tx: Float, ty: Float, b: PolyBackdrop): Spec {
                val scale = EffectParams.scaleOf(sx, sy)
                val chain = b.chain
                var stages = chain.textureStages
                var frost = 0f
                val refract = chain.refract
                val last = stages.lastOrNull()
                if (refract != null && chain.liquid == null && last is PolyEffect.Blur && last.radius * scale <= INLINE_FROST) {
                    frost = sigma(last.radius * scale) * FROST_PER_SIGMA
                    stages = stages.subList(0, stages.size - 1)
                }
                val l = chain.liquid
                val ds = when {
                    b.downscale >= 4 -> 4
                    b.downscale >= 2 -> 2
                    else -> 1
                }
                return Spec(
                    x0, y0, x1, y1, if (l != null) 0f else radius, scale, b.source, ds, b.sample || (l != null && !l.clear), stages, frost,
                    (refract?.band ?: 0f) * scale, refract?.strength ?: 0f, refract?.dispersion ?: 0f,
                    matrixOf(chain), offsetOf(chain), highlightColorOf(chain), highlightOf(chain, scale), noiseOf(chain, scale),
                    premultiplied(b.fallbackColor), l?.let { liquidOf(it, sx, sy, tx, ty, scale) },
                    max(max(b.padding, if (l != null) chain.outset else 0f), 0f) * scale + frost,
                    0f, 0f, clampFirst = true, bleed = false, passless = false,
                )
            }

            fun legacy(x0: Float, y0: Float, x1: Float, y1: Float, radius: Int, band: Int, strength: Int): Spec = Spec(
                x0, y0, x1, y1, radius.toFloat(), 1f, PolyBackdrop.SOURCE_BEHIND, 1, true, emptyList(), EffectParams.LEGACY_FROST,
                band.toFloat(), strength / 255f, EffectParams.LEGACY_DISPERSION,
                IDENTITY, ZERO, ZERO, ZERO, ZERO, ZERO, null, EffectParams.legacyPad(band, strength),
                0f, 0f, clampFirst = true, bleed = false, passless = false,
            )

            fun layer(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, scale: Float, chain: PolyEffectChain): Spec {
                val refract = chain.refract
                val highlight = chain.highlight
                val firstBlur = chain.textureStages.firstOrNull() as? PolyEffect.Blur
                val clampFirst = firstBlur?.clampEdges != false
                val passless = chain.textureStages.isEmpty() && refract == null && highlight == null && chain.colorMatrix == null && chain.noise == null
                return Spec(
                    x0, y0, x1, y1, radius, scale, -1, 1, false, chain.textureStages, 0f,
                    (refract?.band ?: 0f) * scale, refract?.strength ?: 0f, refract?.dispersion ?: 0f,
                    matrixOf(chain), offsetOf(chain), highlightColorOf(chain), highlightOf(chain, scale), noiseOf(chain, scale),
                    ZERO, null, chain.outset * scale, chain.offsetX * scale, chain.offsetY * scale,
                    clampFirst, bleed = !clampFirst && refract == null && highlight == null && radius <= 0f, passless = passless,
                )
            }

            private fun matrixOf(chain: PolyEffectChain): FloatArray {
                val m = chain.colorMatrix ?: return IDENTITY
                return FloatArray(16).also { EffectParams.matrixInto(m, it, 0) }
            }

            private fun offsetOf(chain: PolyEffectChain): FloatArray {
                val m = chain.colorMatrix ?: return ZERO
                return FloatArray(4).also { EffectParams.matrixOffsetInto(m, it, 0) }
            }

            private fun highlightColorOf(chain: PolyEffectChain): FloatArray = chain.highlight?.let { premultiplied(it.color) } ?: ZERO

            private fun highlightOf(chain: PolyEffectChain, scale: Float): FloatArray {
                val h = chain.highlight ?: return ZERO
                return FloatArray(4).also { EffectParams.highlightInto(h, scale, it, 0) }
            }

            private fun noiseOf(chain: PolyEffectChain, scale: Float): FloatArray {
                val n = chain.noise ?: return ZERO
                return FloatArray(4).also { EffectParams.noiseInto(n, scale, it, 0) }
            }

            private fun liquidOf(l: PolyEffect.Liquid, sx: Float, sy: Float, tx: Float, ty: Float, scale: Float): Liquid =
                Liquid.of(EffectParams.packLiquid(l, sx, sy, tx, ty, scale), l.clear, l.sampleId)

            private fun premultiplied(argb: Int): FloatArray {
                if (argb ushr 24 == 0) return ZERO
                return FloatArray(4).also { ColorMath.premultiplyInto(argb, it, 0) }
            }
        }
    }

    class Target(val fbo: Int, val tex: Int, val w: Int, val h: Int)

    private class Src(
        val tex: Int, val fbo: Int, val allocW: Int, val allocH: Int, val usedW: Int, val usedH: Int,
        val ox: Float, val oy: Float, val sx: Float, val sy: Float, val owned: Target?,
    )

    private class Prog(val id: Int) {
        val scale = loc("uScale")
        val offset = loc("uOffset")
        val rect = loc("peRect")
        val shape = loc("peShape")
        val source = loc("peSource")
        val mode = loc("peMode")
        val matrix = loc("peMatrix")
        val matrixOffset = loc("peMatrixOffset")
        val highlightColor = loc("peHighlightColor")
        val highlight = loc("peHighlight")
        val noise = loc("peNoise")
        val fallback = loc("peFallback")
        val params = loc("peParams")
        val liquidA = loc("peLiquidA")
        val liquidB = loc("peLiquidB")
        val liquidRect = loc("peLiquidRect")
        val liquidRadius = loc("peLiquidRadius")
        val liquidGlow = loc("peLiquidGlow")
        val liquidC = loc("peLiquidC")
        val frag = loc("peHostFrag")
        val clamp = loc("peHostClamp")
        val bounds = loc("peHostBounds")
        val decal = loc("peHostDecal")
        val step = loc("peHostStep")

        private fun loc(name: String) = GL20.glGetUniformLocation(id, name)
    }

    private const val INLINE_FROST = 4f
    private const val FROST_PER_SIGMA = 1.5625f
    private const val MAX_POOLED = 4
    private const val INCLUDE = "/assets/polycompose/shaders/include/poly_effect.glsl"
    private const val LIQUID_INCLUDE = "/assets/polycompose/shaders/include/poly_liquid.glsl"

    private val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
    private val ZERO = FloatArray(4)
    private val NO_PARAMS = FloatArray(PolyEffectShader.MAX_PARAMS)
    private val paramScratch = FloatArray(PolyEffectShader.MAX_PARAMS)

    private var core = false
    private var failed = false
    private var main: Prog? = null
    private var mainJob: GlShaderJob? = null
    private var mainJobLiquid = false
    private var layerComposite: Prog? = null
    private var blurPass: Prog? = null
    private var layerJob: GlShaderJob? = null
    private var blurJob: GlShaderJob? = null
    private val customs = HashMap<String, Prog?>()
    private val customJobs = HashMap<String, GlShaderJob>()
    private var liquid = false
    private val alphaTest: Boolean by lazy(LazyThreadSafetyMode.NONE) { GlProgram.hasAlphaTest() }
    private var vao = 0
    private var vbo = 0
    private val pool = ArrayList<Target>()
    private val pending = ArrayList<Target>()

    var gameFrameDemand = false
        private set

    fun takeGameFrameDemand(): Boolean = gameFrameDemand.also { gameFrameDemand = false }

    private val include: String? by lazy { resource(INCLUDE) }
    private val liquidInclude: String? by lazy { resource(LIQUID_INCLUDE) }

    private fun resource(path: String): String? = GlEffects::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }

    private fun sigma(radius: Float): Float = EffectParams.SIGMA_SCALE * radius + EffectParams.SIGMA_BIAS

    fun warm(): Boolean {
        if (main != null || failed) return true
        val job = mainJob
        if (job == null) {
            startMain()
        } else if (job.ready) {
            advanceMain()
        }
        return main != null || failed
    }

    fun warmPasses(): Boolean {
        if (failed) return true
        if (main == null) return false
        val vs = if (core) GlBlur.VS_150 else GlBlur.VS_120
        if (blurPass == null && blurJob == null) blurJob = begin(vs, fragment(BLUR_PASS, mainDraw = false, liquid = false, custom = null), "blur")
        if (layerComposite == null && layerJob == null) {
            layerJob = begin(vs, fragment(LAYER_PASS, mainDraw = false, liquid = false, custom = null), "effect layer")
        }
        blurJob?.takeIf { it.ready }?.let {
            blurJob = null
            blurPass = complete(it, "blur")
        }
        layerJob?.takeIf { it.ready }?.let {
            layerJob = null
            layerComposite = complete(it, "effect layer")
        }
        return blurJob == null && layerJob == null
    }

    fun release() {
        for (t in pool) delete(t)
        pool.clear()
    }

    private fun mainProgram(): Prog? {
        main?.let { return it }
        if (failed) return null
        if (mainJob == null) startMain()
        while (main == null && !failed) advanceMain()
        return main
    }

    private fun startMain() {
        core = GlProgram.isCoreGlsl(GlProgram.glslVersion())
        startMainVariant(liquidInclude != null)
    }

    private fun startMainVariant(withLiquid: Boolean) {
        val vs = if (core) GlProgram.VS_150 else GlProgram.VS_120
        mainJobLiquid = withLiquid
        mainJob = begin(vs, fragment(MAIN_DRAW, mainDraw = true, liquid = withLiquid, custom = null), mainName(withLiquid))
        if (mainJob != null) return
        if (withLiquid) startMainVariant(false) else failed = true
    }

    private fun advanceMain() {
        val job = mainJob ?: return
        val withLiquid = mainJobLiquid
        mainJob = null
        val p = complete(job, mainName(withLiquid))
        if (p != null) {
            liquid = liquidInclude != null && GL20.glGetUniformLocation(p.id, "peLiquidA") >= 0
            main = p
            return
        }
        if (withLiquid) startMainVariant(false) else failed = true
    }

    private fun mainName(withLiquid: Boolean) = if (withLiquid) "liquid glass" else "backdrop"

    private fun passProgram(slot: Int): Prog? {
        if (mainProgram() == null) return null
        if (vbo == 0) {
            if (core) vao = GL30.glGenVertexArrays()
            vbo = GL15.glGenBuffers()
            if (core) GL30.glBindVertexArray(vao)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, BufferUtils.createFloatBuffer(6).put(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)).flip(), GL15.GL_STATIC_DRAW)
            if (core) {
                GL20.glEnableVertexAttribArray(0)
                GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L)
            }
        }
        val vs = if (core) GlBlur.VS_150 else GlBlur.VS_120
        return if (slot == 0) {
            blurPass ?: (blurJob?.let { blurJob = null; complete(it, "blur") } ?: link(vs, fragment(BLUR_PASS, mainDraw = false, liquid = false, custom = null), "blur"))
                ?.also { blurPass = it }
        } else {
            layerComposite ?: (layerJob?.let { layerJob = null; complete(it, "effect layer") }
                ?: link(vs, fragment(LAYER_PASS, mainDraw = false, liquid = false, custom = null), "effect layer"))
                ?.also { layerComposite = it }
        }
    }

    private fun customProgram(shader: PolyEffectShader): Prog? {
        if (customs.containsKey(shader.key)) return customs[shader.key]
        val job = customJobs[shader.key] ?: startCustom(shader)
        if (job == null) {
            customs[shader.key] = null
            return null
        }
        if (!job.ready) return null
        customJobs.remove(shader.key)
        val p = complete(job, "custom effect '${shader.key}'")
        customs[shader.key] = p
        return p
    }

    private fun startCustom(shader: PolyEffectShader): GlShaderJob? {
        val vs = if (core) GlBlur.VS_150 else GlBlur.VS_120
        return begin(vs, fragment(CUSTOM_PASS, mainDraw = false, liquid = false, custom = shader.source), "custom effect '${shader.key}'")
            ?.also { customJobs[shader.key] = it }
    }

    private fun precompile(stages: List<PolyEffect>) {
        if (main == null || failed || stages.isEmpty()) return
        for (i in stages.indices) {
            val e = stages[i] as? PolyEffect.Custom ?: continue
            val key = e.shader.key
            if (customs.containsKey(key) || customJobs.containsKey(key)) continue
            if (startCustom(e.shader) == null) customs[key] = null
        }
    }

    private fun fragment(body: String, mainDraw: Boolean, liquid: Boolean, custom: String?): String {
        val out = StringBuilder()
        if (core) out.append("#version 150\n#define PE_TEXFN texture\nout vec4 peOut;\n")
        else out.append("#version 120\n#define PE_TEXFN texture2D\n#define peOut gl_FragColor\n")
        if (mainDraw) out.append(if (core) "in vec4 vColor;\n" else "varying vec4 vColor;\n")
        if (custom != null) out.append("#define PE_CUSTOM\n")
        if (liquid) out.append("#define PE_HOST_LIQUID\n")
        out.append(HOST)
        out.append(include ?: "").append('\n')
        if (liquid) out.append(liquidInclude ?: "").append('\n')
        if (custom != null) out.append("vec4 polyEffect(vec2 uv, vec2 px) {\n").append(custom).append("\n}\n")
        out.append(RESULT).append(body)
        return out.toString()
    }

    private fun link(vs: String, fs: String, what: String): Prog? = begin(vs, fs, what)?.let { complete(it, what) }

    private fun begin(vs: String, fs: String, what: String): GlShaderJob? {
        if (include == null) {
            LOG.warn("PolyCompose {} shader is unavailable: {} is missing", what, INCLUDE)
            return null
        }
        return GlShaderJob(what, vs, fs, "aPos", "aUv", "aColor")
    }

    private fun complete(job: GlShaderJob, what: String): Prog? {
        val p = try {
            job.finish()
        } catch (e: IllegalStateException) {
            LOG.warn("PolyCompose {} shader is unavailable", what, e)
            return null
        }
        GL20.glUseProgram(p)
        GL20.glUniform1i(GL20.glGetUniformLocation(p, "peHostTex"), 0)
        return Prog(p)
    }

    fun prepare(spec: Spec): Boolean {
        val p = mainProgram() ?: return false
        GlGlass.resolve()
        var touched = false
        var src: Src? = null
        val target = GlGlass.target
        if (target >= 0 && !(spec.liquid != null && !liquid)) {
            if (spec.source == PolyBackdrop.SOURCE_GAME_FRAME) gameFrameDemand = true
            val r = region(spec)
            if (r != null) {
                touched = true
                src = try {
                    val auto = spec.source != PolyBackdrop.SOURCE_GAME_FRAME && spec.downscale == 1 && coarseBlur(spec)
                    val s = if (spec.source == PolyBackdrop.SOURCE_GAME_FRAME) gameFrame() else behind(target, r, if (auto) 2 else spec.downscale, auto)
                    val staged = if (s == null || spec.stages.isEmpty()) s else stages(spec, s, r, null)
                    if (staged != null && spec.sample) sample(spec, staged, r)
                    staged
                } catch (e: Throwable) {
                    GlGlass.fail(e)
                    null
                }
            }
        }
        if (touched) GlProgram.rebind(target)
        if (src == null && !spec.hasFallback) {
            finish()
            return false
        }
        GL20.glUseProgram(p.id)
        GL20.glUniform2f(p.scale, GlProgram.scaleX, GlProgram.scaleY)
        GL20.glUniform2f(p.offset, GlProgram.offsetX, GlProgram.offsetY)
        val m = GlProgram.frag
        GL20.glUniform4f(p.frag, m[0], m[1], m[2], m[3])
        val mode = when {
            src == null -> EffectParams.MODE_FALLBACK
            spec.liquid != null -> EffectParams.MODE_LIQUID
            else -> EffectParams.MODE_BACKDROP
        }
        composite(p, spec, mode, spec.x0, spec.y0, spec.x1, spec.y1)
        if (src != null) source(p, src, null, false)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, src?.tex ?: 0)
        src?.owned?.let(pending::add)
        return true
    }

    private fun coarseBlur(spec: Spec): Boolean {
        val first = spec.stages.firstOrNull() as? PolyEffect.Blur ?: return false
        return sigma(first.radius * spec.scale) >= EffectParams.COARSE_SIGMA
    }

    private fun sample(spec: Spec, s: Src, r: IntArray) {
        val l = spec.liquid
        val id = if (l != null && !l.clear && l.sampleId != 0) l.sampleId else 0
        val region = spec.sampleKey
        val key: Any = when {
            id != 0 -> if (PolyLiquidSamples.due(id)) id else return
            region != null -> if (region.due()) region else return
            else -> if (GlGlass.takeFresh() && BackdropSample.due()) GlGlass.Global else return
        }
        val area = intArrayOf(
            max(floor(spec.x0).toInt(), r[0]), max(floor(spec.y0).toInt(), r[1]),
            min(ceil(spec.x1).toInt(), r[2]), min(ceil(spec.y1).toInt(), r[3]),
        )
        if (area[2] <= area[0] || area[3] <= area[1]) return
        GlGlass.sample(s.fbo, texelRect(s, area), s.sy < 0f, key)
    }

    fun finish() {
        for (t in pending) recycle(t)
        pending.clear()
    }

    private fun region(spec: Spec): IntArray? {
        val w = GlGlass.targetW
        val h = GlGlass.targetH
        if (w <= 0 || h <= 0) return null
        val m = GlProgram.frag
        val left = m[0]
        val top = if (m[3] < 0f) m[1] - h else m[1]
        val pad = spec.pad
        val x0 = max(floor(spec.x0 - pad).toInt() - 1, left.toInt())
        val y0 = max(floor(spec.y0 - pad).toInt() - 1, top.toInt())
        val x1 = min(ceil(spec.x1 + pad).toInt() + 1, left.toInt() + w)
        val y1 = min(ceil(spec.y1 + pad).toInt() + 1, top.toInt() + h)
        if (x1 <= x0 || y1 <= y0) return null
        return intArrayOf(x0, y0, x1, y1)
    }

    private fun gameFrame(): Src? {
        val id = GlBlur.backdropTexture
        if (id == 0 || !GlHost.backdropFresh) return null
        val tw = GlBlur.backdropTextureWidth
        val th = GlBlur.backdropTextureHeight
        val w = GlBlur.backdropWidth.toFloat()
        val h = GlBlur.backdropHeight.toFloat()
        return Src(id, GlBlur.backdropFbo, tw, th, tw, th, 0f, h, w / tw, -h / th, null)
    }

    private fun behind(target: Int, r: IntArray, ds: Int, exact: Boolean): Src? {
        if (GlGlass.failed || !GlFbo.canBlit) return null
        var rw = r[2] - r[0]
        var rh = r[3] - r[1]
        val cw = (rw + ds - 1) / ds
        val ch = (rh + ds - 1) / ds
        val m = GlProgram.frag
        val flipped = m[3] < 0f
        val gx = r[0] - m[0].toInt()
        val gy = if (flipped) m[1].toInt() - r[3] else r[1] - m[1].toInt()
        if (exact && gx + cw * ds <= GlGlass.targetW && gy + ch * ds <= GlGlass.targetH) {
            rw = cw * ds
            rh = ch * ds
        }
        val t = acquire(cw, ch)
        GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, target)
        GlFbo.bind(GL30.GL_DRAW_FRAMEBUFFER, t.fbo)
        GlFbo.blit(gx, gy, gx + rw, gy + rh, 0, 0, cw, ch, GL11.GL_COLOR_BUFFER_BIT, if (ds > 1) GL11.GL_LINEAR else GL11.GL_NEAREST)
        val sx = rw.toFloat() / cw
        val sy = rh.toFloat() / ch
        return Src(t.tex, t.fbo, t.w, t.h, cw, ch, r[0].toFloat(), (if (flipped) r[3] else r[1]).toFloat(), sx, if (flipped) -sy else sy, t)
    }

    private fun texelRect(s: Src, r: IntArray): IntArray {
        val ax = (r[0] - s.ox) / s.sx
        val bx = (r[2] - s.ox) / s.sx
        val ay = (r[1] - s.oy) / s.sy
        val by = (r[3] - s.oy) / s.sy
        return intArrayOf(
            floor(min(ax, bx)).toInt().coerceIn(0, s.usedW), floor(min(ay, by)).toInt().coerceIn(0, s.usedH),
            ceil(max(ax, bx)).toInt().coerceIn(0, s.usedW), ceil(max(ay, by)).toInt().coerceIn(0, s.usedH),
        )
    }

    private fun stages(spec: Spec, input: Src, r: IntArray, firstClamp: FloatArray?): Src {
        beginPasses()
        var cur = input
        for ((i, e) in spec.stages.withIndex()) {
            val clamp = if (i == 0) firstClamp else null
            val next = when (e) {
                is PolyEffect.Blur -> blur(cur, e, spec, r, clamp)
                is PolyEffect.Custom -> custom(cur, e, spec, r, clamp)
                else -> cur
            }
            if (next !== cur) cur.owned?.let(::recycle)
            cur = next
        }
        return cur
    }

    private fun blur(cur: Src, e: PolyEffect.Blur, spec: Spec, r: IntArray, clamp: FloatArray?): Src {
        val p = passProgram(0) ?: return cur
        val s = sigma(e.radius * spec.scale)
        if (s <= 0f) return cur
        val level = 1 shl EffectParams.blurLevel(s)
        val d = max(level, spec.downscale)
        val decal = !e.clampEdges
        val from = reduce(cur, d, r, p, clamp, decal)
        val rw = r[2] - r[0]
        val rh = r[3] - r[1]
        val w = (rw + d - 1) / d
        val h = (rh + d - 1) / d
        val stepX = (if (e.radiusX > 0f) sigma(e.radiusX * spec.scale) else 0f) / 2f
        val stepY = (if (e.radiusY > 0f) sigma(e.radiusY * spec.scale) else 0f) / 2f
        val a = acquire(w, h)
        GL20.glUseProgram(p.id)
        GL20.glUniform2f(p.step, stepX / (abs(from.sx) * from.allocW), 0f)
        pass(p, from, a, w, h, r[0].toFloat(), r[1].toFloat(), d.toFloat(), if (from === cur) clamp else null, decal && from === cur)
        if (from !== cur) from.owned?.let(::recycle)
        val aSrc = local(a, w, h, r, d)
        val b = acquire(w, h)
        GL20.glUniform2f(p.step, 0f, stepY / (d.toFloat() * a.h))
        pass(p, aSrc, b, w, h, r[0].toFloat(), r[1].toFloat(), d.toFloat(), null, false)
        recycle(a)
        return local(b, w, h, r, d)
    }

    private fun reduce(cur: Src, d: Int, r: IntArray, p: Prog, clamp: FloatArray?, decal: Boolean): Src {
        var c = cur
        while (abs(c.sx) * 1.5f < d) {
            val ns = Integer.highestOneBit(max(abs(c.sx).roundToInt(), 1)) * 2
            val w = (r[2] - r[0] + ns - 1) / ns
            val h = (r[3] - r[1] + ns - 1) / ns
            val t = acquire(w, h)
            GL20.glUseProgram(p.id)
            GL20.glUniform2f(p.step, 0f, 0f)
            pass(p, c, t, w, h, r[0].toFloat(), r[1].toFloat(), ns.toFloat(), if (c === cur) clamp else null, decal && c === cur)
            if (c !== cur) c.owned?.let(::recycle)
            c = local(t, w, h, r, ns)
        }
        return c
    }

    private fun custom(cur: Src, e: PolyEffect.Custom, spec: Spec, r: IntArray, clamp: FloatArray?): Src {
        val p = customProgram(e.shader) ?: return cur
        val sc = if (cur.owned != null) Integer.highestOneBit(max(abs(cur.sx).roundToInt(), 1)) else spec.downscale
        val w = (r[2] - r[0] + sc - 1) / sc
        val h = (r[3] - r[1] + sc - 1) / sc
        val t = acquire(w, h)
        GL20.glUseProgram(p.id)
        GL20.glUniform4f(p.rect, r[0].toFloat(), r[1].toFloat(), r[2].toFloat(), r[3].toFloat())
        val params = paramScratch
        if (e.params.size >= PolyEffectShader.MAX_PARAMS) {
            e.params.copyInto(params, 0, 0, PolyEffectShader.MAX_PARAMS)
        } else {
            NO_PARAMS.copyInto(params)
            e.params.copyInto(params)
        }
        GL20.glUniform4fv(p.params, params)
        pass(p, cur, t, w, h, r[0].toFloat(), r[1].toFloat(), sc.toFloat(), clamp, false)
        return local(t, w, h, r, sc)
    }

    private fun local(t: Target, w: Int, h: Int, r: IntArray, scale: Int): Src =
        Src(t.tex, t.fbo, t.w, t.h, w, h, r[0].toFloat(), r[1].toFloat(), scale.toFloat(), scale.toFloat(), t)

    private fun beginPasses() {
        passProgram(0)
        if (core) GL30.glBindVertexArray(vao)
        else {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo)
            GL20.glEnableVertexAttribArray(0)
            GL20.glDisableVertexAttribArray(1)
            GL20.glDisableVertexAttribArray(2)
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L)
        }
        GL11.glDisable(GL11.GL_BLEND)
        GL11.glDisable(GL11.GL_SCISSOR_TEST)
        GL11.glDisable(GL11.GL_DEPTH_TEST)
        GL11.glDisable(GL11.GL_STENCIL_TEST)
        GL11.glDisable(GL11.GL_CULL_FACE)
        if (alphaTest) GL11.glDisable(GL11.GL_ALPHA_TEST)
        GL11.glColorMask(true, true, true, true)
    }

    private fun pass(p: Prog, src: Src, dst: Target, w: Int, h: Int, fx: Float, fy: Float, fs: Float, clamp: FloatArray?, decal: Boolean) {
        GlFbo.bind(GL30.GL_FRAMEBUFFER, dst.fbo)
        GL11.glViewport(0, 0, w, h)
        GL20.glUseProgram(p.id)
        GL20.glUniform4f(p.frag, fx, fy, fs, fs)
        source(p, src, clamp, decal)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, src.tex)
        RenderCounters.batch(3)
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3)
    }

    private fun source(p: Prog, s: Src, clamp: FloatArray?, decal: Boolean) {
        val aw = s.allocW.toFloat()
        val ah = s.allocH.toFloat()
        GL20.glUniform4f(p.source, s.ox, s.oy, 1f / (s.sx * aw), 1f / (s.sy * ah))
        val c0 = if (clamp != null) clamp[0] else 0f
        val c1 = if (clamp != null) clamp[1] else 0f
        val c2 = if (clamp != null) clamp[2] else s.usedW.toFloat()
        val c3 = if (clamp != null) clamp[3] else s.usedH.toFloat()
        GL20.glUniform4f(p.clamp, (c0 + 0.5f) / aw, (c1 + 0.5f) / ah, (c2 - 0.5f) / aw, (c3 - 0.5f) / ah)
        GL20.glUniform4f(p.bounds, c0 / aw, c1 / ah, c2 / aw, c3 / ah)
        GL20.glUniform1f(p.decal, if (decal) 1f else 0f)
    }

    private fun composite(p: Prog, s: Spec, mode: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        GL20.glUniform4f(p.rect, x0, y0, x1, y1)
        GL20.glUniform4f(p.shape, s.radius, s.band, s.strength, s.dispersion)
        GL20.glUniform4f(p.mode, 1f, mode.toFloat(), s.frost, 0f)
        GL20.glUniformMatrix4fv(p.matrix, false, s.matrix)
        GL20.glUniform4fv(p.matrixOffset, s.matrixOffset)
        GL20.glUniform4fv(p.highlightColor, s.highlightColor)
        GL20.glUniform4fv(p.highlight, s.highlight)
        GL20.glUniform4fv(p.noise, s.noise)
        GL20.glUniform4fv(p.fallback, s.fallback)
        val l = s.liquid ?: return
        GL20.glUniform4fv(p.liquidA, l.a)
        GL20.glUniform4fv(p.liquidB, l.b)
        GL20.glUniform4fv(p.liquidRect, l.rects)
        GL20.glUniform4fv(p.liquidRadius, l.radii)
        GL20.glUniform4fv(p.liquidGlow, l.glow)
        GL20.glUniform4fv(p.liquidC, l.c)
    }

    fun layer(spec: Spec, tex: Int, allocW: Int, allocH: Int, x: Int, y: Int, w: Int, h: Int, out: Target?): Target? {
        if (spec.passless) return null
        val p = passProgram(1) ?: run {
            spec.settle()
            return null
        }
        val input = Src(tex, 0, allocW, allocH, w, h, x.toFloat(), y.toFloat(), 1f, 1f, null)
        val r = intArrayOf(x, y, x + w, y + h)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        try {
            val clamp = if (spec.clampFirst) {
                floatArrayOf(
                    (spec.x0 - x).coerceIn(0f, w.toFloat()), (spec.y0 - y).coerceIn(0f, h.toFloat()),
                    (spec.x1 - x).coerceIn(0f, w.toFloat()), (spec.y1 - y).coerceIn(0f, h.toFloat()),
                ).takeIf { it[2] - it[0] >= 1f && it[3] - it[1] >= 1f }
            } else null
            beginPasses()
            val cur = if (spec.stages.isEmpty()) input else stages(spec, input, r, clamp)
            val o = out?.takeIf { it.w >= w && it.h >= h } ?: run {
                out?.let(::recycle)
                acquire(w, h)
            }
            GL20.glUseProgram(p.id)
            if (spec.bleed) composite(p, spec, EffectParams.MODE_LAYER, spec.x0 - spec.pad, spec.y0 - spec.pad, spec.x1 + spec.pad, spec.y1 + spec.pad)
            else composite(p, spec, EffectParams.MODE_LAYER, spec.x0, spec.y0, spec.x1, spec.y1)
            pass(p, cur, o, w, h, x.toFloat(), y.toFloat(), 1f, if (cur === input) clamp else null, false)
            cur.owned?.let(::recycle)
            return o
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        }
    }

    fun recycle(t: Target) {
        if (pool.size < MAX_POOLED) pool.add(t) else delete(t)
    }

    private fun acquire(w: Int, h: Int): Target {
        var fit = -1
        for (i in pool.indices) {
            val p = pool[i]
            if (p.w >= w && p.h >= h && (fit < 0 || p.w * p.h < pool[fit].w * pool[fit].h)) fit = i
        }
        if (fit >= 0) return pool.removeAt(fit)
        val reuse = pool.removeLastOrNull()
        val fbo = reuse?.fbo ?: GlFbo.gen()
        val tex = reuse?.tex ?: GL11.glGenTextures()
        val t = Target(fbo, tex, PolyTessellator.layerTargetSize(0, max(w, 1)), PolyTessellator.layerTargetSize(0, max(h, 1)))
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, t.w, t.h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GlFbo.bind(GL30.GL_FRAMEBUFFER, fbo)
        GlFbo.attachColor(tex)
        return t
    }

    private fun delete(t: Target) {
        GlFbo.delete(t.fbo)
        GL11.glDeleteTextures(t.tex)
    }

    private const val HOST = """uniform sampler2D peHostTex;
uniform vec4 peHostFrag;
uniform vec4 peHostClamp;
uniform vec4 peHostBounds;
uniform float peHostDecal;
uniform vec2 peHostStep;
vec4 peHostSample(vec2 uv) {
    if (peHostDecal > 0.5 && (any(lessThan(uv, peHostBounds.xy)) || any(greaterThan(uv, peHostBounds.zw)))) {
        return vec4(0.0);
    }
    return PE_TEXFN(peHostTex, clamp(uv, peHostClamp.xy, peHostClamp.zw));
}
#define PE_SOURCE peHostTex
#define PE_TEX(uv) peHostSample(uv)
#define PE_FRAG (peHostFrag.xy + gl_FragCoord.xy * peHostFrag.zw)
"""

    private const val RESULT = """vec4 peHostResult() {
#ifdef PE_HOST_LIQUID
    if (peMode.y > 2.5) {
        return peLiquid();
    }
#endif
    return peComposite();
}
"""

    private const val MAIN_DRAW = """void main() {
    peOut = peHostResult() * vColor.a;
}
"""

    private const val LAYER_PASS = """void main() {
    peOut = peHostResult();
}
"""

    private const val BLUR_PASS = """void main() {
    peOut = peBlur(peUv(PE_FRAG), peHostStep);
}
"""

    private const val CUSTOM_PASS = """void main() {
    peOut = peCustom();
}
"""
}
