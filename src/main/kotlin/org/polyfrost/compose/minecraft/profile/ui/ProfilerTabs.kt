package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.minecraft.platform.OperatingSystem
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCapture
import org.polyfrost.compose.profile.ProfileSpike
import org.polyfrost.compose.profile.ProfilerCapture
import org.polyfrost.compose.profile.ProfilerSettings
import org.polyfrost.compose.profile.ProfilerSpikes
import org.polyfrost.compose.profile.SpikeReason

private val LOGGER = LogManager.getLogger("PolyCompose/Profiler")

private fun seconds(nanos: Long): String = String.format(Locale.ROOT, "%.1f", nanos / NANOS_PER_SECOND)

private fun time(epochMillis: Long): String = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(epochMillis))

private fun export(state: ProfilerScreenState, capture: ProfileCapture) {
    state.message = "Exporting ${capture.label}..."
    ProfilerControl.exportCapture(capture, announce = false) { path ->
        state.message = if (path != null) "Saved $path" else "Could not save ${capture.label}. Check the log for details."
        Snapshot.sendApplyNotifications()
    }
}

@Composable
internal fun SpikesTab(state: ProfilerScreenState) {
    val tick = state.tick
    val spikes = remember(tick) { ProfilerSpikes.list().asReversed() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfilerToggle("Catch spikes", ProfilerSettings.spikes) { ProfilerControl.setSpikes(it) }
            BasicText(
                "A spike is a frame where UI work takes over ${ProfilerSettings.spikeThresholdMs} ms, " +
                    "or the whole frame takes over ${ProfilerSpikes.frameTimeFactor}x the usual time.",
                style = ProfilerTheme.SmallMuted,
            )
            ProfilerButton("Clear") { ProfilerSpikes.clear() }
        }
        if (spikes.isEmpty()) BasicText("No spikes caught yet.", style = ProfilerTheme.BodyMuted)
        for (spike in spikes) SpikeRow(state, spike)
    }
}

@Composable
private fun SpikeRow(state: ProfilerScreenState, spike: ProfileSpike) {
    val c = spike.culprit
    Column(Modifier.fillMaxWidth().background(ProfilerTheme.PanelRaised, ProfilerTheme.Corner).padding(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            val reason = if (spike.reason == SpikeReason.HostTime) "UI time ${ms(spike.hostNanos)} ms" else "frame time ${ms(spike.frameNanos)} ms"
            BasicText("${time(spike.epochMillis)}  frame #${spike.frame}  $reason", style = ProfilerTheme.Body.copy(color = ProfilerTheme.Spike))
            ProfilerButton("Inspect") { state.inspect(spike.capture, spike.frame) }
            ProfilerButton("Export") { export(state, spike.capture) }
        }
        val culprit = buildString {
            append("culprit ").append(c.host ?: "unknown")
            c.phase?.let { append(", ").append(ProfilerTheme.PhaseNames[it.ordinal]).append(' ').append(ms(c.phaseNanos)).append(" ms") }
            c.gpuCategory?.let { append(", gpu ").append(it).append(' ').append(ms(c.gpuCategoryNanos)).append(" ms") }
            if (spike.medianFrameNanos > 0L) append(", usual frame ").append(ms(spike.medianFrameNanos)).append(" ms")
        }
        BasicText(culprit, style = ProfilerTheme.Small)
        if (c.recomposers.isNotEmpty()) {
            BasicText(c.recomposers.joinToString("  ") { "${it.name} x${it.count}" }, style = ProfilerTheme.SmallMuted)
        }
    }
}

@Composable
internal fun CapturesTab(state: ProfilerScreenState) {
    val tick = state.tick
    var seconds by remember { mutableIntStateOf(DURATIONS[1]) }
    val captures = remember(tick) { ProfilerCapture.list().asReversed() }
    val current = ProfilerCapture.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (current == null) {
                BasicText("Length", style = ProfilerTheme.BodyMuted)
                for (d in DURATIONS) ProfilerButton(if (d == 0) "until stopped" else "${d}s", active = seconds == d) { seconds = d }
                ProfilerButton("Start") {
                    ProfilerControl.startCapture(seconds.toDouble())
                    ProfilerControl.refreshEnabled()
                }
            } else {
                val total = if (current.durationNanos > 0L) " of ${seconds(current.durationNanos)} s" else ""
                BasicText(
                    "Recording: ${seconds(current.elapsedNanos)} s$total, ${current.frames} frames",
                    style = ProfilerTheme.Body.copy(color = ProfilerTheme.Spike),
                )
                ProfilerButton("Stop and save") { ProfilerControl.stopCapture() }
            }
            ProfilerButton("Save recent history") {
                val capture = FrameProfiler.history(label = "history")
                if (capture.frames.isEmpty()) state.message = "Nothing recorded yet." else export(state, capture)
            }
            ProfilerButton("Open folder") { openFolder(state, ProfilerControl.profilesDir) }
        }
        BasicText("Saved files are Chrome trace JSON (open in Perfetto or chrome://tracing) plus a text summary.", style = ProfilerTheme.SmallMuted)
        if (captures.isEmpty()) BasicText("No captures yet.", style = ProfilerTheme.BodyMuted)
        for (capture in captures) {
            Row(
                Modifier.fillMaxWidth().background(ProfilerTheme.PanelRaised, ProfilerTheme.Corner).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicText(
                    "${time(capture.startedAtEpochMillis)}  ${capture.label}  ${capture.frames.size} frames  " +
                        "${seconds(capture.durationNanos)} s  ${capture.spikes.size} spikes",
                    style = ProfilerTheme.Body,
                )
                ProfilerButton("Inspect") { state.inspect(capture) }
                ProfilerButton("Export") { export(state, capture) }
            }
        }
    }
}

private fun openFolder(state: ProfilerScreenState, dir: Path) {
    BackgroundTasks.submit {
        try {
            Files.createDirectories(dir)
            val command = when {
                OperatingSystem.isWindows -> listOf("explorer", dir.toString())
                OperatingSystem.isMac -> listOf("open", dir.toString())
                else -> listOf("xdg-open", dir.toString())
            }
            ProcessBuilder(command).start()
        } catch (e: IOException) {
            LOGGER.warn("Could not open {}", dir, e)
            state.message = "Could not open $dir"
            Snapshot.sendApplyNotifications()
        }
    }
}

@Composable
internal fun SettingsTab(state: ProfilerScreenState) {
    state.tick
    var revision by remember { mutableIntStateOf(0) }
    var threshold by remember { mutableDoubleStateOf(ProfilerSettings.spikeThresholdMs) }
    revision
    fun changed() {
        ProfilerSettings.apply()
        ProfilerControl.refreshEnabled()
        ProfilerSettings.save()
        revision++
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        ProfilerToggle("Show the live overlay", ProfilerSettings.overlayVisible) {
            ProfilerControl.toggleOverlay()
            revision++
        }
        ProfilerToggle("Expanded overlay", ProfilerSettings.overlayExpanded) {
            ProfilerSettings.overlayExpanded = it
            changed()
        }
        ProfilerToggle("Draw graphs", ProfilerSettings.graphs) {
            ProfilerSettings.graphs = it
            changed()
        }
        ProfilerToggle("Flash redrawn areas", ProfilerSettings.highlighter) {
            ProfilerSettings.highlighter = it
            changed()
        }
        ProfilerToggle("Track recompositions", ProfilerSettings.recompositions) {
            ProfilerSettings.recompositions = it
            changed()
        }
        ProfilerToggle("Catch spikes", ProfilerSettings.spikes) {
            ProfilerControl.setSpikes(it)
            revision++
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("Spike threshold", style = ProfilerTheme.Body)
            ProfilerButton("-") {
                threshold = (threshold - THRESHOLD_STEP).coerceAtLeast(THRESHOLD_STEP)
                ProfilerSettings.spikeThresholdMs = threshold
                changed()
            }
            BasicText(String.format(Locale.ROOT, "%.1f ms", threshold), style = ProfilerTheme.Body)
            ProfilerButton("+") {
                threshold += THRESHOLD_STEP
                ProfilerSettings.spikeThresholdMs = threshold
                changed()
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            BasicText("History length", style = ProfilerTheme.Body)
            for (frames in HISTORY_OPTIONS) {
                ProfilerButton("$frames frames", active = ProfilerSettings.historyFrames == frames) {
                    ProfilerSettings.historyFrames = frames
                    changed()
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ProfilerButton("Reset overlay position") {
                ProfilerSettings.overlayX = -1f
                ProfilerSettings.overlayY = -1f
                changed()
            }
            ProfilerButton("Clear all data") {
                ProfilerControl.reset()
                ProfilerUi.sampler.reset()
                state.resume()
                state.message = "Profiler data cleared."
            }
            ProfilerButton(if (FrameProfiler.enabled) "Turn profiler off" else "Turn profiler on") {
                ProfilerControl.setEnabled(!FrameProfiler.enabled)
                revision++
            }
        }
        BasicText(
            "Settings are saved to config/polycompose-profiler.json. The overlay key is unbound by default; set it in Controls.",
            style = ProfilerTheme.SmallMuted,
        )
    }
}

private val DURATIONS = intArrayOf(5, 10, 30, 60, 0)
private val HISTORY_OPTIONS = intArrayOf(600, 1800, 3600, 7200, 18000)
private const val THRESHOLD_STEP = 0.5
private const val NANOS_PER_SECOND = 1_000_000_000.0
