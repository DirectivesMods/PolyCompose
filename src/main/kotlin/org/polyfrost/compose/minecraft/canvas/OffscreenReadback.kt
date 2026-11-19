package org.polyfrost.compose.minecraft.canvas

import com.mojang.blaze3d.pipeline.RenderTarget
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.render.PolyBitmap

//? if >= 1.21.8 {
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.buffers.GpuBuffer
//? if >= 1.21.11
import org.polyfrost.compose.minecraft.render.blaze.topDownTargets
//?} else {
/*import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.lwjgl.system.MemoryUtil
import org.polyfrost.compose.minecraft.preview.McFrameHooks
import org.polyfrost.compose.minecraft.render.GpuBackend
import org.polyfrost.compose.minecraft.render.RenderTargetFbo
import org.polyfrost.compose.minecraft.render.gl.GlFbo
*///?}

object OffscreenReadback {
    private const val BYTES_PER_PIXEL = 4

    val unsupportedReason: String? by lazy {
        //? if >= 1.21.8 {
        null
        //?} else {
        /*if (GpuBackend.isVulkan) "Readback needs OpenGL before Minecraft 1.21.8, and a non-OpenGL backend is active" else null
        *///?}
    }

    val isSupported: Boolean
        get() = unsupportedReason == null

    @JvmStatic
    fun read(target: RenderTarget): CompletableFuture<PolyBitmap> {
        val future = CompletableFuture<PolyBitmap>()
        unsupportedReason?.let {
            future.completeExceptionally(UnsupportedOperationException(it))
            return future
        }
        val start = Runnable {
            try {
                begin(target, future)
            } catch (t: Exception) {
                future.completeExceptionally(t)
            }
        }
        if (RenderThread.isCurrent()) start.run() else RenderThread.execute(start)
        return future
    }

    private fun flipped(src: ByteBuffer, width: Int, height: Int, topDown: Boolean = false): PolyBitmap {
        val row = width * BYTES_PER_PIXEL
        val pixels = ByteArray(row * height)
        val view = src.duplicate()
        for (y in 0 until height) {
            view.position(y * row)
            view.get(pixels, (if (topDown) y else height - 1 - y) * row, row)
        }
        return PolyBitmap(width, height, pixels)
    }

    //? if >= 1.21.8 {
    private fun begin(target: RenderTarget, future: CompletableFuture<PolyBitmap>) {
        val texture = target.colorTexture ?: error("Offscreen target has no colour texture")
        val width = texture.getWidth(0)
        val height = texture.getHeight(0)
        //? if >= 26.2 {
        val pixelSize = texture.format.blockSize()
        //?} else
        //val pixelSize = texture.format.pixelSize()
        check(pixelSize == BYTES_PER_PIXEL) { "Readback only supports 8-bit RGBA targets" }
        val size = width * height * BYTES_PER_PIXEL
        val device = RenderSystem.getDevice()
        val usage = GpuBuffer.USAGE_MAP_READ or GpuBuffer.USAGE_COPY_DST
        //? if >= 1.21.11 {
        val buffer = device.createBuffer({ "polycompose offscreen readback" }, usage, size.toLong())
        //?} else
        //val buffer = device.createBuffer({ "polycompose offscreen readback" }, usage, size)
        val encoder = device.createCommandEncoder()
        //? if >= 1.21.11 {
        val topDown = topDownTargets
        //?} else
        //val topDown = false
        val done = Runnable {
            try {
                //? if >= 26.2 {
                val view = buffer.map(true, false)
                //?} else
                //val view = RenderSystem.getDevice().createCommandEncoder().mapBuffer(buffer, true, false)
                try {
                    future.complete(flipped(view.data(), width, height, topDown))
                } finally {
                    view.close()
                }
            } catch (t: Exception) {
                future.completeExceptionally(t)
            } finally {
                buffer.close()
            }
        }
        try {
            //? if >= 1.21.11 {
            encoder.copyTextureToBuffer(texture, buffer, 0L, done, 0)
            //?} else
            //encoder.copyTextureToBuffer(texture, buffer, 0, done, 0)
        } catch (t: Exception) {
            buffer.close()
            throw t
        }
    }
    //?} else {
    /*private const val MAX_WAIT_FRAMES = 8

    private class Pending(val pbo: Int, val fence: Long, val width: Int, val height: Int, val future: CompletableFuture<PolyBitmap>) {
        var frames = 0
    }

    private val pending = ArrayList<Pending>()
    private var pollHook: AutoCloseable? = null

    private fun begin(target: RenderTarget, future: CompletableFuture<PolyBitmap>) {
        val fbo = RenderTargetFbo.getFboId(target)
        check(fbo > 0) { "Offscreen target has no framebuffer" }
        val width = target.width
        val height = target.height
        val previousRead = GlFbo.binding(GL30.GL_READ_FRAMEBUFFER)
        val previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
        val pbo = GL15.glGenBuffers()
        var fence = 0L
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo)
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, width.toLong() * height * BYTES_PER_PIXEL, GL15.GL_STREAM_READ)
            GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, fbo)
            GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
            if (GL.getCapabilities().OpenGL32) fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        } catch (t: Exception) {
            GL15.glDeleteBuffers(pbo)
            throw t
        } finally {
            GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, previousRead)
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack)
        }
        pending += Pending(pbo, fence, width, height, future)
        if (pollHook == null) pollHook = McFrameHooks.beforeUi(::poll)
    }

    private fun ready(entry: Pending): Boolean {
        if (entry.frames >= MAX_WAIT_FRAMES) return true
        if (entry.fence == 0L) return entry.frames >= 2
        val status = GL32.glClientWaitSync(entry.fence, 0, 0L)
        return status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED || status == GL32.GL_WAIT_FAILED
    }

    private fun poll() {
        val previousPack = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
        try {
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                entry.frames++
                if (!ready(entry)) continue
                iterator.remove()
                finish(entry)
            }
        } finally {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPack)
        }
        if (pending.isEmpty()) {
            pollHook?.close()
            pollHook = null
        }
    }

    private fun finish(entry: Pending) {
        val size = entry.width * entry.height * BYTES_PER_PIXEL
        val bytes = MemoryUtil.memAlloc(size)
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, entry.pbo)
            GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, bytes)
            entry.future.complete(flipped(bytes, entry.width, entry.height))
        } catch (t: Exception) {
            entry.future.completeExceptionally(t)
        } finally {
            MemoryUtil.memFree(bytes)
            if (entry.fence != 0L) GL32.glDeleteSync(entry.fence)
            GL15.glDeleteBuffers(entry.pbo)
        }
    }
    *///?}
}
