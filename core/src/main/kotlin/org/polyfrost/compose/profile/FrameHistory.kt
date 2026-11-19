package org.polyfrost.compose.profile

internal class FrameHistory(val capacity: Int, val top: Int) {
    val index = LongArray(capacity) { FrameColumns.NO_FRAME }
    val endNanos = LongArray(capacity)
    val frameNanos = LongArray(capacity)
    val cpuNanos = LongArray(capacity)
    val flags = IntArray(capacity)
    val unattributed = IntArray(capacity)
    val counters = LongArray(capacity * COUNTER_COUNT)
    val topName = IntArray(capacity * top)
    val topHost = IntArray(capacity * top)
    val topCount = IntArray(capacity * top)
    val topUsed = IntArray(capacity)

    fun slot(frame: Long): Int = (frame % capacity).toInt()

    fun has(frame: Long): Boolean = frame > 0L && index[slot(frame)] == frame

    fun begin(frame: Long): Int {
        val slot = slot(frame)
        index[slot] = frame
        flags[slot] = 0
        unattributed[slot] = 0
        topUsed[slot] = 0
        counters.fill(0L, slot * COUNTER_COUNT, (slot + 1) * COUNTER_COUNT)
        return slot
    }

    fun offerTop(slot: Int, host: Int, name: Int, count: Int) {
        val base = slot * top
        var used = topUsed[slot]
        if (used == top && topCount[base + used - 1] >= count) return
        if (used < top) used++
        var i = used - 1
        while (i > 0 && topCount[base + i - 1] < count) {
            topCount[base + i] = topCount[base + i - 1]
            topName[base + i] = topName[base + i - 1]
            topHost[base + i] = topHost[base + i - 1]
            i--
        }
        topCount[base + i] = count
        topName[base + i] = name
        topHost[base + i] = host
        topUsed[slot] = used
    }

    fun adopt(old: FrameHistory, last: Long) {
        for (f in maxOf(1L, last - minOf(old.capacity, capacity) + 1)..last) {
            if (!old.has(f)) continue
            val s = slot(f)
            val o = old.slot(f)
            index[s] = f
            endNanos[s] = old.endNanos[o]
            frameNanos[s] = old.frameNanos[o]
            cpuNanos[s] = old.cpuNanos[o]
            flags[s] = old.flags[o]
            unattributed[s] = old.unattributed[o]
            topUsed[s] = old.topUsed[o]
            old.counters.copyInto(counters, s * COUNTER_COUNT, o * COUNTER_COUNT, (o + 1) * COUNTER_COUNT)
            old.topName.copyInto(topName, s * top, o * top, (o + 1) * top)
            old.topHost.copyInto(topHost, s * top, o * top, (o + 1) * top)
            old.topCount.copyInto(topCount, s * top, o * top, (o + 1) * top)
        }
    }

    fun clear() {
        index.fill(FrameColumns.NO_FRAME)
    }

    companion object {
        const val SPIKE = 1
    }
}
