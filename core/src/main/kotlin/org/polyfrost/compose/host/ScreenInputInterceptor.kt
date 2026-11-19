package org.polyfrost.compose.host

public interface ScreenInputInterceptor {
    public val isCapturing: Boolean get() = false
    public fun consumeEscape(): Boolean = false
    public fun consumeMouse(button: Int, pressed: Boolean): Boolean = false
    public fun onDeferredMouse(): Boolean = false
    public fun onDeferredKeyboard() {}
}
