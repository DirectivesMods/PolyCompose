package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.state.TickStates

object HostEvents {
    private val frameStart = CopyOnWriteArrayList<Runnable>()
    private val frameEnd = CopyOnWriteArrayList<Runnable>()
    private val resourcesReloaded = CopyOnWriteArrayList<Runnable>()
    private val clientTick = CopyOnWriteArrayList<Runnable>()
    private val delayed = ConcurrentLinkedQueue<Delayed>()

    private class Delayed(var frames: Int, val action: Runnable)

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
        var queued = delayed.size
        fire(frameEnd, "A frame end listener")
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

    private fun register(listeners: MutableList<Runnable>, listener: Runnable): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun fire(listeners: List<Runnable>, what: String) {
        for (listener in listeners) run(listener, what)
    }

    private fun run(action: Runnable, what: String) {
        HostCallbacks.call(action, what, Unit) { action.run() }
    }
}
