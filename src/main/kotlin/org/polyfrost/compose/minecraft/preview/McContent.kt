package org.polyfrost.compose.minecraft.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import com.mojang.blaze3d.pipeline.RenderTarget
import kotlin.math.ceil
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.minecraft.canvas.PolyOffscreen
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.render.RenderContext

object McContentDefaults {
    const val MAX_PX: Int = 1024
    const val FADE_IN_MILLIS: Int = 150
    const val IDLE_MILLIS: Long = 250L
    const val RELEASE_MILLIS: Long = 1000L
}

@Composable
fun McContent(
    modifier: Modifier = Modifier,
    live: Boolean = true,
    maxPx: Int = McContentDefaults.MAX_PX,
    key: Any? = null,
    fadeInMillis: Int = McContentDefaults.FADE_IN_MILLIS,
    fallback: @Composable BoxScope.() -> Unit = {},
    render: (target: RenderTarget, width: Int, height: Int) -> Boolean,
) {
    val slot = remember { McContentSlot() }
    SideEffect {
        slot.render = render
        slot.live = live
        slot.maxPx = maxPx.coerceAtLeast(1)
        slot.fadeInNanos = fadeInMillis.coerceAtLeast(0) * NANOS_PER_MILLI
        slot.rekey(key)
    }
    DisposableEffect(slot) {
        McContents.register(slot)
        onDispose { McContents.unregister(slot) }
    }
    Box(
        modifier
            .onGloballyPositioned(slot::measure)
            .drawBehind {
                if (slot.version.longValue < 0L) return@drawBehind
                slot.lastDrawNanos = System.nanoTime()
                val w = size.width
                val h = size.height
                drawPoly { slot.draw(it, w, h) }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (!slot.available) fallback()
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val IDLE_NANOS = McContentDefaults.IDLE_MILLIS * NANOS_PER_MILLI
private const val RELEASE_NANOS = McContentDefaults.RELEASE_MILLIS * NANOS_PER_MILLI
private const val HEARTBEAT_NANOS = 100L * NANOS_PER_MILLI
private const val RETRY_BASE_NANOS = 1_000_000_000L
private const val MAX_RETRY_NANOS = 60_000_000_000L
private const val MAX_RETRY_SHIFT = 6

private object Unset

internal class McContentSlot {
    var render: ((RenderTarget, Int, Int) -> Boolean)? = null
    var live = true
    var maxPx = McContentDefaults.MAX_PX
    var fadeInNanos = 0L

    val version = mutableLongStateOf(0L)
    var available by mutableStateOf(true)
        private set

    var lastDrawNanos = 0L

    private var key: Any? = Unset
    private var fractionW = 0f
    private var fractionH = 0f
    private var renderedW = 0
    private var renderedH = 0
    private var offscreen: PolyOffscreen? = null
    private var hasContent = false
    private var contentSince = 0L
    private var dirty = true
    private var failures = 0
    private var retryAt = 0L
    private var bumpedAt = 0L

    fun rekey(next: Any?) {
        if (key == next) return
        key = next
        dirty = true
    }

    fun measure(coordinates: LayoutCoordinates) {
        var root = coordinates
        while (true) root = root.parentLayoutCoordinates ?: break
        val rootW = root.size.width
        val rootH = root.size.height
        if (rootW <= 0 || rootH <= 0) return
        val w = coordinates.size.width.toFloat() / rootW
        val h = coordinates.size.height.toFloat() / rootH
        if (w != fractionW || h != fractionH) {
            fractionW = w
            fractionH = h
            dirty = true
        }
    }

    fun draw(ctx: RenderContext, w: Float, h: Float) {
        if (!hasContent || w <= 0f || h <= 0f) return
        val target = offscreen ?: return
        val alpha = if (fadeInNanos <= 0L) 1f else ((System.nanoTime() - contentSince).toFloat() / fadeInNanos).coerceIn(0f, 1f)
        with(target) { ctx.blit(0f, 0f, w, h, alpha) }
    }

    fun tick(now: Long, viewportW: Int, viewportH: Int) {
        val drawnAt = lastDrawNanos
        if (drawnAt == 0L) return
        val since = now - drawnAt
        if (since > RELEASE_NANOS) {
            if (offscreen != null) release()
            return
        }
        if (since > IDLE_NANOS) {
            heartbeat(now)
            return
        }
        val fading = hasContent && now - contentSince < fadeInNanos
        val requestedW = ceil(fractionW * viewportW).toInt()
        val requestedH = ceil(fractionH * viewportH).toInt()
        val sized = requestedW > 0 && requestedH > 0
        val fit = if (sized) minOf(1f, maxPx.toFloat() / maxOf(requestedW, requestedH)) else 1f
        val w = (requestedW * fit).toInt().coerceIn(1, maxPx)
        val h = (requestedH * fit).toInt().coerceIn(1, maxPx)
        val resized = sized && (w != renderedW || h != renderedH)
        if (!live && !dirty && hasContent && !resized) {
            if (fading) bump(now) else heartbeat(now)
            return
        }
        if (now < retryAt) return
        val renderer = render ?: return
        if (!sized) return
        val target = offscreen ?: PolyOffscreen(w, h).also { offscreen = it }
        target.resize(w, h)
        renderedW = w
        renderedH = h
        val drawn: Boolean? = HostCallbacks.call(renderer, "An McContent renderer", null) {
            var result = false
            target.render {
                val rt = target.renderTarget
                if (rt != null) result = renderer(rt, w, h)
            }
            result
        }
        when {
            drawn == null -> {
                failures++
                retryAt = now + (RETRY_BASE_NANOS shl (failures - 1).coerceAtMost(MAX_RETRY_SHIFT)).coerceAtMost(MAX_RETRY_NANOS)
                hasContent = false
                updateAvailable(false)
            }
            drawn && target.hasContent -> {
                failures = 0
                if (!hasContent) contentSince = now
                hasContent = true
                dirty = false
                updateAvailable(true)
                bump(now)
            }
            else -> {
                hasContent = false
                updateAvailable(false)
            }
        }
    }

    fun release() {
        offscreen?.close()
        offscreen = null
        hasContent = false
        renderedW = 0
        renderedH = 0
        dirty = true
    }

    private fun heartbeat(now: Long) {
        if (now - bumpedAt >= HEARTBEAT_NANOS) bump(now)
    }

    private fun bump(now: Long) {
        bumpedAt = now
        version.longValue++
    }

    private fun updateAvailable(value: Boolean) {
        if (available != value) available = value
    }
}

internal object McContents {
    private val slots = ArrayList<McContentSlot>()
    private var hook: AutoCloseable? = null

    fun register(slot: McContentSlot) {
        if (slot !in slots) slots += slot
        if (hook == null) hook = McFrameHooks.beforeUi(::renderAll)
    }

    fun unregister(slot: McContentSlot) {
        slots -= slot
        slot.release()
        if (slots.isEmpty()) {
            hook?.close()
            hook = null
        }
    }

    private fun renderAll() {
        if (slots.isEmpty()) return
        val viewportW = McWindow.viewportWidth()
        val viewportH = McWindow.viewportHeight()
        if (viewportW <= 0 || viewportH <= 0) return
        val now = System.nanoTime()
        for (slot in slots.toTypedArray()) slot.tick(now, viewportW, viewportH)
    }
}
