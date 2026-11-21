@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.minecraft.canvas.InlineCanvasSurface
import org.polyfrost.compose.minecraft.screen.ComposeSupport
import org.polyfrost.compose.minecraft.screen.SceneHandle
import org.polyfrost.compose.state.LocalTickActivity
import org.polyfrost.compose.state.TickActivity

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}

internal class IsolatedHudScene(private val layer: HudLayer) : HudSceneBase("hud/${layer.id}", layer.options), AutoCloseable {
    private val surface = InlineCanvasSurface("hud/${layer.id}")
    private val tickActivity = TickActivity { layer.visible && System.nanoTime() - lastDrawn < HudPointer.INPUT_STALE_NANOS }
    private var retryAt = 0L

    val failing: Boolean
        get() = failures > 0

    override val routesInput: Boolean
        get() = layer.acceptsInput

    override val acceptsPointer: Boolean
        get() = layer.visible && layer.acceptsInput

    //? if > 1.8.9 {
    fun draw(graphics: GuiGraphicsExtractor): Boolean = drawLayer { redraw, block -> surface.draw(graphics, redraw, block) }
    //?} else {
    /*
    fun draw(): Boolean = drawLayer { redraw, block -> surface.draw(redraw, block) }
    *///?}

    override fun collectLayers(into: MutableList<HudLayer>) {
        into.add(layer)
    }

    override fun close() {
        releaseScene()
        surface.close()
    }

    fun stopInput() {
        pointer.stop(handle?.scene)
    }

    private inline fun drawLayer(paint: (Boolean, (PolyCanvas) -> Unit) -> Boolean): Boolean =
        renderFrame({ layer.density }, { _, _ -> tickActivity.resume() }, paint)

    override fun ensureScene(): SceneHandle? {
        handle?.let { return it }
        if (failures > 0 && System.nanoTime() - retryAt < 0) return null
        if (!ComposeSupport.isAvailable) return null
        return openScene {
            CompositionLocalProvider(
                *pointer.wheel.provided(),
                LocalTickActivity provides tickActivity,
            ) {
                layer.layerContent()
            }
        }
    }

    override fun fail(cause: Throwable) {
        failures++
        retryAt = System.nanoTime() + retryDelayNanos(failures)
        HostErrors.error("Compose HUD layer '${layer.id}' (${layer.ownerName}) failed; it will be rebuilt after a delay", cause, LOGGER)
        releaseScene()
    }

    private companion object {
        const val MAX_RETRY_DELAY_NANOS = 60_000_000_000L

        fun retryDelayNanos(failures: Int): Long =
            (1_000_000_000L shl (failures - 1).coerceIn(0, 6)).coerceAtMost(MAX_RETRY_DELAY_NANOS)
    }
}
