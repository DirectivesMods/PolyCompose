package org.polyfrost.compose.minecraft.preview

import org.polyfrost.compose.minecraft.canvas.OffscreenReadback

object McContentSupport {
    @JvmStatic
    val beforeUiHook: Boolean
        get() = true

    @JvmStatic
    val readback: Boolean
        get() = OffscreenReadback.isSupported

    @JvmStatic
    val readbackUnsupportedReason: String?
        get() = OffscreenReadback.unsupportedReason
}
