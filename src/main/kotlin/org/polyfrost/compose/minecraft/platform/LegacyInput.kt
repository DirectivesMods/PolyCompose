package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.CopyOnWriteArrayList
import org.polyfrost.compose.host.HostCallbacks

fun interface LegacyInputListener {
    fun onEvent()
}

object LegacyInput {
    @JvmStatic
    val available: Boolean
        //? if = 1.8.9 {
        /*get() = true
        *///?} else
        get() = false

    @JvmStatic
    fun onKeyboard(listener: LegacyInputListener): AutoCloseable = LegacyInputDispatch.keyboard.add(listener)

    @JvmStatic
    fun onMouse(listener: LegacyInputListener): AutoCloseable = LegacyInputDispatch.mouse.add(listener)
}

internal object LegacyInputDispatch {
    class Listeners(private val what: String) {
        private val listeners = CopyOnWriteArrayList<LegacyInputListener>()

        fun add(listener: LegacyInputListener): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners.remove(listener) }
        }

        fun fire() {
            if (listeners.isEmpty()) return
            HostCallbacks.each(listeners, what) { it.onEvent() }
        }
    }

    val keyboard = Listeners("LegacyInputListener.onKeyboard")
    val mouse = Listeners("LegacyInputListener.onMouse")

    @JvmStatic
    fun keyboardPassed() = keyboard.fire()

    @JvmStatic
    fun mousePassed() = mouse.fire()
}
