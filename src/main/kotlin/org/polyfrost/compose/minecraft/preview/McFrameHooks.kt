package org.polyfrost.compose.minecraft.preview

import java.util.concurrent.CopyOnWriteArrayList
import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.host.HostCallbacks

object McFrameHooks {
    private val beforeUi = CopyOnWriteArrayList<Runnable>()
    private var running = false

    @JvmStatic
    fun beforeUi(callback: Runnable): AutoCloseable {
        beforeUi += callback
        return AutoCloseable { beforeUi -= callback }
    }

    @JvmStatic
    val hasBeforeUiCallbacks: Boolean
        get() = beforeUi.isNotEmpty()

    @ApiStatus.Internal
    @JvmStatic
    fun runBeforeUi() {
        if (beforeUi.isEmpty() || running) return
        running = true
        try {
            HostCallbacks.each(beforeUi, "A pre-UI frame hook") { it.run() }
        } finally {
            running = false
        }
    }
}
