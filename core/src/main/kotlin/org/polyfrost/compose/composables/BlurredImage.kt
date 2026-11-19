package org.polyfrost.compose.composables

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import kotlin.math.roundToInt
import org.polyfrost.compose.backend.PolyImageBitmap
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.placeBlur

private const val BLEED_SIGMAS = 3f

public fun DrawScope.drawBlurredImage(
    image: PolyBitmap,
    blurRadius: Float,
    dstOffset: Offset = Offset.Zero,
    dstSize: Size = Size(image.width.toFloat(), image.height.toFloat()),
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    val placed = placeBlur(image, blurRadius, 0f, 0f, image.width.toFloat(), image.height.toFloat(), dstOffset.x, dstOffset.y, dstSize.width, dstSize.height)
    val bitmap = placed?.bitmap ?: image
    val left = (placed?.x ?: dstOffset.x).roundToInt()
    val top = (placed?.y ?: dstOffset.y).roundToInt()
    val right = (placed?.let { it.x + it.w } ?: (dstOffset.x + dstSize.width)).roundToInt()
    val bottom = (placed?.let { it.y + it.h } ?: (dstOffset.y + dstSize.height)).roundToInt()
    drawImage(
        PolyImageBitmap(bitmap),
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(bitmap.width, bitmap.height),
        dstOffset = IntOffset(left, top),
        dstSize = IntSize(right - left, bottom - top),
        alpha = alpha,
        colorFilter = colorFilter,
    )
}

@Composable
public fun BlurredImage(
    bitmap: PolyBitmap,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    Layout(
        content = {},
        modifier = modifier.clipToBounds().drawBehind {
            val source = Size(bitmap.width.toFloat(), bitmap.height.toFloat())
            val scale = contentScale.computeScaleFactor(source, size)
            val scaled = Size(source.width * scale.scaleX, source.height * scale.scaleY)
            val at = alignment.align(
                IntSize(scaled.width.roundToInt(), scaled.height.roundToInt()),
                IntSize(size.width.roundToInt(), size.height.roundToInt()),
                layoutDirection,
            )
            val sigma = blurRadius.toPx()
            val covers = scaled.width >= size.width && scaled.height >= size.height
            val bleed = if (covers) sigma * BLEED_SIGMAS else 0f
            drawBlurredImage(
                bitmap,
                sigma,
                Offset(at.x - bleed, at.y - bleed),
                Size(scaled.width + 2f * bleed, scaled.height + 2f * bleed),
                alpha,
                colorFilter,
            )
        },
    ) { _, constraints ->
        layout(constraints.constrainWidth(bitmap.width), constraints.constrainHeight(bitmap.height)) {}
    }
}

@Composable
public fun BlurredImage(
    image: ImageBitmap,
    blurRadius: Dp,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = 1f,
    colorFilter: ColorFilter? = null,
) {
    val bitmap = (image as? PolyImageBitmap)?.bitmap ?: return
    BlurredImage(bitmap, blurRadius, modifier, contentScale, alignment, alpha, colorFilter)
}
