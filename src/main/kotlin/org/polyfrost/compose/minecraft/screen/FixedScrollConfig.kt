@file:Suppress("INVISIBLE_REFERENCE")

package org.polyfrost.compose.minecraft.screen

import androidx.compose.foundation.gestures.LocalScrollConfig
import androidx.compose.foundation.gestures.ScrollConfig
import androidx.compose.runtime.ProvidedValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import org.polyfrost.compose.host.WheelRouter

internal class FixedScrollConfig(private val router: WheelRouter) : ScrollConfig {
    override val isSmoothScrollingEnabled: Boolean get() = false

    override fun Density.calculateMouseWheelScroll(event: PointerEvent, bounds: IntSize): Offset {
        if (router.defersWheelScroll()) return Offset.Zero
        var delta = Offset.Zero
        val changes = event.changes
        for (i in changes.indices) delta += changes[i].scrollDelta
        return delta * -density
    }

    fun provided(): ProvidedValue<*> = LocalScrollConfig provides this
}
