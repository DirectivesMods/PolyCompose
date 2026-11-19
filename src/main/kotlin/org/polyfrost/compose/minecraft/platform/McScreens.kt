package org.polyfrost.compose.minecraft.platform

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

//? if > 1.8.9 {
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
//?} else {
/*import net.minecraft.text.Formatting
import net.minecraft.text.LiteralText
import net.minecraft.text.Style
import org.polyfrost.compose.minecraft.legacy.LegacyPanoramaTracker
*///?}

object McScreens {
    @JvmStatic
    fun current(): Screen? {
        //? if >= 26.2 {
        return Minecraft.getInstance().gui.screen()
        //?} else
        //return Minecraft.getInstance().screen
    }

    @JvmStatic
    @JvmOverloads
    fun display(screen: Screen?, frames: Int = 0) {
        if (!RenderThread.isCurrent()) {
            RenderThread.schedule { display(screen, frames) }
            return
        }
        if (frames < 1) show(screen)
        else HostEvents.afterFrames(frames) { show(screen) }
    }

    @JvmStatic
    fun preventReplacement(owner: Any, allow: (next: Screen?) -> Boolean): AutoCloseable = ScreenGuards.add(owner, allow)

    @JvmStatic
    fun close() {
        display(null)
    }

    @JvmStatic
    fun showMessage(message: String?) {
        if (message.isNullOrEmpty()) return
        if (!RenderThread.isCurrent()) {
            RenderThread.schedule { showMessage(message) }
            return
        }
        val minecraft = Minecraft.getInstance()
        //? if >= 26.2 {
        val chat = minecraft.gui.hud.chat
        //?} else
        //val chat = minecraft.gui.chat
        //? if >= 26.1 {
        chat.addClientSystemMessage(Component.literal(message).withStyle(ChatFormatting.RED))
        //?} elif > 1.8.9 {
        /*chat.addMessage(Component.literal(message).withStyle(ChatFormatting.RED))
        *///?} else
        //chat.addMessage(LiteralText(message).setStyle(Style().setColor(Formatting.RED)))
    }

    private fun show(screen: Screen?) {
        val minecraft = Minecraft.getInstance()
        //? if >= 26.2 {
        minecraft.gui.setScreen(screen)
        //?} elif > 1.8.9 {
        /*minecraft.setScreen(screen)
        *///?} else {
        /*val target = if (screen == null && minecraft.level == null) LegacyPanoramaTracker.current() else screen
        minecraft.setScreen(target)
        *///?}
    }
}
