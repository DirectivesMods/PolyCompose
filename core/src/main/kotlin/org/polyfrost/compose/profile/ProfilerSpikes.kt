package org.polyfrost.compose.profile

public enum class SpikeReason {
    HostTime,
    FrameTime,
}

public data class SpikeCulprit(
    val host: String?,
    val phase: ProfilePhase?,
    val phaseNanos: Long,
    val gpuCategory: String?,
    val gpuCategoryNanos: Long,
    val recomposers: List<Recomposer>,
)

public class ProfileSpike internal constructor(
    public val frame: Long,
    public val epochMillis: Long,
    public val reason: SpikeReason,
    public val hostNanos: Long,
    public val frameNanos: Long,
    public val medianFrameNanos: Long,
    public val thresholdNanos: Long,
    public val culprit: SpikeCulprit,
    public val capture: ProfileCapture,
)

public object ProfilerSpikes {
    public const val DEFAULT_THRESHOLD_MS: Double = 8.0
    public const val FRAMES_BEFORE: Int = 60
    public const val FRAMES_AFTER: Int = 30

    @JvmField
    public var enabled: Boolean = false

    @JvmField
    public var thresholdMs: Double = DEFAULT_THRESHOLD_MS

    @JvmField
    public var frameTimeFactor: Double = 2.0

    @JvmField
    public var maxSpikes: Int = 16

    public var medianFrameNanos: Long = 0L
        private set

    private val spikes = ArrayList<ProfileSpike>()
    private val listeners = ArrayList<(ProfileSpike) -> Unit>()
    private val medianScratch = LongArray(MEDIAN_WINDOW)
    private var pending: Pending? = null

    private class Pending(
        val frame: Long,
        val epochMillis: Long,
        val reason: SpikeReason,
        val hostNanos: Long,
        val frameNanos: Long,
        val median: Long,
        val threshold: Long,
    )

    @JvmStatic
    public fun list(): List<ProfileSpike> = synchronized(spikes) { ArrayList(spikes) }

    @JvmStatic
    public fun clear() {
        synchronized(spikes) { spikes.clear() }
        pending = null
    }

    @JvmStatic
    public fun addListener(listener: (ProfileSpike) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    @JvmStatic
    public fun removeListener(listener: (ProfileSpike) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    internal fun onFrame(now: Long) {
        if (!enabled) {
            pending = null
            return
        }
        if (now % MEDIAN_EVERY == 0L) updateMedian(now)
        val open = pending
        if (open != null) {
            if (now >= open.frame + FRAMES_AFTER + SETTLE_FRAMES) finish(open)
            return
        }
        detect(now)
    }

    private fun detect(now: Long) {
        val cpu = FrameProfiler.frameStat(now, true)
        val total = FrameProfiler.frameStat(now, false)
        val threshold = (thresholdMs * NANOS_PER_MILLI).toLong()
        val median = medianFrameNanos
        val reason = when {
            cpu > threshold -> SpikeReason.HostTime
            median > 0L && total > 0L && total > median * frameTimeFactor -> SpikeReason.FrameTime
            else -> return
        }
        FrameProfiler.markSpike(now)
        pending = Pending(now, System.currentTimeMillis(), reason, cpu, total, median, threshold)
    }

    private fun updateMedian(now: Long) {
        var n = 0
        for (f in now - MEDIAN_WINDOW + 1..now) {
            val v = FrameProfiler.frameStat(f, false)
            if (v > 0L) medianScratch[n++] = v
        }
        if (n == 0) return
        medianScratch.sort(0, n)
        medianFrameNanos = medianScratch[n / 2]
    }

    private fun finish(open: Pending) {
        pending = null
        val frames = ArrayList<FrameRecord>()
        FrameProfiler.range(open.frame - FRAMES_BEFORE, open.frame + FRAMES_AFTER, frames)
        val capture = ProfileCapture(
            label = "spike-" + open.frame,
            kind = CaptureKind.Spike,
            startedAtEpochMillis = open.epochMillis,
            environment = ProfileEnvironment.snapshot(),
            gpuTiming = FrameProfiler.gpuTiming,
            recompositionsTracked = FrameProfiler.recompositionsActive,
            frames = frames,
            spikes = emptyList(),
        )
        val spike = ProfileSpike(
            frame = open.frame,
            epochMillis = open.epochMillis,
            reason = open.reason,
            hostNanos = open.hostNanos,
            frameNanos = open.frameNanos,
            medianFrameNanos = open.median,
            thresholdNanos = open.threshold,
            culprit = culprit(frames.firstOrNull { it.index == open.frame }),
            capture = capture,
        )
        capture.spikes = listOf(spike)
        synchronized(spikes) {
            spikes += spike
            while (spikes.size > maxSpikes.coerceAtLeast(1)) spikes.removeAt(0)
        }
        val targets = synchronized(listeners) { ArrayList(listeners) }
        for (listener in targets) listener(spike)
    }

    private fun culprit(record: FrameRecord?): SpikeCulprit {
        if (record == null) return SpikeCulprit(null, null, 0L, null, 0L, emptyList())
        val host = record.hosts.maxByOrNull { it.cpuNanos + it.gpuNanos.coerceAtLeast(0L) }
        var phase: ProfilePhase? = null
        var phaseNanos = 0L
        if (host != null) {
            for (p in ProfilePhase.entries) {
                val v = host.phase(p)
                if (v > phaseNanos) {
                    phase = p
                    phaseNanos = v
                }
            }
        }
        var category: String? = null
        var categoryNanos = 0L
        for (s in record.series) {
            for (i in s.timingNames.indices) {
                if (s.timing(i) > categoryNanos) {
                    category = s.series + "/" + s.timingNames[i]
                    categoryNanos = s.timing(i)
                }
            }
        }
        val recomposers = record.recomposers.filter { host == null || it.host == host.host }.take(TOP_CULPRIT_RECOMPOSERS)
        return SpikeCulprit(host?.host, phase, phaseNanos, category, categoryNanos, recomposers)
    }

    internal const val SETTLE_FRAMES = 8
    private const val MEDIAN_WINDOW = 120
    private const val MEDIAN_EVERY = 30L
    private const val TOP_CULPRIT_RECOMPOSERS = 5
    private const val NANOS_PER_MILLI = 1_000_000.0
}
