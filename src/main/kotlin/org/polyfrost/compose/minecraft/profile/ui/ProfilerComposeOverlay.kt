@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.profile.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.SingleComposeSceneRenderingScope
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.host.GameInputLayer
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.minecraft.hud.HudInput
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.screen.ComposeSceneContextImpl
import org.polyfrost.compose.minecraft.screen.ComposeSupport
import org.polyfrost.compose.minecraft.screen.SceneHandle
import org.polyfrost.compose.minecraft.screen.ScenePlatformContext
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.within
import org.polyfrost.compose.render.RenderContext

//? if > 1.8.9 {
import net.minecraft.client.Minecraft
//?} else {
/*import org.lwjgl.input.Mouse
*///?}

internal class ProfilerComposeOverlay(private val sampler: LiveSampler) : OverlayRenderer, GameInputLayer {
    override val order: Int = GameInputLayer.ABOVE_HUD_LAYERS

    @JvmField
    var shown = false

    @JvmField
    var panelLeft = 0f

    @JvmField
    var panelTop = 0f

    @JvmField
    var panelRight = 0f

    @JvmField
    var panelBottom = 0f

    private val platform = ScenePlatformContext()
    private var handle: SceneHandle? = null
    private var scope: SingleComposeSceneRenderingScope? = null
    private var profile: ProfileHost? = null

    @Volatile
    private var dirty = true
    private var failures = 0
    private var retryAt = 0L
    private var frameNanos = 0L
    private var lastPointer = Offset.Zero
    private var hasPointer = false
    private var buttons = 0

    val failed: Boolean
        get() = failures >= MAX_FAILURES

    override val visible: Boolean
        get() = shown

    override val showWhenGuiHidden: Boolean
        get() = true

    override fun prepare(guiWidth: Float, guiHeight: Float): Boolean {
        val w = McWindow.windowWidth()
        val h = McWindow.windowHeight()
        if (w <= 0 || h <= 0) return false
        val handle = ensureScene() ?: return false
        return try {
            host().within { step(handle, w, h) }
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    override fun draw(ctx: RenderContext) {
        val handle = handle ?: return
        val scope = scope ?: return
        try {
            host().within {
                val ratio = (McWindow.viewportWidth().toFloat() / McWindow.windowWidth().coerceAtLeast(1)).coerceAtLeast(MIN_RATIO)
                val canvas = PolyCanvas(ctx.renderer)
                canvas.save()
                if (ratio != 1f) canvas.scale(ratio, ratio)
                try {
                    with(scope) { handle.scene.render(handle.recomposer, canvas, frameNanos) }
                } finally {
                    canvas.restore()
                }
                handle.rethrow()
            }
        } catch (t: Throwable) {
            fail(t)
        }
    }

    private fun step(handle: SceneHandle, w: Int, h: Int): Boolean {
        val density = McWindow.guiScale().toFloat().coerceAtLeast(1f)
        if (handle.resize(w, h, density)) dirty = true
        platform.updateContainerSize(w, h, density)
        sampler.refreshIfDue(System.nanoTime())
        routePointer(handle.scene)
        val nanos = System.nanoTime()
        frameNanos = nanos
        handle.recomposer.performFrame(nanos)
        handle.scene.measureAndLayout()
        handle.rethrow()
        val wasDirty = dirty
        dirty = false
        return wasDirty
    }

    private fun host(): ProfileHost = profile ?: FrameProfiler.host(ProfilerUi.OVERLAY_KEY).also { profile = it }

    private fun ensureScene(): SceneHandle? {
        handle?.let { return it }
        if (failed || System.nanoTime() - retryAt < 0L || !ComposeSupport.isAvailable) return null
        return try {
            val newScope = SingleComposeSceneRenderingScope { dirty = true }
            val created = SceneHandle.create(platform, ComposeOptions.Default, newScope::onSceneInvalidation) { dirty = true }
            handle = created
            scope = newScope
            dirty = true
            created.scene.setContent { ProfilerOverlayContent(this, sampler) }
            created
        } catch (t: Throwable) {
            fail(t)
            null
        }
    }

    fun release() {
        releaseInput()
        val old = handle
        handle = null
        scope = null
        old?.close()
    }

    private fun fail(cause: Throwable) {
        failures++
        retryAt = System.nanoTime() + (RETRY_NANOS shl (failures - 1).coerceIn(0, MAX_BACKOFF_SHIFT))
        HostErrors.error("PolyCompose profiler overlay failed; it will be rebuilt after a delay", cause, LOGGER)
        release()
    }

    private fun cursorFree(): Boolean = McWindow.isActive() && McScreens.current() != null

    private fun inside(p: Offset): Boolean = p.x >= panelLeft && p.x < panelRight && p.y >= panelTop && p.y < panelBottom

    private fun routePointer(scene: ComposeScene) {
        if (!cursorFree()) {
            releaseInput()
            return
        }
        val p = pointerPosition()
        if (inside(p) || buttons != 0) {
            if (!hasPointer || p != lastPointer) {
                lastPointer = p
                hasPointer = true
                scene.sendPointerEvent(PointerEventType.Move, p)
            }
        } else if (hasPointer) {
            hasPointer = false
            scene.sendPointerEvent(PointerEventType.Move, p)
            scene.sendPointerEvent(PointerEventType.Exit, p)
        }
    }

    private fun releaseInput() {
        val scene = handle?.scene
        if (buttons != 0) {
            buttons = 0
            runCatching { scene?.cancelPointerInput() }
        }
        if (hasPointer) {
            hasPointer = false
            runCatching { scene?.sendPointerEvent(PointerEventType.Exit, lastPointer) }
            ComposeSceneContextImpl.resetPointerIcon()
        }
    }

    override fun consumeButton(button: Int, pressed: Boolean): Boolean = route(button, pressed)

    private fun route(button: Int, pressed: Boolean): Boolean {
        if (!shown) return false
        val scene = handle?.scene ?: return false
        val pointer = HudInput.pointerButtonOf(button) ?: return false
        val bit = 1 shl pointer.index
        val p = pointerPosition()
        if (!pressed) {
            if (buttons and bit == 0) return false
            buttons = buttons and bit.inv()
            return deliver(scene, PointerEventType.Release, p, pointer)
        }
        if (buttons and bit != 0) return true
        if (!cursorFree() || !inside(p)) return false
        buttons = buttons or bit
        lastPointer = p
        hasPointer = true
        deliver(scene, PointerEventType.Move, p, null)
        return deliver(scene, PointerEventType.Press, p, pointer)
    }

    private fun deliver(scene: ComposeScene, type: PointerEventType, p: Offset, button: PointerButton?): Boolean {
        try {
            host().within { scene.sendPointerEvent(type, p, button = button) }
        } catch (t: Throwable) {
            fail(t)
        }
        dirty = true
        return true
    }

    private companion object {
        val LOGGER = LogManager.getLogger("PolyCompose/Profiler")
        const val MIN_RATIO = 0.01f
        const val MAX_FAILURES = 4
        const val RETRY_NANOS = 1_000_000_000L
        const val MAX_BACKOFF_SHIFT = 5

        fun pointerPosition(): Offset {
            //? if > 1.8.9 {
            val mouse = Minecraft.getInstance().mouseHandler
            return Offset(mouse.xpos().toFloat(), mouse.ypos().toFloat())
            //?} else {
            /*val pixelRatio = McWindow.pixelRatio()
            return Offset(Mouse.getX() / pixelRatio, (McWindow.viewportHeight() - Mouse.getY() - 1) / pixelRatio)
            *///?}
        }
    }
}
