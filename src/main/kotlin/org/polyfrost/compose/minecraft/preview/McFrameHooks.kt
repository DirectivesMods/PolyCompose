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
        synchronized(lock) { beforeUi = without(beforeUi, callback) }
    }

    @Volatile
    private var beforeExtract: Array<Runnable> = emptyArray()
    private var extracting = false

    /** For 26.1+: like [beforeUi], but runs before GUI extraction, which happens before rendering */
    @JvmStatic
    fun beforeExtract(callback: Runnable): AutoCloseable {
        synchronized(lock) { beforeExtract += callback }
        return AutoCloseable { synchronized(lock) { beforeExtract = without(beforeExtract, callback) } }
    }

    private fun without(current: Array<Runnable>, callback: Runnable): Array<Runnable> {
        val index = current.indexOf(callback)
        return if (index < 0) current else current.filterIndexed { i, _ -> i != index }.toTypedArray()
    }

    @ApiStatus.Internal
    @JvmStatic
    fun runBeforeExtract() {
        val hooks = beforeExtract
        if (hooks.isEmpty() || extracting) return
        extracting = true
        try {
            for (i in hooks.indices) {
                val hook = hooks[i]
                HostCallbacks.call(hook, "A pre-extract frame hook", Unit) { hook.run() }
            }
        } finally {
            extracting = false
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
