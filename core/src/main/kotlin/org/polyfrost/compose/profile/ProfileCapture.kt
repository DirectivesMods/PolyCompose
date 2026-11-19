package org.polyfrost.compose.profile

public enum class CaptureKind {
    Manual,
    Spike,
    History,
}

public class HostFrame internal constructor(public val host: String, private val values: LongArray) {
    public val cpuNanos: Long
        get() {
            var sum = 0L
            for (p in 0 until ProfileHost.CPU_PHASES) sum += values[p]
            return sum
        }

    public val gpuNanos: Long
        get() = values[ProfilePhase.Gpu.ordinal]

    public fun phase(phase: ProfilePhase): Long = values[phase.ordinal]

    public fun counter(counter: ProfileCounter): Long = values[ProfileHost.PHASES + counter.ordinal]
}

public class SeriesFrame internal constructor(
    public val series: String,
    public val timingNames: List<String>,
    public val countNames: List<String>,
    private val values: LongArray,
) {
    public val totalTimingNanos: Long
        get() {
            var sum = 0L
            for (i in timingNames.indices) sum += values[i]
            return sum
        }

    public fun timing(index: Int): Long = values[index]

    public fun count(index: Int): Long = values[timingNames.size + index]

    public fun timing(name: String): Long = timingNames.indexOf(name).let { if (it < 0) 0L else values[it] }

    public fun count(name: String): Long = countNames.indexOf(name).let { if (it < 0) 0L else values[timingNames.size + it] }
}

public data class Recomposer(
    val name: String,
    val host: String,
    val count: Int,
)

public class FrameRecord internal constructor(
    public val index: Long,
    public val endNanos: Long,
    public val frameNanos: Long,
    public val cpuNanos: Long,
    public val hosts: List<HostFrame>,
    public val series: List<SeriesFrame>,
    public val recomposers: List<Recomposer>,
    public val unattributedRecompositions: Int,
    public val spike: Boolean,
    private val counters: LongArray,
) {
    public val startNanos: Long
        get() = endNanos - (if (frameNanos > 0L) frameNanos else cpuNanos)

    public val gpuNanos: Long
        get() {
            var sum = 0L
            for (h in hosts) if (h.gpuNanos > 0L) sum += h.gpuNanos
            for (s in series) sum += s.totalTimingNanos
            return sum
        }

    public fun counter(counter: ProfileCounter): Long = counters[counter.ordinal]

    public fun host(key: String): HostFrame? = hosts.firstOrNull { it.host == key }
}

public class FrameDigest {
    public var seriesTimingNanos: Long = 0L
        internal set

    public var recomposerCount: Int = 0
        internal set

    private val names = arrayOfNulls<String>(FrameProfiler.TOP_RECOMPOSERS)
    private val hosts = arrayOfNulls<String>(FrameProfiler.TOP_RECOMPOSERS)
    private val counts = IntArray(FrameProfiler.TOP_RECOMPOSERS)

    public fun recomposerName(index: Int): String = names[index] ?: ""

    public fun recomposerHost(index: Int): String = hosts[index] ?: ""

    public fun recomposerCount(index: Int): Int = counts[index]

    internal fun setRecomposer(index: Int, name: String, host: String, count: Int) {
        names[index] = name
        hosts[index] = host
        counts[index] = count
    }
}

public class ProfileCapture internal constructor(
    public val label: String,
    public val kind: CaptureKind,
    public val startedAtEpochMillis: Long,
    public val environment: Map<String, String>,
    public val gpuTiming: GpuTiming,
    public val recompositionsTracked: Boolean,
    public val frames: List<FrameRecord>,
    spikes: List<ProfileSpike>,
) {
    public var spikes: List<ProfileSpike> = spikes
        internal set

    public val hosts: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        for (f in frames) for (h in f.hosts) seen += h.host
        seen.toList()
    }

    public val seriesKeys: List<String> by lazy {
        val seen = LinkedHashSet<String>()
        for (f in frames) for (s in f.series) seen += s.series
        seen.toList()
    }

    public val durationNanos: Long
        get() = if (frames.isEmpty()) 0L else frames.last().endNanos - frames.first().startNanos
}

public data class RedrawEvent(
    val host: String?,
    val frame: Long,
    val nanos: Long,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

public data class CaptureStatus(
    val label: String,
    val startedAtEpochMillis: Long,
    val frames: Int,
    val elapsedNanos: Long,
    val durationNanos: Long,
)
