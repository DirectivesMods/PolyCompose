package org.polyfrost.compose.minecraft.platform

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import org.apache.logging.log4j.LogManager

//? if sdl {
import org.lwjgl.sdl.SDLClipboard.SDL_ClearClipboardData
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
    fun hasString(): Boolean = !getString().isNullOrEmpty()

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
    fun clear(): Boolean = try {
        //? if sdl {
        RenderThread.call { SDL_ClearClipboardData() }
        //?} else {
        /*RenderThread.call {
            glfwSetClipboardString(McWindow.handle(), "")
            true
        }
        *///?}
    } catch (failure: RuntimeException) {
        LOGGER.debug("Failed to clear the clipboard", failure)
        false
    }

    @JvmStatic
    internal fun getTransferable(): Transferable? {
        val text = getString()
        if (text != null) return StringSelection(text)
        if (!awtClipboardUsable()) return null
        return try {
            Toolkit.getDefaultToolkit().systemClipboard.getContents(null)?.takeIf { it.transferDataFlavors.isNotEmpty() }
        } catch (failure: Exception) {
            LOGGER.debug("Failed to read clipboard contents", failure)
            null
        }
    }

    @JvmStatic
    internal fun setTransferable(transferable: Transferable?): Boolean {
        if (transferable == null) return clear()
        return try {
            val flavors = transferable.transferDataFlavors
            val textOnly = flavors.all { it.isFlavorTextType }
            if (transferable.isDataFlavorSupported(DataFlavor.stringFlavor) && (textOnly || !awtClipboardUsable())) {
                setString(transferable.getTransferData(DataFlavor.stringFlavor) as? String)
            } else if (awtClipboardUsable()) {
                Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                true
            } else {
                false
            }
        } catch (failure: Exception) {
            LOGGER.debug("Failed to write clipboard contents", failure)
            false
        }
    }

    private fun awtClipboardUsable(): Boolean = !OperatingSystem.isMac && !GraphicsEnvironment.isHeadless()
}
