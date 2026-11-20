package org.polyfrost.compose.minecraft.profile.ui

import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.minecraft.profile.ProfilerHooks
import org.polyfrost.compose.minecraft.screen.ComposeSupport
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfilerSettings

internal object ProfilerUi {
    val OVERLAY_KEY: String = "overlay/" + ProfilerHooks.simpleName(ProfilerComposeOverlay::class.java)
    private val HIGHLIGHTER_KEY: String = "overlay/" + ProfilerHooks.simpleName(RedrawHighlighter::class.java)
    private val SCREEN_KEY: String = "screen/" + ProfilerHooks.simpleName(ProfilerScreen::class.java)

    val sampler = LiveSampler()

    private var overlay: ProfilerComposeOverlay? = null
    private val highlighter = RedrawHighlighter()
    private var installed = false
    private var available: Boolean? = null

    @JvmStatic
    fun install() {
        if (installed) return
        installed = true
        FrameProfiler.ignore(OVERLAY_KEY)
        FrameProfiler.ignore(HIGHLIGHTER_KEY)
        FrameProfiler.ignore(SCREEN_KEY)
        ProfilerControl.openScreen = ::openScreen
    }

    @JvmStatic
    fun openScreen() {
        if (!composeAvailable()) return
        McScreens.display(ProfilerScreen())
    }

    @JvmStatic
    fun sync(enabled: Boolean) {
        ProfilerControl.textOverlay = !syncOverlays(enabled)
    }

    private fun syncOverlays(enabled: Boolean): Boolean {
        val wantOverlay = enabled && ProfilerSettings.overlayVisible
        val wantHighlighter = enabled && ProfilerSettings.highlighter
        val current = overlay
        if (!wantOverlay && !wantHighlighter && current == null && !highlighter.shown) return available != false
        if (!composeAvailable()) {
            if (current != null) hideOverlay(current)
            setHighlighter(false)
            return false
        }
        val showOverlay = wantOverlay && McScreens.current() !is ProfilerScreen
        if (showOverlay && current == null) showOverlay() else if (!showOverlay && current != null) hideOverlay(current)
        setHighlighter(wantHighlighter)
        val live = overlay ?: return true
        if (!live.failed) return true
        hideOverlay(live)
        return false
    }

    private fun composeAvailable(): Boolean = available ?: ComposeSupport.isAvailable.also { available = it }

    private fun showOverlay() {
        val created = ProfilerComposeOverlay(sampler)
        created.shown = true
        overlay = created
        ComposeHost.overlays += created
        ComposeHost.gameInput += created
    }

    private fun hideOverlay(current: ProfilerComposeOverlay) {
        current.shown = false
        overlay = null
        ComposeHost.overlays -= current
        ComposeHost.gameInput -= current
        current.release()
        if (current.failed) available = false
    }

    private fun setHighlighter(show: Boolean) {
        if (show == highlighter.shown) return
        highlighter.shown = show
        if (show) ComposeHost.overlays += highlighter else ComposeHost.overlays -= highlighter
    }
}
