package org.polyfrost.compose.minecraft.profile

import java.nio.file.Path
import net.minecraft.client.Minecraft
import org.polyfrost.compose.minecraft.platform.RenderThread

//? if > 1.8.9 {
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
//?} else {
/*import net.minecraft.text.ClickEvent
import net.minecraft.text.Formatting as ChatFormatting
import net.minecraft.text.LiteralText
import net.minecraft.text.Style
import net.minecraft.text.Text
import net.minecraft.text.TranslatableText
*///?}

internal object ProfilerChat {
    fun info(key: String, vararg args: Any) {
        send(translatable(key, args, ChatFormatting.GRAY))
    }

    fun success(key: String, vararg args: Any) {
        send(translatable(key, args, ChatFormatting.GREEN))
    }

    fun error(key: String, vararg args: Any) {
        send(translatable(key, args, ChatFormatting.RED))
    }

    fun exported(file: Path) {
        send(translatable("polycompose.profiler.export.saved", arrayOf(fileLink(file)), ChatFormatting.GREEN))
    }

    fun spike(millis: String, culprit: String, file: Path) {
        send(translatable("polycompose.profiler.spike", arrayOf(millis, culprit, fileLink(file)), ChatFormatting.YELLOW))
    }

    //? if > 1.8.9 {
    private fun translatable(key: String, args: Array<out Any>, color: ChatFormatting): MutableComponent =
        Component.translatable(key, *args).withStyle(color)

    private fun fileLink(file: Path): Component {
        val folder = file.toAbsolutePath().parent ?: file.toAbsolutePath()
        //? if >= 1.21.5 {
        val click = ClickEvent.OpenFile(folder)
        //?} else
        //val click = ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toString())
        return Component.literal(file.fileName.toString()).withStyle { it.withClickEvent(click).withUnderlined(true) }
    }

    private fun send(message: Component) {
        if (!RenderThread.isCurrent()) {
            RenderThread.schedule { send(message) }
            return
        }
        val minecraft = Minecraft.getInstance()
        //? if >= 26.2 {
        minecraft.gui.hud.chat.addClientSystemMessage(message)
        //?} elif >= 26.1 {
        /*minecraft.gui.chat.addClientSystemMessage(message)
        *///?} else
        //minecraft.gui.chat.addMessage(message)
    }
    //?} else {
    /*private fun translatable(key: String, args: Array<out Any>, color: ChatFormatting): Text =
        TranslatableText(key, *args).setStyle(Style().setColor(color))

    private fun fileLink(file: Path): Text {
        val folder = file.toAbsolutePath().parent ?: file.toAbsolutePath()
        val click = ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toString())
        return LiteralText(file.fileName.toString()).setStyle(Style().setClickEvent(click).setUnderlined(true))
    }

    private fun send(message: Text) {
        if (!RenderThread.isCurrent()) {
            RenderThread.schedule { send(message) }
            return
        }
        Minecraft.getInstance().gui.chat.addMessage(message)
    }
    *///?}
}
