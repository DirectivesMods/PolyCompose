package org.polyfrost.compose.profile

import java.util.Locale
import org.apache.logging.log4j.LogManager

public object FrameProfiler {
    private val LOGGER = LogManager.getLogger("PolyCompose/Profiler")

    public const val DEFAULT_CAPACITY: Int = 600
    public const val DEFAULT_HISTORY_FRAMES: Int = 3600
    public const val MIN_HISTORY_FRAMES: Int = 256
    public const val MAX_HISTORY_FRAMES: Int = 36_000
    public const val TOP_RECOMPOSERS: Int = 8
    public const val REDRAW_CAPACITY: Int = 1024

    @JvmField
    public var enabled: Boolean = flag("polycompose.profiler") || flag("polycompose.profiler.log")

    @JvmField
    public var logSummaries: Boolean = flag("polycompose.profiler.log")

    @JvmField
    public var logIntervalSeconds: Int = System.getProperty("polycompose.profiler.log.interval")?.toIntOrNull() ?: DEFAULT_LOG_INTERVAL

    @JvmField
    public var gpuTiming: GpuTiming = GpuTiming.Unknown

    @JvmField
    public var active: ProfileHost? = null

    @JvmField
    public var trackRecompositions: Boolean = true

    @Volatile
    public var frame: Long = 0L
        private set

    private val lock = Any()
    private val byKey = HashMap<String, ProfileHost>()
    private val hosts = ArrayList<ProfileHost>()
    private val seriesByKey = HashMap<String, ProfileSeries>()
    private val seriesList = ArrayList<ProfileSeries>()
    private var lastLogNanos = 0L

    private val ignoredKeys = HashSet<String>()
    private val names = ArrayList<String>()
    private val nameIds = HashMap<String, Int>()
    private var history: FrameHistory? = null
    private val looseCounters = LongArray(COUNTER_COUNT)
    private var pendingFrameNanos = -1L
    private var lastEndNanos = 0L
    private var tracer: RecompositionTracer? = null
    private var tracerFailed = false

    private val redrawHost = arrayOfNulls<String>(REDRAW_CAPACITY)
    private val redrawFrame = LongArray(REDRAW_CAPACITY)
    private val redrawNanos = LongArray(REDRAW_CAPACITY)
    private val redrawRect = FloatArray(REDRAW_CAPACITY * 4)
    private var redrawCount = 0L

    public var historyFrames: Int = DEFAULT_HISTORY_FRAMES
        set(value) {
            val clamped = value.coerceIn(MIN_HISTORY_FRAMES, MAX_HISTORY_FRAMES)
            synchronized(lock) {
                if (clamped == field) return
                field = clamped
                if (history != null) allocateHistory()
            }
        }

    public val currentFrame: Long
        get() = frame + 1

    public val recompositionsAvailable: Boolean
        get() = !tracerFailed

    public val recompositionsActive: Boolean
        get() = tracer?.installed == true

    @JvmStatic
    public fun host(key: String): ProfileHost = synchronized(lock) {
        byKey.getOrPut(key) {
            ProfileHost(key, DEFAULT_CAPACITY).also {
                it.id = hosts.size
                it.ignored = key in ignoredKeys
                it.history = history?.let { h -> FrameColumns(h.capacity, HOST_WIDTH) }
                hosts += it
            }
        }
    }

    @JvmStatic
    public fun hosts(): List<ProfileHost> = synchronized(lock) { hosts.filter { !it.ignored } }

    @JvmStatic
    public fun ignore(key: String) {
        synchronized(lock) {
            ignoredKeys += key
            byKey[key]?.ignored = true
        }
    }

    @JvmStatic
    public fun series(key: String, timings: List<String>, counts: List<String>): ProfileSeries = synchronized(lock) {
        seriesByKey.getOrPut(key) {
            ProfileSeries(key, timings.toList(), counts.toList(), DEFAULT_CAPACITY).also {
                it.id = seriesList.size
                it.history = history?.let { h -> FrameColumns(h.capacity, it.timings.size + it.counts.size) }
                seriesList += it
            }
        }
    }

    @JvmStatic
    public fun count(host: ProfileHost?, counter: ProfileCounter, delta: Long) {
        if (!enabled) return
        if (host == null) looseCounters[counter.ordinal] += delta
        else if (!host.ignored) host.count(counter.ordinal, delta)
    }

    @JvmStatic
    public fun count(counter: ProfileCounter, delta: Long) {
        if (!enabled) return
        count(active, counter, delta)
    }

    @JvmStatic
    public fun frameTime(nanos: Long) {
        if (!enabled) return
        pendingFrameNanos = nanos
    }

    @JvmStatic
    public fun redraw(host: ProfileHost?, x: Float, y: Float, width: Float, height: Float) {
        if (!enabled) return
        if (host != null && host.ignored) return
        count(host, ProfileCounter.LayerRecords, 1L)
        synchronized(redrawNanos) {
            val i = (redrawCount % REDRAW_CAPACITY).toInt()
            redrawHost[i] = host?.key
            redrawFrame[i] = currentFrame
            redrawNanos[i] = System.nanoTime()
            redrawRect[i * RECT] = x
            redrawRect[i * RECT + 1] = y
            redrawRect[i * RECT + 2] = width
            redrawRect[i * RECT + 3] = height
            redrawCount++
        }
    }

    @JvmStatic
    public fun redraws(sinceNanos: Long): List<RedrawEvent> = synchronized(redrawNanos) {
        val n = minOf(redrawCount, REDRAW_CAPACITY.toLong()).toInt()
        val out = ArrayList<RedrawEvent>()
        for (k in n downTo 1) {
            val i = ((redrawCount - k) % REDRAW_CAPACITY).toInt()
            if (redrawNanos[i] < sinceNanos) continue
            val r = i * RECT
            out += RedrawEvent(redrawHost[i], redrawFrame[i], redrawNanos[i], redrawRect[r], redrawRect[r + 1], redrawRect[r + 2], redrawRect[r + 3])
        }
        out
    }

    @JvmStatic
    public fun seriesSnapshot(): List<SeriesSnapshot> {
        val current = synchronized(lock) { ArrayList(seriesList) }
        val globalFrame = frame
        return current.map { it.snapshot(globalFrame) }
    }

    @JvmStatic
    public fun enter(host: ProfileHost): ProfileHost? {
        val previous = active
        active = host
        return previous
    }

    @JvmStatic
    public fun exit(previous: ProfileHost?) {
        active = previous
    }

    @JvmStatic
    public fun endFrame() {
        if (!enabled) return
        active = null
        if (trackRecompositions && tracer?.installed != true) installTracer()
        val end = System.nanoTime()
        val now = ++frame
        synchronized(lock) { commitFrame(now, end) }
        lastEndNanos = end
        pendingFrameNanos = -1L
        ProfilerSpikes.onFrame(now)
        ProfilerCapture.onFrame(now)
        if (logSummaries) logIfDue()
    }

    private fun commitFrame(now: Long, end: Long) {
        val h = history ?: allocateHistory()
        val slot = h.begin(now)
        h.endNanos[slot] = end
        h.frameNanos[slot] = when {
            pendingFrameNanos >= 0L -> pendingFrameNanos
            lastEndNanos != 0L && end - lastEndNanos < NANOS_PER_SECOND -> end - lastEndNanos
            else -> -1L
        }
        val t = tracer
        if (t != null) {
            h.unattributed[slot] = t.unattributed.toInt()
            t.unattributed = 0L
        }
        val counterBase = slot * COUNTER_COUNT
        for (c in 0 until COUNTER_COUNT) {
            h.counters[counterBase + c] = looseCounters[c]
            looseCounters[c] = 0L
        }
        var cpu = 0L
        for (i in hosts.indices) {
            val host = hosts[i]
            drainNames(h, slot, host)
            if (!host.commit(now)) continue
            cpu += host.lastCpuNanos
            val columns = host.history ?: continue
            val base = columns.base(now) + ProfileHost.PHASES
            for (c in 0 until COUNTER_COUNT) h.counters[counterBase + c] += columns.values[base + c]
        }
        h.cpuNanos[slot] = cpu
    }

    private fun drainNames(h: FrameHistory, slot: Int, host: ProfileHost) {
        val touched = host.touchedNames
        if (touched.isEmpty()) return
        for (i in touched.indices) {
            val entry = touched[i]
            if (!host.ignored) h.offerTop(slot, host.id, entry.nameId, entry.count)
            entry.count = 0
        }
        touched.clear()
    }

    private fun allocateHistory(): FrameHistory {
        val h = FrameHistory(historyFrames, TOP_RECOMPOSERS)
        val last = frame
        history?.let { h.adopt(it, last) }
        history = h
        for (host in hosts) host.history = FrameColumns(h.capacity, HOST_WIDTH).also { c -> host.history?.let { c.adopt(it, last) } }
        for (s in seriesList) s.history = FrameColumns(h.capacity, s.timings.size + s.counts.size).also { c -> s.history?.let { c.adopt(it, last) } }
        return h
    }

    private fun installTracer() {
        if (tracerFailed) return
        try {
            val t = tracer ?: RecompositionTracer(Thread.currentThread()).also { tracer = it }
            t.install()
        } catch (e: LinkageError) {
            tracerFailed = true
            LOGGER.warn("PolyCompose recomposition tracking is unavailable on this Compose runtime", e)
        }
    }

    internal fun nameId(name: String): Int = synchronized(lock) {
        nameIds.getOrPut(name) { names.size.also { names += name } }
    }

    internal fun markSpike(frame: Long) {
        synchronized(lock) {
            val h = history ?: return
            if (h.has(frame)) h.flags[h.slot(frame)] = h.flags[h.slot(frame)] or FrameHistory.SPIKE
        }
    }

    internal fun frameStat(frame: Long, cpu: Boolean): Long = synchronized(lock) {
        val h = history ?: return -1L
        if (!h.has(frame)) return -1L
        if (cpu) h.cpuNanos[h.slot(frame)] else h.frameNanos[h.slot(frame)]
    }

    @JvmStatic
    public fun record(index: Long): FrameRecord? = synchronized(lock) { materialize(index) }

    @JvmStatic
    public fun digest(index: Long, into: FrameDigest): Boolean = synchronized(lock) {
        val h = history ?: return false
        if (!h.has(index)) return false
        val slot = h.slot(index)
        var sum = 0L
        for (s in seriesList) {
            val columns = s.history ?: continue
            if (!columns.has(index)) continue
            val base = columns.base(index)
            for (i in s.timings.indices) sum += columns.values[base + i]
        }
        into.seriesTimingNanos = sum
        val used = h.topUsed[slot]
        for (k in 0 until used) {
            val at = slot * h.top + k
            into.setRecomposer(k, names[h.topName[at]], hosts[h.topHost[at]].key, h.topCount[at])
        }
        into.recomposerCount = used
        true
    }

    @JvmStatic
    public fun frames(count: Int): List<FrameRecord> {
        val out = ArrayList<FrameRecord>(count.coerceIn(0, historyFrames))
        val last = frame
        range(last - count + 1, last, out)
        return out
    }

    internal fun range(from: Long, to: Long, out: MutableList<FrameRecord>) {
        synchronized(lock) {
            for (f in maxOf(1L, from)..to) materialize(f)?.let(out::add)
        }
    }

    @JvmStatic
    public fun frameTimes(out: LongArray): Int = fillFrameStat(out, false)

    @JvmStatic
    public fun cpuTimes(out: LongArray): Int = fillFrameStat(out, true)

    private fun fillFrameStat(out: LongArray, cpu: Boolean): Int = synchronized(lock) {
        val h = history ?: return 0
        val last = frame
        val n = minOf(out.size.toLong(), last, h.capacity.toLong()).toInt()
        val first = last - n + 1
        val source = if (cpu) h.cpuNanos else h.frameNanos
        var slot = (first % h.capacity).toInt()
        var f = first
        for (k in 0 until n) {
            out[k] = if (f > 0L && h.index[slot] == f) source[slot].coerceAtLeast(0L) else 0L
            f++
            if (++slot == h.capacity) slot = 0
        }
        n
    }

    @JvmStatic
    @JvmOverloads
    public fun history(frames: Int = historyFrames, label: String = "history"): ProfileCapture {
        val records = ArrayList<FrameRecord>()
        val last = frame
        range(last - frames + 1, last, records)
        val first = records.firstOrNull()?.index ?: 0L
        val spikes = ProfilerSpikes.list().filter { it.frame >= first }
        return ProfileCapture(
            label = label,
            kind = CaptureKind.History,
            startedAtEpochMillis = System.currentTimeMillis(),
            environment = ProfileEnvironment.snapshot(),
            gpuTiming = gpuTiming,
            recompositionsTracked = recompositionsActive,
            frames = records,
            spikes = spikes,
        )
    }

    private fun materialize(index: Long): FrameRecord? {
        val h = history ?: return null
        if (!h.has(index)) return null
        val slot = h.slot(index)
        val hostFrames = ArrayList<HostFrame>()
        for (host in hosts) {
            val columns = host.history ?: continue
            if (host.ignored || !columns.has(index)) continue
            val values = LongArray(HOST_WIDTH)
            columns.copy(index, values)
            hostFrames += HostFrame(host.key, values)
        }
        val seriesFrames = ArrayList<SeriesFrame>()
        for (s in seriesList) {
            val columns = s.history ?: continue
            val values = LongArray(columns.width)
            if (columns.copy(index, values)) seriesFrames += SeriesFrame(s.key, s.timings, s.counts, values)
        }
        val used = h.topUsed[slot]
        val recomposers = ArrayList<Recomposer>(used)
        for (k in 0 until used) {
            val at = slot * h.top + k
            recomposers += Recomposer(names[h.topName[at]], hosts[h.topHost[at]].key, h.topCount[at])
        }
        return FrameRecord(
            index = index,
            endNanos = h.endNanos[slot],
            frameNanos = h.frameNanos[slot],
            cpuNanos = h.cpuNanos[slot],
            hosts = hostFrames,
            series = seriesFrames,
            recomposers = recomposers,
            unattributedRecompositions = h.unattributed[slot],
            spike = h.flags[slot] and FrameHistory.SPIKE != 0,
            counters = h.counters.copyOfRange(slot * COUNTER_COUNT, (slot + 1) * COUNTER_COUNT),
        )
    }

    @JvmStatic
    public fun snapshot(): List<ProfileSnapshot> {
        val current = synchronized(lock) { hosts.filter { !it.ignored } }
        val globalFrame = frame
        val timing = gpuTiming
        return current.map { it.snapshot(globalFrame, timing) }
    }

    @JvmStatic
    public fun snapshot(key: String): ProfileSnapshot? {
        val host = synchronized(lock) { byKey[key] } ?: return null
        return host.snapshot(frame, gpuTiming)
    }

    @JvmStatic
    public fun reset() {
        synchronized(lock) {
            for (host in hosts) host.clear()
            for (s in seriesList) s.clear()
            history?.clear()
            looseCounters.fill(0L)
        }
        synchronized(redrawNanos) { redrawCount = 0L }
        lastEndNanos = 0L
        pendingFrameNanos = -1L
    }

    private fun logIfDue() {
        val now = System.nanoTime()
        val interval = logIntervalSeconds.coerceAtLeast(1) * NANOS_PER_SECOND
        if (lastLogNanos != 0L && now - lastLogNanos < interval) return
        val first = lastLogNanos == 0L
        lastLogNanos = now
        if (first) return
        for (snapshot in snapshot()) {
            if (snapshot.frames == 0 || snapshot.framesSinceActive > IDLE_FRAMES) continue
            LOGGER.info(summary(snapshot))
        }
        for (snapshot in seriesSnapshot()) {
            if (snapshot.frames == 0 || snapshot.framesSinceActive > IDLE_FRAMES) continue
            LOGGER.info(summary(snapshot))
        }
    }

    @JvmStatic
    public fun summary(snapshot: ProfileSnapshot): String = buildString {
        append(snapshot.key).append(": ").append(snapshot.frames).append(" frames, total ")
        appendStats(snapshot.total)
        for (phase in ProfilePhase.entries) {
            append(" | ").append(phase.name.lowercase(Locale.ROOT)).append(' ')
            val stats = snapshot.phase(phase)
            if (stats == null) append(if (snapshot.gpuTiming == GpuTiming.Unavailable) "unavailable" else "n/a")
            else appendStats(stats)
        }
    }

    @JvmStatic
    public fun summary(snapshot: SeriesSnapshot): String = buildString {
        append(snapshot.key).append(": ").append(snapshot.frames).append(" frames")
        for ((name, stats) in snapshot.timings) {
            append(" | ").append(name).append(" p50 ").append(millis(stats.p50Nanos))
            append(" p95 ").append(millis(stats.p95Nanos))
            append(" max ").append(millis(stats.maxNanos)).append(" ms")
        }
        for ((name, stats) in snapshot.counts) {
            append(" | ").append(name).append(" p50 ").append(stats.p50)
            append(" p95 ").append(stats.p95)
            append(" max ").append(stats.max)
        }
    }

    private fun StringBuilder.appendStats(stats: PhaseStats) {
        append("p50 ").append(millis(stats.p50Nanos))
        append(" p95 ").append(millis(stats.p95Nanos))
        append(" p99 ").append(millis(stats.p99Nanos))
        append(" max ").append(millis(stats.maxNanos)).append(" ms")
    }

    @JvmStatic
    public fun millis(nanos: Long): String = String.format(Locale.ROOT, "%.2f", nanos / NANOS_PER_MILLI)

    private const val DEFAULT_LOG_INTERVAL = 10
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000.0
    private const val IDLE_FRAMES = 120L
    private const val RECT = 4

    private fun flag(key: String): Boolean = System.getProperty(key).equals("true", ignoreCase = true)
}

public inline fun <T> ProfileHost?.measure(phase: ProfilePhase, block: () -> T): T {
    if (this == null) return block()
    val previous = FrameProfiler.enter(this)
    begin(phase)
    try {
        return block()
    } finally {
        end()
        FrameProfiler.exit(previous)
    }
}

public inline fun <T> ProfileHost?.within(block: () -> T): T {
    if (this == null) return block()
    val previous = FrameProfiler.enter(this)
    try {
        return block()
    } finally {
        FrameProfiler.exit(previous)
    }
}

public inline fun <T> ProfileHost?.measureSlice(parent: ProfileHost?, phase: ProfilePhase, block: () -> T): T {
    if (this == null || !FrameProfiler.enabled) return block()
    val previous = FrameProfiler.enter(this)
    val start = System.nanoTime()
    begin(phase)
    try {
        return block()
    } finally {
        end()
        FrameProfiler.exit(previous)
        parent?.carve(System.nanoTime() - start)
    }
}
