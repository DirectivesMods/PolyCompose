package org.polyfrost.compose.render

import org.polyfrost.compose.backend.blurred

internal class BlurPlacement(
    val bitmap: PolyBitmap,
    val srcX: Float,
    val srcY: Float,
    val srcW: Float,
    val srcH: Float,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

internal fun placeBlur(
    bitmap: PolyBitmap, radius: Float, srcX: Float, srcY: Float, srcW: Float, srcH: Float, x: Float, y: Float, w: Float, h: Float,
): BlurPlacement? {
    if (radius <= 0f || w <= 0f || h <= 0f || srcW <= 0f || srcH <= 0f) return null
    val blur = blurred(bitmap, radius * srcW / w, radius * srcH / h) ?: return null
    val kx = blur.scaleX.toFloat()
    val ky = blur.scaleY.toFloat()
    val padX = blur.padX.toFloat()
    val padY = blur.padY.toFloat()
    if (srcX <= 0f && srcY <= 0f && srcW >= bitmap.width && srcH >= bitmap.height) {
        val dx = padX * kx * w / srcW
        val dy = padY * ky * h / srcH
        return BlurPlacement(blur.bitmap, 0f, 0f, srcW / kx + 2f * padX, srcH / ky + 2f * padY, x - dx, y - dy, w + 2f * dx, h + 2f * dy)
    }
    return BlurPlacement(blur.bitmap, srcX / kx + padX, srcY / ky + padY, srcW / kx, srcH / ky, x, y, w, h)
}

public fun RenderContext.blurredImage(
    bitmap: PolyBitmap,
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
    u0: Float = 0f,
    v0: Float = 0f,
    u1: Float = 1f,
    v1: Float = 1f,
    tint: PolyColor = PolyColor.WHITE,
    filterQuality: FilterQuality = FilterQuality.Low,
) {
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()
    val placed = placeBlur(bitmap, radius, u0 * bw, v0 * bh, (u1 - u0) * bw, (v1 - v0) * bh, x, y, w, h)
        ?: return image(bitmap, x, y, w, h, u0, v0, u1, v1, tint, filterQuality)
    val pw = placed.bitmap.width.toFloat()
    val ph = placed.bitmap.height.toFloat()
    image(
        placed.bitmap, placed.x, placed.y, placed.w, placed.h,
        placed.srcX / pw, placed.srcY / ph, (placed.srcX + placed.srcW) / pw, (placed.srcY + placed.srcH) / ph, tint, filterQuality,
    )
}
