package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.polyfrost.compose.host.BackdropSample
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.render.PolyLiquidSamples
import org.slf4j.LoggerFactory
import java.nio.ByteBuffer

internal object GlGlass {
    class Key(val spec: GlEffects.Spec)

    private class Slot(val pbo: Int) {
        var pending = false
        var frame = 0L
        var usedAt = 0L
        var fence = 0L
    }

    object Global

    private val LOG = LoggerFactory.getLogger(GlGlass::class.java)

    var failed = false
        private set
    var target = -1
        private set
    var targetW = 0
        private set
    var targetH = 0
        private set
    private var fresh = false
    private var sampleTex = 0
    private var sampleFbo = 0
    private val slots = HashMap<Any, Slot>()
    private var anyPending = false
    private var sampleFailures = 0
    private var sampleRetryAt = 0L
    private var sampleDisabled = false
    private var sweptAt = 0L
    private var syncChecked = false
    private var syncSupported = false
    private val sampleBytes: ByteBuffer by lazy { BufferUtils.createByteBuffer(SAMPLE * SAMPLE * 4) }

    private const val SAMPLE = 8
    private const val MAX_SAMPLE_SLOTS = 16
    private const val MAX_SAMPLE_FAILURES = 3
    private const val RETRY_NANOS = 5_000_000_000L
    private const val IDLE_NANOS = 5_000_000_000L
    private const val SWEEP_NANOS = 1_000_000_000L

    fun begin(target: Int, w: Int, h: Int) {
        this.target = target
        targetW = w
        targetH = h
        fresh = true
        if (slots.isNotEmpty()) {
            val now = System.nanoTime()
            if (now - sweptAt > SWEEP_NANOS) {
                sweptAt = now
                sweep(now)
            }
        }
    }

    fun end() {
        target = -1
    }

    fun takeFresh(): Boolean = fresh.also { fresh = false }

    fun fail(e: Throwable) {
        LOG.warn("PolyCompose backdrop effects are unavailable", e)
        failed = true
        if (target >= 0) GlFbo.bind(GL30.GL_FRAMEBUFFER, target)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
        release()
    }

    fun warm(): Boolean {
        if (failed) return true
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            return GlEffects.warm()
        } finally {
            GL20.glUseProgram(previousProgram)
        }
    }

    fun release() {
        GlEffects.release()
        releaseSample()
    }

    private fun releaseSample() {
        if (sampleFbo != 0) GlFbo.delete(sampleFbo)
        if (sampleTex != 0) GL11.glDeleteTextures(sampleTex)
        for (slot in slots.values) {
            deleteFence(slot)
            GL15.glDeleteBuffers(slot.pbo)
        }
        slots.clear()
        anyPending = false
        sampleFbo = 0
        sampleTex = 0
    }

    private fun sweep(now: Long) {
        val it = slots.values.iterator()
        while (it.hasNext()) {
            val slot = it.next()
            if (!slot.pending && now - slot.usedAt > IDLE_NANOS) {
                deleteFence(slot)
                GL15.glDeleteBuffers(slot.pbo)
                it.remove()
            }
        }
    }

    private fun evictOldest() {
        var oldest: Any? = null
        var oldestAt = Long.MAX_VALUE
        for ((key, slot) in slots) {
            if (!slot.pending && slot.usedAt < oldestAt) {
                oldest = key
                oldestAt = slot.usedAt
            }
        }
        val slot = slots.remove(oldest ?: return) ?: return
        deleteFence(slot)
        GL15.glDeleteBuffers(slot.pbo)
    }

    private fun syncSupported(): Boolean {
        if (!syncChecked) {
            syncChecked = true
            syncSupported = runCatching { GL.getCapabilities().OpenGL32 }.getOrDefault(false)
        }
        return syncSupported
    }

    private fun ready(slot: Slot): Boolean {
        val fence = slot.fence
        if (fence == 0L) return true
        val status = GL32.glClientWaitSync(fence, 0, 0L)
        return status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED || status == GL32.GL_WAIT_FAILED
    }

    private fun deleteFence(slot: Slot) {
        val fence = slot.fence
        if (fence == 0L) return
        slot.fence = 0L
        GL32.glDeleteSync(fence)
    }

    fun resolve() {
        if (!anyPending) return
        val frame = HostEvents.frame
        var still = false
        var previousPackBuffer = -1
        try {
            for ((key, slot) in slots) {
                if (!slot.pending) continue
                if (slot.frame >= frame || !ready(slot)) {
                    still = true
                    continue
                }
                slot.pending = false
                deleteFence(slot)
                if (previousPackBuffer < 0) previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo)
                val bytes = sampleBytes
                bytes.clear()
                GL15.glGetBufferSubData(GL21.GL_PIXEL_PACK_BUFFER, 0L, bytes)
                val argb = IntArray(SAMPLE * SAMPLE) {
                    val o = it * 4
                    (0xFF shl 24) or ((bytes.get(o).toInt() and 0xFF) shl 16) or ((bytes.get(o + 1).toInt() and 0xFF) shl 8) or (bytes.get(o + 2).toInt() and 0xFF)
                }
                when (key) {
                    is Int -> PolyLiquidSamples.publish(key, argb)
                    is BackdropSample.Region -> key.publish(argb)
                    else -> BackdropSample.publish(argb)
                }
            }
            anyPending = still
        } catch (e: Throwable) {
            sampleFailed(e)
        } finally {
            if (previousPackBuffer >= 0) GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer)
        }
    }

    fun sample(fbo: Int, rect: IntArray, flip: Boolean, key: Any) {
        if (sampleDisabled || !GlFbo.canBlit || rect[2] <= rect[0] || rect[3] <= rect[1]) return
        val now = System.nanoTime()
        if (now < sampleRetryAt) return
        val previousPackBuffer = GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING)
        val previousRowLength = GL11.glGetInteger(GL11.GL_PACK_ROW_LENGTH)
        val previousSkipRows = GL11.glGetInteger(GL11.GL_PACK_SKIP_ROWS)
        val previousSkipPixels = GL11.glGetInteger(GL11.GL_PACK_SKIP_PIXELS)
        val previousAlignment = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT)
        try {
            if (sampleTex == 0) {
                sampleTex = GL11.glGenTextures()
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sampleTex)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, SAMPLE, SAMPLE, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
                sampleFbo = GlFbo.gen()
                GlFbo.bind(GL30.GL_FRAMEBUFFER, sampleFbo)
                GlFbo.attachColor(sampleTex)
                check(GlFbo.status() == GL30.GL_FRAMEBUFFER_COMPLETE) { "tint sample framebuffer is incomplete" }
            }
            var slot = slots[key]
            if (slot == null) {
                if (slots.size >= MAX_SAMPLE_SLOTS) evictOldest()
                if (slots.size >= MAX_SAMPLE_SLOTS) return
                slot = Slot(GL15.glGenBuffers())
                slots[key] = slot
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo)
                GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, (SAMPLE * SAMPLE * 4).toLong(), GL15.GL_STREAM_READ)
            }
            slot.usedAt = now
            if (slot.pending) return
            GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, fbo)
            GlFbo.bind(GL30.GL_DRAW_FRAMEBUFFER, sampleFbo)
            val top = if (flip) SAMPLE else 0
            GlFbo.blit(rect[0], rect[1], rect[2], rect[3], 0, top, SAMPLE, SAMPLE - top, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR)
            GlFbo.bind(GL30.GL_READ_FRAMEBUFFER, sampleFbo)
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, slot.pbo)
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0)
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0)
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1)
            GL11.glReadPixels(0, 0, SAMPLE, SAMPLE, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
            if (syncSupported()) slot.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
            slot.pending = true
            slot.frame = HostEvents.frame
            anyPending = true
            sampleFailures = 0
        } catch (e: Throwable) {
            sampleFailed(e)
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, previousRowLength)
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, previousSkipRows)
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, previousSkipPixels)
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, previousAlignment)
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, previousPackBuffer)
        }
    }

    private fun sampleFailed(e: Throwable) {
        sampleFailures++
        releaseSample()
        if (sampleFailures >= MAX_SAMPLE_FAILURES) {
            sampleDisabled = true
            LOG.warn("PolyCompose glass tint sampling is unavailable", e)
        } else {
            sampleRetryAt = System.nanoTime() + RETRY_NANOS * sampleFailures
            LOG.warn("PolyCompose glass tint sampling failed; retrying later", e)
        }
    }
}
