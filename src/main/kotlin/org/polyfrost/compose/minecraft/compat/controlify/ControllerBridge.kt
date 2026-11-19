package org.polyfrost.compose.minecraft.compat.controlify

import java.util.concurrent.CopyOnWriteArraySet
import java.util.function.Consumer
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.minecraft.screen.ComposeScreen

//? if > 1.8.9 {
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.minecraft.platform.LoadedMods
//?}

internal interface ScreenControllerSupport {
    fun collectSnapPoints(screen: ComposeScreen, consumer: Consumer<Any?>)
}

internal object ControllerBridge {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controller")

    val backend: ControllerBackend? by lazy { createBackend() }

    @Volatile
    var screens: ScreenControllerSupport? = null

    private val watched = CopyOnWriteArraySet<ControllerAction>()

    val tracksSemantics: Boolean
        get() = screens != null

    fun watch(action: ControllerAction) {
        watched += action
    }

    fun unwatch(action: ControllerAction) {
        watched -= action
    }

    fun tick() {
        if (watched.isEmpty()) return
        for (action in watched) action.tick()
    }

    fun collectSnapPoints(screen: ComposeScreen, consumer: Consumer<Any?>) {
        val support = screens ?: return
        HostCallbacks.call(support, "ScreenControllerSupport.collectSnapPoints", Unit) {
            support.collectSnapPoints(screen, consumer)
        }
    }

    @JvmStatic
    fun consumeBack(screen: Any?): Boolean = (screen as? ComposeScreen)?.controllerBack() == true

    private fun createBackend(): ControllerBackend? {
        //? if > 1.8.9 {
        if (!LoadedMods.isLoaded("controlify")) return null
        return try {
            ControlifyActions
        } catch (t: Throwable) {
            HostErrors.warn("Controller actions are unavailable", t, LOGGER)
            null
        }
        //?} else {
        /*LOGGER.debug("Controller actions are unavailable on this version")
        return null
        *///?}
    }
}
