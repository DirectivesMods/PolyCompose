@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import com.mojang.blaze3d.platform.InputConstants
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent as AwtKeyEvent
import org.polyfrost.compose.minecraft.platform.McKeys
import org.polyfrost.compose.minecraft.screen.MinecraftKeyboardAdapter
import org.polyfrost.compose.minecraft.screen.isKeyDown
//? if >= 26.3
import org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode

internal object HudKeys {
    private object Source : Component()

    fun key(type: KeyEventType, key: Int): KeyEvent {
        val awtCode = MinecraftKeyboardAdapter.toAwtKeyCode(shortcutKey(key))
        val location = MinecraftKeyboardAdapter.keyLocation(key)
        val down = type == KeyEventType.KeyDown
        return event(
            Key(awtCode, location),
            type,
            key,
            AwtKeyEvent(
                Source,
                if (down) AwtKeyEvent.KEY_PRESSED else AwtKeyEvent.KEY_RELEASED,
                System.currentTimeMillis(),
                awtModifiers(),
                awtCode,
                if (down) Char(0) else AwtKeyEvent.CHAR_UNDEFINED,
                location,
            ),
        )
    }

    private fun shortcutKey(key: Int): Int {
        //? if >= 26.3 {
        return SDL_GetKeyFromScancode(key, 0, true)
        //?} else
        //return key
    }

    fun typed(codePoint: Int): KeyEvent? {
        val char = if (Character.isBmpCodePoint(codePoint)) Char(codePoint) else Character.highSurrogate(codePoint)
        if (char == AwtKeyEvent.CHAR_UNDEFINED) return null
        return event(
            Key(AwtKeyEvent.VK_UNDEFINED),
            KeyEventType.KeyDown,
            codePoint,
            AwtKeyEvent(
                Source,
                AwtKeyEvent.KEY_TYPED,
                System.currentTimeMillis(),
                awtModifiers(),
                0,
                char,
                AwtKeyEvent.KEY_LOCATION_UNKNOWN,
            ),
        )
    }

    private fun event(key: Key, type: KeyEventType, codePoint: Int, native: AwtKeyEvent): KeyEvent = KeyEvent(
        key = key,
        type = type,
        codePoint = codePoint,
        isCtrlPressed = ctrl(),
        isShiftPressed = shift(),
        isAltPressed = alt(),
        isMetaPressed = meta(),
        nativeEvent = native,
    )

    private fun ctrl() = isKeyDown(InputConstants.KEY_LCONTROL) || isKeyDown(InputConstants.KEY_RCONTROL)

    private fun shift() = isKeyDown(InputConstants.KEY_LSHIFT) || isKeyDown(InputConstants.KEY_RSHIFT)

    private fun alt() = isKeyDown(InputConstants.KEY_LALT) || isKeyDown(InputConstants.KEY_RALT)

    private fun meta() = isKeyDown(McKeys.LEFT_SUPER) || isKeyDown(McKeys.RIGHT_SUPER)

    private fun awtModifiers(): Int {
        var m = 0
        if (ctrl()) m = m or InputEvent.CTRL_DOWN_MASK
        if (alt()) m = m or InputEvent.ALT_DOWN_MASK
        if (shift()) m = m or InputEvent.SHIFT_DOWN_MASK
        if (meta()) m = m or InputEvent.META_DOWN_MASK
        return m
    }
}
