/*
 * This file is part of OneConfig.
 * OneConfig - Next Generation Config Library for Minecraft: Java Edition
 * Copyright (C) 2021~2024 Polyfrost.
 *   <https://polyfrost.org> <https://github.com/Polyfrost/>
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *   OneConfig is licensed under the terms of version 3 of the GNU Lesser
 * General Public License as published by the Free Software Foundation, AND
 * under the Additional Terms Applicable to OneConfig, as published by Polyfrost,
 * either version 1.0 of the Additional Terms, or (at your option) any later
 * version.
 *
 *   This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 *   You should have received a copy of the GNU Lesser General Public
 * License.  If not, see <https://www.gnu.org/licenses/>. You should
 * have also received a copy of the Additional Terms Applicable
 * to OneConfig, as published by Polyfrost. If not, see
 * <https://polyfrost.org/legal/oneconfig/additional-terms>
 */

package org.polyfrost.compose.host

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlendModeColorFilter
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import org.polyfrost.compose.backend.asComposeImageBitmap
import org.polyfrost.compose.host.svg.SvgMask
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

public val LocalUiOversample: ProvidableCompositionLocal<Float> = staticCompositionLocalOf { 1f }

private class Raster(val image: ImageBitmap?, val key: String = "")

private const val MAX_SVG_RASTERS = 256

private val svgRasters = object : LinkedHashMap<String, Raster>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Raster>) = size > MAX_SVG_RASTERS
}

private val contentRasters = object : LinkedHashMap<String, Raster>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Raster>) = size > MAX_SVG_RASTERS
}

private val svgSizes = ConcurrentHashMap<String, Size>()

private val svgRoot = Regex("<svg\\b[^>]*>")
private val svgWidth = svgAttrRegex("width")
private val svgHeight = svgAttrRegex("height")
private val svgViewBox = Regex("viewBox\\s*=\\s*[\"']([^\"']+)[\"']")
private val svgSeparators = Regex("[\\s,]+")

private fun svgAttrRegex(name: String) = Regex("\\s$name\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(px)?\\s*[\"']")

private fun svgAttr(el: String, attr: Regex): Float? =
    attr.find(el)?.groupValues?.get(1)?.toFloatOrNull()

public class OversampledSvgPainter(
    bytes: ByteArray,
    private val oversample: Float,
    private val cacheKey: String? = null,
    density: Float = 1f,
) : Painter() {
    private val svg = String(bytes, Charsets.UTF_8)

    private fun measure(): Size {
        val root = svgRoot.find(svg)?.value ?: return Size.Unspecified
        val w = svgAttr(root, svgWidth)
        val h = svgAttr(root, svgHeight)
        if (w != null && h != null && w > 0f && h > 0f) return Size(w, h)
        val view = svgViewBox.find(root)?.groupValues?.get(1)
            ?.trim()?.split(svgSeparators)?.mapNotNull { it.toFloatOrNull() }
        return if (view != null && view.size >= 4 && view[2] > 0f && view[3] > 0f) Size(view[2], view[3]) else Size.Unspecified
    }

    private val defaultSize: Size =
        if (cacheKey == null) measure() else svgSizes.getOrPut(cacheKey, ::measure)

    public override val intrinsicSize: Size = if (defaultSize.isSpecified) defaultSize * density else defaultSize

    private var alpha = 1f
    private var colorFilter: ColorFilter? = null
    private var cached: ImageBitmap? = null
    private var cachedPx = -1
    private var cachedMask = false

    override fun applyAlpha(alpha: Float): Boolean {
        this.alpha = alpha
        return true
    }

    override fun applyColorFilter(colorFilter: ColorFilter?): Boolean {
        this.colorFilter = colorFilter
        return true
    }

    override fun DrawScope.onDraw() {
        if (size.width <= 0f || size.height <= 0f) return
        val px = max(1, ceil(max(size.width, size.height) * max(1f, oversample)).toInt())
        val mask = (colorFilter as? BlendModeColorFilter)?.blendMode == BlendMode.SrcIn
        var img = cached
        if (img == null || cachedPx != px || cachedMask != mask) {
            val key = "$px@$mask"
            val shared = cacheKey
            img = if (shared == null) {
                (synchronized(contentRasters) { contentRasters[svg]?.takeIf { it.key == key } }
                    ?: Raster(rasterize(px, mask), key).also { synchronized(contentRasters) { contentRasters[svg] = it } }).image
            } else {
                val sharedKey = "$shared@$key"
                (synchronized(svgRasters) { svgRasters[sharedKey] } ?: Raster(rasterize(px, mask)).also { synchronized(svgRasters) { svgRasters[sharedKey] = it } }).image
            }
            cached = img
            cachedPx = px
            cachedMask = mask
        }
        img ?: return
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()),
            alpha = alpha,
            colorFilter = colorFilter,
            filterQuality = FilterQuality.Medium,
        )
    }

    private fun rasterize(px: Int, mask: Boolean): ImageBitmap? =
        SvgMask.rasterize(svg, px, if (mask) -1 else null)?.asComposeImageBitmap()
}
