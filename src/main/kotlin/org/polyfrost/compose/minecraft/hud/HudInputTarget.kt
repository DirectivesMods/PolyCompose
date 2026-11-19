@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.PointerEventResult
import kotlin.math.abs
import net.minecraft.client.gui.screens.ChatScreen
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.WheelRouter
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.platform.OperatingSystem
import org.polyfrost.compose.minecraft.screen.ComposeSceneContextImpl
import org.polyfrost.compose.minecraft.screen.FixedScrollConfig

//? if > 1.8.9 {
import net.minecraft.client.Minecraft
//?}

//? if = 1.8.9 {
/*import org.lwjgl.input.Mouse
*///?}

internal interface HudInputTarget {
    val takingInput: Boolean

    val textFocused: Boolean

    fun collectLayers(into: MutableList<HudLayer>)

    fun claims(layer: HudLayer, position: Offset): Boolean

    fun press(button: PointerButton): Boolean

    fun release(button: PointerButton)

    fun scroll(x: Double, y: Double): Boolean

    fun sendKey(event: KeyEvent): Boolean

    fun releaseFocus()
}

internal class HudPointerRouter {
    val wheelRouter = WheelRouter()
    val scrollConfig = FixedScrollConfig(wheelRouter)

    var active = false
        private set

    private var lastPointer = Offset.Zero
    private var hasLastPointer = false
    private var glideAnchor = Offset.Zero
    private var glideCarry = Offset.Zero
    private var glideScene: ComposeScene? = null
    private var glidePointer = Offset.Zero
    private var glideDensity = 1f

    private val glideStep: (Offset) -> Unit = { step ->
        val scene = glideScene
        if (scene != null) {
            glideCarry += step
            val scale = glideDensity
            if (abs(glideCarry.x * scale) >= 1f || abs(glideCarry.y * scale) >= 1f) {
                val carried = glideCarry
                glideCarry = Offset.Zero
                scene.sendPointerEvent(PointerEventType.Scroll, glideAnchor, scrollDelta = carried)
                if (glidePointer != glideAnchor) scene.sendPointerEvent(PointerEventType.Move, glidePointer)
            }
        }
    }

    fun route(scene: ComposeScene, enabled: Boolean, density: Float) {
        if (!enabled) {
            stop(scene)
            return
        }
        if (!active) {
            active = true
            HudPointer.routerStarted()
        }
        val pointer = HudPointer.position()
        HudPointer.pointerRouted(pointer)
        if (!hasLastPointer || pointer != lastPointer) {
            lastPointer = pointer
            hasLastPointer = true
            scene.sendPointerEvent(PointerEventType.Move, pointer)
        }
        glideScene = scene
        glidePointer = pointer
        glideDensity = density
        try {
            wheelRouter.glideFrame(glideStep)
        } finally {
            glideScene = null
        }
    }

    fun stop(scene: ComposeScene?) {
        wheelRouter.stopFallback()
        glideCarry = Offset.Zero
        if (!active) return
        active = false
        HudPointer.routerStopped()
        val hadPointer = hasLastPointer
        hasLastPointer = false
        if (scene == null) return
        runCatching {
            scene.cancelPointerInput()
            if (hadPointer) scene.sendPointerEvent(PointerEventType.Exit, lastPointer)
            ComposeSceneContextImpl.resetPointerIcon()
        }
    }

    fun scroll(scene: ComposeScene, x: Double, y: Double): Boolean {
        val delta = Offset((-x * HudPointer.WHEEL_UNIT_DP).toFloat(), (-y * HudPointer.WHEEL_UNIT_DP).toFloat())
        val position = HudPointer.position()
        var hit = false
        wheelRouter.stopFallback()
        glideAnchor = position
        glideCarry = Offset.Zero
        wheelRouter.route(delta) {
            lastPointer = position
            hasLastPointer = true
            scene.sendPointerEvent(PointerEventType.Move, position)
            val result = scene.sendPointerEvent(PointerEventType.Scroll, position, scrollDelta = delta)
            hit = result in HudPointer.CONSUMED_RESULTS || wheelRouter.lastRouteHandled
        }
        return hit
    }

    fun button(scene: ComposeScene, type: PointerEventType, button: PointerButton): Boolean {
        if (type == PointerEventType.Press) wheelRouter.stopFallback()
        return scene.sendPointerEvent(type, HudPointer.position(), button = button) in HudPointer.CONSUMED_RESULTS
    }
}

internal object HudPointer {
    const val INPUT_STALE_NANOS = 250_000_000L

    val WHEEL_UNIT_DP = if (OperatingSystem.isMac) 50f else 150f

    val CONSUMED_RESULTS: Set<PointerEventResult> = listOf(false, true).map { moved ->
        PointerEventResult(
            anyMovementConsumed = moved,
            anyChangeConsumed = true,
            dispatchedToAPointerInputModifier = true,
        )
    }.toSet()

    private var allowedFrame = -1L
    private var allowed = false
    private var routers = 0
    private var notified = false
    private var notifiedAt = Offset.Zero

    fun routerStarted() {
        routers++
    }

    fun routerStopped() {
        routers = (routers - 1).coerceAtLeast(0)
        if (routers > 0 || !notified) return
        notified = false
        HostCallbacks.each(ComposeHost.listeners, "HostListener.onPointer") { it.onPointer(HudPointer, null) }
    }

    fun pointerRouted(position: Offset) {
        if (notified && position == notifiedAt) return
        notified = true
        notifiedAt = position
        HostCallbacks.each(ComposeHost.listeners, "HostListener.onPointer") { it.onPointer(HudPointer, position) }
    }

    fun inputAllowedThisFrame(): Boolean {
        val frame = HostEvents.frame
        if (frame != allowedFrame) {
            allowedFrame = frame
            allowed = inputAllowed()
        }
        return allowed
    }

    fun inputAllowed(): Boolean {
        if (!McWindow.isActive()) return false
        val screen = runCatching { McScreens.current() }.getOrNull() ?: return false
        return screen is ChatScreen || screen is HudLayer.InputScreen
    }

    fun position(): Offset {
        //? if > 1.8.9 {
        val mouse = Minecraft.getInstance().mouseHandler
        return Offset(mouse.xpos().toFloat(), mouse.ypos().toFloat())
        //?} else {
        /*val pixelRatio = McWindow.pixelRatio()
        return Offset(Mouse.getX() / pixelRatio, (McWindow.viewportHeight() - Mouse.getY() - 1) / pixelRatio)
        *///?}
    }
}
