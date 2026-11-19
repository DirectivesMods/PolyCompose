package org.polyfrost.compose.profile

public enum class ProfilePhase {
    Compose,
    Layout,
    Record,
    Upload,
    Gpu,
}

public enum class GpuTiming {
    Unknown,
    Available,
    Unavailable,
}

public data class PhaseStats(
    val samples: Int,
    val p50Nanos: Long,
    val p95Nanos: Long,
    val p99Nanos: Long,
    val maxNanos: Long,
) {
    public companion object {
        @JvmField
        public val Empty: PhaseStats = PhaseStats(0, 0L, 0L, 0L, 0L)
    }
}

public data class ProfileSnapshot(
    val key: String,
    val frames: Int,
    val framesSinceActive: Long,
    val compose: PhaseStats,
    val layout: PhaseStats,
    val record: PhaseStats,
    val upload: PhaseStats,
    val gpu: PhaseStats?,
    val gpuTiming: GpuTiming,
    val total: PhaseStats,
) {
    public fun phase(phase: ProfilePhase): PhaseStats? = when (phase) {
        ProfilePhase.Compose -> compose
        ProfilePhase.Layout -> layout
        ProfilePhase.Record -> record
        ProfilePhase.Upload -> upload
        ProfilePhase.Gpu -> gpu
    }
}
