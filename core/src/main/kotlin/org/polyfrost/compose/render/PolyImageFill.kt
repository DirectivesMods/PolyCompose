package org.polyfrost.compose.render

internal class PolyImageFill(
    val bitmap: PolyBitmap,
    private val ux: Float, private val uy: Float, private val u0: Float,
    private val vx: Float, private val vy: Float, private val v0: Float,
) : PolyGradient() {
    override val colors: List<PolyColor> by lazy(LazyThreadSafetyMode.NONE) { listOf(PolyColor(average())) }
    override val stops: List<Float>? get() = null

    override fun position(px: Float, py: Float, x: Float, y: Float, w: Float, h: Float): Float = 0f

    fun u(x: Float, y: Float): Float = ux * x + uy * y + u0
    fun v(x: Float, y: Float): Float = vx * x + vy * y + v0

    private fun average(): Int {
        val px = bitmap.pixels
        var r = 0L; var g = 0L; var b = 0L; var a = 0L
        for (o in px.indices step 4) {
            val pa = px[o + 3].toLong() and 0xFF
            r += (px[o].toLong() and 0xFF) * pa
            g += (px[o + 1].toLong() and 0xFF) * pa
            b += (px[o + 2].toLong() and 0xFF) * pa
            a += pa
        }
        if (a == 0L) return 0
        val n = (px.size / 4).coerceAtLeast(1)
        return ((a / n).toInt() shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
    }
}
