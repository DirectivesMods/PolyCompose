package org.polyfrost.compose.minecraft.font

import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.PolyGlyphPage
//? if >= 1.21.11 {
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Style
import net.minecraft.util.FormattedCharSequence
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.render.blaze.BitmapTextures
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyBitmap
//?}

object McFontService {
    @JvmStatic
    fun init() {
        installEngines()
        //? if >= 1.21.11 {
        McFontQueue.measureWidth = { text, scale -> Minecraft.getInstance().font.width(text) * scale }
        McFontQueue.measureLiteralWidth = { text, scale ->
            Minecraft.getInstance().font.width(FormattedCharSequence.forward(text, Style.EMPTY)) * scale
        }
        HostEvents.onResourcesReloaded {
            FontManager.invalidateMetrics()
            HostCallbacks.each(ComposeHost.listeners, "HostListener.onFontsChanged") { it.onFontsChanged() }
        }
        //?} else {
        /*McResourceFont.init()
        *///?}
    }

    private fun installEngines() {
        PolyFont.engine = StbFonts
        PolyGlyphPage.rasterizer = StbFonts
        BackgroundTasks.submit(StbFonts::warmEmoji)
        //? if >= 1.21.11
        PolyBitmap.decoder = BitmapTextures.decoder
    }
}
