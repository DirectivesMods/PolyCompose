package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import androidx.compose.ui.geometry.Rect
import com.mojang.blaze3d.platform.InputConstants
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.screenop.keyboard.InputTarget
import dev.isxander.controlify.screenop.keyboard.KeyboardLayouts
import dev.isxander.controlify.screenop.keyboard.KeyboardOverlayScreen
import kotlin.math.abs
import kotlin.math.roundToInt
import net.minecraft.client.gui.navigation.ScreenRectangle
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.minecraft.screen.ComposeScreen

internal object ComposeKeyboard {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controlify")

    private const val HEIGHT_RATIO = 0.38f
    private const val MIN_HEIGHT = 96
    private const val MAX_HEIGHT = 190
    private const val MAX_WIDTH = 560
    private const val SIDE_RATIO = 0.03f
    private const val MIN_SIDE = 4
    private const val MAX_SIDE = 16
    private const val BOTTOM = 4

    @Volatile
    private var broken = false

    fun tryOpen(screen: ComposeScreen, controller: ControllerEntity): Boolean {
        if (broken) return false
        return try {
            open(screen, controller)
        } catch (e: LinkageError) {
            broken = true
            HostErrors.warn("The on-screen keyboard is unavailable with this Controlify version", e, LOGGER)
            false
        } catch (e: RuntimeException) {
            HostErrors.warn("Could not open the on-screen keyboard", e, LOGGER)
            false
        }
    }

    private fun open(screen: ComposeScreen, controller: ControllerEntity): Boolean {
        screen.controllerTextField() ?: return false
        if (!controller.settings().generic.keyboard.showOnScreenKeyboard) return true
        val positioner = KeyboardOverlayScreen.KeyboardPositioner { width, height ->
            dock(width, height).also { screen.keyboardDocked(toScene(screen, it)) }
        }
        screen.showBehind(KeyboardOverlayScreen(screen, KeyboardLayouts.simple(), ComposeInputTarget(screen), positioner))
        return true
    }

    private fun dock(width: Int, height: Int): ScreenRectangle {
        val side = (width * SIDE_RATIO).roundToInt().coerceIn(MIN_SIDE, MAX_SIDE)
        val reserved = BOTTOM + ((KeyboardPreview.PANEL_HEIGHT + KeyboardPreview.GAP * 2f).roundToInt())
        val h = (height * HEIGHT_RATIO).roundToInt()
            .coerceIn(MIN_HEIGHT, MAX_HEIGHT)
            .coerceAtMost(height - reserved)
            .coerceAtLeast(1)
        val w = (width - side * 2).coerceAtMost(MAX_WIDTH).coerceAtLeast(1)
        return ScreenRectangle((width - w) / 2, height - h - BOTTOM, w, h)
    }

    private fun toScene(screen: ComposeScreen, rect: ScreenRectangle): Rect {
        val scale = screen.guiUnitsPerScenePixel
        return Rect(
            rect.left() / scale,
            rect.top() / scale,
            rect.right() / scale,
            rect.bottom() / scale,
        )
    }
}

private class ComposeInputTarget(private val screen: ComposeScreen) : InputTarget {
    override fun supportsCharInput(): Boolean = true

    override fun acceptChar(ch: Char, modifiers: Int): Boolean {
        screen.controllerChar(ch, modifiers)
        return true
    }

    override fun supportsKeyCodeInput(): Boolean = true

    override fun acceptKeyCode(keycode: Int, scancode: Int, modifiers: Int): Boolean {
        screen.controllerKey(keycode, true, modifiers)
        screen.controllerKey(keycode, false, modifiers)
        return true
    }

    override fun supportsCursorMovement(): Boolean = true

    override fun moveCursor(amount: Int): Boolean {
        val key = if (amount < 0) InputConstants.KEY_LEFT else InputConstants.KEY_RIGHT
        repeat(abs(amount)) {
            screen.controllerKey(key, true)
            screen.controllerKey(key, false)
        }
        return true
    }
}
//?}
