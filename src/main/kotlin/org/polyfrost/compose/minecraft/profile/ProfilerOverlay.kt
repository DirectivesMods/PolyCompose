package org.polyfrost.compose.minecraft.profile

import kotlin.math.max
import kotlin.math.roundToInt
import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.GpuTiming
import org.polyfrost.compose.profile.PhaseStats
import org.polyfrost.compose.profile.ProfileSnapshot
import org.polyfrost.compose.profile.SeriesSnapshot
import org.polyfrost.compose.render.RenderContext

internal class ProfilerOverlay : OverlayRenderer {
    private var rows: List<Row> = emptyList()
    private var refreshedAt = 0L
    private var lastGuiWidth = 0f
    private var lastGuiHeight = 0f
    private var textScale = 1f
    private var viewportWidth = 0f

    override val visible: Boolean
        get() = FrameProfiler.enabled

    override fun prepare(guiWidth: Float, guiHeight: Float): Boolean {
        val now = System.nanoTime()
        val resized = guiWidth != lastGuiWidth || guiHeight != lastGuiHeight
        if (!resized && refreshedAt != 0L && now - refreshedAt < REFRESH_NANOS) return false
        refreshedAt = now
        lastGuiWidth = guiWidth
        lastGuiHeight = guiHeight
        viewportWidth = McWindow.viewportWidth().toFloat()
        val guiScale = if (guiWidth > 0f) viewportWidth / guiWidth else 1f
        textScale = max(1f, (guiScale * TEXT_SCALE).roundToInt().toFloat())
        val next = buildRows()
        val changed = resized || next != rows
        rows = next
        return changed
    }

    override fun draw(ctx: RenderContext) {
        val rows = rows
        if (rows.isEmpty()) return
        val renderer = ctx.renderer
        val scale = textScale
        val pad = PADDING * scale
        val gap = COLUMN_GAP * scale
        val lineHeight = McFontQueue.measureTextHeight(scale) + scale
        val blocks = rows.maxOf { it.block } + 1
        val widths = Array(blocks) { b -> FloatArray(rows.maxOf { if (it.block == b) it.cells.size else 0 }) }
        for (row in rows) {
            if (row.cells.size == 1) continue
            val w = widths[row.block]
            for (c in row.cells.indices) w[c] = max(w[c], McFontQueue.measureTextWidth(row.cells[c], scale))
        }
        var tableWidth = 0f
        for (w in widths) if (w.isNotEmpty()) tableWidth = max(tableWidth, w.sum() + gap * (w.size - 1))
        for (row in rows) if (row.cells.size == 1) tableWidth = max(tableWidth, McFontQueue.measureTextWidth(row.cells[0], scale))
        val width = tableWidth + pad * 2
        val height = lineHeight * rows.size + pad * 2 - scale
        val left = viewportWidth - width - MARGIN * scale
        val top = MARGIN * scale
        renderer.rect(left, top, width, height, BACKGROUND, 0f)
        var y = top + pad
        for (row in rows) {
            val color = if (row.header) HEADER_COLOR else TEXT_COLOR
            val cells = row.cells
            val w = widths[row.block]
            var x = left + pad
            for (c in cells.indices) {
                val text = cells[c]
                val right = c > 0 && cells.size > 1
                val cellX = if (right) x + w[c] - McFontQueue.measureTextWidth(text, scale) else x
                renderer.mcText(text, cellX, y, color, false, scale)
                x += (if (cells.size > 1) w[c] else 0f) + gap
            }
            y += lineHeight
        }
    }

    private fun buildRows(): List<Row> {
        val snapshots = FrameProfiler.snapshot().filter { it.frames > 0 && it.framesSinceActive <= IDLE_FRAMES }
        val series = FrameProfiler.seriesSnapshot().filter { it.frames > 0 && it.framesSinceActive <= IDLE_FRAMES }
        val out = ArrayList<Row>(snapshots.size + series.size * 2 + 2)
        val effects = ProfilerHooks.effectTimingLabel?.let { ", effects gpu $it" } ?: ""
        out += Row(listOf("PolyCompose profiler, ms (total p50/p95/p99/max, phases p95), host gpu ${gpuLabel()}$effects"), true, 0)
        out += Row(listOf("host", "frames", "p50", "p95", "p99", "max", "compose", "layout", "record", "upload", "gpu"), true, 0)
        for (s in snapshots.sortedBy { it.key }) out += Row(row(s), false, 0)
        for (s in series.sortedBy { it.key }) {
            out += Row(listOf(s.key, "frames") + s.timings.keys + s.counts.keys, true, 1)
            out += Row(seriesRow(s), false, 1)
        }
        return out
    }

    private fun seriesRow(s: SeriesSnapshot): List<String> {
        val cells = ArrayList<String>(s.timings.size + s.counts.size + 2)
        cells += "ms p50/p95/max, counts p50/max"
        cells += s.frames.toString()
        for (t in s.timings.values) cells += FrameProfiler.millis(t.p50Nanos) + "/" + FrameProfiler.millis(t.p95Nanos) + "/" + FrameProfiler.millis(t.maxNanos)
        for (c in s.counts.values) cells += "${c.p50}/${c.max}"
        return cells
    }

    private fun row(s: ProfileSnapshot): List<String> = listOf(
        s.key,
        s.frames.toString(),
        FrameProfiler.millis(s.total.p50Nanos),
        FrameProfiler.millis(s.total.p95Nanos),
        FrameProfiler.millis(s.total.p99Nanos),
        FrameProfiler.millis(s.total.maxNanos),
        p95(s.compose),
        p95(s.layout),
        p95(s.record),
        p95(s.upload),
        s.gpu?.let(::p95) ?: "-",
    )

    private fun p95(stats: PhaseStats): String = FrameProfiler.millis(stats.p95Nanos)

    private fun gpuLabel(): String = when (FrameProfiler.gpuTiming) {
        GpuTiming.Available -> "timer queries"
        GpuTiming.Unavailable -> "unavailable"
        GpuTiming.Unknown -> "pending"
    }

    private data class Row(val cells: List<String>, val header: Boolean, val block: Int)

    private companion object {
        const val REFRESH_NANOS = 250_000_000L
        const val IDLE_FRAMES = 120L
        const val TEXT_SCALE = 0.75f
        const val PADDING = 3f
        const val COLUMN_GAP = 6f
        const val MARGIN = 2f
        const val BACKGROUND = 0xA0000000.toInt()
        const val HEADER_COLOR = 0xFFAAAAAA.toInt()
        const val TEXT_COLOR = 0xFFFFFFFF.toInt()
    }
}
