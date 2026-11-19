package org.polyfrost.compose.host

public interface HostSettings {
    public val animationScale: Float get() = 1f
    public val reducedMotion: Boolean get() = false
    public val registerProfilerKey: Boolean get() = true
    public fun framerateLimit(): Int? = null
    public val messages: HostMessages get() = HostMessages.Default

    public object Default : HostSettings
}

public interface HostMessages {
    public fun unavailable(reason: String): String = reason
    public fun disabledByFlag(property: String): String = "The UI is disabled by the -D$property JVM flag."
    public val failedToStart: String get() = "The UI failed to start. Check your logs and report this."
    public val renderError: String get() = "The UI hit a rendering error and had to close."
    public fun gpuAllocFailed(width: Int, height: Int): String = "Couldn't allocate GPU memory for the UI (${width}x$height)."
    public val vulkanModUnsupported: String get() = "The UI can't render while VulkanMod is installed."
    public val awtMissing: String get() = "The UI can't start: this Java runtime has no working AWT support."

    public object Default : HostMessages
}
