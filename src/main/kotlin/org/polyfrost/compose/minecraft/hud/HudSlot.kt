package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import org.polyfrost.compose.minecraft.canvas.InlineCanvasSurface
import org.polyfrost.compose.minecraft.screen.sceneDensity
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measureSlice
import org.polyfrost.compose.state.LocalTickActivity
import org.polyfrost.compose.state.TickActivity

internal fun hudBaseDensity(): Float = try {
    sceneDensity()
} catch (_: Throwable) {
    1f
}

internal class HudSlot(val layer: HudLayer, val seq: Int) {
    var shown by mutableStateOf(false)
    var order by mutableIntStateOf(layer.order)
    var density by mutableFloatStateOf(1f)

    var requestedFrame = Long.MIN_VALUE
    var attached = false
    var probationUntil = 0L
    var exiles = 0
    var calledAt = 0L
    var dirty = true
    var runNext: HudSlot? = null
    var learnedNext: HudSlot? = null
    var paintedFrame = -1L
    var collectedBy = 0L
    val run = ArrayList<HudSlot>()
    val gate = HudSlotGate()

    var painting: Boolean
        get() = gate.painting
        set(value) {
            gate.painting = value
        }

    private val recordedRun = ArrayList<HudSlot>()
    private var surface: InlineCanvasSurface? = null
    private var surfaceFrame = 0L

    private var profile: ProfileHost? = null

    val tickActivity = TickActivity {
        attached && shown && layer.visible && System.nanoTime() - layer.sharedScene.drawnAt < HudPointer.INPUT_STALE_NANOS
    }

    fun profile(): ProfileHost? {
        if (!FrameProfiler.enabled) return null
        return profile ?: FrameProfiler.host("hud/${layer.id}").also { profile = it }
    }

    private val measurePolicy = MeasurePolicy { measurables, constraints ->
        val placeables = profile().measureSlice(layer.sharedScene.profile(), ProfilePhase.Layout) {
            measurables.map { it.measure(constraints) }
        }
        val width = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
        val height = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
        layout(width, height) {
            if (shown) {
                profile().measureSlice(layer.sharedScene.profile(), ProfilePhase.Layout) {
                    for (placeable in placeables) placeable.place(0, 0)
                }
            }
        }
    }

    fun surface(frame: Long): InlineCanvasSurface {
        surfaceFrame = frame
        return surface ?: InlineCanvasSurface("hud_shared/$seq").also {
            surface = it
            recordedRun.clear()
        }
    }

    fun runChanged(): Boolean = recordedRun != run

    fun recorded(drawn: Boolean) {
        recordedRun.clear()
        if (drawn) for (i in run.indices) recordedRun.add(run[i])
    }

    fun releaseIdleSurface(frame: Long) {
        if (surface != null && frame - surfaceFrame > 1) closeSurface()
    }

    fun closeSurface() {
        val old = surface ?: return
        surface = null
        recordedRun.clear()
        dirty = true
        old.close()
    }

    var hasFocus = false
        private set

    private val modifier = gate.modifier
        .onFocusChanged { hasFocus = it.hasFocus }
        .drawWithContent {
            profile().measureSlice(layer.sharedScene.profile(), ProfilePhase.Record) { drawContent() }
        }

    @Composable
    fun host() {
        CompositionLocalProvider(LocalDensity provides Density(density), LocalTickActivity provides tickActivity) {
            Layout(content = { layer.layerContent() }, modifier = modifier, measurePolicy = measurePolicy)
        }
    }
}
