package org.polyfrost.compose.profile

public class ProfileHost internal constructor(public val key: String, public val capacity: Int) {
    private val stackPhase = IntArray(MAX_DEPTH)
    private val stackStart = LongArray(MAX_DEPTH)
    private val stackChild = LongArray(MAX_DEPTH)
    private var depth = 0

    private val pending = LongArray(CPU_PHASES)
    private var pendingGpu = NO_SAMPLE
    private val pendingCounters = LongArray(COUNTER_COUNT)
    private var touched = false

    private val samples = Array(PHASES) { LongArray(capacity) }
    private val globalOf = LongArray(capacity)
    private var committed = 0L
    private var lastActiveFrame = 0L

    internal var id: Int = -1
    internal var history: FrameColumns? = null
    internal var lastCpuNanos: Long = 0L
    internal val names = HashMap<String, NameCount>()
    internal val touchedNames = ArrayList<NameCount>()

    public var ignored: Boolean = false
        internal set

    public val frame: Long
        get() = committed

    public fun begin(phase: ProfilePhase) {
        val d = depth++
        if (d >= MAX_DEPTH) return
        stackPhase[d] = if (phase == ProfilePhase.Gpu) NO_PHASE else phase.ordinal
        stackChild[d] = 0L
        touched = true
        stackStart[d] = System.nanoTime()
    }

    public fun end() {
        if (depth == 0) return
        val d = --depth
        if (d >= MAX_DEPTH) return
        val elapsed = System.nanoTime() - stackStart[d]
        val phase = stackPhase[d]
        if (phase != NO_PHASE) pending[phase] += elapsed - stackChild[d]
        if (d > 0) stackChild[d - 1] += elapsed
    }

    public fun carve(nanos: Long) {
        if (!FrameProfiler.enabled || nanos <= 0L) return
        val d = depth - 1
        if (d < 0 || d >= MAX_DEPTH) return
        stackChild[d] += nanos
    }

    internal fun count(counter: Int, delta: Long) {
        pendingCounters[counter] += delta
        touched = true
    }

    @Synchronized
    public fun gpu(frame: Long, nanos: Long) {
        if (nanos < 0L) return
        if (frame == committed) {
            pendingGpu = if (pendingGpu < 0L) nanos else pendingGpu + nanos
            return
        }
        if (frame > committed || committed - frame > capacity) return
        val column = samples[ProfilePhase.Gpu.ordinal]
        val i = (frame % capacity).toInt()
        column[i] = if (column[i] < 0L) nanos else column[i] + nanos
        val h = history ?: return
        val global = globalOf[i]
        if (!h.has(global)) return
        val at = h.base(global) + ProfilePhase.Gpu.ordinal
        h.values[at] = if (h.values[at] < 0L) nanos else h.values[at] + nanos
    }

    @Synchronized
    internal fun commit(globalFrame: Long): Boolean {
        depth = 0
        if (!touched) return false
        touched = false
        if (ignored) {
            pending.fill(0L)
            pendingCounters.fill(0L)
            pendingGpu = NO_SAMPLE
            return false
        }
        val i = (committed % capacity).toInt()
        var cpu = 0L
        val h = history
        val base = h?.claim(globalFrame) ?: 0
        for (p in 0 until CPU_PHASES) {
            samples[p][i] = pending[p]
            cpu += pending[p]
            if (h != null) h.values[base + p] = pending[p]
            pending[p] = 0L
        }
        samples[ProfilePhase.Gpu.ordinal][i] = pendingGpu
        if (h != null) {
            h.values[base + ProfilePhase.Gpu.ordinal] = pendingGpu
            pendingCounters.copyInto(h.values, base + PHASES)
        }
        pendingCounters.fill(0L)
        pendingGpu = NO_SAMPLE
        globalOf[i] = globalFrame
        lastCpuNanos = cpu
        committed++
        lastActiveFrame = globalFrame
        return true
    }

    @Synchronized
    internal fun clear() {
        depth = 0
        touched = false
        pending.fill(0L)
        pendingCounters.fill(0L)
        pendingGpu = NO_SAMPLE
        committed = 0L
        history?.frameOf?.fill(FrameColumns.NO_FRAME)
        for (entry in touchedNames) entry.count = 0
        touchedNames.clear()
    }

    @Synchronized
    public fun history(phase: ProfilePhase, out: LongArray): Int = fill(out, phase.ordinal)

    @Synchronized
    public fun history(counter: ProfileCounter, out: LongArray): Int = fill(out, PHASES + counter.ordinal)

    private fun fill(out: LongArray, column: Int): Int {
        val h = history
        val last = FrameProfiler.frame
        val n = minOf(out.size.toLong(), last, h?.capacity?.toLong() ?: 0L).toInt()
        val first = last - n + 1
        if (h == null) out.fill(0L, 0, n) else h.gather(first, n, column, out)
        return n
    }

    public fun snapshot(): ProfileSnapshot = snapshot(FrameProfiler.frame, FrameProfiler.gpuTiming)

    @Synchronized
    internal fun snapshot(globalFrame: Long, gpuTiming: GpuTiming): ProfileSnapshot {
        val n = minOf(committed, capacity.toLong()).toInt()
        val scratch = LongArray(n)
        val totals = LongArray(n)
        val cpu = Array(CPU_PHASES) { p ->
            val column = samples[p]
            for (i in 0 until n) {
                scratch[i] = column[i]
                totals[i] += column[i]
            }
            percentileStats(scratch, n)
        }
        var gpuCount = 0
        val gpuColumn = samples[ProfilePhase.Gpu.ordinal]
        for (i in 0 until n) if (gpuColumn[i] >= 0L) scratch[gpuCount++] = gpuColumn[i]
        return ProfileSnapshot(
            key = key,
            frames = n,
            framesSinceActive = if (committed == 0L) globalFrame else globalFrame - lastActiveFrame,
            compose = cpu[ProfilePhase.Compose.ordinal],
            layout = cpu[ProfilePhase.Layout.ordinal],
            record = cpu[ProfilePhase.Record.ordinal],
            upload = cpu[ProfilePhase.Upload.ordinal],
            gpu = if (gpuCount == 0) null else percentileStats(scratch, gpuCount),
            gpuTiming = gpuTiming,
            total = percentileStats(totals, n),
        )
    }

    internal companion object {
        const val MAX_DEPTH = 8
        const val PHASES = 5
        const val CPU_PHASES = 4
        const val NO_SAMPLE = -1L
        const val NO_PHASE = -1
    }
}

internal class NameCount(val nameId: Int) {
    var count = 0
}
