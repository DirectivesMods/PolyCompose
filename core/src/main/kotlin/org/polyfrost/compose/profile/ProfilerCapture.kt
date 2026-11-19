package org.polyfrost.compose.profile

public object ProfilerCapture {
    public const val MAX_FRAMES: Int = 36_000

    @JvmField
    public var maxCaptures: Int = 8

    private val captures = ArrayList<ProfileCapture>()
    private val listeners = ArrayList<(ProfileCapture) -> Unit>()
    private var active: Active? = null

    private class Active(
        val label: String,
        val startedAtEpochMillis: Long,
        val startNanos: Long,
        val durationNanos: Long,
        val enabledProfiler: Boolean,
        var next: Long,
    ) {
        val frames = ArrayList<FrameRecord>()
        var last = -1L
    }

    public val recording: Boolean
        get() = active != null

    public val current: CaptureStatus?
        get() = active?.let { CaptureStatus(it.label, it.startedAtEpochMillis, it.frames.size, System.nanoTime() - it.startNanos, it.durationNanos) }

    @JvmStatic
    @JvmOverloads
    public fun start(seconds: Double = 0.0, label: String = "capture"): Boolean {
        if (active != null) return false
        val enable = !FrameProfiler.enabled
        if (enable) FrameProfiler.enabled = true
        val duration = if (seconds > 0.0) (seconds * NANOS_PER_SECOND).toLong() else 0L
        active = Active(label, System.currentTimeMillis(), System.nanoTime(), duration, enable, FrameProfiler.currentFrame)
        return true
    }

    @JvmStatic
    public fun stop(): ProfileCapture? {
        val c = active ?: return null
        if (c.last < 0L) c.last = FrameProfiler.frame
        append(c, c.last)
        return finish(c)
    }

    @JvmStatic
    public fun requestStop(): Boolean {
        val c = active ?: return false
        if (c.last < 0L) c.last = FrameProfiler.frame
        return true
    }

    @JvmStatic
    public fun list(): List<ProfileCapture> = synchronized(captures) { ArrayList(captures) }

    @JvmStatic
    public fun clear() {
        synchronized(captures) { captures.clear() }
    }

    @JvmStatic
    public fun addListener(listener: (ProfileCapture) -> Unit) {
        synchronized(listeners) { listeners += listener }
    }

    @JvmStatic
    public fun removeListener(listener: (ProfileCapture) -> Unit) {
        synchronized(listeners) { listeners -= listener }
    }

    internal fun onFrame(now: Long) {
        val c = active ?: return
        if (c.last < 0L && c.durationNanos > 0L && System.nanoTime() - c.startNanos >= c.durationNanos) c.last = now
        if (c.last < 0L && now - c.next + 1 + c.frames.size >= MAX_FRAMES) c.last = now
        val settled = now - ProfilerSpikes.SETTLE_FRAMES
        append(c, if (c.last >= 0L) minOf(settled, c.last) else settled)
        if (c.last >= 0L && c.next > c.last && (!ProfilerSpikes.enabled || now >= c.last + ProfilerSpikes.FRAMES_AFTER + ProfilerSpikes.SETTLE_FRAMES)) finish(c)
    }

    private fun append(c: Active, upTo: Long) {
        if (upTo < c.next) return
        FrameProfiler.range(c.next, upTo, c.frames)
        c.next = upTo + 1
    }

    private fun finish(c: Active): ProfileCapture {
        active = null
        val first = c.frames.firstOrNull()?.index ?: c.next
        val last = c.frames.lastOrNull()?.index ?: c.last
        val capture = ProfileCapture(
            label = c.label,
            kind = CaptureKind.Manual,
            startedAtEpochMillis = c.startedAtEpochMillis,
            environment = ProfileEnvironment.snapshot(),
            gpuTiming = FrameProfiler.gpuTiming,
            recompositionsTracked = FrameProfiler.recompositionsActive,
            frames = c.frames,
            spikes = ProfilerSpikes.list().filter { it.frame in first..last },
        )
        synchronized(captures) {
            captures += capture
            while (captures.size > maxCaptures.coerceAtLeast(1)) captures.removeAt(0)
        }
        if (c.enabledProfiler) FrameProfiler.enabled = false
        val targets = synchronized(listeners) { ArrayList(listeners) }
        for (listener in targets) listener(capture)
        return capture
    }

    private const val NANOS_PER_SECOND = 1_000_000_000.0
}
