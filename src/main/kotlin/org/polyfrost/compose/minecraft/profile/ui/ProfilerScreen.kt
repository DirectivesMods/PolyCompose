package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.platform.InputConstants
import kotlinx.coroutines.delay
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.hostGraph
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.liveGraph
import org.polyfrost.compose.minecraft.screen.ComposeScreen
import org.polyfrost.compose.minecraft.screen.sceneDensity
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCapture
import org.polyfrost.compose.profile.ProfileCounter
import org.polyfrost.compose.profile.ProfilerCapture
import org.polyfrost.compose.profile.ProfilerSettings

internal enum class ProfilerTab(val label: String) {
    Live("Live"),
    Frames("Frames"),
    Spikes("Spikes"),
    Captures("Captures"),
    Settings("Settings"),
}

internal class ProfilerScreenState {
    var tab by mutableStateOf(ProfilerTab.Live)
    var source by mutableStateOf<ProfileCapture?>(null)
    var selected by mutableIntStateOf(-1)
    var tick by mutableIntStateOf(0)
    var message by mutableStateOf<String?>(null)

    fun inspect(capture: ProfileCapture, frame: Long = -1L) {
        source = capture
        val at = capture.frames.indexOfFirst { it.index == frame }
        selected = if (at >= 0) at else capture.frames.lastIndex
        tab = ProfilerTab.Frames
    }

    fun pause() {
        val capture = FrameProfiler.history(minOf(ProfilerSettings.historyFrames, PAUSE_FRAMES), "paused")
        if (capture.frames.isEmpty()) return
        inspect(capture)
    }

    fun resume() {
        source = null
        selected = -1
    }

    fun step(delta: Int) {
        val capture = source ?: return
        if (capture.frames.isEmpty()) return
        selected = (selected + delta).coerceIn(0, capture.frames.lastIndex)
    }

    fun jump(end: Boolean) {
        val capture = source ?: return
        selected = if (end) capture.frames.lastIndex else 0
    }

    private companion object {
        const val PAUSE_FRAMES = 1800
    }
}

internal class ProfilerScreen : ComposeScreen() {
    private val state = ProfilerScreenState()

    @Composable
    override fun compose() {
        ProfilerScreenContent(state) { McScreens.close() }
    }

    override fun densityScale(): Float = McWindow.guiScale() / sceneDensity()

    override fun handleKeyPressed(key: Int, modifiers: Int): Boolean {
        if (state.tab != ProfilerTab.Frames) return false
        when (key) {
            InputConstants.KEY_LEFT -> if (state.source == null) state.pause() else state.step(-1)
            InputConstants.KEY_RIGHT -> state.step(1)
            InputConstants.KEY_HOME -> state.jump(false)
            InputConstants.KEY_END -> state.jump(true)
            InputConstants.KEY_SPACE -> if (state.source == null) state.pause() else state.resume()
            else -> return false
        }
        return true
    }
}

@Composable
private fun ProfilerScreenContent(state: ProfilerScreenState, close: () -> Unit) {
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = System.nanoTime()
            if (FrameProfiler.enabled) ProfilerUi.sampler.refreshIfDue(now)
            if (now - last >= TICK_NANOS) {
                last = now
                state.tick++
            }
            delay(POLL_MILLIS)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).padding(10.dp)) {
        Column(
            Modifier.fillMaxSize().background(ProfilerTheme.Panel, ProfilerTheme.Corner).padding(6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TopBar(state, close)
            if (!FrameProfiler.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicText("The profiler is off, so nothing new is being recorded.", style = ProfilerTheme.BodyMuted)
                    ProfilerButton("Turn on") { ProfilerControl.setEnabled(true) }
                }
            }
            state.message?.let { BasicText(it, style = ProfilerTheme.SmallMuted) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when (state.tab) {
                    ProfilerTab.Live -> LiveTab(state)
                    ProfilerTab.Frames -> FramesTab(state)
                    ProfilerTab.Spikes -> SpikesTab(state)
                    ProfilerTab.Captures -> CapturesTab(state)
                    ProfilerTab.Settings -> SettingsTab(state)
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: ProfilerScreenState, close: () -> Unit) {
    state.tick
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        BasicText("PolyCompose profiler", style = ProfilerTheme.Title, modifier = Modifier.padding(end = 6.dp))
        for (tab in ProfilerTab.entries) {
            ProfilerButton(tab.label, active = state.tab == tab) { state.tab = tab }
        }
        Spacer(Modifier.weight(1f))
        ProfilerCapture.current?.let {
            BasicText("recording ${it.label}: ${it.frames} frames", style = ProfilerTheme.Small.copy(color = ProfilerTheme.Spike))
        }
        ProfilerButton("Close", onClick = close)
    }
}

@Composable
private fun LiveTab(state: ProfilerScreenState) {
    val sampler = ProfilerUi.sampler
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.fillMaxWidth().height(80.dp)) {
            sampler.graphTick.intValue
            liveGraph(sampler)
        }
        sampler.statsTick.intValue
        state.tick
        BasicText(
            "Last ${ms1(sampler.windowNanos)} ms, ${sampler.window} frames, ${sampler.fps} fps. " +
                "Frame time p50 ${ms(sampler.frameP50)} p95 ${ms(sampler.frameP95)} p99 ${ms(sampler.frameP99)} ms. " +
                "UI time p50 ${ms(sampler.uiP50)} p95 ${ms(sampler.uiP95)} p99 ${ms(sampler.uiP99)} ms.",
            style = ProfilerTheme.Body,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in ProfilerTheme.Phases.indices) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Swatch(ProfilerTheme.Phases[i])
                    BasicText(ProfilerTheme.PhaseNames[i], style = ProfilerTheme.SmallMuted)
                }
            }
            Swatch(ProfilerTheme.Spike)
            BasicText("spike", style = ProfilerTheme.SmallMuted)
            Swatch(ProfilerTheme.Threshold)
            BasicText("threshold ${ProfilerSettings.spikeThresholdMs} ms", style = ProfilerTheme.SmallMuted)
        }
        Row {
            Cell("host", HOST_COLUMN.dp, ProfilerTheme.SmallMuted, end = false)
            for (h in LIVE_HEADERS) Cell(h, NUMBER_COLUMN.dp, ProfilerTheme.SmallMuted)
        }
        for (track in sampler.tracks) {
            if (track.activeFrames == 0) continue
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell(track.host.key, HOST_COLUMN.dp, end = false)
                Cell(ms(track.p50), NUMBER_COLUMN.dp)
                Cell(ms(track.p95), NUMBER_COLUMN.dp)
                Cell(ms(track.p99), NUMBER_COLUMN.dp)
                for (c in ProfileCounter.entries) Cell(compact(track.counters[c.ordinal]), NUMBER_COLUMN.dp)
            }
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                sampler.graphTick.intValue
                hostGraph(sampler, track)
            }
        }
        Recomposers(sampler, (HOST_COLUMN + NUMBER_COLUMN * 4).dp)
        Spacer(Modifier.width(1.dp).height(4.dp))
    }
}

internal const val HOST_COLUMN = 120
internal const val NUMBER_COLUMN = 38
private val LIVE_HEADERS = listOf("p50", "p95", "p99", "ops", "batches", "verts", "uploads", "bytes", "layers", "recomp")
private const val TICK_NANOS = 250_000_000L
private const val POLL_MILLIS = 50L
