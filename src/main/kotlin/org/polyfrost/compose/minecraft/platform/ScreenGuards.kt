package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.client.gui.screens.Screen
import org.polyfrost.compose.host.HostCallbacks

object ScreenGuards {
    private class Guard(val owner: Any, val allow: (Screen?) -> Boolean)

    private val guards = CopyOnWriteArrayList<Guard>()
    private var evaluating = false

    @JvmStatic
    fun add(owner: Any, allow: (Screen?) -> Boolean): AutoCloseable {
        val guard = Guard(owner, allow)
        guards += guard
        return AutoCloseable { guards -= guard }
    }

    @JvmStatic
    fun allows(next: Screen?): Boolean {
        if (guards.isEmpty() || evaluating) return true
        evaluating = true
        try {
            val current = McScreens.current()
            for (guard in guards) {
                if (next != null && next === guard.owner) continue
                if (guard.owner is Screen && guard.owner !== current) {
                    guards -= guard
                    continue
                }
                if (!HostCallbacks.call(guard.allow, "McScreens.preventReplacement guard", true) { guard.allow(next) }) return false
            }
            return true
        } finally {
            evaluating = false
        }
    }
}
