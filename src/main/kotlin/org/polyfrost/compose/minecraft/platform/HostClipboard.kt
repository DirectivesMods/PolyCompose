package org.polyfrost.compose.minecraft.platform

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import org.apache.logging.log4j.LogManager

//? if sdl {
import org.lwjgl.sdl.SDLClipboard.SDL_GetClipboardText
import org.lwjgl.sdl.SDLClipboard.SDL_SetClipboardText
//?} else {
/*import org.lwjgl.glfw.GLFW.glfwGetClipboardString
import org.lwjgl.glfw.GLFW.glfwSetClipboardString
*///?}

object HostClipboard {
    private val LOGGER = LogManager.getLogger("PolyCompose/Clipboard")

    @JvmStatic
    fun getString(): String? = try {
        //? if sdl {
        RenderThread.call { SDL_GetClipboardText() }?.takeIf { it.isNotEmpty() }
        //?} else
        //RenderThread.call { glfwGetClipboardString(McWindow.handle()) }?.takeIf { it.isNotEmpty() }
    } catch (failure: RuntimeException) {
        LOGGER.debug("Failed to read clipboard text", failure)
        null
    }

    @JvmStatic
    fun setString(value: String?): Boolean = try {
        //? if sdl {
        RenderThread.call { SDL_SetClipboardText(value.orEmpty()) }
        //?} else {
        /*RenderThread.call {
            glfwSetClipboardString(McWindow.handle(), value.orEmpty())
            true
        }
        *///?}
    } catch (failure: RuntimeException) {
        LOGGER.debug("Failed to write clipboard text", failure)
        false
    }

    @JvmStatic
    internal fun setTransferable(transferable: Transferable?): Boolean {
        if (transferable == null) return setString(null)
        return try {
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                setString(transferable.getTransferData(DataFlavor.stringFlavor) as? String)
            } else if (OperatingSystem.isMac) {
                false
            } else {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                true
            }
        } catch (failure: Exception) {
            LOGGER.debug("Failed to write clipboard contents", failure)
            false
        }
    }
}
