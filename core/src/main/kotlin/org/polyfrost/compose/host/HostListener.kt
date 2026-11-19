package org.polyfrost.compose.host

import androidx.compose.ui.geometry.Offset

public interface HostListener {
    public fun onFrame(screen: Any): Boolean = false
    public fun onPointer(screen: Any, position: Offset?) {}
    public fun onInputKind(keyboard: Boolean) {}
    public fun onScreenRemoved(screen: Any) {}
    public fun onSceneCreated(kind: SceneKind) {}
    public fun onFontsChanged() {}
}

public enum class SceneKind { Screen, HudLayer }
