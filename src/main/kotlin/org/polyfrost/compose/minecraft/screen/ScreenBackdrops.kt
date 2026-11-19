package org.polyfrost.compose.minecraft.screen

//? if = 1.8.9 {
/*import java.util.concurrent.CopyOnWriteArrayList
import net.minecraft.client.gui.screens.Screen
import org.polyfrost.compose.host.HostCallbacks

fun interface ScreenBackdrop {
    fun draw(screen: Screen, mouseX: Int, mouseY: Int, tickDelta: Float): Boolean
}

object ScreenBackdrops {
    private val providers = CopyOnWriteArrayList<ScreenBackdrop>()

    @JvmStatic
    fun register(provider: ScreenBackdrop): AutoCloseable {
        providers += provider
        return AutoCloseable { providers -= provider }
    }

    internal val active: Boolean
        get() = providers.isNotEmpty()

    internal fun draw(screen: Screen, mouseX: Int, mouseY: Int, tickDelta: Float): Boolean =
        HostCallbacks.any(providers, "ScreenBackdrop.draw") { it.draw(screen, mouseX, mouseY, tickDelta) }
}
*///?}
