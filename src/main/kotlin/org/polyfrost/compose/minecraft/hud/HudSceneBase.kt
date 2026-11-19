@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.Logger
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.SceneKind
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.screen.ComposeSceneContextImpl
import org.polyfrost.compose.minecraft.screen.SceneFrames
import org.polyfrost.compose.minecraft.screen.SceneHandle
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure

internal abstract class HudSceneBase(private val key: String) : HudInputTarget {
    protected val pointer = HudPointerRouter()
    private val text = HudTextContext()
    protected val frames = SceneFrames { dirty = true }
    protected val paintScene: (PolyCanvas) -> Unit = { canvas -> paintInto(canvas) }
    protected var handle: SceneHandle? = null
    protected var dirty = true
    protected var failures = 0
    protected var builtAt = 0L
    protected var lastDrawn = 0L
    protected var appliedRatio = 0f
    private var profile: ProfileHost? = null

    val drawnAt: Long
        get() = lastDrawn

    override val textFocused: Boolean
        get() = text.sessions > 0 && handle != null

    protected abstract val routesInput: Boolean

    protected abstract val acceptsPointer: Boolean

    protected abstract fun ensureScene(): SceneHandle?

    protected abstract fun fail(cause: Throwable)

    protected open fun afterFrame(now: Long) {
        if (failures > 0 && now - builtAt > HEALTHY_NANOS) failures = 0
    }

    fun profile(): ProfileHost? {
        if (!FrameProfiler.enabled) return null
        return profile ?: FrameProfiler.host(key).also { profile = it }
    }

    protected inline fun renderFrame(
        density: () -> Float,
        prepare: (Float, Long) -> Unit,
        paint: (Boolean, (PolyCanvas) -> Unit) -> Boolean,
    ): Boolean {
        val handle = advanceFrame(density, prepare) ?: return false
        return try {
            val redraw = dirty
            dirty = false
            val drawn = profile().measure(ProfilePhase.Record) { paint(redraw, paintScene) }
            if (!drawn) dirty = true
            handle.rethrow()
            afterFrame(lastDrawn)
            drawn
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    protected inline fun advanceFrame(density: () -> Float, prepare: (Float, Long) -> Unit): SceneHandle? {
        val w = McWindow.windowWidth()
        val h = McWindow.windowHeight()
        if (w <= 0 || h <= 0) return null
        val handle = ensureScene() ?: return null
        return try {
            val scene = handle.scene
            val sceneDensity = density()
            if (handle.resize(w, h, sceneDensity)) dirty = true
            val now = System.nanoTime()
            prepare(sceneDensity, now)
            ComposeSceneContextImpl.updateContainerSize(w, h)
            lastDrawn = now
            val routing = routesInput && HudPointer.inputAllowedThisFrame()
            pointer.route(scene, routing, sceneDensity)
            if (!routing && text.sessions > 0) scene.focusManager.releaseFocus()
            text.sync(routing)
            val ratio = (McWindow.viewportWidth().toFloat() / w).coerceAtLeast(MIN_RATIO)
            if (ratio != appliedRatio) {
                appliedRatio = ratio
                dirty = true
            }
            if (dirty || frames.hasWork(handle.recomposer, scene)) frames.advance(handle.recomposer, scene, now, profile())
            handle.rethrow()
            handle
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    protected fun openScene(content: @Composable () -> Unit): SceneHandle? =
        try {
            HostCallbacks.each(ComposeHost.listeners, "HostListener.onSceneCreated") { it.onSceneCreated(SceneKind.HudLayer) }
            val created = SceneHandle.create(text, frames.onInvalidation) { dirty = true }
            handle = created
            builtAt = System.nanoTime()
            dirty = true
            HudInput.register(this)
            created.scene.setContent { content() }
            created
        } catch (t: Throwable) {
            fail(t)
            null
        }

    protected open fun releaseScene() {
        val old = handle
        pointer.stop(old?.scene)
        text.sync(false)
        handle = null
        dirty = true
        HudInput.unregister(this)
        old?.close()
    }

    private fun paintInto(canvas: PolyCanvas) {
        val scene = handle?.scene ?: return
        val ratio = appliedRatio
        canvas.save()
        if (ratio != 1f) canvas.scale(ratio, ratio)
        try {
            frames.draw(scene, canvas)
        } finally {
            canvas.restore()
        }
    }

    override val takingInput: Boolean
        get() = pointer.active && acceptsPointer && handle != null &&
            System.nanoTime() - lastDrawn < HudPointer.INPUT_STALE_NANOS && HudPointer.inputAllowed()

    override fun claims(layer: HudLayer, position: Offset): Boolean = true

    override fun press(button: PointerButton): Boolean = sendButton(PointerEventType.Press, button)

    override fun release(button: PointerButton) {
        sendButton(PointerEventType.Release, button)
    }

    override fun scroll(x: Double, y: Double): Boolean {
        if (!takingInput) return false
        val scene = handle?.scene ?: return false
        return try {
            pointer.scroll(scene, x, y)
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    override fun sendKey(event: KeyEvent): Boolean {
        val handle = handle ?: return false
        return try {
            val handled = handle.scene.sendKeyEvent(event)
            handle.rethrow()
            handled
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    override fun releaseFocus() {
        val handle = handle ?: return
        try {
            handle.scene.focusManager.releaseFocus()
            handle.rethrow()
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun sendButton(type: PointerEventType, button: PointerButton): Boolean {
        if (!takingInput) return false
        val scene = handle?.scene ?: return false
        return try {
            pointer.button(scene, type, button)
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    internal companion object {
        val LOGGER: Logger = LogManager.getLogger("PolyCompose/HudLayer")

        const val HEALTHY_NANOS = 30_000_000_000L
        const val MIN_RATIO = 0.01f
    }
}
