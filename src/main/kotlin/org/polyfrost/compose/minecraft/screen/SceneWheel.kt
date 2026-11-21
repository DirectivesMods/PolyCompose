@file:Suppress("INVISIBLE_REFERENCE")
@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.screen

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.LocalWheelHandler
import org.polyfrost.compose.host.SceneKind
import org.polyfrost.compose.host.WheelHandler
import org.polyfrost.compose.host.WheelInput
import org.polyfrost.compose.host.wheelInput
import org.polyfrost.compose.minecraft.platform.OperatingSystem

internal class SceneWheel(options: ComposeOptions, kind: SceneKind) : ScrollConfig {
    private val handler: WheelHandler? =
        HostCallbacks.call(options, "ComposeOptions.createWheelHandler", null) { options.createWheelHandler(kind) }

    private var anchor = Offset.Zero
    private var carry = Offset.Zero
    private var overridden = false

    override val isSmoothScrollingEnabled: Boolean get() = true

    override fun isPreciseWheelScroll(event: PointerEvent): Boolean =
        overridden || event.wheelInput == WheelInput.Precise || event.wheelInput == WheelInput.Direct

    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        overridden = false
        var delta = Offset.Zero
        val changes = event.changes
        for (i in changes.indices) delta += changes[i].scrollDelta
        if (event.wheelInput == WheelInput.Direct) return delta * -density
        val handler = handler
        if (handler != null) {
            val custom = HostCallbacks.call(handler, "WheelHandler.scrollOffset", null) {
                with(handler) { scrollOffset(event, bounds) }
            }
            if (custom != null) {
                overridden = true
                return custom
            }
        }
        val scaled = when {
            OperatingSystem.isMac -> delta * 10.dp.toPx()
            OperatingSystem.isLinux -> Offset(delta.x * sqrt(bounds.width.toFloat()), delta.y * sqrt(bounds.height.toFloat())) * LINES
            else -> Offset(delta.x * bounds.width / WINDOWS_DIVISOR, delta.y * bounds.height / WINDOWS_DIVISOR) * LINES
        }
        return -scaled
    }

    fun provided(): Array<ProvidedValue<*>> = arrayOf(LocalScrollConfig provides this, LocalWheelHandler provides handler)

    fun dispatch(position: Offset, delta: Offset, precise: Boolean, send: () -> Unit): Boolean {
        anchor = position
        carry = Offset.Zero
        val handler = handler
        if (handler == null || HostCallbacks.isDisabled(handler)) {
            send()
            return false
        }
        var sent = false
        val handled = HostCallbacks.call(handler, "WheelHandler.dispatch", null) {
            handler.dispatch(delta, precise) {
                sent = true
                send()
            }
        }
        if (handled == null && !sent) send()
        return handled == true
    }

    fun frame(scene: ComposeScene, density: Float, pointer: () -> Offset) {
        val handler = handler ?: return
        HostCallbacks.call(handler, "WheelHandler.frame", Unit) {
            handler.frame { step ->
                carry += step
                if (abs(carry.x * density) >= 1f || abs(carry.y * density) >= 1f) {
                    val carried = carry
                    carry = Offset.Zero
                    scrollDirect(scene, anchor, carried)
                    val now = pointer()
                    if (now != anchor) scene.sendPointerEvent(PointerEventType.Move, now)
                }
            }
        }
    }

    fun stop() {
        carry = Offset.Zero
        val handler = handler ?: return
        HostCallbacks.call(handler, "WheelHandler.stop", Unit) { handler.stop() }
    }

    companion object {
        private const val LINES = 3f

        private const val WINDOWS_DIVISOR = 20f

        fun send(scene: ComposeScene, position: Offset, delta: Offset, precise: Boolean): PointerEventResult =
            scene.sendPointerEvent(
                PointerEventType.Scroll,
                position,
                scrollDelta = delta,
                nativeEvent = if (precise) WheelInput.Precise else WheelInput.Notched,
            )

        fun scrollDirect(scene: ComposeScene, position: Offset, dp: Offset): PointerEventResult =
            scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = dp, nativeEvent = WheelInput.Direct)
    }
}
