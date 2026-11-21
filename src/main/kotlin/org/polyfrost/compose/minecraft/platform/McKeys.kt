package org.polyfrost.compose.minecraft.platform

import com.mojang.blaze3d.platform.InputConstants

//? if !sdl
//import org.polyfrost.compose.minecraft.screen.GlfwKeyTable

object McKeys {
    //? if sdl {
    const val LEFT_SUPER: Int = InputConstants.KEY_LGUI
    const val RIGHT_SUPER: Int = InputConstants.KEY_RGUI
    //?} elif >= 1.21.10 {
    /*const val LEFT_SUPER: Int = InputConstants.KEY_LSUPER
    const val RIGHT_SUPER: Int = InputConstants.KEY_RSUPER
    *///?} else {
    /*const val LEFT_SUPER: Int = InputConstants.KEY_LWIN
    const val RIGHT_SUPER: Int = InputConstants.KEY_RWIN
    *///?}

    //? if sdl {
    const val MOUSE_BACK: Int = 4
    const val MOUSE_FORWARD: Int = 5

    fun isNumpad(key: Int): Boolean = key in 83..99 || key == 103
    //?} else {
    /*const val MOUSE_BACK: Int = 3
    const val MOUSE_FORWARD: Int = 4

    fun isNumpad(key: Int): Boolean = GlfwKeyTable.isNumpad(key)
    *///?}
}
