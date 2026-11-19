package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.liveGraph
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.strip
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.FrameRecord
import org.polyfrost.compose.profile.ProfileCapture
import org.polyfrost.compose.profile.ProfileCounter
import org.polyfrost.compose.profile.ProfilePhase

private class StripData(val totals: LongArray, val spikes: BooleanArray)

@Composable
internal fun FramesTab(state: ProfilerScreenState) {
    val source = state.source
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (source == null) {
                ProfilerButton("Pause") { state.pause() }
                BasicText("Live: showing the newest finished frame. Pause to step through history.", style = ProfilerTheme.SmallMuted)
            } else {
                ProfilerButton("Live") { state.resume() }
                ProfilerButton("|<") { state.jump(false) }
                ProfilerButton("<") { state.step(-1) }
                ProfilerButton(">") { state.step(1) }
                ProfilerButton(">|") { state.jump(true) }
                ProfilerButton("Export") { ProfilerControl.exportCapture(source) }
                BasicText(
                    "${source.label}: frame ${state.selected + 1} of ${source.frames.size}. Arrow keys step, Space resumes.",
                    style = ProfilerTheme.SmallMuted,
                )
            }
        }
        if (source == null) LiveFrames(state) else CaptureFrames(state, source)
    }
}

@Composable
private fun LiveFrames(state: ProfilerScreenState) {
    val sampler = ProfilerUi.sampler
    Canvas(Modifier.fillMaxWidth().height(40.dp)) {
        sampler.graphTick.intValue
        liveGraph(sampler)
    }
    val tick = state.tick
    val record = remember(tick) { FrameProfiler.record(FrameProfiler.frame - SETTLED_FRAMES) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (record == null) BasicText("No frames recorded yet.", style = ProfilerTheme.BodyMuted) else FrameDetail(record)
    }
}

@Composable
private fun CaptureFrames(state: ProfilerScreenState, source: ProfileCapture) {
    val data = remember(source) {
        val n = source.frames.size
        val totals = LongArray(n)
        val spikes = BooleanArray(n)
        for (i in 0 until n) {
            val f = source.frames[i]
            totals[i] = f.cpuNanos + f.gpuNanos.coerceAtLeast(0L)
            spikes[i] = f.spike
        }
        StripData(totals, spikes)
    }
    val count = data.totals.size
    val selected = state.selected
    Canvas(
        Modifier.fillMaxWidth().height(40.dp)
            .pointerInput(source) { detectTapGestures { state.selected = FrameGraph.indexAt(it.x, size.width.toFloat(), count) } }
            .pointerInput(source) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    state.selected = FrameGraph.indexAt(change.position.x, size.width.toFloat(), count)
                }
            },
    ) {
        strip(data.totals, data.spikes, count, selected)
    }
    val record = source.frames.getOrNull(selected)
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        if (record == null) BasicText("This capture has no frames.", style = ProfilerTheme.BodyMuted) else FrameDetail(record)
    }
}

@Composable
internal fun FrameDetail(record: FrameRecord) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        val frameTime = if (record.frameNanos >= 0L) "${ms(record.frameNanos)} ms" else "unknown"
        BasicText(
            "Frame #${record.index}: frame time $frameTime, UI CPU ${ms(record.cpuNanos)} ms, GPU ${ms(record.gpuNanos.coerceAtLeast(0L))} ms" +
                if (record.spike) ", spike" else "",
            style = if (record.spike) ProfilerTheme.Body.copy(color = ProfilerTheme.Spike) else ProfilerTheme.Body,
        )
        BasicText("Hosts", style = ProfilerTheme.BodyMuted)
        Row {
            Cell("host", HOST_COLUMN.dp, ProfilerTheme.SmallMuted, end = false)
            for (p in ProfilePhase.entries) Cell(ProfilerTheme.PhaseNames[p.ordinal], NUMBER_COLUMN.dp, ProfilerTheme.Small.copy(color = ProfilerTheme.phaseColor(p)))
            Cell("total", NUMBER_COLUMN.dp, ProfilerTheme.SmallMuted)
        }
        for (h in record.hosts) {
            Row {
                Cell(h.host, HOST_COLUMN.dp, end = false)
                for (p in ProfilePhase.entries) {
                    val v = h.phase(p)
                    Cell(if (v < 0L) "-" else ms(v), NUMBER_COLUMN.dp)
                }
                Cell(ms(h.cpuNanos + h.gpuNanos.coerceAtLeast(0L)), NUMBER_COLUMN.dp)
            }
        }
        BasicText("Counters", style = ProfilerTheme.BodyMuted)
        Row {
            Cell("host", HOST_COLUMN.dp, ProfilerTheme.SmallMuted, end = false)
            for (c in ProfileCounter.entries) Cell(c.label, NUMBER_COLUMN.dp, ProfilerTheme.SmallMuted)
        }
        for (h in record.hosts) {
            Row {
                Cell(h.host, HOST_COLUMN.dp, end = false)
                for (c in ProfileCounter.entries) Cell(compact(h.counter(c)), NUMBER_COLUMN.dp)
            }
        }
        Row {
            Cell("frame total", HOST_COLUMN.dp, ProfilerTheme.SmallMuted, end = false)
            for (c in ProfileCounter.entries) Cell(compact(record.counter(c)), NUMBER_COLUMN.dp, ProfilerTheme.SmallMuted)
        }
        if (record.series.isNotEmpty()) {
            BasicText("GPU categories", style = ProfilerTheme.BodyMuted)
            for (s in record.series) {
                val timings = s.timingNames.indices.joinToString("  ") { "${s.timingNames[it]} ${ms(s.timing(it))}" }
                val counts = s.countNames.indices.joinToString("  ") { "${s.countNames[it]} ${s.count(it)}" }
                BasicText("${s.series}: $timings", style = ProfilerTheme.Small)
                if (counts.isNotEmpty()) BasicText("    $counts", style = ProfilerTheme.SmallMuted)
            }
        }
        BasicText("Top recomposers", style = ProfilerTheme.BodyMuted)
        if (record.recomposers.isEmpty()) {
            val why = if (FrameProfiler.recompositionsActive) "none this frame" else "tracking is off or unavailable"
            BasicText(why, style = ProfilerTheme.SmallMuted)
        }
        for (r in record.recomposers) {
            Row {
                Cell(r.name, HOST_COLUMN.dp * 2, end = false)
                Cell(r.host, HOST_COLUMN.dp, ProfilerTheme.SmallMuted, end = false)
                Cell(r.count.toString(), NUMBER_COLUMN.dp)
            }
        }
        if (record.unattributedRecompositions > 0) {
            BasicText("unattributed recompositions: ${record.unattributedRecompositions}", style = ProfilerTheme.SmallMuted)
        }
    }
}

private const val SETTLED_FRAMES = 8
