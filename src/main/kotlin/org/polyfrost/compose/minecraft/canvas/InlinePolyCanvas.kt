package org.polyfrost.compose.minecraft.canvas

import net.minecraft.client.Minecraft
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.minecraft.platform.HostEvents

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}

//? if >= 1.21.11 {
import org.polyfrost.compose.minecraft.render.blaze.BlazeRenderer
import org.polyfrost.compose.minecraft.render.blaze.GlyphAtlas
import org.polyfrost.compose.minecraft.render.blaze.Recording
import org.polyfrost.compose.render.RenderContext
//?} else {
/*import org.polyfrost.compose.minecraft.render.RenderTargetFbo
import org.polyfrost.compose.minecraft.render.gl.GlHost
import org.polyfrost.compose.minecraft.render.gl.GlRenderer
import org.polyfrost.compose.minecraft.render.gl.StoredGLState
*///?}

//? if >= 1.21.8 && < 1.21.11 {
/*import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier
import org.polyfrost.compose.minecraft.render.OffscreenTarget
*///?}

//? if = 1.21.1 {
/*import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.resources.ResourceManager
import org.polyfrost.compose.minecraft.render.OffscreenTarget
*///?}

//? if = 1.8.9 {
/*import org.polyfrost.compose.minecraft.platform.McWindow
*///?}

object InlinePolyCanvas {
    private val surfaces = ArrayList<InlineCanvasSurface>()
    private var used = 0
    private var frame = 0L
    private var frameHook: AutoCloseable? = null

    //? if > 1.8.9 {
    @JvmStatic
    fun draw(graphics: GuiGraphicsExtractor?, block: (PolyCanvas) -> Unit): Boolean = next().draw(graphics, block)
    //?} else {
    /*
    @JvmStatic
    fun draw(block: (PolyCanvas) -> Unit): Boolean = next().draw(block)
    *///?}

    private fun next(): InlineCanvasSurface {
        val surface = surfaces.getOrNull(used) ?: InlineCanvasSurface("pool_${surfaces.size}").also(surfaces::add)
        used++
        surface.lastUsedFrame = frame
        if (frameHook == null) frameHook = HostEvents.onFrameEnd { endFrame() }
        return surface
    }

    private fun endFrame() {
        frame++
        used = 0
        while (surfaces.isNotEmpty() && frame - surfaces[surfaces.size - 1].lastUsedFrame > IDLE_FRAMES) {
            surfaces.removeAt(surfaces.size - 1).close()
        }
        if (surfaces.isEmpty()) {
            frameHook?.close()
            frameHook = null
        }
    }

    private const val IDLE_FRAMES = 120
}

internal class InlineCanvasSurface(val name: String) {
    var lastUsedFrame = 0L

    //? if >= 1.21.11 {
    private var renderer: BlazeRenderer? = null

    fun draw(ctx: GuiGraphicsExtractor?, block: (PolyCanvas) -> Unit): Boolean {
        if (ctx == null) return false
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        val renderer = renderer?.takeIf { it.matches(guiScale) }?.also { it.reset() } ?: BlazeRenderer(guiScale).also { renderer = it }
        block(PolyCanvas(renderer))
        val ops = renderer.finish()
        for (i in ops.indices) ops[i](ctx)
        GlyphAtlas.uploadDirty()
        return true
    }

    private var recording: Recording? = null
    private var recordedW = -1
    private var recordedH = -1
    private var recorded = false
    private var recordBlock: ((PolyCanvas) -> Unit)? = null
    private val record: (RenderContext) -> Unit = { recordBlock?.invoke(PolyCanvas(it.renderer)) }

    fun draw(ctx: GuiGraphicsExtractor?, dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (ctx == null) return false
        val window = Minecraft.getInstance().window
        val w = window.width
        val h = window.height
        val recording = recording ?: Recording().also { this.recording = it }
        val redraw = dirty || !recorded || w != recordedW || h != recordedH
        recorded = false
        recordBlock = block
        try {
            recording.render(ctx, redraw, record)
        } finally {
            recordBlock = null
        }
        recordedW = w
        recordedH = h
        recorded = true
        return true
    }

    fun close() {
        renderer = null
        recording = null
        recorded = false
    }
    //?} elif >= 1.21.8 {
    /*private val textureId = Identifier.fromNamespaceAndPath("polycompose", "inline_canvas/" + pathSafe(name))
    private val offscreen = OffscreenTarget(depth = false)
    private var texture: TargetTexture? = null

    private class TargetTexture : AbstractTexture() {
        fun set(target: RenderTarget) {
            texture = target.colorTexture
            textureView = target.colorTextureView
        }

        override fun close() {
            texture = null
            textureView = null
        }
    }

    fun draw(ctx: GuiGraphicsExtractor?, block: (PolyCanvas) -> Unit): Boolean {
        if (ctx == null || !GlHost.legacyGl) return false
        val mc = Minecraft.getInstance()
        val w = mc.window.width
        val h = mc.window.height
        if (w <= 0 || h <= 0 || !offscreen.resolve(w, h)) return false
        val target = offscreen.target ?: return false
        val fbo = RenderTargetFbo.getFboId(target)
        retained = null
        if (!drawGl(fbo, w, h, topDown = true, clear = true, block)) return false
        hasBounds = pooled?.drawnBounds(bounds, w, h) == true
        blit(mc, ctx, target, w, h)
        return true
    }

    private val bounds = IntArray(4)
    private var hasBounds = false

    private var retained: RenderTarget? = null
    private var retainedScale = 0f

    fun draw(ctx: GuiGraphicsExtractor?, dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (ctx == null || !GlHost.legacyGl) return false
        val mc = Minecraft.getInstance()
        val w = mc.window.width
        val h = mc.window.height
        if (w <= 0 || h <= 0 || !offscreen.resolve(w, h)) return false
        val target = offscreen.target ?: return false
        val guiScale = mc.window.guiScale.toFloat()
        if (dirty || target !== retained || guiScale != retainedScale) {
            retained = null
            if (!drawGl(RenderTargetFbo.getFboId(target), w, h, topDown = true, clear = true, block)) return false
            hasBounds = pooled?.drawnBounds(bounds, w, h) == true
            retained = target
            retainedScale = guiScale
        }
        blit(mc, ctx, target, w, h)
        return true
    }

    private fun blit(mc: Minecraft, ctx: GuiGraphicsExtractor, target: RenderTarget, w: Int, h: Int) {
        if (!hasBounds) return
        val tex = texture ?: TargetTexture().also {
            texture = it
            mc.textureManager.register(textureId, it)
        }
        tex.set(target)
        val guiScale = mc.window.guiScale.toFloat()
        ctx.pose().pushMatrix()
        try {
            ctx.pose().scale(1f / guiScale, 1f / guiScale)
            val b = bounds
            ctx.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, textureId, b[0], b[1], b[0].toFloat(), b[1].toFloat(), b[2] - b[0], b[3] - b[1], w, h)
        } finally {
            ctx.pose().popMatrix()
        }
    }

    fun close() {
        retained = null
        hasBounds = false
        pooled = null
        offscreen.destroy()
        if (texture != null) {
            texture = null
            Minecraft.getInstance().textureManager.release(textureId)
        }
    }

    private fun pathSafe(raw: String): String =
        raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "_-./") it else '_' }.joinToString("")
    *///?} elif > 1.8.9 {
    /*fun draw(ctx: GuiGraphicsExtractor?, block: (PolyCanvas) -> Unit): Boolean {
        if (!GlHost.legacyGl) return false
        //? if = 1.21.1 {
        /^if (ctx != null && !GlHost.vanillaPipeline) return drawCached(ctx, true, block)
        ^///?}
        ctx?.flush()
        val mc = Minecraft.getInstance()
        //? if = 1.21.1 {
        /^if (GlHost.vanillaPipeline) {
            val renderer = GlRenderer()
            block(PolyCanvas(renderer))
            mc.mainRenderTarget.bindWrite(true)
            renderer.drawVanilla(mc.window.width, mc.window.height, topDown = false)
            return true
        }
        ^///?}
        return drawGl(RenderTargetFbo.getFboId(mc.mainRenderTarget), mc.window.width, mc.window.height, topDown = false, clear = false, block)
    }

    fun draw(ctx: GuiGraphicsExtractor?, dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (!GlHost.legacyGl) return false
        //? if = 1.21.1 {
        /^if (ctx != null && !GlHost.vanillaPipeline) return drawCached(ctx, dirty, block)
        ^///?}
        ctx?.flush()
        val mc = Minecraft.getInstance()
        val w = mc.window.width
        val h = mc.window.height
        //? if = 1.21.1 {
        /^if (GlHost.vanillaPipeline) {
            val frame = vanillaFrame?.takeIf { !dirty && w == retainedW && h == retainedH && it.matches() } ?: run {
                vanillaFrame = null
                GlRenderer().also { block(PolyCanvas(it)) }
            }
            vanillaFrame = frame
            retainedW = w
            retainedH = h
            mc.mainRenderTarget.bindWrite(true)
            frame.drawVanilla(w, h, topDown = false)
            return true
        }
        ^///?}
        return drawRetained(RenderTargetFbo.getFboId(mc.mainRenderTarget), w, h, dirty, block)
    }

    //? if = 1.21.1 {
    /^private var vanillaFrame: GlRenderer? = null
    private val textureId = Identifier.fromNamespaceAndPath("polycompose", "inline_canvas/" + pathSafe(name))
    private val offscreen = OffscreenTarget(depth = false)
    private var texture: TargetTexture? = null
    private val bounds = IntArray(4)
    private var hasBounds = false
    private var cached: RenderTarget? = null
    private var cachedScale = 0f

    private class TargetTexture : AbstractTexture() {
        fun set(glId: Int) {
            id = glId
        }

        override fun load(manager: ResourceManager) {}

        override fun releaseId() {
            id = -1
        }
    }

    private fun drawCached(ctx: GuiGraphicsExtractor, dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        val mc = Minecraft.getInstance()
        val w = mc.window.width
        val h = mc.window.height
        if (w <= 0 || h <= 0 || !offscreen.resolve(w, h)) return false
        val target = offscreen.target ?: return false
        val guiScale = mc.window.guiScale.toFloat()
        if (dirty || target !== cached || guiScale != cachedScale) {
            cached = null
            if (!drawGl(RenderTargetFbo.getFboId(target), w, h, topDown = true, clear = true, block)) return false
            hasBounds = pooled?.drawnBounds(bounds, w, h) == true
            cached = target
            cachedScale = guiScale
        }
        if (!hasBounds) return true
        val tex = texture ?: TargetTexture().also {
            texture = it
            mc.textureManager.register(textureId, it)
        }
        tex.set(RenderTargetFbo.getColorTexId(target))
        val b = bounds
        ctx.pose().pushPose()
        RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
        try {
            ctx.pose().scale(1f / guiScale, 1f / guiScale, 1f)
            ctx.blit(textureId, b[0], b[1], b[0].toFloat(), b[1].toFloat(), b[2] - b[0], b[3] - b[1], w, h)
        } finally {
            RenderSystem.defaultBlendFunc()
            RenderSystem.disableBlend()
            ctx.pose().popPose()
        }
        return true
    }

    private fun pathSafe(raw: String): String =
        raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9' || it in "_-./") it else '_' }.joinToString("")
    ^///?}

    fun close() {
        //? if = 1.21.1 {
        /^vanillaFrame = null
        cached = null
        hasBounds = false
        offscreen.destroy()
        if (texture != null) {
            texture = null
            Minecraft.getInstance().textureManager.release(textureId)
        }
        ^///?}
        pooled = null
        releaseRetained()
    }
    *///?} else {
    /*fun draw(block: (PolyCanvas) -> Unit): Boolean {
        if (!GlHost.legacyGl) return false
        GlHost.flushLegacyBatches()
        return drawGl(GlHost.mainDrawFbo(), McWindow.viewportWidth(), McWindow.viewportHeight(), topDown = false, clear = false, block)
    }

    fun draw(dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (!GlHost.legacyGl) return false
        GlHost.flushLegacyBatches()
        return drawRetained(GlHost.mainDrawFbo(), McWindow.viewportWidth(), McWindow.viewportHeight(), dirty, block)
    }

    fun close() {
        pooled = null
        releaseRetained()
    }
    *///?}

    //? if < 1.21.11 {
    /*private val glState = StoredGLState(exact = true)
    private var pooled: GlRenderer? = null

    private fun drawGl(fbo: Int, w: Int, h: Int, topDown: Boolean, clear: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (fbo < 0 || w <= 0 || h <= 0) return false
        glState.capture()
        try {
            val renderer = pooled?.takeIf { it.matches() }?.also { it.reset() } ?: GlRenderer().also { pooled = it }
            block(PolyCanvas(renderer))
            renderer.draw(fbo, w, h, topDown, clear)
        } finally {
            glState.restore()
        }
        return true
    }
    *///?}

    //? if < 1.21.8 {
    /*private var layer: GlRenderer.Layer? = null
    private var retainedFrame: GlRenderer? = null
    private var retainedW = -1
    private var retainedH = -1

    private fun drawRetained(fbo: Int, w: Int, h: Int, dirty: Boolean, block: (PolyCanvas) -> Unit): Boolean {
        if (fbo < 0 || w <= 0 || h <= 0) return false
        val clean = retainedFrame?.takeIf { !dirty && w == retainedW && h == retainedH && it.matches() }
        if (clean != null && layer?.isEmpty == true && clean.isEmpty()) return true
        glState.capture()
        try {
            val layer = layer ?: GlRenderer.Layer(coalesce = true).also { this.layer = it }
            if (clean != null) {
                layer.take(clean)
            } else {
                val renderer = retainedFrame?.takeIf { it.matches() }?.also { it.reset() } ?: GlRenderer()
                retainedFrame = null
                block(PolyCanvas(renderer))
                layer.take(renderer)
                retainedFrame = renderer
                retainedW = w
                retainedH = h
            }
            GlRenderer.Layer.draw(fbo, w, h, topDown = false, clear = false, layer, emptyLayer)
        } finally {
            glState.restore()
        }
        return true
    }

    private fun releaseRetained() {
        layer = null
        retainedFrame = null
        retainedW = -1
        retainedH = -1
    }

    private val emptyLayer = GlRenderer.Layer()
    *///?}
}
