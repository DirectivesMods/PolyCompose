package org.polyfrost.compose.minecraft.preview

import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.host.HostCallbacks

object McFrameHooks {
    private val lock = Any()
    @Volatile
    private var beforeUi: Array<Runnable> = emptyArray()
    private var running = false

    @JvmStatic
    fun beforeUi(callback: Runnable): AutoCloseable {
        synchronized(lock) { beforeUi += callback }
        return AutoCloseable { remove(callback) }
    }

    private fun remove(callback: Runnable) {
        synchronized(lock) {
            val current = beforeUi
            val index = current.indexOf(callback)
            if (index >= 0) beforeUi = current.filterIndexed { i, _ -> i != index }.toTypedArray()
        }
    }

    @JvmStatic
    val hasBeforeUiCallbacks: Boolean
        get() = beforeUi.isNotEmpty()

    @ApiStatus.Internal
    @JvmStatic
    fun runBeforeUi() {
        val hooks = beforeUi
        if (hooks.isEmpty() || running) return
        running = true
        try {
            for (i in hooks.indices) {
                val hook = hooks[i]
                HostCallbacks.call(hook, "A pre-UI frame hook", Unit) { hook.run() }
            }
        } finally {
            running = false
        }
    }
}
