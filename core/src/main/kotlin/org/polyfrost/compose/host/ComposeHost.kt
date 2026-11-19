package org.polyfrost.compose.host

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

public object ComposeHost {
    @Volatile
    @JvmStatic
    public var settings: HostSettings = HostSettings.Default

    @JvmStatic
    public val listeners: MutableList<HostListener> = CopyOnWriteArrayList()

    @JvmStatic
    public val screenInput: MutableList<ScreenInputInterceptor> = CopyOnWriteArrayList()

    @JvmStatic
    public val gameInput: MutableList<GameInputLayer> = CopyOnWriteArrayList()

    @JvmStatic
    public val overlays: MutableList<OverlayRenderer> = CopyOnWriteArrayList()

    @JvmStatic
    public fun addListener(listener: HostListener): AutoCloseable = register(listeners, listener)

    @JvmStatic
    public fun addScreenInput(interceptor: ScreenInputInterceptor): AutoCloseable = register(screenInput, interceptor)

    @JvmStatic
    public fun addGameInput(layer: GameInputLayer): AutoCloseable = register(gameInput, layer)

    @JvmStatic
    public fun addOverlay(overlay: OverlayRenderer): AutoCloseable = register(overlays, overlay)

    private fun <T> register(list: MutableList<T>, element: T): AutoCloseable {
        list += element
        val open = AtomicBoolean(true)
        return AutoCloseable { if (open.compareAndSet(true, false)) list.remove(element) }
    }
}
