@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.screen

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineExceptionHandler
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.backend.PolyCompose
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.minecraft.platform.RenderThreadDispatcher

internal object SceneMotionScale : MotionDurationScale {
    override val scaleFactor: Float get() = ComposeHost.settings.animationScale
}

internal class SceneHandle private constructor(
    val scene: ComposeScene,
    val recomposer: FrameRecomposer,
    private val failure: AtomicReference<Throwable?>,
) : AutoCloseable {
    private var appliedW = -1
    private var appliedH = -1
    private var appliedDensity = 0f

    fun rethrow() {
        failure.get()?.let { throw it }
    }

    fun resize(w: Int, h: Int, density: Float): Boolean {
        if (w == appliedW && h == appliedH && density == appliedDensity) return false
        scene.density = Density(density)
        scene.size = IntSize(w, h)
        appliedW = w
        appliedH = h
        appliedDensity = density
        return true
    }

    override fun close() {
        try {
            scene.close()
        } catch (t: Throwable) {
            LOGGER.debug("Ignoring failure while closing a Compose scene", t)
        }
        try {
            recomposer.close()
        } catch (t: Throwable) {
            LOGGER.debug("Ignoring failure while closing a Compose recomposer", t)
        }
    }

    companion object {
        private val LOGGER = LogManager.getLogger("PolyCompose/Scene")

        fun create(
            platformContext: PlatformContext,
            invalidate: () -> Unit,
            onRecompose: () -> Unit,
        ): SceneHandle {
            PolyCompose.install()
            val failure = AtomicReference<Throwable?>()
            val handler = CoroutineExceptionHandler { _, t -> failure.compareAndSet(null, t) }
            val recomposer = FrameRecomposer(RenderThreadDispatcher + handler + SceneMotionScale, onRecompose)
            val scene = try {
                CanvasLayersComposeScene(
                    frameRecomposer = recomposer,
                    platformContext = platformContext,
                    invalidateLayout = invalidate,
                    invalidateDraw = invalidate,
                )
            } catch (t: Throwable) {
                runCatching { recomposer.close() }.exceptionOrNull()?.let(t::addSuppressed)
                throw t
            }
            return SceneHandle(scene, recomposer, failure)
        }
    }
}
