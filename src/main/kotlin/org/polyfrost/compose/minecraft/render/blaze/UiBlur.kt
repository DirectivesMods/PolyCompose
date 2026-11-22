//~ main_render_target
package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.PostChain
import net.minecraft.client.renderer.PostPass
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryStack
import org.polyfrost.compose.minecraft.mixin.render.GuiRenderStateBlurAccessor
import org.polyfrost.compose.minecraft.mixin.render.PostChainAccessor
import org.polyfrost.compose.minecraft.mixin.render.PostPassAccessor
import org.polyfrost.compose.minecraft.render.OffscreenTarget
//? if >= 26.2 {
import java.util.Optional
import org.joml.Vector4fc
//?} else
//import java.util.OptionalInt

object UiBlur {
    private val CHAIN = Identifier.fromNamespaceAndPath("polycompose", "ui_blur")
    private val OUT = Identifier.fromNamespaceAndPath("polycompose", "ui_blur_out")
    private val TARGETS = setOf(PostChain.MAIN_TARGET_ID, OUT)
    private const val REUSE_NS = 1_000_000_000L
    private const val IDLE_DISPOSE_FRAMES = 120

    private val out = OffscreenTarget(depth = false)
    private val a = OffscreenTarget(depth = false)
    private val b = OffscreenTarget(depth = false)
    private var pending = false
    private var requested = false
    private var requestState: Any? = null
    private var owner: Any? = null
    private var shared = false
    private var handled = false
    private var reachedBlur = false
    private var early = false
    private var blurred: RenderTarget? = null
    private var blurredAt = 0L

    private var downInfo: GpuBuffer? = null
    private var blurInfo: GpuBuffer? = null
    private var infoKey = 0L
    private var compositor: BlazeRenderer? = null
    private var compositeView: GpuTextureView? = null
    private var compositeWidth = 0
    private var compositeHeight = 0
    private var compositeAlpha = Float.NaN
    private var idleFrames = 0

    fun extract(ctx: GuiGraphicsExtractor, alpha: Float) {
        if (alpha <= 0f || !request(ctx)) return
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val target = out.target ?: return
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        val view = target.colorTextureView
        val cached = compositor?.takeIf { it.matches(guiScale) }
        val renderer = if (
            cached != null && view != null && view === compositeView && !view.isClosed &&
            main.width == compositeWidth && main.height == compositeHeight && alpha == compositeAlpha
        ) {
            cached
        } else {
            val r = cached?.also { it.reset() } ?: BlazeRenderer(guiScale).also { compositor = it }
            r.texture(target, 0f, 0f, main.width.toFloat(), main.height.toFloat(), 0f, 1f, 1f, 0f, alpha)
            compositeView = view
            compositeWidth = main.width
            compositeHeight = main.height
            compositeAlpha = alpha
            r
        }
        val ops = renderer.finish()
        for (i in ops.indices) ops[i](ctx)
    }

    fun beginFrame() {
        requested = false
    }

    fun request(ctx: GuiGraphicsExtractor): Boolean {
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        if (!out.resolve((main.width + 3) / 4, (main.height + 3) / 4)) return false
        val state = ctx.guiRenderState
        requested = true
        if (pending && requestState === state) return true
        shared = !scheduleBlur(ctx)
        requestState = state
        owner = if (shared) null else state
        pending = true
        return true
    }

    private fun scheduleBlur(ctx: GuiGraphicsExtractor): Boolean {
        val accessor = ctx.guiRenderState as? GuiRenderStateBlurAccessor
        if (accessor != null) {
            if (accessor.`polycompose$getFirstStratumAfterBlur`() != Int.MAX_VALUE) return false
            ctx.blurBeforeThisStratum()
            return true
        }
        return try {
            ctx.blurBeforeThisStratum()
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    @JvmStatic
    fun mergeForeignBlur(state: Any): Boolean {
        if (!pending || owner !== state) return false
        shared = true
        return true
    }

    @JvmStatic
    fun beforeGuiRender() {
        if (early && pending) handled = process()
    }

    @JvmStatic
    fun processAtBlur(): Boolean {
        reachedBlur = true
        val ours = handled || process()
        if (ours) handled = true
        return ours && !shared
    }

    @JvmStatic
    fun afterGuiRender() {
        if (pending || handled) {
            early = !reachedBlur
            idleFrames = 0
        } else if (++idleFrames == IDLE_DISPOSE_FRAMES) {
            disposeTargets()
        }
        pending = false
        handled = false
        reachedBlur = false
        shared = false
        requestState = null
        owner = null
    }

    private fun disposeTargets() {
        out.dispose()
        a.dispose()
        b.dispose()
        blurred = null
        compositeView = null
        downInfo?.close()
        blurInfo?.close()
        downInfo = null
        blurInfo = null
        infoKey = 0L
    }

    val backdrop: GpuTextureView? get() = if (requested) out.target?.colorTextureView else null

    internal var fresh = false
        private set

    internal fun frameEnded() {
        fresh = false
    }

    val backdropWidth: Int get() = Minecraft.getInstance().gameRenderer.mainRenderTarget().width
    val backdropHeight: Int get() = Minecraft.getInstance().gameRenderer.mainRenderTarget().height

    @JvmStatic
    fun process(): Boolean {
        if (!pending) return false
        pending = false
        val target = out.target ?: return false
        val now = System.nanoTime()
        if (target === blurred && now - blurredAt < REUSE_NS && Minecraft.getInstance().isPaused) {
            fresh = true
            return true
        }
        val chain = chain() ?: return false
        val passes = (chain as PostChainAccessor).`polycompose$getPasses`()
        if (passes.size != 3) return false
        val w = target.width
        val h = target.height
        if (!a.resolve(w, h) || !b.resolve(w, h)) return false
        val main = Minecraft.getInstance().gameRenderer.mainRenderTarget()
        val down = downInfo(main.width, main.height, w, h)
        val blur = blurInfo ?: return false
        val src = main.colorTextureView ?: return false
        val ta = a.target ?: return false
        val tb = b.target ?: return false
        draw(passes[0], src, ta, down)
        draw(passes[1], ta.colorTextureView ?: return false, tb, blur)
        draw(passes[2], tb.colorTextureView ?: return false, target, blur)
        blurred = target
        blurredAt = now
        fresh = true
        return true
    }

    internal fun chain(): PostChain? = Minecraft.getInstance().shaderManager.getPostChain(CHAIN, TARGETS)

    private fun downInfo(mainW: Int, mainH: Int, w: Int, h: Int): GpuBuffer {
        val key = mainW.toLong() shl 48 or (mainH.toLong() shl 32) or (w.toLong() shl 16) or h.toLong()
        val down = downInfo
        if (down != null && key == infoKey) return down
        downInfo?.close()
        blurInfo?.close()
        infoKey = key
        blurInfo = info(w, h, w, h)
        return info(w, h, mainW, mainH).also { downInfo = it }
    }

    private fun info(outW: Int, outH: Int, inW: Int, inH: Int): GpuBuffer = MemoryStack.stackPush().use { stack ->
        val data = stack.malloc(16)
        data.putFloat(outW.toFloat()).putFloat(outH.toFloat()).putFloat(inW.toFloat()).putFloat(inH.toFloat()).flip()
        RenderSystem.getDevice().createBuffer({ "PolyCompose UI blur SamplerInfo" }, GpuBuffer.USAGE_UNIFORM, data)
    }

    private fun draw(pass: PostPass, input: GpuTextureView, output: RenderTarget, info: GpuBuffer) {
        val access = pass as PostPassAccessor
        val view = output.colorTextureView ?: return
        val sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
        //? if >= 26.2 {
        val clear = Optional.empty<Vector4fc>()
        //?} else
        //val clear = OptionalInt.empty()
        RenderSystem.getDevice().createCommandEncoder().createRenderPass({ "PolyCompose UI blur" }, view, clear).use { rp ->
            //? if >= 26.3 {
            rp.setPipeline(RenderSystem.getCompiledPipeline(access.`polycompose$getPipeline`()))
            //?} else
            //rp.setPipeline(access.`polycompose$getPipeline`())
            RenderSystem.bindDefaultUniforms(rp)
            rp.setUniform("SamplerInfo", info)
            for ((name, buffer) in access.`polycompose$getCustomUniforms`()) rp.setUniform(name, buffer)
            //? if >= 26.3 {
            rp.setUniform("InSampler", input, sampler)
            //?} else
            //rp.bindTexture("InSampler", input, sampler)
            //? if >= 26.2 {
            rp.draw(3, 1, 0, 0)
            //?} else
            //rp.draw(0, 3)
        }
    }
}
//?}
