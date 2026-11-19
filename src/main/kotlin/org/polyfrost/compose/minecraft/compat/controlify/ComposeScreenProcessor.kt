package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import com.mojang.blaze3d.platform.InputConstants
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.ControllerStateView
import dev.isxander.controlify.controller.input.GamepadInputs
import dev.isxander.controlify.screenop.ScreenProcessor
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler
import java.util.Optional
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.polyfrost.compose.minecraft.screen.ComposeScreen

internal class ComposeScreenProcessor(screen: ComposeScreen) : ScreenProcessor<ComposeScreen>(screen) {
    private class Direction(val binding: InputBindingSupplier, val dpad: Identifier, val focus: FocusDirection)

    private val directions = listOf(
        Direction(ControlifyBindings.GUI_NAVI_UP, GamepadInputs.DPAD_UP_BUTTON, FocusDirection.Up),
        Direction(ControlifyBindings.GUI_NAVI_DOWN, GamepadInputs.DPAD_DOWN_BUTTON, FocusDirection.Down),
        Direction(ControlifyBindings.GUI_NAVI_LEFT, GamepadInputs.DPAD_LEFT_BUTTON, FocusDirection.Left),
        Direction(ControlifyBindings.GUI_NAVI_RIGHT, GamepadInputs.DPAD_RIGHT_BUTTON, FocusDirection.Right),
    )

    override fun handleComponentNavigation(controller: ControllerEntity) {
        val input = controller.input().orElse(null) ?: return
        val now = input.stateNow()
        val then = input.stateThen()
        val canRepeat = holdRepeatHelper.canNavigate()
        for (direction in directions) {
            val binding = direction.binding.onOrNull(controller)
            if (binding?.digitalNow() != true && !now.isButtonDown(direction.dpad)) continue
            val wasDown = binding?.digitalPrev() == true || then.isButtonDown(direction.dpad)
            if (wasDown && !canRepeat) return
            if (!wasDown) holdRepeatHelper.reset()
            if (screen.controllerMoveFocus(direction.focus)) {
                holdRepeatHelper.onNavigate()
                input.notifyGuiPressOutputsOfNavigate()
            }
            return
        }
    }

    override fun handleButtons(controller: ControllerEntity) {
        val press = ControlifyBindings.GUI_PRESS.on(controller)
        val back = ControlifyBindings.GUI_BACK.on(controller)
        val pressed = press.guiPressed().get()
        val backPressed = back.guiPressed().get()
        val guarded = screen.swallowControllerPress(pressed || backPressed || press.digitalNow() || back.digitalNow())
        if (!guarded && pressed && !ComposeKeyboard.tryOpen(screen, controller)) {
            screen.controllerKey(InputConstants.KEY_RETURN, true)
            screen.controllerKey(InputConstants.KEY_RETURN, false)
        }
        if (!guarded && backPressed) {
            if (!screen.controllerBack() && screen.shouldCloseOnEsc()) {
                playClackSound()
                screen.onClose()
            }
        }
        val scroll = stickScroll(controller)
        if (scroll != Offset.Zero) screen.controllerScroll(scroll * SCROLL_STEP)
    }

    override fun handleTabNavigation(controller: ControllerEntity) {
        val state = controller.input().orElse(null)
        val next = ControlifyBindings.GUI_NEXT_TAB.on(controller).justPressed() ||
            state != null && triggerPressed(state.stateNow(), state.stateThen(), GamepadInputs.RIGHT_TRIGGER_AXIS)
        val previous = ControlifyBindings.GUI_PREV_TAB.on(controller).justPressed() ||
            state != null && triggerPressed(state.stateNow(), state.stateThen(), GamepadInputs.LEFT_TRIGGER_AXIS)
        if (next) {
            screen.controllerMoveFocus(FocusDirection.Next)
        } else if (previous) {
            screen.controllerMoveFocus(FocusDirection.Previous)
        }
    }

    override fun render(
        controller: ControllerEntity,
        graphics: GuiGraphicsExtractor,
        tickDelta: Float,
        vmouse: Optional<VirtualMouseHandler>,
    ) {
        if (vmouse.isPresent || !controller.settings().generic.guide.showScreenGuides) return
        val font = Minecraft.getInstance().font
        val line = Component.empty()
            .append(ControlifyBindings.GUI_PRESS.inputGlyph()).append(" ").append(SELECT).append("   ")
            .append(ControlifyBindings.GUI_BACK.inputGlyph()).append(" ").append(BACK).append("   ")
            .append(ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.inputGlyph()).append(" ").append(SCROLL)
        val y = screen.height - font.lineHeight - GUIDE_MARGIN
        //? if >= 26.1 {
        graphics.text(font, line, GUIDE_MARGIN, y, -1, true)
        //?} else
        //graphics.drawString(font, line, GUIDE_MARGIN, y, -1, true)
    }

    private fun triggerPressed(now: ControllerStateView, then: ControllerStateView, axis: Identifier): Boolean =
        now.getAxisState(axis) > TRIGGER_THRESHOLD && then.getAxisState(axis) <= TRIGGER_THRESHOLD

    private fun stickScroll(controller: ControllerEntity): Offset {
        val x = ControlifyBindings.GUI_SECONDARY_NAVI_RIGHT.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_LEFT.on(controller).analogueNow()
        val y = ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_UP.on(controller).analogueNow()
        return Offset(x * abs(x), y * abs(y))
    }

    private companion object {
        const val SCROLL_STEP = 60f
        const val TRIGGER_THRESHOLD = 0.5f
        const val GUIDE_MARGIN = 4

        val SELECT: Component = Component.translatableWithFallback("polycompose.controller.select", "Select")
        val BACK: Component = Component.translatable("gui.back")
        val SCROLL: Component = Component.translatableWithFallback("polycompose.controller.scroll", "Scroll")
    }
}
//?}
