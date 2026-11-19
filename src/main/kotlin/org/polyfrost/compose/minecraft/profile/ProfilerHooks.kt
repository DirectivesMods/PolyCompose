package org.polyfrost.compose.minecraft.profile

import java.util.WeakHashMap
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.profile.ui.ProfilerUi
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileEnvironment
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilerSettings

//? if >= 1.21.11
import org.polyfrost.compose.profile.GpuTiming

object ProfilerHooks {
    @JvmField
    var showOverlay: Boolean = System.getProperty("polycompose.profiler.overlay") != "false"

    internal var effectTimingLabel: String? = null
        set(value) {
            field = value
            if (value == null) {
                ProfileEnvironment.remove(ProfileEnvironment.EFFECT_GPU_TIMING_KEY)
            } else {
                ProfileEnvironment.put(ProfileEnvironment.EFFECT_GPU_TIMING_KEY, if (value == "timestamp queries") "available" else value)
            }
        }

    private val overlay = ProfilerOverlay()
    private val overlayHosts = WeakHashMap<OverlayRenderer, ProfileHost>()
    private var installed = false
    private var overlayShown = false
    private var lastFrameEnd = 0L

    @JvmStatic
    fun install() {
        if (installed) return
        installed = true
        //? if >= 1.21.11 {
        FrameProfiler.gpuTiming = GpuTiming.Unavailable
        effectTimingLabel = "pending"
        //?}
        ProfilerControl.install()
        ProfilerUi.install()
        ProfilerEnvironment.install()
        //? if = 1.8.9
        //ProfilerKeybind.register()
        HostEvents.onClientTick { ProfilerKeybind.poll() }
        HostEvents.onFrameEnd { endFrame() }
    }

    private fun endFrame() {
        val enabled = FrameProfiler.enabled
        ProfilerUi.sync(enabled)
        val show = enabled && showOverlay && ProfilerSettings.overlayVisible && ProfilerControl.textOverlay
        if (show != overlayShown) {
            overlayShown = show
            if (show) ComposeHost.overlays += overlay else ComposeHost.overlays -= overlay
        }
        //? if >= 1.21.11
        EffectGpuTimer.endFrame(enabled)
        if (!enabled) {
            lastFrameEnd = 0L
            return
        }
        val now = System.nanoTime()
        if (lastFrameEnd != 0L) FrameProfiler.frameTime(now - lastFrameEnd)
        lastFrameEnd = now
        ProfilerEnvironment.fillGpu()
        ProfilerEnvironment.fillFrame()
        GlGpuTimer.poll()
        FrameProfiler.endFrame()
    }

    internal fun overlayHost(overlay: OverlayRenderer): ProfileHost? {
        if (!FrameProfiler.enabled || overlay === this.overlay) return null
        return overlayHosts.getOrPut(overlay) { FrameProfiler.host("overlay/" + simpleName(overlay.javaClass)) }
    }

    internal fun simpleName(type: Class<*>): String = type.name.substringAfterLast('.')
}
