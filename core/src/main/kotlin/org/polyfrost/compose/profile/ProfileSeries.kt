package org.polyfrost.compose.profile

public class ProfileSeries internal constructor(
    public val key: String,
    public val timings: List<String>,
    public val counts: List<String>,
    public val capacity: Int,
) {
    private val columns = Array(timings.size + counts.size) { LongArray(capacity) }
    private var recorded = 0L
    private var lastActiveFrame = 0L

    internal var id: Int = -1
    internal var history: FrameColumns? = null

    public fun record(timingNanos: LongArray, countValues: LongArray) {
        record(FrameProfiler.currentFrame, timingNanos, countValues)
    }

    @Synchronized
    public fun record(frame: Long, timingNanos: LongArray, countValues: LongArray) {
        require(timingNanos.size >= timings.size && countValues.size >= counts.size) { "Series $key expects ${timings.size} timings and ${counts.size} counts" }
        val i = (recorded % capacity).toInt()
        for (c in timings.indices) columns[c][i] = timingNanos[c]
        for (c in counts.indices) columns[timings.size + c][i] = countValues[c]
        recorded++
        lastActiveFrame = FrameProfiler.frame
        val h = history ?: return
        if (frame <= 0L || frame > FrameProfiler.currentFrame || FrameProfiler.currentFrame - frame >= h.capacity) return
        val base = h.claim(frame)
        for (c in timings.indices) h.values[base + c] += timingNanos[c]
        for (c in counts.indices) h.values[base + timings.size + c] += countValues[c]
    }

    public fun snapshot(): SeriesSnapshot = snapshot(FrameProfiler.frame)

    @Synchronized
    internal fun clear() {
        recorded = 0L
        history?.frameOf?.fill(FrameColumns.NO_FRAME)
    }

    @Synchronized
    internal fun snapshot(globalFrame: Long): SeriesSnapshot {
        val n = minOf(recorded, capacity.toLong()).toInt()
        val scratch = LongArray(n)
        val timingStats = LinkedHashMap<String, PhaseStats>(timings.size)
        for (c in timings.indices) {
            columns[c].copyInto(scratch, 0, 0, n)
            timingStats[timings[c]] = percentileStats(scratch, n)
        }
        val countStats = LinkedHashMap<String, CountStats>(counts.size)
        for (c in counts.indices) {
            columns[timings.size + c].copyInto(scratch, 0, 0, n)
            val stats = percentileStats(scratch, n)
            countStats[counts[c]] = CountStats(stats.samples, stats.p50Nanos, stats.p95Nanos, stats.maxNanos)
        }
        return SeriesSnapshot(
            key = key,
            frames = n,
            framesSinceActive = if (recorded == 0L) globalFrame else globalFrame - lastActiveFrame,
            timings = timingStats,
            counts = countStats,
        )
    }
}

public data class CountStats(
    val samples: Int,
    val p50: Long,
    val p95: Long,
    val max: Long,
)

public data class SeriesSnapshot(
    val key: String,
    val frames: Int,
    val framesSinceActive: Long,
    val timings: Map<String, PhaseStats>,
    val counts: Map<String, CountStats>,
)

internal fun percentileStats(values: LongArray, n: Int): PhaseStats {
    if (n == 0) return PhaseStats.Empty
    values.sort(0, n)
    return PhaseStats(n, values[percentileRank(n, 50)], values[percentileRank(n, 95)], values[percentileRank(n, 99)], values[n - 1])
}

private fun percentileRank(n: Int, percentile: Int): Int = ((n.toLong() * percentile + 99) / 100 - 1).toInt().coerceIn(0, n - 1)
