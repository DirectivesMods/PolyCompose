package org.polyfrost.compose.minecraft.profile

//? if >= 1.21.11 {
//? if >= 26.2 {
import com.mojang.blaze3d.systems.RenderSystem
//?} else {
/*import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33
*///?}
//? if >= 26.3 {
import com.mojang.renderpearl.api.commands.GpuQueryPool
//?} elif >= 26.2
//import com.mojang.blaze3d.systems.GpuQueryPool
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileSeries
import org.slf4j.LoggerFactory

internal object EffectGpuTimer {
    const val COPY = 0
    const val BLUR = 1
    const val COMPOSITE = 2
    const val LAYER = 3

    const val BACKDROPS = 0
    const val LAYERS = 1
    const val COPIES = 2
    const val PASSES = 3

    private const val IDLE = -1
    private const val CATEGORIES = 4
    private const val COUNTERS = 4
    private const val FRAMES = 6
    private const val PER_FRAME = 256
    private const val GIVE_UP_FRAMES = 60L
    private const val SERIES_KEY = "gpu/effects"
    private val LOG = LoggerFactory.getLogger("PolyCompose/Profiler")

    @JvmField
    var on = false

    private val counts = LongArray(COUNTERS)
    private val slotCategories = Array(FRAMES) { IntArray(PER_FRAME) }
    private val slotUsed = IntArray(FRAMES)
    private val slotCounts = Array(FRAMES) { LongArray(COUNTERS) }
    private val slotFrame = LongArray(FRAMES)
    private val slotProfileFrame = LongArray(FRAMES)
    private val stamps = LongArray(PER_FRAME)
    private val timings = LongArray(CATEGORIES)
    private var head = 0L
    private var tail = 0L
    private var current = -1
    private var category = IDLE
    private var layerDepth = 0
    private var frames = 0L
    private var support = 0
    private var period = 1.0
    private var series: ProfileSeries? = null

    //? if >= 26.2 {
    private var pool: GpuQueryPool? = null
    //?} else
    //private var ids = IntArray(0)

    fun mark(category: Int) {
        if (on) markSlow(category)
    }

    fun end() {
        if (on) markSlow(IDLE)
    }

    fun count(counter: Int) {
        if (on) counts[counter]++
    }

    fun layerBegin() {
        if (!on) return
        layerDepth++
        counts[LAYERS]++
        markSlow(LAYER)
    }

    fun layerEnd() {
        if (!on) return
        if (layerDepth > 0) layerDepth--
        markSlow(IDLE)
    }

    fun endFrame(enabled: Boolean) {
        frames++
        if (current >= 0) close()
        if (head != tail) poll()
        on = enabled && supported()
        if (on) open()
    }

    private fun open() {
        counts.fill(0L)
        category = IDLE
        layerDepth = 0
        if (head - tail >= FRAMES) {
            current = -1
            return
        }
        current = (head % FRAMES).toInt()
        slotUsed[current] = 0
        head++
    }

    private fun close() {
        val slot = current
        current = -1
        val used = slotUsed[slot]
        if (category != IDLE && used > 0) slotCategories[slot][used - 1] = IDLE
        category = IDLE
        layerDepth = 0
        if (used < 2) {
            head--
            return
        }
        counts.copyInto(slotCounts[slot])
        slotFrame[slot] = frames
    }

    private fun markSlow(requested: Int) {
        val slot = current
        if (slot < 0) return
        var next = if (layerDepth > 0) LAYER else requested
        val used = slotUsed[slot]
        if (next != IDLE && used >= PER_FRAME - 1) next = IDLE
        if (next == category) return
        if (!write(slot * PER_FRAME + used)) return
        if (used == 0) slotProfileFrame[slot] = FrameProfiler.currentFrame
        slotCategories[slot][used] = next
        slotUsed[slot] = used + 1
        category = next
    }

    private fun poll() {
        while (tail < head) {
            val slot = (tail % FRAMES).toInt()
            if (slot == current) return
            val used = slotUsed[slot]
            if (!read(slot * PER_FRAME, used)) {
                if (frames - slotFrame[slot] <= GIVE_UP_FRAMES) return
                tail++
                continue
            }
            publish(slotCategories[slot], used, slotCounts[slot], slotProfileFrame[slot])
            tail++
        }
    }

    private fun publish(categories: IntArray, used: Int, frameCounts: LongArray, frame: Long) {
        timings.fill(0L)
        for (k in 0 until used - 1) {
            val c = categories[k]
            if (c == IDLE) continue
            val delta = stamps[k + 1] - stamps[k]
            if (delta > 0L) timings[c] += (delta * period).toLong()
        }
        val target = series ?: FrameProfiler.series(SERIES_KEY, listOf("copy", "blur", "composite", "layer"), listOf("backdrops", "layers", "copies", "passes"))
            .also { series = it }
        target.record(frame, timings, frameCounts)
    }

    private fun supported(): Boolean {
        if (support == 0) {
            val ok = init()
            support = if (ok) 1 else -1
            ProfilerHooks.effectTimingLabel = if (ok) "timestamp queries" else "unavailable"
        }
        return support > 0
    }

    private fun disable(e: Throwable) {
        LOG.warn("PolyCompose effect GPU timing failed and is turned off", e)
        support = -1
        on = false
        current = -1
        head = tail
        ProfilerHooks.effectTimingLabel = "unavailable"
    }

    //? if >= 26.2 {
    @Suppress("TooGenericExceptionCaught")
    private fun init(): Boolean = try {
        val device = RenderSystem.getDevice()
        val ticks = device.deviceInfo.timestampPeriod()
        if (ticks <= 0f) {
            LOG.info("PolyCompose effect GPU timing is unavailable: the device reports no timestamp support")
            false
        } else {
            pool = device.createTimestampQueryPool(FRAMES * PER_FRAME)
            period = ticks.toDouble()
            true
        }
    } catch (e: Exception) {
        LOG.warn("PolyCompose effect GPU timing is unavailable", e)
        false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun write(index: Int): Boolean {
        val p = pool ?: return false
        return try {
            RenderSystem.getDevice().createCommandEncoder().writeTimestamp(p, index)
            true
        } catch (e: Exception) {
            disable(e)
            false
        }
    }

    private fun read(base: Int, used: Int): Boolean {
        val p = pool ?: return false
        if (!p.getValue(base + used - 1).isPresent) return false
        val values = p.getValues(base, used)
        for (k in 0 until used) {
            val v = values[k]
            if (!v.isPresent) return false
            stamps[k] = v.asLong
        }
        return true
    }
    //?} else {
    /*@Suppress("TooGenericExceptionCaught")
    private fun init(): Boolean = try {
        if (GL.getCapabilities().OpenGL33) {
            ids = IntArray(FRAMES * PER_FRAME)
            GL15.glGenQueries(ids)
            true
        } else {
            LOG.info("PolyCompose effect GPU timing is unavailable: OpenGL 3.3 timestamp queries are not supported")
            false
        }
    } catch (e: Exception) {
        LOG.warn("PolyCompose effect GPU timing is unavailable", e)
        false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun write(index: Int): Boolean = try {
        GL33.glQueryCounter(ids[index], GL33.GL_TIMESTAMP)
        true
    } catch (e: Exception) {
        disable(e)
        false
    }

    private fun read(base: Int, used: Int): Boolean {
        for (k in 0 until used) if (GL15.glGetQueryObjecti(ids[base + k], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return false
        for (k in 0 until used) stamps[k] = GL33.glGetQueryObjecti64(ids[base + k], GL15.GL_QUERY_RESULT)
        return true
    }
    *///?}
}
//?}
