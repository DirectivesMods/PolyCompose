package org.polyfrost.compose.host

public interface GameInputLayer {
    public val order: Int
    public fun consumeButton(button: Int, pressed: Boolean): Boolean = false
    public fun consumeScroll(x: Double, y: Double): Boolean = false
    public fun consumeKey(key: Int, state: KeyState): Boolean = false

    public companion object {
        public const val ABOVE_HUD_LAYERS: Int = 100
        public const val HUD_LAYERS: Int = 0
        public const val BELOW_HUD_LAYERS: Int = -100
    }
}

public enum class KeyState { Pressed, Repeated, Released }
