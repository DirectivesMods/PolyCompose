package org.polyfrost.compose.profile

internal class FrameColumns(val capacity: Int, val width: Int) {
    val frameOf = LongArray(capacity) { NO_FRAME }
    val values = LongArray(capacity * width)

    fun slot(frame: Long): Int = (frame % capacity).toInt()

    fun has(frame: Long): Boolean = frame > 0L && frameOf[slot(frame)] == frame

    fun base(frame: Long): Int = slot(frame) * width

    fun claim(frame: Long, fill: Long = 0L): Int {
        val slot = slot(frame)
        val base = slot * width
        if (frameOf[slot] != frame) {
            frameOf[slot] = frame
            values.fill(fill, base, base + width)
        }
        return base
    }

    fun get(frame: Long, column: Int, missing: Long): Long =
        if (has(frame)) values[base(frame) + column] else missing

    fun gather(first: Long, count: Int, column: Int, out: LongArray) {
        var slot = (first % capacity).toInt()
        var frame = first
        for (k in 0 until count) {
            out[k] = if (frame > 0L && frameOf[slot] == frame) values[slot * width + column].coerceAtLeast(0L) else 0L
            frame++
            if (++slot == capacity) slot = 0
        }
    }

    fun adopt(old: FrameColumns, last: Long) {
        for (f in maxOf(1L, last - minOf(old.capacity, capacity) + 1)..last) {
            if (!old.has(f)) continue
            val s = slot(f)
            frameOf[s] = f
            old.values.copyInto(values, s * width, old.base(f), old.base(f) + width)
        }
    }

    fun copy(frame: Long, out: LongArray): Boolean {
        if (!has(frame)) return false
        values.copyInto(out, 0, base(frame), base(frame) + width)
        return true
    }

    companion object {
        const val NO_FRAME = -1L
    }
}
