package org.polyfrost.compose.minecraft.screen

import java.awt.event.KeyEvent

internal object GlfwKeyTable {
    private const val F13 = 302
    private const val F24 = 313
    private const val KP_0 = 320
    private const val KP_9 = 329

    fun toAwt(key: Int): Int = when (key) {
        32 -> KeyEvent.VK_SPACE
        39 -> KeyEvent.VK_QUOTE
        44 -> KeyEvent.VK_COMMA
        45 -> KeyEvent.VK_MINUS
        46 -> KeyEvent.VK_PERIOD
        47 -> KeyEvent.VK_SLASH
        59 -> KeyEvent.VK_SEMICOLON
        61 -> KeyEvent.VK_EQUALS
        91 -> KeyEvent.VK_OPEN_BRACKET
        92 -> KeyEvent.VK_BACK_SLASH
        93 -> KeyEvent.VK_CLOSE_BRACKET
        96 -> KeyEvent.VK_BACK_QUOTE
        281 -> KeyEvent.VK_SCROLL_LOCK
        282 -> KeyEvent.VK_NUM_LOCK
        283 -> KeyEvent.VK_PRINTSCREEN
        284 -> KeyEvent.VK_PAUSE
        348 -> KeyEvent.VK_CONTEXT_MENU
        in F13..F24 -> KeyEvent.VK_F13 + (key - F13)
        in KP_0..KP_9 -> KeyEvent.VK_NUMPAD0 + (key - KP_0)
        330 -> KeyEvent.VK_DECIMAL
        331 -> KeyEvent.VK_DIVIDE
        332 -> KeyEvent.VK_MULTIPLY
        333 -> KeyEvent.VK_SUBTRACT
        334 -> KeyEvent.VK_ADD
        335 -> KeyEvent.VK_ENTER
        336 -> KeyEvent.VK_EQUALS
        else -> KeyEvent.VK_UNDEFINED
    }

    fun isNumpad(key: Int): Boolean = key == 282 || key in KP_0..336
}
