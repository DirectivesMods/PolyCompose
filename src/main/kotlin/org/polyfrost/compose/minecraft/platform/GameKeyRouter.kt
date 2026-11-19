package org.polyfrost.compose.minecraft.platform

import java.util.BitSet
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.GameInputLayer
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.KeyState
import org.polyfrost.compose.minecraft.hud.HudInput

internal object GameKeyRouter {
    private val passedKeys = BitSet()
    private val passedButtons = BitSet()
    private val legacyDown = BitSet()

    @JvmStatic
    var legacyKeyState: KeyState = KeyState.Released
        private set

    @JvmStatic
    val scrollAfterOthers: Boolean by lazy { LoadedMods.isLoaded("macos_input_fixes") }

    @JvmStatic
    fun legacyKey(key: Int, pressed: Boolean, repeat: Boolean): KeyState {
        val state = when {
            !pressed -> KeyState.Released.also { if (key >= 0) legacyDown.clear(key) }
            repeat || key >= 0 && legacyDown[key] -> KeyState.Repeated
            else -> KeyState.Pressed.also { if (key >= 0) legacyDown.set(key) }
        }
        legacyKeyState = state
        return state
    }

    @JvmStatic
    fun consume(key: Int, state: KeyState): Boolean =
        settle(passedKeys, key, state == KeyState.Pressed, state == KeyState.Released, consumeKey(key, state))

    @JvmStatic
    fun consumeChar(codePoint: Int): Boolean = HudInput.consumeChar(codePoint)

    @JvmStatic
    fun consumeButton(button: Int, pressed: Boolean): Boolean =
        settle(passedButtons, button, pressed, !pressed, consumeAnyButton(button, pressed))

    private fun settle(passed: BitSet, code: Int, pressed: Boolean, released: Boolean, consumed: Boolean): Boolean {
        if (code < 0) return consumed
        if (pressed) {
            if (consumed) passed.clear(code) else passed.set(code)
        } else if (released && passed[code]) {
            passed.clear(code)
            return false
        }
        return consumed
    }

    private fun consumeKey(key: Int, state: KeyState): Boolean {
        if (ComposeHost.gameInput.isEmpty()) return HudInput.consumeKey(key, state)
        if (route(above = true) { layer -> HostCallbacks.call(layer, "GameInputLayer.consumeKey", false) { layer.consumeKey(key, state) } }) return true
        if (HudInput.consumeKey(key, state)) return true
        return route(above = false) { layer -> HostCallbacks.call(layer, "GameInputLayer.consumeKey", false) { layer.consumeKey(key, state) } }
    }

    private fun consumeAnyButton(button: Int, pressed: Boolean): Boolean {
        if (!pressed) {
            val hudConsumed = HudInput.releaseLayers(button)
            if (route(above = true) { layer -> pressButton(layer, button, pressed) }) return true
            return hudConsumed || consumeBelowButton(button, pressed)
        }
        if (route(above = true) { layer -> pressButton(layer, button, pressed) }) return true
        return HudInput.consumeButton(button, pressed)
    }

    @JvmStatic
    fun consumeBelowButton(button: Int, pressed: Boolean): Boolean =
        route(above = false) { layer -> pressButton(layer, button, pressed) }

    @JvmStatic
    fun consumeScroll(x: Double, y: Double): Boolean {
        if (route(above = true) { layer -> scroll(layer, x, y) }) return true
        if (HudInput.consumeScroll(x, y)) return true
        return route(above = false) { layer -> scroll(layer, x, y) }
    }

    private fun pressButton(layer: GameInputLayer, button: Int, pressed: Boolean): Boolean =
        HostCallbacks.call(layer, "GameInputLayer.consumeButton", false) { layer.consumeButton(button, pressed) }

    private fun scroll(layer: GameInputLayer, x: Double, y: Double): Boolean =
        HostCallbacks.call(layer, "GameInputLayer.consumeScroll", false) { layer.consumeScroll(x, y) }

    private inline fun route(above: Boolean, consume: (GameInputLayer) -> Boolean): Boolean {
        if (ComposeHost.gameInput.isEmpty()) return false
        val snapshot = GameInputOrder.current()
        for (i in 0 until snapshot.size) {
            if (snapshot.aboveHud(i) == above && consume(snapshot.layers[i])) return true
        }
        return false
    }
}
