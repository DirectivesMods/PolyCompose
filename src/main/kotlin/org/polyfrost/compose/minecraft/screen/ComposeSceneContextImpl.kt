package org.polyfrost.compose.minecraft.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformScreenReader
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.scene.ComposeSceneContext
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mojang.blaze3d.platform.InputConstants
import java.awt.Cursor
import net.minecraft.client.Minecraft
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.McKeys
import org.polyfrost.compose.minecraft.platform.McWindow

//? if >= 26.1 || = 1.8.9 {
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import kotlinx.coroutines.awaitCancellation
import org.polyfrost.compose.minecraft.platform.TextInput
//?}

//? if = 1.8.9 {
/*import org.lwjgl.input.Keyboard
import org.polyfrost.compose.minecraft.legacy.KeyCodes
*///?}

//? if sdl {
import org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardFocus
import org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyboardState
import org.lwjgl.sdl.SDLMouse.*
import org.lwjgl.sdl.SDLVideo.SDL_RaiseWindow
//?} else {
/*import org.lwjgl.glfw.GLFW.*
*///?}

private class InputModeManagerImpl : InputModeManager {
    override val inputMode: InputMode = InputMode.Keyboard

    @ExperimentalComposeUiApi
    override fun requestInputMode(inputMode: InputMode) = inputMode == InputMode.Keyboard
}

internal fun isKeyDown(key: Int): Boolean {
    //? if sdl {
    return InputConstants.isKeyDown(key)
    //?} else {
    /*//~ if < 1.21.10 'Minecraft.getInstance().window' -> 'McWindow.handle()'
    return InputConstants.isKeyDown(Minecraft.getInstance().window, key)
    *///?}
}

internal fun heldKeysNow(): List<Int> {
    //? if = 1.8.9 {
    /*return (1 until Keyboard.KEYBOARD_SIZE).filter(Keyboard::isKeyDown).map { KeyCodes.fromLegacy(it).value }.filter { it > 0 }
    *///?} elif sdl {
    val state = SDL_GetKeyboardState() ?: return emptyList()
    return (0 until state.limit()).filter { state.get(it) != 0.toByte() }
    //?} else
    //return (GLFW_KEY_SPACE..GLFW_KEY_LAST).filter(::isKeyDown)
}

internal fun mouseButtonsDownNow(): Int {
    //? if sdl {
    return Integer.bitCount(SDL_GetMouseState(null, null))
    //?} else {
    /*val window = McWindow.handle()
    return (GLFW_MOUSE_BUTTON_1..GLFW_MOUSE_BUTTON_LAST).count { glfwGetMouseButton(window, it) == GLFW_PRESS }
    *///?}
}

internal fun isWindowFocused(): Boolean {
    //? if sdl {
    return SDL_GetKeyboardFocus() != 0L
    //?} else
    //return McWindow.isActive()
}

private fun readKeyboardModifiers() = PointerKeyboardModifiers(
    isCtrlPressed = isKeyDown(InputConstants.KEY_LCONTROL) || isKeyDown(InputConstants.KEY_RCONTROL),
    isShiftPressed = isKeyDown(InputConstants.KEY_LSHIFT) || isKeyDown(InputConstants.KEY_RSHIFT),
    isAltPressed = isKeyDown(InputConstants.KEY_LALT) || isKeyDown(InputConstants.KEY_RALT),
    isCapsLockOn = isKeyDown(InputConstants.KEY_CAPSLOCK),
    isScrollLockOn = isKeyDown(InputConstants.KEY_SCROLLLOCK),
    isNumLockOn = isKeyDown(InputConstants.KEY_NUMLOCK),
    isMetaPressed = isKeyDown(McKeys.LEFT_SUPER) || isKeyDown(McKeys.RIGHT_SUPER),
)

@ExperimentalComposeUiApi
internal class WindowInfoImpl : WindowInfo {
    private var refreshedFrame = -1L

    override var containerSize: IntSize by mutableStateOf(IntSize(McWindow.windowWidth(), McWindow.windowHeight()))

    override var containerDpSize: DpSize by mutableStateOf(DpSize.Unspecified)

    override var keyboardModifiers: PointerKeyboardModifiers by mutableStateOf(readKeyboardModifiers())
        private set

    override var isWindowFocused: Boolean by mutableStateOf(McWindow.isActive())
        private set

    fun update(width: Int, height: Int, density: Float) {
        if (containerSize.width != width || containerSize.height != height) {
            containerSize = IntSize(width, height)
        }
        val dpSize = DpSize((width / density).dp, (height / density).dp)
        if (containerDpSize != dpSize) containerDpSize = dpSize
        refresh()
    }

    private fun refresh() {
        val frame = HostEvents.frame
        if (frame == refreshedFrame) return
        refreshedFrame = frame
        val focused = McWindow.isActive()
        if (focused != isWindowFocused) isWindowFocused = focused
        val modifiers = readKeyboardModifiers()
        if (modifiers != keyboardModifiers) keyboardModifiers = modifiers
    }
}

@OptIn(InternalComposeUiApi::class)
internal class ScaledViewConfiguration(
    private val density: () -> Float,
) : ViewConfiguration by PlatformContext.DefaultViewConfiguration {
    override val touchSlop: Float get() = TOUCH_SLOP_DP * density()

    private companion object {
        const val TOUCH_SLOP_DP = 18f
    }
}

private enum class CursorShape {
    Arrow, Hand, Text, Crosshair, Wait, Move, ResizeHorizontal, ResizeVertical, ResizeNwse, ResizeNesw
}

private fun cursorShape(icon: PointerIcon): CursorShape = when (icon) {
    PointerIcon.Default -> CursorShape.Arrow
    PointerIcon.Hand -> CursorShape.Hand
    PointerIcon.Text -> CursorShape.Text
    PointerIcon.Crosshair -> CursorShape.Crosshair
    else -> awtCursorShape(icon)
}

private fun awtCursorShape(icon: PointerIcon): CursorShape {
    val type = try {
        (icon.javaClass.getMethod("getCursor").invoke(icon) as Cursor).type
    } catch (_: ReflectiveOperationException) {
        return CursorShape.Arrow
    } catch (_: ClassCastException) {
        return CursorShape.Arrow
    }
    return when (type) {
        Cursor.HAND_CURSOR -> CursorShape.Hand
        Cursor.TEXT_CURSOR -> CursorShape.Text
        Cursor.CROSSHAIR_CURSOR -> CursorShape.Crosshair
        Cursor.WAIT_CURSOR -> CursorShape.Wait
        Cursor.MOVE_CURSOR -> CursorShape.Move
        Cursor.E_RESIZE_CURSOR, Cursor.W_RESIZE_CURSOR -> CursorShape.ResizeHorizontal
        Cursor.N_RESIZE_CURSOR, Cursor.S_RESIZE_CURSOR -> CursorShape.ResizeVertical
        Cursor.NW_RESIZE_CURSOR, Cursor.SE_RESIZE_CURSOR -> CursorShape.ResizeNwse
        Cursor.NE_RESIZE_CURSOR, Cursor.SW_RESIZE_CURSOR -> CursorShape.ResizeNesw
        else -> CursorShape.Arrow
    }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
private class PlatformScreenReaderImpl : PlatformScreenReader {
    override val isActive: Boolean
        get() = false
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
private class PlatformImpl : PlatformContext by PlatformContext.Empty() {
    override val windowInfo: WindowInfoImpl = WindowInfoImpl()
    override val screenReader: PlatformScreenReader = PlatformScreenReaderImpl()
    override val inputModeManager: InputModeManager = InputModeManagerImpl()
    override val viewConfiguration: ViewConfiguration = ScaledViewConfiguration { 1f }

    private val handle = McWindow.handle()

    private val cursors = HashMap<CursorShape, Long>()

    override fun setPointerIcon(pointerIcon: PointerIcon) {
        if (pointerIcon != PointerIcon.Default && !allowCursorChanges()) {
            return
        }
        applyPointerIcon(cursorShape(pointerIcon))
    }

    fun resetPointerIcon() {
        //? if sdl {
        SDL_SetCursor(SDL_GetDefaultCursor())
        //?} else {
        /*glfwSetCursor(handle, 0L)
        *///?}
    }

    private fun cursorFor(shape: CursorShape): Long = cursors.getOrPut(shape) {
        //? if sdl {
        SDL_CreateSystemCursor(
            when (shape) {
                CursorShape.Arrow -> SDL_SYSTEM_CURSOR_DEFAULT
                CursorShape.Hand -> SDL_SYSTEM_CURSOR_POINTER
                CursorShape.Text -> SDL_SYSTEM_CURSOR_TEXT
                CursorShape.Crosshair -> SDL_SYSTEM_CURSOR_CROSSHAIR
                CursorShape.Wait -> SDL_SYSTEM_CURSOR_WAIT
                CursorShape.Move -> SDL_SYSTEM_CURSOR_MOVE
                CursorShape.ResizeHorizontal -> SDL_SYSTEM_CURSOR_EW_RESIZE
                CursorShape.ResizeVertical -> SDL_SYSTEM_CURSOR_NS_RESIZE
                CursorShape.ResizeNwse -> SDL_SYSTEM_CURSOR_NWSE_RESIZE
                CursorShape.ResizeNesw -> SDL_SYSTEM_CURSOR_NESW_RESIZE
            },
        )
        //?} else {
        /*glfwCreateStandardCursor(
            when (shape) {
                CursorShape.Hand -> GLFW_HAND_CURSOR
                CursorShape.Text -> GLFW_IBEAM_CURSOR
                CursorShape.Crosshair -> GLFW_CROSSHAIR_CURSOR
                CursorShape.Move -> GLFW_RESIZE_ALL_CURSOR
                CursorShape.ResizeHorizontal -> GLFW_RESIZE_EW_CURSOR
                CursorShape.ResizeVertical -> GLFW_RESIZE_NS_CURSOR
                CursorShape.ResizeNwse -> GLFW_RESIZE_NWSE_CURSOR
                CursorShape.ResizeNesw -> GLFW_RESIZE_NESW_CURSOR
                CursorShape.Arrow, CursorShape.Wait -> GLFW_ARROW_CURSOR
            },
        )
        *///?}
    }

    private fun applyPointerIcon(shape: CursorShape) {
        //? if sdl {
        SDL_SetCursor(if (shape == CursorShape.Arrow) SDL_GetDefaultCursor() else cursorFor(shape))
        //?} else {
        /*glfwSetCursor(handle, if (shape == CursorShape.Arrow) 0L else cursorFor(shape))
        *///?}
    }

    override fun requestFocus(): Boolean {
        //~ if !sdl 'SDL_RaiseWindow' -> 'glfwFocusWindow'
        SDL_RaiseWindow(handle)
        return McWindow.isActive()
    }

    private fun allowCursorChanges(): Boolean {
        //? if >= 1.21.10 {
        return Minecraft.getInstance().options.allowCursorChanges().get()
        //?} else
        //return true
    }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal open class ScenePlatformContext : PlatformContext by ComposeSceneContextImpl.platformContext {
    final override val windowInfo: WindowInfoImpl = WindowInfoImpl()

    fun updateContainerSize(width: Int, height: Int, density: Float) {
        windowInfo.update(width, height, density)
    }
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
internal class SceneContext(
    private val isShowing: () -> Boolean,
    density: () -> Float,
) : ScenePlatformContext() {
    override val viewConfiguration: ViewConfiguration = ScaledViewConfiguration(density)

    //? if >= 26.1 || = 1.8.9 {
    private var sessions = 0
    private var inputActive = false
    private var closed = false

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        sessions++
        sync()
        try {
            awaitCancellation()
        } finally {
            sessions--
            sync()
        }
    }

    fun sync() {
        val wanted = !closed && sessions > 0 && isShowing()
        if (wanted == inputActive) return
        inputActive = wanted
        if (wanted) TextInput.start(this)
        else TextInput.stop(this)
    }

    fun close() {
        closed = true
        sync()
    }
    //?} else {
    /*fun sync() {}
    fun close() {}
    *///?}
}

@OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
object ComposeSceneContextImpl : ComposeSceneContext {
    private val platform = PlatformImpl()

    override val platformContext: PlatformContext = platform

    fun resetPointerIcon() {
        platform.resetPointerIcon()
    }
}
