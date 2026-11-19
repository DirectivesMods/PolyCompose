package org.polyfrost.compose.profile

internal class SummaryStats(capture: ProfileCapture) {
    class Host(val key: String, val frames: Int, val total: PhaseStats, val phases: List<PhaseStats>, val counterSums: LongArray, val counterMax: LongArray)

    class Recomposed(val name: String, val host: String, var total: Long, var frames: Int, var max: Int)

    val frame: PhaseStats
    val cpu: PhaseStats
    val gpu: PhaseStats
    val hosts: List<Host>
    val series: Map<String, PhaseStats>
    val recomposers: List<Recomposed>

    init {
        val frames = capture.frames
        val scratch = LongArray(frames.size)
        frame = collect(frames, scratch) { f, out -> if (f.frameNanos > 0L) out(f.frameNanos) }
        cpu = collect(frames, scratch) { f, out -> out(f.cpuNanos) }
        gpu = collect(frames, scratch) { f, out -> if (f.gpuNanos > 0L) out(f.gpuNanos) }
        hosts = capture.hosts.map { key -> host(frames, key, scratch) }
        val seriesStats = LinkedHashMap<String, PhaseStats>()
        for (key in capture.seriesKeys) {
            val names = frames.firstNotNullOfOrNull { f -> f.series.firstOrNull { it.series == key } }?.timingNames ?: continue
            for ((i, name) in names.withIndex()) {
                seriesStats["$key/$name"] = collect(frames, scratch) { f, out -> f.series.firstOrNull { it.series == key }?.let { out(it.timing(i)) } }
            }
        }
        series = seriesStats
        val byName = LinkedHashMap<String, Recomposed>()
        for (f in frames) {
            for (r in f.recomposers) {
                val entry = byName.getOrPut(r.name + "\u0000" + r.host) { Recomposed(r.name, r.host, 0L, 0, 0) }
                entry.total += r.count
                entry.frames++
                if (r.count > entry.max) entry.max = r.count
            }
        }
        recomposers = byName.values.sortedByDescending { it.total }.take(TOP)
    }

    private fun host(frames: List<FrameRecord>, key: String, scratch: LongArray): Host {
        val sums = LongArray(COUNTER_COUNT)
        val max = LongArray(COUNTER_COUNT)
        var n = 0
        for (f in frames) {
            val h = f.host(key) ?: continue
            n++
            for (c in COUNTERS) {
                val v = h.counter(c)
                sums[c.ordinal] += v
                if (v > max[c.ordinal]) max[c.ordinal] = v
            }
        }
        val total = collect(frames, scratch) { f, out -> f.host(key)?.let { out(it.cpuNanos) } }
        val phases = ProfilePhase.entries.map { p ->
            collect(frames, scratch) { f, out ->
                val h = f.host(key) ?: return@collect
                val v = h.phase(p)
                if (p != ProfilePhase.Gpu || v >= 0L) out(v)
            }
        }
        return Host(key, n, total, phases, sums, max)
    }

    private fun collect(frames: List<FrameRecord>, scratch: LongArray, pick: (FrameRecord, (Long) -> Unit) -> Unit): PhaseStats {
        var n = 0
        for (f in frames) pick(f) { scratch[n++] = it }
        return percentileStats(scratch, n)
    }

    private companion object {
        const val TOP = 20
    }
}
