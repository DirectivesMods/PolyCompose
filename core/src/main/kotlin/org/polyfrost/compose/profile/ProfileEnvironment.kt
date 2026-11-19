package org.polyfrost.compose.profile

public object ProfileEnvironment {
    public const val EFFECT_GPU_TIMING_KEY: String = "effectGpuTiming"

    private val values = LinkedHashMap<String, String>()

    init {
        values["polycompose.core"] = ProfileEnvironment::class.java.`package`?.implementationVersion ?: "dev"
        for (key in listOf("java.version", "java.vendor", "os.name", "os.arch")) {
            System.getProperty(key)?.let { values[key] = it }
        }
    }

    @JvmStatic
    public fun put(key: String, value: String) {
        synchronized(values) { values[key] = value }
    }

    @JvmStatic
    public fun remove(key: String) {
        synchronized(values) { values -= key }
    }

    @JvmStatic
    public fun snapshot(): Map<String, String> = synchronized(values) {
        val out = LinkedHashMap(values)
        out["cpu.cores"] = Runtime.getRuntime().availableProcessors().toString()
        out["gpuTiming"] = FrameProfiler.gpuTiming.name
        out
    }
}
