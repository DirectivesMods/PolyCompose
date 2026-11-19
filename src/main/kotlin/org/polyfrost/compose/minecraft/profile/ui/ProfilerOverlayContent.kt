package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.hostGraph
import org.polyfrost.compose.minecraft.profile.ui.FrameGraph.liveGraph
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCounter
import org.polyfrost.compose.profile.ProfilerCapture
import org.polyfrost.compose.profile.ProfilerSettings
import org.polyfrost.compose.profile.Recomposer

@Composable
internal fun ProfilerOverlayContent(overlay: ProfilerComposeOverlay, sampler: LiveSampler) {
    val density = LocalDensity.current.density
    var root by remember { mutableStateOf(IntSize.Zero) }
    var panel by remember { mutableStateOf(IntSize.Zero) }
    var drag by remember { mutableStateOf<Offset?>(null) }
    var expanded by remember { mutableStateOf(ProfilerSettings.overlayExpanded) }
    val margin = MARGIN_DP * density
    val stored = if (ProfilerSettings.overlayX >= 0f && ProfilerSettings.overlayY >= 0f) {
        Offset(ProfilerSettings.overlayX * density, ProfilerSettings.overlayY * density)
    } else {
        Offset(root.width - panel.width - margin, margin)
    }
    val raw = drag ?: stored
    val maxX = (root.width - panel.width).toFloat().coerceAtLeast(0f)
    val maxY = (root.height - panel.height).toFloat().coerceAtLeast(0f)
    val position = Offset(raw.x.coerceIn(0f, maxX), raw.y.coerceIn(0f, maxY))
    val latest = rememberUpdatedState(DragFrame(position, maxX, maxY, density))
    Box(Modifier.fillMaxSize().onSizeChanged { root = it }) {
        Column(
            Modifier
                .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                .onSizeChanged { panel = it }
                .onGloballyPositioned {
                    val b = it.boundsInWindow()
                    overlay.panelLeft = b.left
                    overlay.panelTop = b.top
                    overlay.panelRight = b.right
                    overlay.panelBottom = b.bottom
                }
                .background(ProfilerTheme.Panel, ProfilerTheme.Corner)
                .padding(4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Header(
                expanded = expanded,
                dragModifier = Modifier.pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { drag = latest.value.position },
                        onDragEnd = {
                            val end = drag
                            val frame = latest.value
                            if (end != null) {
                                ProfilerSettings.overlayX = end.x.coerceIn(0f, frame.maxX) / frame.density
                                ProfilerSettings.overlayY = end.y.coerceIn(0f, frame.maxY) / frame.density
                                ProfilerSettings.save()
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    ) { change, amount ->
                        change.consume()
                        drag = (drag ?: latest.value.position) + amount
                    }
                },
                onExpand = {
                    expanded = !expanded
                    ProfilerSettings.overlayExpanded = expanded
                    ProfilerSettings.save()
                },
            )
            val width = if (expanded) EXPANDED_WIDTH_DP.dp else COMPACT_WIDTH_DP.dp
            if (ProfilerSettings.graphs) {
                Canvas(Modifier.width(width).height(if (expanded) 44.dp else 30.dp)) {
                    sampler.graphTick.intValue
                    liveGraph(sampler)
                }
            }
            Stats(sampler, width)
            if (expanded) {
                Legend()
                Hosts(sampler, width)
            }
            if (expanded || ProfilerSettings.highlighter) Recomposers(sampler, width)
        }
    }
}

private class DragFrame(val position: Offset, val maxX: Float, val maxY: Float, val density: Float)

@Composable
private fun Header(expanded: Boolean, dragModifier: Modifier, onExpand: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(dragModifier.padding(end = 6.dp)) {
            BasicText("PolyCompose profiler", style = ProfilerTheme.Body)
        }
        if (ProfilerCapture.recording) BasicText("REC", style = ProfilerTheme.Small.copy(color = ProfilerTheme.Spike))
        ProfilerButton(if (expanded) "-" else "+", onClick = onExpand)
        if (ProfilerControl.openScreen != null) ProfilerButton("open") { ProfilerControl.openScreen?.invoke() }
        ProfilerButton("x") { ProfilerControl.toggleOverlay() }
    }
}

@Composable
private fun Stats(sampler: LiveSampler, width: Dp) {
    sampler.statsTick.intValue
    Column(Modifier.width(width)) {
        BasicText(
            "frame ${ms1(sampler.frameP50)} / ${ms1(sampler.frameP95)} / ${ms1(sampler.frameP99)} ms  ${sampler.fps} fps",
            style = ProfilerTheme.Small,
        )
        BasicText(
            "ui ${ms(sampler.uiP50)} / ${ms(sampler.uiP95)} / ${ms(sampler.uiP99)} ms (p50/p95/p99)",
            style = ProfilerTheme.Small,
        )
        var ops = 0L
        var batches = 0L
        var vertices = 0L
        var uploads = 0L
        for (t in sampler.tracks) {
            ops += t.counters[ProfileCounter.DrawOps.ordinal]
            batches += t.counters[ProfileCounter.Batches.ordinal]
            vertices += t.counters[ProfileCounter.Vertices.ordinal]
            uploads += t.counters[ProfileCounter.TextureUploads.ordinal]
        }
        BasicText(
            "ops ${compact(ops)}  batches ${compact(batches)}  verts ${compact(vertices)}  uploads ${compact(uploads)}",
            style = ProfilerTheme.SmallMuted,
        )
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in ProfilerTheme.Phases.indices) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                Swatch(ProfilerTheme.Phases[i])
                BasicText(ProfilerTheme.PhaseNames[i], style = ProfilerTheme.SmallMuted)
            }
        }
    }
}

@Composable
private fun Hosts(sampler: LiveSampler, width: Dp) {
    sampler.statsTick.intValue
    for (track in sampler.tracks) {
        if (track.activeFrames == 0) continue
        Column(Modifier.width(width)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Cell(track.host.key, width - 70.dp, end = false)
                Cell("p95 ${ms(track.p95)}", 35.dp)
                Cell("ops ${compact(track.counters[ProfileCounter.DrawOps.ordinal])}", 35.dp)
            }
            if (ProfilerSettings.graphs) {
                Canvas(Modifier.width(width).height(14.dp)) {
                    sampler.graphTick.intValue
                    hostGraph(sampler, track)
                }
            }
        }
    }
}

@Composable
internal fun Recomposers(sampler: LiveSampler, width: Dp) {
    sampler.statsTick.intValue
    val top: List<Recomposer> = sampler.topRecomposers
    Column(Modifier.width(width)) {
        val label = when {
            !FrameProfiler.recompositionsAvailable -> "top recomposers: unavailable"
            !FrameProfiler.recompositionsActive -> "top recomposers: off"
            top.isEmpty() -> "top recomposers: none"
            else -> "top recomposers (last second)"
        }
        BasicText(label, style = ProfilerTheme.SmallMuted)
        for (r in top) {
            Row {
                Cell(r.name, width - 70.dp, end = false)
                Cell(r.host, 45.dp, ProfilerTheme.SmallMuted, end = false)
                Cell(r.count.toString(), 25.dp)
            }
        }
    }
}

private const val MARGIN_DP = 4f
private const val COMPACT_WIDTH_DP = 150
private const val EXPANDED_WIDTH_DP = 210
