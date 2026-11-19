package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import dev.isxander.controlify.api.ControlifyApi
import dev.isxander.controlify.api.bind.ControlifyBindApi
import dev.isxander.controlify.api.bind.InputBinding
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.bindings.input.AxisInput
import dev.isxander.controlify.bindings.input.ButtonInput
import dev.isxander.controlify.bindings.input.EmptyInput
import dev.isxander.controlify.bindings.input.Input
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.GamepadInputs
import dev.isxander.controlify.controller.input.InputComponent
import net.minecraft.network.chat.Component
import org.apache.logging.log4j.LogManager

internal object ControlifyActions : ControllerBackend {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controller")

    private const val DIGITAL_THRESHOLD = 0.5f

    private val bindings = HashMap<String, InputBindingSupplier>()

    @Volatile
    private var locked = false

    override fun register(id: String, name: String, category: String, button: ControllerButton): ActionState {
        val supplier = synchronized(bindings) { bindings[id] ?: tryRegister(id, name, category, button)?.also { bindings[id] = it } }
        return if (supplier != null) BindingState(supplier) else RawState(inputFor(button))
    }

    @Suppress("DEPRECATION")
    private fun tryRegister(id: String, name: String, category: String, button: ControllerButton): InputBindingSupplier? {
        if (locked) return null
        val namespace = id.substringBefore(':')
        val path = id.substringAfter(':')
        return try {
            ControlifyBindApi.get().registerBinding { builder ->
                builder.id(namespace, path)
                    .name(Component.translatable(name))
                    .category(Component.translatable(category))
                    .defaultInput(inputFor(button))
            }
        } catch (e: IllegalStateException) {
            LOGGER.debug("Controlify bindings are locked, {} reads its default button directly", id, e)
            locked = true
            null
        }
    }

    private fun currentController(): ControllerEntity? = ControlifyApi.get().currentController.orElse(null)

    private fun inputFor(button: ControllerButton): Input = when (button) {
        ControllerButton.NONE -> EmptyInput()
        ControllerButton.A -> ButtonInput(GamepadInputs.SOUTH_BUTTON)
        ControllerButton.B -> ButtonInput(GamepadInputs.EAST_BUTTON)
        ControllerButton.X -> ButtonInput(GamepadInputs.WEST_BUTTON)
        ControllerButton.Y -> ButtonInput(GamepadInputs.NORTH_BUTTON)
        ControllerButton.LEFT_BUMPER -> ButtonInput(GamepadInputs.LEFT_SHOULDER_BUTTON)
        ControllerButton.RIGHT_BUMPER -> ButtonInput(GamepadInputs.RIGHT_SHOULDER_BUTTON)
        ControllerButton.LEFT_TRIGGER -> AxisInput(GamepadInputs.LEFT_TRIGGER_AXIS)
        ControllerButton.RIGHT_TRIGGER -> AxisInput(GamepadInputs.RIGHT_TRIGGER_AXIS)
        ControllerButton.LEFT_STICK -> ButtonInput(GamepadInputs.LEFT_STICK_BUTTON)
        ControllerButton.RIGHT_STICK -> ButtonInput(GamepadInputs.RIGHT_STICK_BUTTON)
        ControllerButton.BACK -> ButtonInput(GamepadInputs.BACK_BUTTON)
        ControllerButton.START -> ButtonInput(GamepadInputs.START_BUTTON)
        ControllerButton.DPAD_UP -> ButtonInput(GamepadInputs.DPAD_UP_BUTTON)
        ControllerButton.DPAD_DOWN -> ButtonInput(GamepadInputs.DPAD_DOWN_BUTTON)
        ControllerButton.DPAD_LEFT -> ButtonInput(GamepadInputs.DPAD_LEFT_BUTTON)
        ControllerButton.DPAD_RIGHT -> ButtonInput(GamepadInputs.DPAD_RIGHT_BUTTON)
    }

    private class BindingState(private val supplier: InputBindingSupplier) : ActionState {
        override val isRebindable: Boolean
            get() = true

        private fun binding(): InputBinding? = currentController()?.let { supplier.onOrNull(it) }

        override fun isDown(): Boolean = binding()?.digitalNow() == true

        override fun justPressed(): Boolean = binding()?.justPressed() == true
    }

    private class RawState(private val input: Input) : ActionState {
        override val isRebindable: Boolean
            get() = false

        private fun inputs(): InputComponent? = currentController()?.input()?.orElse(null)

        override fun isDown(): Boolean {
            if (input is EmptyInput) return false
            val component = inputs() ?: return false
            return input.state(component.stateNow()) > DIGITAL_THRESHOLD
        }

        override fun justPressed(): Boolean {
            if (input is EmptyInput) return false
            val component = inputs() ?: return false
            return input.state(component.stateNow()) > DIGITAL_THRESHOLD &&
                input.state(component.stateThen()) <= DIGITAL_THRESHOLD
        }
    }
}
//?}
