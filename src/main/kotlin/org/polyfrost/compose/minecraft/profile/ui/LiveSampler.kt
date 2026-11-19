package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import org.polyfrost.compose.profile.FrameDigest
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCounter
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.ProfilerSpikes
import org.polyfrost.compose.profile.Recomposer

internal class HostTrack(val host: ProfileHost) {
    val phases: Array<LongArray> = Array(HOST_PHASES) { LongArray(CAPACITY) }
    val total = LongArray(CAPACITY)
    val counters = LongArray(COUNTERS.size)
    var p50 = 0L
    var p95 = 0L
    var p99 = 0L
    var activeFrames = 0
    var displayScale = 0f

    companion object {
        val COUNTERS: Array<ProfileCounter> = ProfileCounter.entries.toTypedArray()
    }
}

internal class LiveSampler {
    val graphTick = mutableIntStateOf(0)
    val statsTick = mutableIntStateOf(0)

    val frames = LongArray(CAPACITY)
    val phases: Array<LongArray> = Array(SERIES) { LongArray(CAPACITY) }
    val stacked = LongArray(CAPACITY)
    val spike = BooleanArray(CAPACITY)
    var size = 0
        private set
    var window = 0
        private set
    var windowNanos = 0L
        private set
    var displayScale = 0f

    val tracks = ArrayList<HostTrack>()

    var frameP50 = 0L
        private set
    var frameP95 = 0L
        private set
    var frameP99 = 0L
        private set
    var uiP50 = 0L
        private set
    var uiP95 = 0L
        private set
    var uiP99 = 0L
        private set
    var fps = 0
        private set

    @Volatile
    var topRecomposers: List<Recomposer> = emptyList()
        private set

    private val effects = LongArray(CAPACITY)
    private val effectsFrame = LongArray(CAPACITY)
    private val scratch = LongArray(CAPACITY)
    private val counterScratch = LongArray(COUNTER_WINDOW)
    private var lastRefresh = 0L
    private var hostsAt = 0L
    private var refreshes = 0
    private var lastPulled = 0L
    private var lastSeenFrame = -1L
    private val digest = FrameDigest()
    private val recomposerCounts = HashMap<String, HashMap<String, RecomposerCount>>()
    private var recomposerEntries = 0
    private var recomposerFrames = 0

    private class RecomposerCount(val name: String, val host: String) {
        var count = 0
    }

    fun refreshIfDue(now: Long): Boolean {
        if (lastRefresh != 0L && now - lastRefresh < REFRESH_NANOS) return false
        val current = FrameProfiler.frame
        if (current == lastSeenFrame) return false
        lastSeenFrame = current
        lastRefresh = now
        refresh(now)
        return true
    }

    fun reset() {
        lastRefresh = 0L
        hostsAt = 0L
        lastPulled = 0L
        lastSeenFrame = -1L
        tracks.clear()
        effectsFrame.fill(0L)
        recomposerCounts.clear()
        recomposerEntries = 0
        recomposerFrames = 0
        topRecomposers = emptyList()
    }

    private fun refresh(now: Long) {
        val last = FrameProfiler.frame
        if (last < lastPulled) reset()
        if (hostsAt == 0L || now - hostsAt > HOSTS_NANOS) syncHosts(now)
        val n = FrameProfiler.frameTimes(frames)
        size = n
        pullRecords(last)
        for (p in 0 until SERIES) phases[p].fill(0L, 0, n)
        for (i in tracks.indices) fillTrack(tracks[i], n)
        val first = last - n + 1
        val effectsRow = phases[EFFECTS]
        for (k in 0 until n) {
            val f = first + k
            val slot = (f % CAPACITY).toInt()
            if (effectsFrame[slot] == f) effectsRow[k] = effects[slot]
            var sum = 0L
            for (p in 0 until SERIES) sum += phases[p][k]
            stacked[k] = sum
        }
        computeWindow(n)
        markSpikes(n)
        val stats = refreshes++ % STATS_EVERY == 0
        if (stats) computeStats()
        graphTick.intValue++
        if (stats) statsTick.intValue++
        Snapshot.sendApplyNotifications()
    }

    private fun syncHosts(now: Long) {
        hostsAt = now
        val current = FrameProfiler.hosts()
        tracks.removeAll { t -> current.none { it === t.host } }
        for (host in current) if (tracks.none { it.host === host }) tracks += HostTrack(host)
        tracks.sortBy { it.host.key }
    }

    private fun fillTrack(track: HostTrack, n: Int) {
        track.total.fill(0L, 0, n)
        for (p in 0 until HOST_PHASES) {
            val row = track.phases[p]
            val got = track.host.history(PHASES[p], row)
            if (got != n) row.fill(0L, 0, n)
            val agg = phases[p]
            for (k in 0 until n) {
                agg[k] += row[k]
                track.total[k] += row[k]
            }
        }
    }

    private fun pullRecords(last: Long) {
        val settled = last - SETTLE_FRAMES
        val from = maxOf(lastPulled + 1, settled - MAX_PULL + 1, 1L)
        for (f in from..settled) {
            if (!FrameProfiler.digest(f, digest)) continue
            val slot = (f % CAPACITY).toInt()
            effects[slot] = digest.seriesTimingNanos
            effectsFrame[slot] = f
            for (i in 0 until digest.recomposerCount) {
                val name = digest.recomposerName(i)
                val host = digest.recomposerHost(i)
                val byName = recomposerCounts.getOrPut(host) { HashMap() }
                val entry = byName[name] ?: RecomposerCount(name, host).also {
                    byName[name] = it
                    recomposerEntries++
                }
                entry.count += digest.recomposerCount(i)
            }
            if (++recomposerFrames >= RECOMPOSER_FRAMES) publishRecomposers()
        }
        if (settled >= from) lastPulled = settled
    }

    private fun publishRecomposers() {
        recomposerFrames = 0
        val top = ArrayList<Recomposer>()
        for (byName in recomposerCounts.values) {
            for (entry in byName.values) {
                if (entry.count > 0) top += Recomposer(entry.name, entry.host, entry.count)
                entry.count = 0
            }
        }
        top.sortByDescending { it.count }
        topRecomposers = if (top.size > TOP) top.subList(0, TOP).toList() else top
        if (recomposerEntries > MAX_NAMES) {
            recomposerCounts.clear()
            recomposerEntries = 0
        }
    }

    private fun computeWindow(n: Int) {
        var total = 0L
        var w = 0
        for (k in n - 1 downTo 0) {
            val f = frames[k]
            total += if (f > 0L) f else ASSUMED_FRAME_NANOS
            w++
            if (total >= WINDOW_NANOS) break
        }
        window = w
        windowNanos = total
    }

    private fun markSpikes(n: Int) {
        val threshold = (ProfilerSpikes.thresholdMs * NANOS_PER_MILLI).toLong()
        val median = ProfilerSpikes.medianFrameNanos
        val factor = ProfilerSpikes.frameTimeFactor
        for (k in 0 until n) {
            val cpu = stacked[k] - phases[ProfilePhase.Gpu.ordinal][k] - phases[EFFECTS][k]
            spike[k] = cpu > threshold || (median > 0L && frames[k] > median * factor)
        }
    }

    private fun computeStats() {
        val start = size - window
        frameP50 = 0L
        frameP95 = 0L
        frameP99 = 0L
        var m = 0
        for (k in start until size) if (frames[k] > 0L) scratch[m++] = frames[k]
        if (m > 0) {
            scratch.sort(0, m)
            frameP50 = scratch[rank(m, P50)]
            frameP95 = scratch[rank(m, P95)]
            frameP99 = scratch[rank(m, P99)]
        }
        fps = if (windowNanos > 0L) (window * NANOS_PER_SECOND / windowNanos).toInt() else 0
        stacked.copyInto(scratch, 0, start, size)
        if (window > 0) {
            scratch.sort(0, window)
            uiP50 = scratch[rank(window, P50)]
            uiP95 = scratch[rank(window, P95)]
            uiP99 = scratch[rank(window, P99)]
        } else {
            uiP50 = 0L
            uiP95 = 0L
            uiP99 = 0L
        }
        for (i in tracks.indices) trackStats(tracks[i], start)
    }

    private fun trackStats(track: HostTrack, start: Int) {
        var m = 0
        for (k in start until size) if (track.total[k] > 0L) scratch[m++] = track.total[k]
        track.activeFrames = m
        if (m > 0) {
            scratch.sort(0, m)
            track.p50 = scratch[rank(m, P50)]
            track.p95 = scratch[rank(m, P95)]
            track.p99 = scratch[rank(m, P99)]
        } else {
            track.p50 = 0L
            track.p95 = 0L
            track.p99 = 0L
        }
        for (c in HostTrack.COUNTERS.indices) {
            val got = track.host.history(HostTrack.COUNTERS[c], counterScratch)
            var sum = 0L
            var active = 0
            for (k in 0 until got) {
                if (counterScratch[k] > 0L) active++
                sum += counterScratch[k]
            }
            track.counters[c] = if (active == 0) 0L else sum / active
        }
    }

    companion object {
        const val SERIES = 6
        const val EFFECTS = 5
        const val WINDOW_NANOS = 5_000_000_000L
        private const val REFRESH_NANOS = 50_000_000L
        private const val HOSTS_NANOS = 1_000_000_000L
        private const val STATS_EVERY = 5
        private const val SETTLE_FRAMES = 8
        private const val MAX_PULL = 256
        private const val RECOMPOSER_FRAMES = 60
        private const val TOP = 8
        private const val MAX_NAMES = 4096
        private const val COUNTER_WINDOW = 60
        private const val ASSUMED_FRAME_NANOS = 16_666_667L
        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val P50 = 50
        private const val P95 = 95
        private const val P99 = 99
        private val PHASES: Array<ProfilePhase> = ProfilePhase.entries.toTypedArray()

        private fun rank(n: Int, percentile: Int): Int = ((n.toLong() * percentile + 99) / 100 - 1).toInt().coerceIn(0, n - 1)
    }
}

internal const val CAPACITY = 1024
internal const val HOST_PHASES = 5
