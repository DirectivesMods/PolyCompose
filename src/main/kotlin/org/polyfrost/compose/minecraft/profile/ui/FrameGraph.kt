package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import org.polyfrost.compose.profile.ProfilerSpikes

internal object FrameGraph {
    fun DrawScope.liveGraph(sampler: LiveSampler) {
        val scale = drawStacked(sampler.phases, LiveSampler.SERIES, sampler.stacked, sampler.spike, sampler.size, sampler.window, sampler.displayScale)
        sampler.displayScale = scale
    }

    fun DrawScope.hostGraph(sampler: LiveSampler, track: HostTrack) {
        val scale = drawStacked(track.phases, HOST_PHASES, track.total, null, sampler.size, sampler.window, track.displayScale)
        track.displayScale = scale
    }

    fun DrawScope.drawStacked(
        rows: Array<LongArray>,
        rowCount: Int,
        totals: LongArray,
        spikes: BooleanArray?,
        size: Int,
        window: Int,
        previousScale: Float,
    ): Float {
        val width = this.size.width
        val height = this.size.height
        drawRect(ProfilerTheme.GraphBackground, Offset.Zero, this.size)
        val threshold = (ProfilerSpikes.thresholdMs * NANOS_PER_MILLI).toFloat()
        if (window <= 0 || width < 1f || height < 1f) return previousScale
        val start = size - window
        val columns = minOf(window, width.toInt())
        var peak = 0L
        for (k in start until size) if (totals[k] > peak) peak = totals[k]
        val target = maxOf(threshold * THRESHOLD_HEADROOM, peak * PEAK_HEADROOM, MIN_SCALE_NANOS)
        val scale = if (previousScale <= 0f || target > previousScale) target else previousScale + (target - previousScale) * SCALE_EASE
        val barWidth = width / columns
        for (c in 0 until columns) {
            val from = start + (c.toLong() * window / columns).toInt()
            val to = maxOf(from + 1, start + ((c + 1).toLong() * window / columns).toInt())
            var pick = from
            var spike = false
            for (k in from until to) {
                if (totals[k] > totals[pick]) pick = k
                if (spikes != null && spikes[k]) spike = true
            }
            val x = c * barWidth
            var y = height
            for (p in 0 until rowCount) {
                val v = rows[p][pick]
                if (v <= 0L) continue
                val h = (v / scale * height).coerceAtMost(y)
                y -= h
                drawRect(ProfilerTheme.Phases[p], Offset(x, y), Size(barWidth, h))
            }
            if (spike) drawRect(ProfilerTheme.Spike, Offset(x, 0f), Size(maxOf(barWidth, 1f), MARKER_PX))
        }
        val ty = height - threshold / scale * height
        if (ty > 0f) {
            var x = 0f
            while (x < width) {
                drawRect(ProfilerTheme.Threshold, Offset(x, ty), Size(minOf(DASH_PX, width - x), 1f))
                x += DASH_PX * 2
            }
        }
        return scale
    }

    fun DrawScope.strip(totals: LongArray, spikes: BooleanArray, count: Int, selected: Int) {
        val width = size.width
        val height = size.height
        drawRect(ProfilerTheme.GraphBackground, Offset.Zero, size)
        if (count <= 0 || width < 1f) return
        val columns = minOf(count, width.toInt())
        var peak = 1L
        for (k in 0 until count) if (totals[k] > peak) peak = totals[k]
        val threshold = (ProfilerSpikes.thresholdMs * NANOS_PER_MILLI).toFloat()
        val scale = maxOf(peak * PEAK_HEADROOM, threshold * THRESHOLD_HEADROOM)
        val barWidth = width / columns
        for (c in 0 until columns) {
            val from = (c.toLong() * count / columns).toInt()
            val to = maxOf(from + 1, ((c + 1).toLong() * count / columns).toInt())
            var top = 0L
            var spike = false
            for (k in from until to) {
                if (totals[k] > top) top = totals[k]
                if (spikes[k]) spike = true
            }
            val h = (top / scale * height).coerceAtMost(height)
            drawRect(if (spike) ProfilerTheme.Spike else ProfilerTheme.Accent, Offset(c * barWidth, height - h), Size(barWidth, h))
        }
        val ty = height - threshold / scale * height
        if (ty > 0f) drawRect(ProfilerTheme.Grid, Offset(0f, ty), Size(width, 1f))
        if (selected in 0 until count) {
            val x = selected.toFloat() / count * width
            drawRect(ProfilerTheme.Selection, Offset(x, 0f), Size(maxOf(barWidth, 2f), height))
        }
    }

    fun indexAt(x: Float, width: Float, count: Int): Int {
        if (count <= 0 || width <= 0f) return -1
        return (x / width * count).toInt().coerceIn(0, count - 1)
    }

    private const val THRESHOLD_HEADROOM = 1.25f
    private const val PEAK_HEADROOM = 1.1f
    private const val MIN_SCALE_NANOS = 500_000f
    private const val SCALE_EASE = 0.08f
    private const val MARKER_PX = 2f
    private const val DASH_PX = 3f
}
