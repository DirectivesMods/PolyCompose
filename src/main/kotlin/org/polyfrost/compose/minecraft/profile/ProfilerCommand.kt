package org.polyfrost.compose.minecraft.profile

import java.util.Locale
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.profile.ProfilerCapture
import org.polyfrost.compose.profile.ProfilerSettings

internal object ProfilerCommand {
    private const val ROOT = "polycompose"
    private const val SUB = "profiler"
    private const val SCREEN_DELAY_FRAMES = 2
    private val WHITESPACE = Regex("\\s+")

    @JvmStatic
    fun handle(command: String): Boolean {
        val args = command.trim().split(WHITESPACE)
        if (!args[0].equals(ROOT, ignoreCase = true)) return false
        if (args.size < 2 || !args[1].equals(SUB, ignoreCase = true)) return false
        val rest = args.subList(2, args.size)
        run(rest.firstOrNull()?.lowercase(Locale.ROOT), rest.drop(1))
        return true
    }

    private fun run(action: String?, args: List<String>) {
        when (action) {
            null -> open()
            "on" -> {
                ProfilerControl.setEnabled(true)
                ProfilerChat.success("polycompose.profiler.on")
            }
            "off" -> {
                ProfilerControl.setEnabled(false)
                ProfilerChat.info("polycompose.profiler.off")
            }
            "capture" -> capture(args)
            "stop" -> if (!ProfilerControl.stopCapture()) ProfilerChat.error("polycompose.profiler.capture.none")
            "export" -> if (!ProfilerControl.exportLatest()) ProfilerChat.error("polycompose.profiler.export.empty")
            "spikes" -> spikes(args)
            "reset" -> {
                ProfilerControl.reset()
                ProfilerChat.info("polycompose.profiler.reset")
            }
            else -> usage()
        }
    }

    private fun open() {
        val screen = ProfilerControl.openScreen
        if (screen == null) {
            usage()
            return
        }
        HostEvents.afterFrames(SCREEN_DELAY_FRAMES) { screen() }
    }

    private fun capture(args: List<String>) {
        val raw = args.firstOrNull()
        val seconds = if (raw == null) 0.0 else raw.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return invalid(raw)
        if (ProfilerCapture.recording) return ProfilerChat.error("polycompose.profiler.capture.busy")
        if (!ProfilerControl.startCapture(seconds)) return ProfilerChat.error("polycompose.profiler.capture.busy")
        if (seconds > 0.0) ProfilerChat.success("polycompose.profiler.capture.timed", format(seconds))
        else ProfilerChat.success("polycompose.profiler.capture.started")
    }

    private fun spikes(args: List<String>) {
        val on = when (args.firstOrNull()?.lowercase(Locale.ROOT)) {
            "on" -> true
            "off" -> false
            else -> return usage()
        }
        val raw = args.getOrNull(1)
        val threshold = if (raw == null) null else raw.toDoubleOrNull()?.takeIf { it > 0.0 } ?: return invalid(raw)
        ProfilerControl.setSpikes(on, threshold)
        if (on) ProfilerChat.success("polycompose.profiler.spikes.on", format(ProfilerSettings.spikeThresholdMs))
        else ProfilerChat.info("polycompose.profiler.spikes.off")
    }

    private fun invalid(raw: String) {
        ProfilerChat.error("polycompose.profiler.invalid", raw)
    }

    private fun usage() {
        ProfilerChat.info("polycompose.profiler.usage")
    }

    private fun format(value: Double): String =
        if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.ROOT, "%.1f", value)
}
