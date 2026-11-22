package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.ConcurrentLinkedQueue
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.state.TickStates

object HostEvents {
    private val frameStart = Listeners()
    private val frameEnd = Listeners()
    private val resourcesReloaded = Listeners()
    private val clientTick = Listeners()
    private val delayed = ConcurrentLinkedQueue<Delayed>()

    private class Delayed(var frames: Int, val action: Runnable)

    private class Listeners {
        @Volatile
        var snapshot: Array<Runnable> = emptyArray()
            private set

        fun add(listener: Runnable) = synchronized(this) {
            snapshot += listener
        }

        fun remove(listener: Runnable) = synchronized(this) {
            val current = snapshot
            val index = current.indexOf(listener)
            if (index >= 0) snapshot = current.filterIndexed { i, _ -> i != index }.toTypedArray()
        }
    }

    @JvmStatic
    var frame: Long = 0L
        private set

    @JvmStatic
    fun onFrameStart(listener: Runnable): AutoCloseable = register(frameStart, listener)

    @JvmStatic
    fun onFrameEnd(listener: Runnable): AutoCloseable = register(frameEnd, listener)

    @JvmStatic
    fun onResourcesReloaded(listener: Runnable): AutoCloseable = register(resourcesReloaded, listener)

    @JvmStatic
    fun onClientTick(listener: Runnable): AutoCloseable = register(clientTick, listener)

    @JvmStatic
    fun afterFrames(frames: Int, action: Runnable) {
        delayed += Delayed(frames, action)
    }

    @JvmStatic
    fun postFrameStart() {
        TickStates.onFrame()
        fire(frameStart, "A frame start listener")
    }

    @JvmStatic
    fun postFrameEnd() {
        frame++
        var queued = if (delayed.isEmpty()) 0 else delayed.size
        fire(frameEnd, "A frame end listener")
        if (queued == 0) return
        val iterator = delayed.iterator()
        while (queued-- > 0 && iterator.hasNext()) {
            val entry = iterator.next()
            if (--entry.frames > 0) continue
            iterator.remove()
            run(entry.action, "A delayed frame task")
        }
    }

    @JvmStatic
    fun postResourcesReloaded() {
        RenderThread.execute { fire(resourcesReloaded, "A resource reload listener") }
    }

    @JvmStatic
    fun postClientTick() {
        TickStates.onClientTick()
        fire(clientTick, "A client tick listener")
    }

    private fun register(listeners: Listeners, listener: Runnable): AutoCloseable {
        listeners.add(listener)
        return AutoCloseable { listeners.remove(listener) }
    }

    private fun fire(listeners: Listeners, what: String) {
        val snapshot = listeners.snapshot
        for (i in snapshot.indices) run(snapshot[i], what)
    }

    private fun run(action: Runnable, what: String) {
        HostCallbacks.call(action, what, Unit) { action.run() }
    }
}
