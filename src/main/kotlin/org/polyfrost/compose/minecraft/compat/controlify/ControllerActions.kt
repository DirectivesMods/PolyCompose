package org.polyfrost.compose.minecraft.compat.controlify

import java.util.concurrent.CopyOnWriteArrayList
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.HostErrors

object ControllerActions {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controller")

    private val ID_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    @JvmStatic
    val isAvailable: Boolean
        get() = ControllerBridge.backend != null

    @JvmStatic
    @JvmOverloads
    fun register(
        id: String,
        name: String,
        defaultButton: ControllerButton = ControllerButton.NONE,
        category: String = id.substringBefore(':'),
    ): ControllerAction {
        require(ID_PATTERN.matches(id)) { "Controller action id must look like namespace:path, got '$id'" }
        val state = ControllerBridge.backend?.let { backend ->
            try {
                backend.register(id, name, category, defaultButton)
            } catch (t: Throwable) {
                HostErrors.warn("Could not register controller action $id", t, LOGGER)
                null
            }
        }
        return ControllerAction(id, name, defaultButton, state)
    }
}

class ControllerAction internal constructor(
    val id: String,
    val name: String,
    val defaultButton: ControllerButton,
    private val state: ActionState?,
) : AutoCloseable {
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    private var closed = false

    val isActive: Boolean
        get() = state != null && !closed

    val isRebindable: Boolean
        get() = isActive && state?.isRebindable == true

    val isPressed: Boolean
        get() = isActive && state?.isDown() == true

    val justPressed: Boolean
        get() = isActive && state?.justPressed() == true

    fun onPress(listener: Runnable): AutoCloseable {
        if (!isActive) return AutoCloseable {}
        listeners += listener
        ControllerBridge.watch(this)
        return AutoCloseable {
            listeners -= listener
            if (listeners.isEmpty()) ControllerBridge.unwatch(this)
        }
    }

    internal fun tick() {
        if (listeners.isEmpty() || !justPressed) return
        HostCallbacks.each(listeners, "ControllerAction.onPress") { it.run() }
    }

    override fun close() {
        closed = true
        listeners.clear()
        ControllerBridge.unwatch(this)
    }
}

internal interface ActionState {
    val isRebindable: Boolean

    fun isDown(): Boolean

    fun justPressed(): Boolean
}

internal interface ControllerBackend {
    fun register(id: String, name: String, category: String, button: ControllerButton): ActionState
}
