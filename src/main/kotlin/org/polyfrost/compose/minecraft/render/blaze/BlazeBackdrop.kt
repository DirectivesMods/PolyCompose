package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.BindGroupLayout
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.UniformType
//?} else {
/*import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.UniformType
*///?}
//? if 26.2
//import com.mojang.blaze3d.pipeline.BindGroupLayout
//? if >= 26.1 && < 26.3
//import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.util.Collections
import java.util.IdentityHashMap
//? if >= 26.2 {
import com.mojang.renderpearl.api.GpuFormat
import java.util.Optional
import org.joml.Vector4f
import org.joml.Vector4fc
//?} else {
/*import com.mojang.blaze3d.textures.TextureFormat
import java.util.OptionalInt
*///?}
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.profile.EffectGpuTimer
import org.polyfrost.compose.minecraft.profile.RenderCounters
import org.polyfrost.compose.minecraft.render.ColorMath
import org.polyfrost.compose.minecraft.render.EffectParams
import org.polyfrost.compose.minecraft.render.GpuBackend
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyEffectShader
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyLiquidSamples
import org.slf4j.LoggerFactory
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

internal object BlazeBackdrop {
    private const val RELEASE_AFTER_NANOS = 5_000_000_000L
    private const val KERNEL_SIGMA = 1.94f
    private const val MIN_SIGMA = 0.35f
    private const val EFFECT_FLOATS = 68
    private const val TARGET_FLOATS = 16
    private const val BLUR_FLOATS = 16
    private const val SAMPLE_GRID = 8
    private const val MAX_POOLED = 8
    private const val FAR = 1e9f
    private val LOG = LoggerFactory.getLogger("PolyCompose/Backdrop")

    //? if >= 26.3 {
    val composite: RenderPipeline = pipeline("pipeline/poly_backdrop", "core/poly_backdrop", true, "PolyEffect", "PolyLiquid", "PolyTarget")
    val blur: RenderPipeline = pipeline("pipeline/poly_blur", "core/poly_blur", false, "PolyBlur")
    //?} else {
    /*val composite: RenderPipeline = pipeline("pipeline/poly_backdrop", "core/poly_backdrop_legacy", true, "PolyEffect", "PolyLiquid", "PolyTarget")
    val blur: RenderPipeline = pipeline("pipeline/poly_blur", "core/poly_blur_legacy", false, "PolyBlur")
    *///?}

    private class Pooled(val texture: GpuTexture, val view: GpuTextureView, val width: Int, val height: Int)

    private class Src(
        val view: GpuTextureView, val texture: GpuTexture,
        val ox: Float, val oy: Float, val ws: Float,
        val texW: Float, val texH: Float, val usedW: Float, val usedH: Float,
    )

    private class Ctx(val height: Float, val scale: Float, val rect: FloatArray, val uniforms: EffectUniforms)

    private class PendingRead(val buffer: GpuBuffer, val key: Any, val frame: Long) {
        @Volatile
        var done = false
    }

    private object GlobalSample

    private val free = ArrayList<Pooled>()
    private val inUse = ArrayList<Pooled>()
    private val slots = UniformSlots()
    private val failed: MutableSet<RenderPipeline> = Collections.newSetFromMap(IdentityHashMap())
    private val effect = FloatArray(EFFECT_FLOATS)
    private val targetData = FloatArray(TARGET_FLOATS)
    private val blurData = FloatArray(BLUR_FLOATS)
    private var zeroLiquid: GpuBuffer? = null
    private var lastUsed = 0L
    private var sampledFrame = false
    private var warnedSample = false
    private var grid: Pooled? = null
    private val pendingReads = ArrayList<PendingRead>()
    private val sampleBuffers = ArrayList<GpuBuffer>()
    private var frame = 0L
    private val bottomUp = !topDownTargets

    init {
        HostEvents.onResourcesReloaded { failed.clear() }
    }

    fun pipeline(location: String, fragment: String, blend: Boolean, vararg uniforms: String): RenderPipeline {
        val builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
            .withLocation(Identifier.fromNamespaceAndPath("polycompose", location))
            .withVertexShader("core/screenquad")
            .withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", fragment))
        //? if >= 26.2 {
        val layout = BindGroupLayout.builder()
        //? if >= 26.3 {
        layout.withUniform("Source", UniformType.COMBINED_IMAGE_SAMPLER)
        //?} else
        //layout.withSampler("Source")
        for (u in uniforms) layout.withUniform(u, UniformType.UNIFORM_BUFFER)
        builder.withBindGroupLayout(layout.build())
        //?} else {
        /*builder.withSampler("Source")
        for (u in uniforms) builder.withUniform(u, UniformType.UNIFORM_BUFFER)
        *///?}
        //? if >= 26.3 {
        builder.withColorTargetState(if (blend) ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA) else ColorTargetState.DEFAULT)
        //?} elif >= 26.1 {
        /*if (blend) builder.withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA))
        *///?} else
        //if (blend) builder.withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)
        return builder.build()
    }

    @Suppress("TooGenericExceptionCaught")
    fun ready(pipeline: RenderPipeline): Boolean {
        if (pipeline in failed) return false
        val ok = try {
            //? if >= 26.3 {
            RenderSystem.getCompiledPipelineNullable(pipeline) != null
            //?} else
            //RenderSystem.getDevice().precompilePipeline(pipeline).isValid
        } catch (e: Exception) {
            LOG.warn("PolyCompose failed to compile {}", pipeline.location, e)
            false
        }
        if (!ok) {
            failed.add(pipeline)
            LOG.warn("PolyCompose effect pipeline {} is unavailable; backdrops using it are skipped", pipeline.location)
        }
        return ok
    }

    fun execute(spec: BackdropSpec, target: RenderTarget) {
        val out = target.colorTextureView ?: return
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        lastUsed = System.nanoTime()
        val b = spec.backdrop
        val reach = if (spec.liquid != null) spec.pad else 0f
        var cx0 = max(spec.x0 - reach, 0f)
        var cy0 = max(spec.y0 - reach, 0f)
        var cx1 = min(spec.x1 + reach, w)
        var cy1 = min(spec.y1 + reach, h)
        spec.clip?.let { cx0 = max(cx0, it[0]); cy0 = max(cy0, it[1]); cx1 = min(cx1, it[2]); cy1 = min(cy1, it[3]) }
        if (cx1 <= cx0 || cy1 <= cy0) return
        val clip = floatArrayOf(cx0, cy0, cx1, cy1)
        val ctx = Ctx(h, spec.scale, floatArrayOf(spec.x0, spec.y0, spec.x1, spec.y1), slots)
        EffectGpuTimer.count(EffectGpuTimer.BACKDROPS)
        try {
            val source = if (GuiTargetRedirect.target != null) null else source(spec, target, ctx)
            if (source == null) {
                if (b.fallbackColor ushr 24 != 0) draw(out, w, h, false, spec, null, EffectParams.MODE_FALLBACK, clip, ctx)
                return
            }
            val staged = stages(source, b.chain, ctx)
            sample(staged, spec, h, ctx)
            draw(out, w, h, false, spec, staged, if (spec.liquid != null) EffectParams.MODE_LIQUID else EffectParams.MODE_BACKDROP, clip, ctx)
        } finally {
            EffectGpuTimer.end()
            free.addAll(inUse)
            inUse.clear()
            trimPool()
        }
    }

    fun layer(e: LayerEffect, content: RenderTarget, usedW: Int, usedH: Int, out: RenderTarget, uniforms: EffectUniforms): Boolean {
        val tex = content.colorTexture ?: return false
        val view = content.colorTextureView ?: return false
        val outView = out.colorTextureView ?: return false
        if (!ready(composite)) return false
        lastUsed = System.nanoTime()
        val w = content.width.toFloat()
        val h = content.height.toFloat()
        var x0 = e.x0; var y0 = e.y0; var x1 = e.x1; var y1 = e.y1; var radius = e.radius
        if (e.chain.textureStages.any { it is PolyEffect.Blur && !it.clampEdges }) {
            val grow = e.chain.outset * e.scale
            x0 -= grow; y0 -= grow; x1 += grow; y1 += grow; radius += grow
        }
        val ctx = Ctx(h, e.scale, floatArrayOf(x0, y0, x1, y1), uniforms)
        try {
            var src = Src(view, tex, 0f, 0f, 1f, w, h, w, h)
            if (e.chain.textureStages.isNotEmpty()) {
                src = crop(src, 0f, if (bottomUp) h - usedH else 0f, usedW.toFloat(), usedH.toFloat(), 1f, false, ctx) ?: return false
            }
            src = stages(src, e.chain, ctx)
            fillEffect(ctx.rect, radius, 1f, EffectParams.MODE_LAYER, 0f, e.chain, e.scale, 0, src, h, null)
            fillTarget(w, h, 0f, if (bottomUp) h else 0f, 1f, if (bottomUp) -1f else 1f, src, floatArrayOf(0f, 0f, w, h))
            val effectBuf = uniforms.buffer(effect, EFFECT_FLOATS)
            val targetBuf = uniforms.buffer(targetData, TARGET_FLOATS)
            pass(outView, true) { rp ->
                setPipeline(rp, composite)
                rp.setUniform("PolyEffect", effectBuf)
                rp.setUniform("PolyLiquid", zeroLiquid())
                rp.setUniform("PolyTarget", targetBuf)
                bindSource(rp, src.view, true)
                drawTriangle(rp)
            }
            return true
        } finally {
            free.addAll(inUse)
            inUse.clear()
            trimPool()
        }
    }

    fun endFrame() {
        UiBlur.frameEnded()
        slots.rewind()
        sampledFrame = false
        drainReads()
        frame++
        if (lastUsed != 0L && System.nanoTime() - lastUsed > RELEASE_AFTER_NANOS) release()
    }

    private fun release() {
        lastUsed = 0L
        for (p in free) close(p)
        free.clear()
        slots.release()
        zeroLiquid?.close()
        zeroLiquid = null
        grid?.let(::close)
        grid = null
        for (b in sampleBuffers) b.close()
        sampleBuffers.clear()
    }

    private fun source(spec: BackdropSpec, target: RenderTarget, ctx: Ctx): Src? {
        val w = target.width.toFloat()
        val h = target.height.toFloat()
        val p = spec.pad
        val dx0 = floor(max(spec.x0 - p, 0f))
        val dy0 = floor(max(spec.y0 - p, 0f))
        val dx1 = ceil(min(spec.x1 + p, w))
        val dy1 = ceil(min(spec.y1 + p, h))
        if (dx1 <= dx0 || dy1 <= dy0) return null
        val my0 = if (bottomUp) h - dy1 else dy0
        if (spec.backdrop.source == PolyBackdrop.SOURCE_GAME_FRAME) {
            if (!UiBlur.fresh) return null
            val view = UiBlur.backdrop ?: return null
            val mw = UiBlur.backdropWidth.toFloat()
            val mh = UiBlur.backdropHeight.toFloat()
            val frame = Src(view, view.texture(), 0f, 0f, 1f, mw, mh, mw, mh)
            if (spec.backdrop.chain.textureStages.isEmpty()) return frame
            sourceCopy()
            return crop(frame, dx0, my0, dx1 - dx0, dy1 - dy0, GAME_FRAME_SCALE, true, ctx)
        }
        if (spec.backdrop.source != PolyBackdrop.SOURCE_BEHIND) return null
        val tex = target.colorTexture ?: return null
        val view = target.colorTextureView ?: return null
        val down = spec.backdrop.downscale
        val first = if (down >= 2 || coarse(spec)) 2f else 1f
        val whole = Src(view, tex, 0f, 0f, 1f, w, h, w, h)
        sourceCopy()
        var src = crop(whole, dx0, my0, dx1 - dx0, dy1 - dy0, first, first > 1f, ctx) ?: return null
        if (down >= 4) src = halve(src, ctx) ?: return null
        return src
    }

    private fun coarse(spec: BackdropSpec): Boolean {
        val blur = spec.backdrop.chain.textureStages.firstOrNull() as? PolyEffect.Blur ?: return false
        return EffectParams.SIGMA_SCALE * blur.radius * spec.scale + EffectParams.SIGMA_BIAS >= EffectParams.COARSE_SIGMA
    }

    private fun sourceCopy() {
        EffectGpuTimer.mark(EffectGpuTimer.COPY)
        EffectGpuTimer.count(EffectGpuTimer.COPIES)
    }

    private fun crop(src: Src, mx0: Float, my0: Float, mw: Float, mh: Float, ws: Float, linear: Boolean, ctx: Ctx): Src? {
        val ow = ceil(mw / ws).toInt()
        val oh = ceil(mh / ws).toInt()
        val dst = acquire(ow, oh) ?: return null
        //? if >= 26.2 {
        val rgba = GpuFormat.RGBA8_UNORM
        //?} else
        //val rgba = TextureFormat.RGBA8
        if (ws == 1f && src.ws == 1f && GpuBackend.isVulkan && src.texture.format == rgba && src.texW.toInt() == src.texture.getWidth(0)) {
            EffectGpuTimer.count(EffectGpuTimer.PASSES)
            RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(src.texture, dst.texture, 0, 0, 0, mx0.toInt(), my0.toInt(), ow, oh)
        } else {
            if (!blurPass(src, dst, mx0, my0, ws, 0f, 0f, linear, ctx)) return null
        }
        return Src(dst.view, dst.texture, mx0, my0, ws, dst.width.toFloat(), dst.height.toFloat(), ow.toFloat(), oh.toFloat())
    }

    private fun halve(src: Src, ctx: Ctx): Src? {
        val ow = ceil(src.usedW / 2f).toInt()
        val oh = ceil(src.usedH / 2f).toInt()
        val dst = acquire(ow, oh) ?: return null
        if (!blurPass(src, dst, src.ox, src.oy, src.ws * 2f, 0f, 0f, true, ctx)) return null
        return Src(dst.view, dst.texture, src.ox, src.oy, src.ws * 2f, dst.width.toFloat(), dst.height.toFloat(), ow.toFloat(), oh.toFloat())
    }

    private fun stages(start: Src, chain: PolyEffectChain, ctx: Ctx): Src {
        var cur = start
        for (stage in chain.textureStages) {
            EffectGpuTimer.mark(EffectGpuTimer.BLUR)
            cur = when (stage) {
                is PolyEffect.Blur -> blurStage(cur, sigmaOf(stage.radius, ctx), sigmaOf(stage.radiusX, ctx), sigmaOf(stage.radiusY, ctx), ctx) ?: cur
                is PolyEffect.Custom -> customStage(cur, stage, ctx) ?: cur
                else -> cur
            }
        }
        return cur
    }

    private fun sigmaOf(radius: Float, ctx: Ctx): Float = if (radius > 0f) EffectParams.SIGMA_SCALE * radius * ctx.scale + EffectParams.SIGMA_BIAS else 0f

    private fun blurStage(start: Src, sigma: Float, sigmaX: Float, sigmaY: Float, ctx: Ctx): Src? {
        if (sigma < MIN_SIGMA) return null
        val level = EffectParams.blurLevel(sigma)
        val want = (1 shl level).toFloat()
        var cur = start
        while (cur.ws < want && cur.usedW >= 2f && cur.usedH >= 2f) cur = halve(cur, ctx) ?: return null
        val stepX = sigmaX / cur.ws / KERNEL_SIGMA
        val stepY = sigmaY / cur.ws / KERNEL_SIGMA
        val w = cur.usedW.toInt()
        val h = cur.usedH.toInt()
        val a = acquire(w, h) ?: return null
        if (!blurPass(cur, a, cur.ox, cur.oy, cur.ws, stepX, 0f, true, ctx)) return null
        val mid = Src(a.view, a.texture, cur.ox, cur.oy, cur.ws, a.width.toFloat(), a.height.toFloat(), cur.usedW, cur.usedH)
        val b = acquire(w, h) ?: return null
        if (!blurPass(mid, b, cur.ox, cur.oy, cur.ws, 0f, stepY, true, ctx)) return null
        return Src(b.view, b.texture, cur.ox, cur.oy, cur.ws, b.width.toFloat(), b.height.toFloat(), cur.usedW, cur.usedH)
    }

    private fun customStage(cur: Src, stage: PolyEffect.Custom, ctx: Ctx): Src? {
        val p = EffectShaders.pipeline(stage.shader) ?: return null
        val dst = acquire(cur.usedW.toInt(), cur.usedH.toInt()) ?: return null
        fillEffect(ctx.rect, 0f, 1f, EffectParams.MODE_LAYER, 0f, PolyEffectChain.Empty, ctx.scale, 0, cur, ctx.height, stage.params)
        val h = ctx.height
        fillTarget(dst.width.toFloat(), dst.height.toFloat(), cur.ox, if (bottomUp) h - cur.oy else cur.oy, cur.ws, if (bottomUp) -cur.ws else cur.ws, cur, null)
        val effectBuf = ctx.uniforms.buffer(effect, EFFECT_FLOATS)
        val targetBuf = ctx.uniforms.buffer(targetData, TARGET_FLOATS)
        pass(dst.view, false) { rp ->
            setPipeline(rp, p)
            rp.setUniform("PolyEffect", effectBuf)
            rp.setUniform("PolyTarget", targetBuf)
            bindSource(rp, cur.view, true)
            drawTriangle(rp)
        }
        return Src(dst.view, dst.texture, cur.ox, cur.oy, cur.ws, dst.width.toFloat(), dst.height.toFloat(), cur.usedW, cur.usedH)
    }

    private fun blurPass(src: Src, dst: Pooled, ox: Float, oy: Float, ws: Float, stepX: Float, stepY: Float, linear: Boolean, ctx: Ctx): Boolean {
        if (!ready(blur)) return false
        val kx = src.ws * src.texW
        val ky = src.ws * src.texH
        val d = blurData
        d[0] = dst.width.toFloat(); d[1] = dst.height.toFloat(); d[2] = 0f; d[3] = 0f
        d[4] = ws / kx; d[5] = ws / ky; d[6] = (ox - src.ox) / kx; d[7] = (oy - src.oy) / ky
        d[8] = stepX / src.texW; d[9] = stepY / src.texH; d[10] = 0f; d[11] = 0f
        clampOf(src, d, 12)
        val buf = ctx.uniforms.buffer(d, BLUR_FLOATS)
        pass(dst.view, false) { rp ->
            setPipeline(rp, blur)
            rp.setUniform("PolyBlur", buf)
            bindSource(rp, src.view, linear)
            drawTriangle(rp)
        }
        return true
    }

    private fun draw(out: GpuTextureView, w: Float, h: Float, clear: Boolean, spec: BackdropSpec, src: Src?, mode: Int, clip: FloatArray, ctx: Ctx) {
        if (!ready(composite)) return
        EffectGpuTimer.mark(EffectGpuTimer.COMPOSITE)
        fillEffect(ctx.rect, spec.radius, spec.alpha, mode, spec.frost, spec.backdrop.chain, spec.scale, spec.backdrop.fallbackColor, src, h, null)
        fillTarget(w, h, 0f, if (bottomUp) h else 0f, 1f, if (bottomUp) -1f else 1f, src, clip)
        val effectBuf = ctx.uniforms.buffer(effect, EFFECT_FLOATS)
        val targetBuf = ctx.uniforms.buffer(targetData, TARGET_FLOATS)
        val liquid = spec.liquid
        val liquidBuf = if (liquid != null && mode == EffectParams.MODE_LIQUID) ctx.uniforms.buffer(liquid, EffectParams.LIQUID_FLOATS) else zeroLiquid()
        val sx0 = floor(clip[0]).toInt()
        val sy0 = floor(clip[1]).toInt()
        val sx1 = ceil(clip[2]).toInt()
        val sy1 = ceil(clip[3]).toInt()
        val view = src?.view ?: GlyphAtlas.page(PolyGlyphPage.pages[0]).texture.textureView
        pass(out, clear) { rp ->
            setPipeline(rp, composite)
            rp.setUniform("PolyEffect", effectBuf)
            rp.setUniform("PolyLiquid", liquidBuf)
            rp.setUniform("PolyTarget", targetBuf)
            bindSource(rp, view, true)
            rp.enableScissor(sx0, h.toInt() - sy1, sx1 - sx0, sy1 - sy0)
            drawTriangle(rp)
        }
    }

    private fun fillEffect(
        rect: FloatArray, radius: Float, alpha: Float, mode: Int, frost: Float, chain: PolyEffectChain, scale: Float,
        fallback: Int, src: Src?, h: Float, params: FloatArray?,
    ) {
        val d = effect
        d.fill(0f)
        d[0] = rect[0]; d[1] = rect[1]; d[2] = rect[2]; d[3] = rect[3]
        val r = chain.refract
        d[4] = radius
        if (r != null) { d[5] = r.band * scale; d[6] = r.strength; d[7] = r.dispersion }
        if (src != null) {
            d[8] = src.ox
            d[9] = if (bottomUp) h - src.oy else src.oy
            d[10] = 1f / (src.ws * src.texW)
            d[11] = (if (bottomUp) -1f else 1f) / (src.ws * src.texH)
        }
        d[12] = alpha; d[13] = mode.toFloat(); d[14] = frost
        val m = chain.colorMatrix
        EffectParams.matrixInto(m, d, 16)
        if (m != null) EffectParams.matrixOffsetInto(m, d, 32)
        chain.highlight?.let {
            ColorMath.premultiplyInto(it.color, d, 36)
            EffectParams.highlightInto(it, scale, d, 40)
        }
        chain.noise?.let { EffectParams.noiseInto(it, scale, d, 44) }
        ColorMath.premultiplyInto(fallback, d, 48)
        if (params != null) for (i in 0 until min(params.size, PolyEffectShader.MAX_PARAMS)) d[52 + i] = params[i]
    }

    private fun fillTarget(w: Float, h: Float, mapX: Float, mapY: Float, scaleX: Float, scaleY: Float, src: Src?, clip: FloatArray?) {
        val t = targetData
        t[0] = w; t[1] = h; t[2] = 0f; t[3] = 0f
        t[4] = mapX; t[5] = mapY; t[6] = scaleX; t[7] = scaleY
        if (src != null) clampOf(src, t, 8) else { t[8] = 0f; t[9] = 0f; t[10] = 1f; t[11] = 1f }
        if (clip != null) { t[12] = clip[0]; t[13] = clip[1]; t[14] = clip[2]; t[15] = clip[3] } else { t[12] = -FAR; t[13] = -FAR; t[14] = FAR; t[15] = FAR }
    }

    private fun clampOf(src: Src, out: FloatArray, at: Int) {
        out[at] = 0.5f / src.texW
        out[at + 1] = 0.5f / src.texH
        out[at + 2] = max(src.usedW - 0.5f, 0.5f) / src.texW
        out[at + 3] = max(src.usedH - 0.5f, 0.5f) / src.texH
    }

    private fun zeroLiquid(): GpuBuffer = zeroLiquid?.takeUnless { it.isClosed }
        ?: uniformBuffer(FloatArray(EffectParams.LIQUID_FLOATS), EffectParams.LIQUID_FLOATS).also { zeroLiquid = it }

    private inline fun pass(out: GpuTextureView, clear: Boolean, block: (RenderPass) -> Unit) {
        //? if >= 26.2 {
        val clearColor: Optional<Vector4fc> = if (clear) Optional.of(Vector4f(0f, 0f, 0f, 0f)) else Optional.empty()
        //?} else
        //val clearColor = if (clear) OptionalInt.of(0) else OptionalInt.empty()
        EffectGpuTimer.count(EffectGpuTimer.PASSES)
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "PolyCompose backdrop" }, out, clearColor).use { rp ->
            RenderSystem.bindDefaultUniforms(rp)
            block(rp)
        }
    }

    private fun setPipeline(rp: RenderPass, pipeline: RenderPipeline) {
        //? if >= 26.3 {
        rp.setPipeline(RenderSystem.getCompiledPipeline(pipeline))
        //?} else
        //rp.setPipeline(pipeline)
    }

    private fun bindSource(rp: RenderPass, view: GpuTextureView, linear: Boolean) {
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(if (linear) FilterMode.LINEAR else FilterMode.NEAREST)
        //? if >= 26.3 {
        rp.setUniform("Source", view, sampler)
        //?} else
        //rp.bindTexture("Source", view, sampler)
    }

    private fun drawTriangle(rp: RenderPass) {
        RenderCounters.batch(3)
        //? if >= 26.2 {
        rp.draw(3, 1, 0, 0)
        //?} else
        //rp.draw(0, 3)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun acquire(w: Int, h: Int): Pooled? {
        if (w <= 0 || h <= 0) return null
        var best = -1
        for (i in free.indices) {
            val p = free[i]
            if (p.width >= w && p.height >= h && (best < 0 || p.width * p.height < free[best].width * free[best].height)) best = i
        }
        if (best >= 0) return free.removeAt(best).also { inUse.add(it) }
        val tw = (w + POOL_STEP - 1) / POOL_STEP * POOL_STEP
        val th = (h + POOL_STEP - 1) / POOL_STEP * POOL_STEP
        return try {
            val device = RenderSystem.getDevice()
            //? if >= 26.2 {
            val format = GpuFormat.RGBA8_UNORM
            //?} else
            //val format = TextureFormat.RGBA8
            val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_DST or GpuTexture.USAGE_COPY_SRC
            val texture = device.createTexture({ "PolyCompose backdrop" }, usage, format, tw, th, 1, 1)
            Pooled(texture, device.createTextureView(texture), tw, th).also { inUse.add(it) }
        } catch (e: Exception) {
            LOG.warn("PolyCompose could not create a backdrop texture", e)
            null
        }
    }

    private fun trimPool() {
        while (free.size > MAX_POOLED) close(free.removeAt(0))
    }

    private fun close(p: Pooled) {
        p.view.close()
        p.texture.close()
    }

    private fun sample(src: Src, spec: BackdropSpec, h: Float, ctx: Ctx) {
        val l = spec.backdrop.chain.liquid
        val adaptive = l != null && !l.clear
        if (!adaptive && !spec.backdrop.sample) return
        if (pendingReads.size >= MAX_PENDING_READS) return
        val id = if (l != null && !l.clear) l.sampleId else 0
        val region = spec.sampleKey
        val key: Any = when {
            id != 0 -> if (PolyLiquidSamples.due(id)) id else return
            region != null -> if (region.due()) region else return
            sampledFrame || !BackdropSample.due() -> return
            else -> GlobalSample.also { sampledFrame = true }
        }
        readGrid(src, spec, h, key, ctx)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun readGrid(src: Src, spec: BackdropSpec, h: Float, key: Any, ctx: Ctx) {
        val wsx = (spec.x1 - spec.x0) / SAMPLE_GRID
        val wsy = (spec.y1 - spec.y0) / SAMPLE_GRID
        if (wsx <= 0f || wsy <= 0f || !ready(blur)) return
        val target = sampleGrid() ?: return
        val my0 = if (bottomUp) h - spec.y1 else spec.y0
        var buffer: GpuBuffer? = null
        try {
            val kx = src.ws * src.texW
            val ky = src.ws * src.texH
            val d = blurData
            d[0] = SAMPLE_GRID.toFloat(); d[1] = SAMPLE_GRID.toFloat(); d[2] = 0f; d[3] = 0f
            d[4] = wsx / kx; d[5] = wsy / ky; d[6] = (spec.x0 - src.ox) / kx; d[7] = (my0 - src.oy) / ky
            d[8] = 0f; d[9] = 0f; d[10] = 0f; d[11] = 0f
            clampOf(src, d, 12)
            val buf = ctx.uniforms.buffer(d, BLUR_FLOATS)
            pass(target.view, false) { rp ->
                setPipeline(rp, blur)
                rp.setUniform("PolyBlur", buf)
                bindSource(rp, src.view, true)
                drawTriangle(rp)
            }
            val device = RenderSystem.getDevice()
            val b = sampleBuffers.removeLastOrNull()
                ?: device.createBuffer({ "PolyCompose backdrop sample" }, GpuBuffer.USAGE_COPY_DST or GpuBuffer.USAGE_MAP_READ, SAMPLE_BYTES)
            buffer = b
            val read = PendingRead(b, key, frame)
            device.createCommandEncoder().copyTextureToBuffer(target.texture, b, 0L, { read.done = true }, 0, 0, 0, SAMPLE_GRID, SAMPLE_GRID)
            pendingReads += read
        } catch (e: Exception) {
            warnSample(e)
            buffer?.close()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun sampleGrid(): Pooled? {
        grid?.let { return it }
        return try {
            val device = RenderSystem.getDevice()
            //? if >= 26.2 {
            val format = GpuFormat.RGBA8_UNORM
            //?} else
            //val format = TextureFormat.RGBA8
            val usage = GpuTexture.USAGE_TEXTURE_BINDING or GpuTexture.USAGE_RENDER_ATTACHMENT or GpuTexture.USAGE_COPY_SRC
            val texture = device.createTexture({ "PolyCompose backdrop sample" }, usage, format, SAMPLE_GRID, SAMPLE_GRID, 1, 1)
            Pooled(texture, device.createTextureView(texture), SAMPLE_GRID, SAMPLE_GRID).also { grid = it }
        } catch (e: Exception) {
            warnSample(e)
            null
        }
    }

    private fun warnSample(e: Throwable?) {
        if (warnedSample) return
        warnedSample = true
        LOG.warn("PolyCompose backdrop sampling is unavailable on this render target", e)
    }

    private fun drainReads() {
        var i = 0
        while (i < pendingReads.size) {
            val r = pendingReads[i]
            if (!r.done || r.frame >= frame) {
                i++
                continue
            }
            pendingReads.removeAt(i)
            publish(r)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun publish(read: PendingRead) {
        val buffer = read.buffer
        if (buffer.isClosed) return
        var keep = false
        try {
            //? if >= 26.2 {
            val view = buffer.map(true, false)
            //?} else
            //val view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)
            try {
                val data = view.data()
                val argb = IntArray(SAMPLE_GRID * SAMPLE_GRID) {
                    val row = it / SAMPLE_GRID
                    val o = ((if (bottomUp) SAMPLE_GRID - 1 - row else row) * SAMPLE_GRID + it % SAMPLE_GRID) * 4
                    (0xFF shl 24) or ((data.get(o).toInt() and 0xFF) shl 16) or ((data.get(o + 1).toInt() and 0xFF) shl 8) or (data.get(o + 2).toInt() and 0xFF)
                }
                when (val key = read.key) {
                    is Int -> PolyLiquidSamples.publish(key, argb)
                    is BackdropSample.Region -> key.publish(argb)
                    else -> BackdropSample.publish(argb)
                }
            } finally {
                view.close()
            }
            keep = sampleBuffers.size < MAX_SAMPLE_BUFFERS
        } catch (e: Exception) {
            warnSample(e)
        } finally {
            if (keep) sampleBuffers += buffer else buffer.close()
        }
    }

    private const val POOL_STEP = 64
    private const val MAX_PENDING_READS = 8
    private const val MAX_SAMPLE_BUFFERS = 4
    private const val SAMPLE_BYTES = SAMPLE_GRID * SAMPLE_GRID * 4L
    private const val GAME_FRAME_SCALE = 4f
}
//?}
