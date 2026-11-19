package org.polyfrost.compose.minecraft.profile

import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import net.fabricmc.loader.api.FabricLoader
import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCapture
import org.polyfrost.compose.profile.ProfileExport
import org.polyfrost.compose.profile.ProfileSpike
import org.polyfrost.compose.profile.ProfilerCapture
import org.polyfrost.compose.profile.ProfilerSettings
import org.polyfrost.compose.profile.ProfilerSpikes
import org.slf4j.LoggerFactory

internal object ProfilerControl {
    private val LOG = LoggerFactory.getLogger("PolyCompose/Profiler")
    private val UNSAFE_LABEL = Regex("[^A-Za-z0-9._-]+")
    private const val SPIKE_EXPORT_INTERVAL_NANOS = 10_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000.0

    @JvmField
    var openScreen: (() -> Unit)? = null

    @JvmField
    var textOverlay: Boolean = true

    private var manual = false
    private var exportOnFinish = false
    private var lastSpikeExport = 0L
    private val pendingSettings = AtomicReference<String?>()
    private val settingsLock = Any()

    val profilesDir: Path
        get() = FabricLoader.getInstance().gameDir.resolve("polycompose").resolve("profiles")

    private val settingsFile: Path
        get() = FabricLoader.getInstance().configDir.resolve("polycompose-profiler.json")

    fun install() {
        val fromProperty = FrameProfiler.enabled
        ProfilerSettings.loadHook = ::readSettings
        ProfilerSettings.saveHook = ::writeSettings
        ProfilerSettings.load()
        if (fromProperty) {
            manual = true
            ProfilerSettings.overlayVisible = true
        }
        refreshEnabled()
        ProfilerCapture.addListener(::captureFinished)
        ProfilerSpikes.addListener(::spikeCaptured)
    }

    fun toggleOverlay(): Boolean {
        val visible = !ProfilerSettings.overlayVisible
        ProfilerSettings.overlayVisible = visible
        refreshEnabled()
        ProfilerSettings.save()
        return visible
    }

    fun setEnabled(on: Boolean) {
        manual = on
        if (!on) {
            ProfilerSettings.overlayVisible = false
            ProfilerSettings.spikes = false
            ProfilerSettings.highlighter = false
            ProfilerSettings.apply()
        }
        refreshEnabled()
        ProfilerSettings.save()
    }

    fun setSpikes(on: Boolean, thresholdMs: Double? = null) {
        ProfilerSettings.spikes = on
        if (thresholdMs != null) ProfilerSettings.spikeThresholdMs = thresholdMs
        ProfilerSettings.apply()
        refreshEnabled()
        ProfilerSettings.save()
    }

    fun refreshEnabled() {
        FrameProfiler.enabled = manual || ProfilerSettings.overlayVisible || ProfilerSettings.spikes || ProfilerSettings.highlighter || ProfilerCapture.recording
    }

    fun reset() {
        FrameProfiler.reset()
        ProfilerSpikes.clear()
        ProfilerCapture.clear()
    }

    fun startCapture(seconds: Double = 0.0): Boolean {
        if (!ProfilerCapture.start(seconds)) return false
        exportOnFinish = seconds > 0.0
        return true
    }

    fun stopCapture(): Boolean {
        if (!ProfilerCapture.requestStop()) return false
        exportOnFinish = true
        ProfilerChat.success("polycompose.profiler.capture.stopping")
        return true
    }

    fun exportLatest(): Boolean {
        if (ProfilerCapture.recording) return stopCapture()
        val capture = ProfilerCapture.list().lastOrNull() ?: if (FrameProfiler.enabled) FrameProfiler.history() else null
        if (capture == null || capture.frames.isEmpty()) return false
        exportCapture(capture)
        return true
    }

    @JvmOverloads
    fun exportCapture(capture: ProfileCapture, announce: Boolean = true, onDone: ((Path?) -> Unit)? = null) {
        val dir = profilesDir
        val base = fileBase(capture)
        BackgroundTasks.submit {
            val trace = write(dir, base, capture)
            RenderThread.execute {
                if (announce) {
                    if (trace != null) ProfilerChat.exported(trace) else ProfilerChat.error("polycompose.profiler.export.failed")
                }
                onDone?.invoke(trace)
            }
        }
    }

    private fun captureFinished(capture: ProfileCapture) {
        refreshEnabled()
        if (!exportOnFinish) return
        exportOnFinish = false
        exportCapture(capture)
    }

    private fun spikeCaptured(spike: ProfileSpike) {
        if (!ProfilerSettings.spikes) return
        val now = System.nanoTime()
        if (lastSpikeExport != 0L && now - lastSpikeExport < SPIKE_EXPORT_INTERVAL_NANOS) return
        lastSpikeExport = now
        val millis = String.format(Locale.ROOT, "%.1f", maxOf(spike.hostNanos, spike.frameNanos) / NANOS_PER_MILLI)
        val c = spike.culprit
        val culprit = listOfNotNull(c.host, c.phase?.name?.lowercase(Locale.ROOT)).ifEmpty { listOfNotNull(c.gpuCategory) }.joinToString(" ").ifEmpty { "-" }
        exportCapture(spike.capture, announce = false) { trace ->
            if (trace != null) ProfilerChat.spike(millis, culprit, trace)
        }
    }

    private fun write(dir: Path, base: String, capture: ProfileCapture): Path? = try {
        Files.createDirectories(dir)
        val (name, traceOut) = reserveTrace(dir, base)
        val trace = dir.resolve("$name.trace.json")
        traceOut.use { ProfileExport.chromeTrace(capture, it) }
        Files.newOutputStream(dir.resolve("$name.summary.txt")).use { ProfileExport.summaryText(capture, it) }
        trace
    } catch (e: IOException) {
        LOG.warn("Failed to export profiler capture {}", capture.label, e)
        null
    }

    private fun reserveTrace(dir: Path, base: String): Pair<String, OutputStream> {
        var name = base
        var n = 1
        while (true) {
            try {
                return name to Files.newOutputStream(dir.resolve("$name.trace.json"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            } catch (_: FileAlreadyExistsException) {
                name = base + "-" + ++n
            }
        }
    }

    private fun fileBase(capture: ProfileCapture): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.ROOT).format(Date(capture.startedAtEpochMillis))
        val label = capture.label.replace(UNSAFE_LABEL, "_").trim('_').ifEmpty { "capture" }
        return "$stamp-$label"
    }

    private fun readSettings(): String? {
        val file = settingsFile
        return if (Files.isRegularFile(file)) String(Files.readAllBytes(file), StandardCharsets.UTF_8) else null
    }

    private fun writeSettings(json: String) {
        if (pendingSettings.getAndSet(json) != null) return
        BackgroundTasks.submit {
            synchronized(settingsLock) {
                val latest = pendingSettings.getAndSet(null) ?: return@synchronized
                try {
                    val file = settingsFile
                    Files.createDirectories(file.parent)
                    Files.write(file, latest.toByteArray(StandardCharsets.UTF_8))
                } catch (e: IOException) {
                    LOG.warn("Failed to save profiler settings", e)
                }
            }
        }
    }
}
