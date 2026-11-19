package org.polyfrost.compose.minecraft.warmup

internal enum class WarmupProfile(
    val startDelayMillis: Long,
    val maxClasses: Int,
    val yieldEvery: Int,
    val glyphSizes: FloatArray,
    val allSubpixelPhases: Boolean,
) {
    LOW(5000L, 2500, 64, floatArrayOf(12f, 14f, 16f), false),
    MEDIUM(2000L, 4000, 256, floatArrayOf(12f, 14f, 16f, 18f), true),
    HIGH(1000L, Int.MAX_VALUE, 0, floatArrayOf(12f, 14f, 16f, 18f, 20f, 24f), true),
    ;

    companion object {
        private const val BYTES_PER_MIB = 1024L * 1024L
        private const val LOW_HEAP_MIB = 1280L
        private const val MEDIUM_HEAP_MIB = 2560L
        private const val LOW_CORES = 2
        private const val MEDIUM_CORES = 4

        val current: WarmupProfile by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val cores = Runtime.getRuntime().availableProcessors()
            val heapMib = Runtime.getRuntime().maxMemory() / BYTES_PER_MIB
            when {
                cores <= LOW_CORES || heapMib < LOW_HEAP_MIB -> LOW
                cores <= MEDIUM_CORES || heapMib < MEDIUM_HEAP_MIB -> MEDIUM
                else -> HIGH
            }
        }
    }
}
