package org.polyfrost.compose.host

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize

public interface WheelHandler {
    public fun dispatch(delta: Offset, precise: Boolean, send: () -> Unit): Boolean {
        send()
        return false
    }

    public fun Density.scrollOffset(event: PointerEvent, bounds: IntSize): Offset? = null

    public fun frame(scroll: (Offset) -> Unit) {}

    public fun stop() {}
}

public enum class WheelInput { Notched, Precise, Direct }

public val PointerEvent.wheelInput: WheelInput? get() = nativeEvent as? WheelInput

public val LocalWheelHandler: ProvidableCompositionLocal<WheelHandler?> = staticCompositionLocalOf { null }
