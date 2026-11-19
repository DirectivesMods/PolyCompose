package org.polyfrost.compose.minecraft.render

import com.mojang.blaze3d.pipeline.RenderTarget
import java.util.concurrent.CopyOnWriteArrayList
import org.slf4j.LoggerFactory

//? if < 1.21.10 {
/*import org.polyfrost.compose.minecraft.platform.HostEvents
*///?}

//? if >= 26.2 {
import com.mojang.renderpearl.api.GpuFormat
import org.joml.Vector4f
//?}

//? if >= 1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem
//?}

//? if > 1.8.9 {
import com.mojang.blaze3d.pipeline.TextureTarget
//?}

//? if < 1.21.4 {
/*import net.minecraft.client.Minecraft
*///?}

class OffscreenTarget(val depth: Boolean = true) {
    var target: RenderTarget? = null
        private set
    private var lastW = -1
    private var lastH = -1

    fun resolve(w: Int, h: Int): Boolean {
        if (target != null && lastW == w && lastH == h) return true
        destroy()
        //? if < 1.21.5
        //val bound = RenderTargetFbo.saveBoundTarget()
        try {
            //? if >= 26.3 {
            val rt = TextureTarget(
                null, w, h,
                GpuFormat.RGBA8_UNORM,
                if (depth) GpuFormat.D32_FLOAT else null,
            )
            //?} else if >= 26.2 {
            /*val rt = TextureTarget(null, w, h, true, GpuFormat.RGBA8_UNORM)
            *///?} else if >= 1.21.11 {
            /*val rt = TextureTarget(null, w, h, true)
            *///?} else if >= 1.21.5 {
            /*val rt = TextureTarget(null, w, h, depth)
            *///?} else if >= 1.21.4 {
            /*val rt = TextureTarget(w, h, true)
            *///?} elif > 1.8.9 {
            /*val rt = TextureTarget(w, h, true, Minecraft.ON_OSX)
            *///?} else
            //val rt = RenderTarget(w, h, true)
            target = rt
            //? if < 1.21.5 {
            /*rt.setClearColor(0f, 0f, 0f, 0f)
            *///?}
            if (!GpuBackend.isVulkan && RenderTargetFbo.getFboId(rt) <= 0) {
                destroy()
                return false
            }
            lastW = w; lastH = h
            return true
        } catch (t: Throwable) {
            LOG.warn("Failed to create offscreen target", t)
            destroy()
            return false
        }
        //? if < 1.21.5 {
        /*finally {
            RenderTargetFbo.restoreBoundTarget(bound)
        }
        *///?}
    }

    fun clear() {
        val rt = target ?: return
        //? if < 1.21.5
        //val bound = RenderTargetFbo.saveBoundTarget()
        //? if >= 26.2 {
        val colorTex = rt.colorTexture ?: return
        RenderSystem.getDevice().createCommandEncoder()
            .clearColorTexture(colorTex, Vector4f(0f, 0f, 0f, 0f))
        //? } else if >= 1.21.5 {
        /*val colorTex = rt.colorTexture ?: return
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        encoder.clearColorTexture(colorTex, 0)
        //? if < 1.21.10 {
        /*        rt.depthTexture?.let { encoder.clearDepthTexture(it, 1.0) }
        *///?}
        *///?} elif >= 1.21.4 {
        /*rt.clear()
        *///?} elif > 1.8.9 {
        /*rt.clear(Minecraft.ON_OSX)
        *///?} else
        //rt.clear()
        //? if < 1.21.5
        //RenderTargetFbo.restoreBoundTarget(bound)
    }

    fun destroy() {
        //? if < 1.21.5
        //val bound = RenderTargetFbo.saveBoundTarget()
        target?.destroyBuffers(); target = null
        //? if < 1.21.5
        //RenderTargetFbo.restoreBoundTarget(bound)
        lastW = -1; lastH = -1
    }

    fun dispose() {
        destroy()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger("PolyCompose/OffscreenTarget")
    }
}

interface TextureRenderer {
    fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float)
}

object FramePresentHooks {
    private val LOG = LoggerFactory.getLogger("PolyCompose/FramePresentHooks")

    private class Deferred(var frames: Int, val action: () -> Unit)

    private val callbacks = CopyOnWriteArrayList<() -> Unit>()
    private val deferred = ArrayList<Deferred>()

    init {
        //? if < 1.21.10 {
        /*HostEvents.onFrameStart { run() }
        *///?}
    }

    fun add(callback: () -> Unit): AutoCloseable {
        callbacks += callback
        return AutoCloseable { callbacks.remove(callback) }
    }

    fun later(frames: Int, action: () -> Unit) {
        synchronized(deferred) { deferred += Deferred(frames, action) }
    }

    @JvmStatic
    fun run() {
        if (callbacks.isEmpty() && synchronized(deferred) { deferred.isEmpty() }) return
        for (callback in callbacks) {
            try {
                callback()
            } catch (t: Throwable) {
                LOG.warn("A frame present hook failed", t)
            }
        }
        val due = ArrayList<Deferred>()
        synchronized(deferred) {
            val iterator = deferred.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (--entry.frames > 0) continue
                iterator.remove()
                due += entry
            }
        }
        for (entry in due) {
            try {
                entry.action()
            } catch (t: Throwable) {
                LOG.warn("A deferred frame task failed", t)
            }
        }
    }
}
