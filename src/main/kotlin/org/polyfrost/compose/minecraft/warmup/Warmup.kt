package org.polyfrost.compose.minecraft.warmup

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.minecraft.screen.sceneDensity
import org.slf4j.LoggerFactory

object Warmup {
    private val LOG = LoggerFactory.getLogger("PolyCompose/Warmup")
    private const val NANOS_PER_MILLI = 1_000_000L

    private val enabled = !"false".equals(System.getProperty("polycompose.warmup"), ignoreCase = true)
    private val started = AtomicBoolean()
    private val glyphsStarted = AtomicBoolean()
    private val pending = AtomicInteger(3)

    private var classes = ClassWarmup.Result(0, 0, 0L)
    private var glyphs = 0
    private var glyphNanos = 0L
    private var gpu = GpuWarmup.Result(0, 0L, 0L)
    private var gpuReported = false

    @JvmStatic
    fun start() {
        if (!enabled || !started.compareAndSet(false, true)) return
        Thread(::warmClasses, "PolyCompose Warmup").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }.start()
        HostEvents.onResourcesReloaded { RenderThread.execute(::resourcesReloaded) }
    }

    private fun warmClasses() {
        try {
            Thread.sleep(WarmupProfile.current.startDelayMillis)
        } catch (_: InterruptedException) {
            return
        }
        classes = ClassWarmup.run(Warmup::class.java.classLoader)
        partDone()
    }

    private fun resourcesReloaded() {
        if (glyphsStarted.compareAndSet(false, true)) {
            val density = runCatching { sceneDensity() }.getOrDefault(1f)
            BackgroundTasks.submit { warmGlyphs(density) }
        }
        GpuWarmup.run(::gpuDone)
    }

    private fun warmGlyphs(density: Float) {
        val begin = System.nanoTime()
        glyphs = runCatching { GlyphWarmup.run(density) }.onFailure { LOG.debug("Glyph warm-up failed", it) }.getOrDefault(0)
        glyphNanos = System.nanoTime() - begin
        partDone()
    }

    private fun gpuDone(result: GpuWarmup.Result) {
        if (gpuReported) return
        gpuReported = true
        gpu = result
        partDone()
    }

    private fun partDone() {
        if (pending.decrementAndGet() != 0) return
        val c = classes
        val g = gpu
        LOG.info(
            "Warm-up done in {} ms: {} classes ({} failed) in {} ms and {} glyph rasters in {} ms off the render thread, {} GPU steps in {} ms on it (longest {} ms)",
            (c.nanos + glyphNanos + g.nanos) / NANOS_PER_MILLI,
            c.loaded, c.failed, c.nanos / NANOS_PER_MILLI,
            glyphs, glyphNanos / NANOS_PER_MILLI,
            g.steps, g.nanos / NANOS_PER_MILLI, g.longestNanos / NANOS_PER_MILLI,
        )
    }
}
