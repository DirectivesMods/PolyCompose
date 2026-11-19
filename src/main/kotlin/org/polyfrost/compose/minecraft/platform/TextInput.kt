package org.polyfrost.compose.minecraft.platform

//? if = 1.8.9 {
/*import pl.tomgirl.pylon.window.DisplaySdl
*///?} elif >= 26.1 {
import net.minecraft.client.Minecraft
//?}

object TextInput {
    @JvmStatic
    fun start(owner: Any) {
        //? if = 1.8.9 {
        /*DisplaySdl.instance().setTextInputActive(true)
        *///?} elif sdl {
        Minecraft.getInstance().textInputManager().startTextInput(owner)
        //?} elif >= 26.1 {
        /*Minecraft.getInstance().textInputManager().startTextInput()
        *///?}
    }

    @JvmStatic
    fun stop(owner: Any) {
        //? if = 1.8.9 {
        /*DisplaySdl.instance().setTextInputActive(false)
        *///?} elif sdl {
        Minecraft.getInstance().textInputManager().stopTextInput(owner)
        //?} elif >= 26.1 {
        /*Minecraft.getInstance().textInputManager().stopTextInput()
        *///?}
    }
}
