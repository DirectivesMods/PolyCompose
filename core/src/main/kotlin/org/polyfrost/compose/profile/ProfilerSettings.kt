package org.polyfrost.compose.profile

import org.apache.logging.log4j.LogManager

public object ProfilerSettings {
    private val LOGGER = LogManager.getLogger("PolyCompose/Profiler")

    @JvmField
    public var overlayVisible: Boolean = false

    @JvmField
    public var overlayExpanded: Boolean = false

    @JvmField
    public var overlayX: Float = -1f

    @JvmField
    public var overlayY: Float = -1f

    @JvmField
    public var graphs: Boolean = true

    @JvmField
    public var highlighter: Boolean = false

    @JvmField
    public var recompositions: Boolean = true

    @JvmField
    public var spikes: Boolean = false

    @JvmField
    public var spikeThresholdMs: Double = ProfilerSpikes.DEFAULT_THRESHOLD_MS

    @JvmField
    public var historyFrames: Int = FrameProfiler.DEFAULT_HISTORY_FRAMES

    @JvmField
    public var loadHook: (() -> String?)? = null

    @JvmField
    public var saveHook: ((String) -> Unit)? = null

    @JvmStatic
    public fun apply() {
        FrameProfiler.historyFrames = historyFrames
        FrameProfiler.trackRecompositions = recompositions
        ProfilerSpikes.enabled = spikes
        ProfilerSpikes.thresholdMs = spikeThresholdMs
    }

    @JvmStatic
    public fun load(): Boolean {
        val hook = loadHook ?: return false
        val text = runCatching(hook).onFailure { LOGGER.warn("Failed to read profiler settings", it) }.getOrNull() ?: return false
        if (!read(text)) return false
        apply()
        return true
    }

    @JvmStatic
    public fun save(): Boolean {
        val hook = saveHook ?: return false
        return runCatching { hook(toJson()) }.onFailure { LOGGER.warn("Failed to write profiler settings", it) }.isSuccess
    }

    @JvmStatic
    public fun toJson(): String = buildString {
        append("{\n")
        entry("overlayVisible", overlayVisible.toString())
        entry("overlayExpanded", overlayExpanded.toString())
        entry("overlayX", overlayX.toString())
        entry("overlayY", overlayY.toString())
        entry("graphs", graphs.toString())
        entry("highlighter", highlighter.toString())
        entry("recompositions", recompositions.toString())
        entry("spikes", spikes.toString())
        entry("spikeThresholdMs", spikeThresholdMs.toString())
        entry("historyFrames", historyFrames.toString(), last = true)
        append("}\n")
    }

    @JvmStatic
    public fun read(json: String): Boolean {
        val values = parseFlat(json) ?: run {
            LOGGER.warn("Ignoring malformed profiler settings")
            return false
        }
        values["overlayVisible"]?.toBooleanStrictOrNull()?.let { overlayVisible = it }
        values["overlayExpanded"]?.toBooleanStrictOrNull()?.let { overlayExpanded = it }
        values["overlayX"]?.toFloatOrNull()?.let { overlayX = it }
        values["overlayY"]?.toFloatOrNull()?.let { overlayY = it }
        values["graphs"]?.toBooleanStrictOrNull()?.let { graphs = it }
        values["highlighter"]?.toBooleanStrictOrNull()?.let { highlighter = it }
        values["recompositions"]?.toBooleanStrictOrNull()?.let { recompositions = it }
        values["spikes"]?.toBooleanStrictOrNull()?.let { spikes = it }
        values["spikeThresholdMs"]?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { spikeThresholdMs = it }
        values["historyFrames"]?.toIntOrNull()?.let { historyFrames = it.coerceIn(FrameProfiler.MIN_HISTORY_FRAMES, FrameProfiler.MAX_HISTORY_FRAMES) }
        return true
    }

    private fun StringBuilder.entry(key: String, value: String, last: Boolean = false) {
        append("  \"").append(key).append("\": ").append(value)
        append(if (last) "\n" else ",\n")
    }

    private fun parseFlat(json: String): Map<String, String>? {
        val body = json.trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val out = HashMap<String, String>()
        for (part in body.substring(1, body.length - 1).split(',')) {
            if (part.isBlank()) continue
            val colon = part.indexOf(':')
            if (colon < 0) return null
            val key = part.substring(0, colon).trim().removeSurrounding("\"")
            out[key] = part.substring(colon + 1).trim().removeSurrounding("\"")
        }
        return out
    }
}
