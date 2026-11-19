package org.polyfrost.compose.host.svg

import org.polyfrost.compose.render.PolyBitmap

internal object SvgMask {
    fun rasterize(svg: String, size: Int, tint: Int? = null): PolyBitmap? =
        runCatching { SvgCanvas.rasterize(svg, size, tint) }.getOrNull()
}
