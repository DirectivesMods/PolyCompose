package org.polyfrost.compose.minecraft.mc

//? if > 1.8.9
import net.minecraft.client.Minecraft

internal object McItemGlint {
    //? if > 1.8.9 {
    const val TEXTURE = "textures/misc/enchanted_glint_item.png"
    //?} else
    //const val TEXTURE = "textures/misc/enchanted_item_glint.png"

    const val STRIDE = 9

    private const val SCALE = 0.125f
    private const val COS_10 = 0.98480775f
    private const val SIN_10 = 0.17364818f
    //? if = 1.8.9 {
    /*private const val COS_MINUS_50 = 0.64278761f
    private const val SIN_MINUS_50 = -0.76604444f
    private const val TINT_R = 128f / 255f
    private const val TINT_G = 64f / 255f
    private const val TINT_B = 204f / 255f
    *///?}

    val layers = FloatArray(STRIDE * 2)

    fun update(): FloatArray {
        val millis = System.nanoTime() / 1_000_000L
        //? if > 1.8.9 {
        val options = Minecraft.getInstance().options
        val scaled = (millis * options.glintSpeed().get() * 8.0).toLong()
        val strength = options.glintStrength().get().toFloat()
        layer(0, COS_10, SIN_10, -(scaled % 110_000L) / 110_000f, (scaled % 30_000L) / 30_000f, strength, strength, strength)
        layer(1, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        //?} else {
        /*layer(0, COS_MINUS_50, SIN_MINUS_50, (millis % 3_000L) / 3_000f, 0f, TINT_R, TINT_G, TINT_B)
        layer(1, COS_10, SIN_10, -(millis % 4_873L) / 4_873f, 0f, TINT_R, TINT_G, TINT_B)
        *///?}
        return layers
    }

    private fun layer(index: Int, cos: Float, sin: Float, offsetU: Float, offsetV: Float, r: Float, g: Float, b: Float) {
        val o = index * STRIDE
        val out = layers
        out[o] = SCALE * cos
        out[o + 1] = -SCALE * sin
        out[o + 2] = SCALE * sin
        out[o + 3] = SCALE * cos
        out[o + 4] = offsetU
        out[o + 5] = offsetV
        out[o + 6] = r
        out[o + 7] = g
        out[o + 8] = b
    }
}
