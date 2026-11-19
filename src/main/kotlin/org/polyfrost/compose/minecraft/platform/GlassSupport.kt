package org.polyfrost.compose.minecraft.platform

import org.polyfrost.compose.minecraft.render.gl.GlHost

enum class GlassSurface {
    Screen,
    Hud,
}

object GlassSupport {
    @JvmStatic
    fun supports(surface: GlassSurface): Boolean = unsupportedReason(surface) == null

    @JvmStatic
    fun unsupportedReason(surface: GlassSurface): String? {
        if (GlHost.vanillaPipeline) return "VulkanMod replaces the GUI pipeline"
        return when (surface) {
            GlassSurface.Screen -> null
            GlassSurface.Hud -> hudReason()
        }
    }

    @JvmStatic
    val screen: Boolean
        get() = supports(GlassSurface.Screen)

    @JvmStatic
    val hud: Boolean
        get() = supports(GlassSurface.Hud)

    private fun hudReason(): String? {
        //? if >= 1.21.8 && < 1.21.11 {
        /*return "HUD layers cannot sample the game frame on this Minecraft version"
        *///?} else
        return null
    }
}
