package org.polyfrost.compose.host

public interface LegacyInputObserver {
    public fun onDrainedMouse(): Boolean = false

    public fun onDrainedKeyboard() {}
}
