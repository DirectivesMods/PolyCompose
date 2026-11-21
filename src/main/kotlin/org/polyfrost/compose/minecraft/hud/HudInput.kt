package org.polyfrost.compose.minecraft.hud

import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import com.mojang.blaze3d.platform.InputConstants
import java.util.Collections
import java.util.WeakHashMap
import org.polyfrost.compose.host.BackStack
import org.polyfrost.compose.host.KeyState
import org.polyfrost.compose.minecraft.platform.GameKeyRouter
import org.polyfrost.compose.minecraft.platform.McKeys
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.screen.ComposeScreen

//? if = 1.8.9 {
/*import org.lwjgl.input.Keyboard
*///?}

object HudInput {
    private class Press(val receivers: List<HudInputTarget>, val consumed: Boolean)

    private val scenes: MutableSet<HudInputTarget> = Collections.newSetFromMap(WeakHashMap())
    private val presses = HashMap<Int, Press>()
    private val candidates = ArrayList<HudLayer>()
    private val sceneBuffer = ArrayList<HudInputTarget>()
    private val tried = ArrayList<HudInputTarget>()
    private val heldKeys = HashMap<Int, HudInputTarget>()
    private var escapeConsumed = false
    private var drawSeq = 0L

    private val topmostFirst = Comparator<HudLayer> { a, b -> b.drawSeq.compareTo(a.drawSeq) }

    internal fun register(scene: HudInputTarget) {
        synchronized(scenes) { scenes += scene }
    }

    internal fun unregister(scene: HudInputTarget) {
        synchronized(scenes) { scenes -= scene }
    }

    internal fun nextDrawSeq(): Long = ++drawSeq

    @JvmStatic
    val pointerAllowed: Boolean
        get() = HudPointer.inputAllowed()

    @JvmStatic
    val pointerX: Float
        get() = HudPointer.position().x * McWindow.guiWidth() / McWindow.windowWidth().coerceAtLeast(1)

    @JvmStatic
    val pointerY: Float
        get() = HudPointer.position().y * McWindow.guiHeight() / McWindow.windowHeight().coerceAtLeast(1)

    @JvmStatic
    fun consumeButton(button: Int, pressed: Boolean): Boolean =
        consumeLayerButton(button, pressed) || GameKeyRouter.consumeBelowButton(button, pressed)

    @JvmStatic
    fun releaseLayers(button: Int): Boolean {
        val pointerButton = pointerButtonOf(button) ?: return false
        val press = presses.remove(button) ?: return false
        for (scene in press.receivers) scene.release(pointerButton)
        return press.consumed
    }

    internal fun pointerButtonOf(button: Int): PointerButton? = when (button) {
        InputConstants.MOUSE_BUTTON_LEFT -> PointerButton.Primary
        InputConstants.MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
        InputConstants.MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
        McKeys.MOUSE_BACK -> PointerButton.Back
        McKeys.MOUSE_FORWARD -> PointerButton.Forward
        else -> null
    }

    private fun consumeLayerButton(button: Int, pressed: Boolean): Boolean {
        val pointerButton = pointerButtonOf(button) ?: return false
        if (!pressed) return releaseLayers(button)
        presses.remove(button)
        val position = HudPointer.position()
        val receivers = ArrayList<HudInputTarget>(2)
        var consumed = false
        for (layer in topmostLayers()) {
            if (!layer.takingInput) continue
            val scene = layer.inputTarget ?: continue
            if (scene in receivers || !scene.claims(layer, position)) continue
            receivers += scene
            if (scene.press(pointerButton)) {
                consumed = true
                break
            }
        }
        releaseFocusOutside(receivers)
        if (receivers.isNotEmpty()) presses[button] = Press(receivers, consumed)
        return consumed
    }

    private fun releaseFocusOutside(receivers: List<HudInputTarget>) {
        for (scene in liveScenes()) {
            if (scene.textFocused && scene !in receivers) scene.releaseFocus()
        }
    }

    @JvmStatic
    fun consumeScroll(x: Double, y: Double): Boolean {
        if (x == 0.0 && y == 0.0) return false
        val position = HudPointer.position()
        tried.clear()
        try {
            for (layer in topmostLayers()) {
                if (!layer.takingInput) continue
                val scene = layer.inputTarget ?: continue
                if (scene in tried || !scene.claims(layer, position)) continue
                tried += scene
                if (scene.scroll(x, y)) return true
            }
            return false
        } finally {
            tried.clear()
        }
    }

    @JvmStatic
    fun consumeKey(key: Int, state: KeyState): Boolean {
        if (state == KeyState.Released && key >= 0) {
            val owner = heldKeys.remove(key)
            if (owner != null) {
                if (key != InputConstants.KEY_ESCAPE) owner.sendKey(HudKeys.key(KeyEventType.KeyUp, key))
                return true
            }
        }
        val focused = if (state == KeyState.Released) null else focusedScene()
        if (focused != null) {
            typeInto(focused, key, state)
            if (key >= 0) heldKeys[key] = focused
            return true
        }
        if (key != InputConstants.KEY_ESCAPE) return false
        return when (state) {
            KeyState.Pressed -> {
                escapeConsumed = escapeClosesPopup()
                escapeConsumed
            }
            KeyState.Repeated -> escapeConsumed
            KeyState.Released -> escapeConsumed.also { escapeConsumed = false }
        }
    }

    internal fun releaseHeldKeys(scene: HudInputTarget) {
        if (heldKeys.isEmpty()) return
        val iterator = heldKeys.entries.iterator()
        while (iterator.hasNext()) {
            val (key, owner) = iterator.next()
            if (owner !== scene) continue
            iterator.remove()
            if (key != InputConstants.KEY_ESCAPE) scene.sendKey(HudKeys.key(KeyEventType.KeyUp, key))
        }
    }

    private fun typeInto(scene: HudInputTarget, key: Int, state: KeyState) {
        if (key == InputConstants.KEY_ESCAPE) {
            if (state == KeyState.Pressed) scene.releaseFocus()
            return
        }
        if (key >= 0) scene.sendKey(HudKeys.key(KeyEventType.KeyDown, key))
        //? if = 1.8.9 {
        /*val typed = Keyboard.getEventCharacter()
        if (typed != Keyboard.CHAR_NONE.toChar()) HudKeys.typed(typed.code)?.let(scene::sendKey)
        *///?}
    }

    @JvmStatic
    fun consumeChar(codePoint: Int): Boolean {
        val focused = focusedScene() ?: return false
        HudKeys.typed(codePoint)?.let(focused::sendKey)
        return true
    }

    private fun focusedScene(): HudInputTarget? {
        for (scene in liveScenes()) {
            if (scene.textFocused && scene.takingInput) return scene
        }
        return null
    }

    private fun escapeClosesPopup(): Boolean {
        if (runCatching { McScreens.current() }.getOrNull() is ComposeScreen) return false
        if (liveScenes().none { it.takingInput }) return false
        return BackStack.dispatch()
    }

    private fun liveScenes(): List<HudInputTarget> {
        sceneBuffer.clear()
        synchronized(scenes) { for (scene in scenes) sceneBuffer.add(scene) }
        return sceneBuffer
    }

    private fun topmostLayers(): List<HudLayer> {
        candidates.clear()
        for (scene in liveScenes()) scene.collectLayers(candidates)
        if (candidates.size > 1) candidates.sortWith(topmostFirst)
        return candidates
    }
}
