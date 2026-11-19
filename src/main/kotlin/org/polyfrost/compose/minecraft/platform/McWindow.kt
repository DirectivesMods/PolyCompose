package org.polyfrost.compose.minecraft.platform

import net.minecraft.client.Minecraft

//? if = 1.8.9 {
/*import net.minecraft.client.render.Window
import org.lwjgl.sdl.SDLVideo
import pl.tomgirl.pylon.window.DisplaySdl
*///?}

object McWindow {
    @JvmStatic
    fun viewportWidth(): Int {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().width
        *///?} else
        return Minecraft.getInstance().window.width
    }

    @JvmStatic
    fun viewportHeight(): Int {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().height
        *///?} else
        return Minecraft.getInstance().window.height
    }

    @JvmStatic
    fun windowWidth(): Int {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().windowWidth
        *///?} else
        return Minecraft.getInstance().window.screenWidth
    }

    @JvmStatic
    fun windowHeight(): Int {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().windowHeight
        *///?} else
        return Minecraft.getInstance().window.screenHeight
    }

    @JvmStatic
    fun guiWidth(): Int {
        //? if = 1.8.9 {
        /*return Window(Minecraft.getInstance()).width
        *///?} else
        return Minecraft.getInstance().window.guiScaledWidth
    }

    @JvmStatic
    fun guiHeight(): Int {
        //? if = 1.8.9 {
        /*return Window(Minecraft.getInstance()).height
        *///?} else
        return Minecraft.getInstance().window.guiScaledHeight
    }

    @JvmStatic
    fun guiScale(): Int {
        //? if = 1.8.9 {
        /*return Window(Minecraft.getInstance()).scale
        *///?} elif >= 1.21.8 {
        return Minecraft.getInstance().window.guiScale
        //?} else
        //return Minecraft.getInstance().window.guiScale.toInt()
    }

    @JvmStatic
    fun pixelRatio(): Float {
        val window = windowWidth()
        return if (window > 0) viewportWidth().toFloat() / window else 1f
    }

    @JvmStatic
    fun handle(): Long {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().handle
        *///?} elif >= 1.21.10 {
        return Minecraft.getInstance().window.handle()
        //?} else
        //return Minecraft.getInstance().window.window
    }

    @JvmStatic
    fun isActive(): Boolean {
        //? if = 1.8.9 {
        /*return DisplaySdl.instance().isActive
        *///?} else
        return Minecraft.getInstance().isWindowActive
    }

    @JvmStatic
    fun refreshRate(): Int {
        //? if = 1.8.9 {
        /*val handle = handle()
        val mode = SDLVideo.SDL_GetWindowFullscreenMode(handle)
            ?: SDLVideo.SDL_GetDisplayForWindow(handle).takeIf { it != 0 }?.let(SDLVideo::SDL_GetCurrentDisplayMode)
        return mode?.refresh_rate()?.toInt() ?: 0
        *///?} elif >= 26.3 {
        return Minecraft.getInstance().window.activeVideoMode?.refreshRate?.toInt() ?: 0
        //?} else
        //return Minecraft.getInstance().window.refreshRate
    }
}
