package org.polyfrost.compose.minecraft.platform

import com.mojang.blaze3d.platform.InputConstants

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
}
