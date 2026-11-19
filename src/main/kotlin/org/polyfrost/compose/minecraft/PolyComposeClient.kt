package org.polyfrost.compose.minecraft

import net.fabricmc.api.ClientModInitializer
import org.polyfrost.compose.backend.PolyCompose
import org.polyfrost.compose.host.BackStack
import org.polyfrost.compose.minecraft.font.McFontService
import org.polyfrost.compose.minecraft.platform.ComposeClasspathCheck
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.profile.ProfilerHooks
import org.polyfrost.compose.minecraft.warmup.Warmup
import org.polyfrost.compose.render.FontManager

object PolyComposeClient : ClientModInitializer {
    private const val INTER = "assets/polycompose/fonts/inter"

    override fun onInitializeClient() {
        ComposeClasspathCheck.verify()
        PolyCompose.install()
        registerFonts()
        McFontService.init()
        BackStack.owner = { McScreens.current() }
        ProfilerHooks.install()
        Warmup.start()
    }

    private fun registerFonts() {
        val loader = PolyComposeClient::class.java.classLoader
        FontManager.loadFromResource("$INTER/Inter-Regular.ttf", "inter", loader)
        FontManager.loadFromResource("$INTER/Inter-Medium.ttf", "inter-medium", loader)
        FontManager.loadFromResource("$INTER/Inter-SemiBold.ttf", "inter-semibold", loader)
        FontManager.loadFromResource("$INTER/Inter-Bold.ttf", "inter-bold", loader)
        FontManager.registerWeight("inter", 500, "inter-medium")
        FontManager.registerWeight("inter", 600, "inter-semibold")
        FontManager.registerWeight("inter", 700, "inter-bold")
        if (FontManager.fontData(FontManager.DEFAULT_KEY) == null) FontManager.setDefault("inter")
    }
}
