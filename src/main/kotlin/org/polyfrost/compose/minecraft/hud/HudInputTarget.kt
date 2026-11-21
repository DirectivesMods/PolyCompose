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
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.screens.ChatScreen
import org.polyfrost.compose.effect.PolyGlassLight
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.SceneKind
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.screen.ComposeSceneContextImpl
import org.polyfrost.compose.minecraft.screen.SceneWheel
import org.polyfrost.compose.minecraft.screen.ScrollMath
import org.polyfrost.compose.minecraft.screen.isKeyDown

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

internal class HudPointerRouter(private val options: ComposeOptions) {
    val wheel = SceneWheel(options, SceneKind.HudLayer)
    private var reported: Offset? = null

    var active = false
        private set

    private var lastPointer = Offset.Zero
    private var hasLastPointer = false
    private val wheelPointer: () -> Offset = { lastPointer }

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
        report(pointer)
        if (!hasLastPointer || pointer != lastPointer) {
            lastPointer = pointer
            hasLastPointer = true
            scene.sendPointerEvent(PointerEventType.Move, pointer)
        }
        wheel.frame(scene, density, wheelPointer)
    }

    private fun report(position: Offset?) {
        if (position == reported) return
        reported = position
        HostCallbacks.call(options, "ComposeOptions.onPointer", Unit) { options.onPointer(HudPointer, position) }
    }

    fun stop(scene: ComposeScene?) {
        wheel.stop()
        if (!active) return
        active = false
        HudPointer.routerStopped()
        report(null)
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
        val shift = isKeyDown(InputConstants.KEY_LSHIFT) || isKeyDown(InputConstants.KEY_RSHIFT)
        val notchX = ScrollMath.notches(x)
        val notchY = ScrollMath.notches(y)
        val sceneDelta = Offset(ScrollMath.horizontal(shift, notchX, notchY), ScrollMath.vertical(shift, notchY))
        val position = HudPointer.position()
        var consumed = false
        val handled = wheel.dispatch(position, sceneDelta, false) {
            lastPointer = position
            hasLastPointer = true
            scene.sendPointerEvent(PointerEventType.Move, position)
            consumed = SceneWheel.send(scene, position, sceneDelta, false) in HudPointer.CONSUMED_RESULTS
        }
        return consumed || handled
    }

    fun button(scene: ComposeScene, type: PointerEventType, button: PointerButton): Boolean {
        if (type == PointerEventType.Press) wheel.stop()
        return scene.sendPointerEvent(type, HudPointer.position(), button = button) in HudPointer.CONSUMED_RESULTS
    }
}

internal object HudPointer {
    const val INPUT_STALE_NANOS = 250_000_000L

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
        PolyGlassLight.leave()
    }

    fun pointerRouted(position: Offset) {
        if (notified && position == notifiedAt) return
        notified = true
        notifiedAt = position
        PolyGlassLight.moveTo(position)
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
