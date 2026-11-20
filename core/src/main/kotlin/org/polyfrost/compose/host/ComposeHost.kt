package org.polyfrost.compose.host

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

public object ComposeHost {
    @JvmStatic
    public val gameInput: MutableList<GameInputLayer> = CopyOnWriteArrayList()

    @JvmStatic
    public val overlays: MutableList<OverlayRenderer> = CopyOnWriteArrayList()

    @JvmStatic
    public val fontListeners: MutableList<Runnable> = CopyOnWriteArrayList()

    @JvmStatic
    public val legacyInput: MutableList<LegacyInputObserver> = CopyOnWriteArrayList()

    private val framerateRequests: MutableList<IntArray> = CopyOnWriteArrayList()

    @JvmStatic
    public fun addGameInput(layer: GameInputLayer): AutoCloseable = register(gameInput, layer)

    @JvmStatic
    public fun addOverlay(overlay: OverlayRenderer): AutoCloseable = register(overlays, overlay)

    @JvmStatic
    public fun onFontsChanged(listener: Runnable): AutoCloseable = register(fontListeners, listener)

    @JvmStatic
    public fun addLegacyInput(observer: LegacyInputObserver): AutoCloseable = register(legacyInput, observer)

    @JvmStatic
    public fun requestFramerate(limit: Int): AutoCloseable = register(framerateRequests, intArrayOf(limit))

    @JvmStatic
    public fun requestedFramerate(): Int? = framerateRequests.maxOfOrNull { it[0] }

    private fun <T> register(list: MutableList<T>, element: T): AutoCloseable {
        list += element
        val open = AtomicBoolean(true)
        return AutoCloseable { if (open.compareAndSet(true, false)) list.remove(element) }
    }
}
