package org.polyfrost.compose.minecraft.profile.ui

import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.RedrawEvent
import org.polyfrost.compose.render.RenderContext

internal class RedrawHighlighter : OverlayRenderer {
    @JvmField
    var shown = false

    private var events: List<RedrawEvent> = emptyList()
    private val repeats = HashMap<Long, Int>()
    private var now = 0L

    override val visible: Boolean
        get() = shown

    override fun prepare(guiWidth: Float, guiHeight: Float): Boolean {
        now = System.nanoTime()
        val had = events.isNotEmpty()
        events = FrameProfiler.redraws(now - FLASH_NANOS)
        repeats.clear()
        for (e in events) repeats.merge(key(e), 1, Int::plus)
        return had || events.isNotEmpty()
    }

    override fun draw(ctx: RenderContext) {
        val events = events
        if (events.isEmpty()) return
        val ratio = McWindow.viewportWidth().toFloat() / McWindow.windowWidth().coerceAtLeast(1)
        val renderer = ctx.renderer
        for (e in events) {
            if (e.width <= 0f || e.height <= 0f) continue
            val fade = (1f - (now - e.nanos).toFloat() / FLASH_NANOS).coerceIn(0f, 1f)
            if (fade <= 0f) continue
            val rgb = color(repeats[key(e)] ?: 1)
            val x = e.x * ratio
            val y = e.y * ratio
            val w = e.width * ratio
            val h = e.height * ratio
            renderer.rect(x, y, w, h, argb(rgb, FILL_ALPHA * fade), 0f)
            renderer.rectStroke(x, y, w, h, argb(rgb, STROKE_ALPHA * fade), ratio.coerceAtLeast(1f), 0f)
        }
    }

    private fun color(count: Int): Int = when {
        count >= HOT -> HOT_RGB
        count >= WARM -> WARM_RGB
        else -> COOL_RGB
    }

    private fun argb(rgb: Int, alpha: Float): Int = ((alpha * MAX_ALPHA).toInt().coerceIn(0, MAX_ALPHA) shl ALPHA_SHIFT) or rgb

    private fun key(e: RedrawEvent): Long =
        (e.x.toLong() and MASK) or ((e.y.toLong() and MASK) shl 16) or ((e.width.toLong() and MASK) shl 32) or ((e.height.toLong() and MASK) shl 48)

    private companion object {
        const val FLASH_NANOS = 300_000_000L
        const val FILL_ALPHA = 0.18f
        const val STROKE_ALPHA = 0.9f
        const val MAX_ALPHA = 255
        const val ALPHA_SHIFT = 24
        const val MASK = 0xFFFFL
        const val WARM = 3
        const val HOT = 8
        const val COOL_RGB = 0x56B4E9
        const val WARM_RGB = 0xE69F00
        const val HOT_RGB = 0xD55E00
    }
}
