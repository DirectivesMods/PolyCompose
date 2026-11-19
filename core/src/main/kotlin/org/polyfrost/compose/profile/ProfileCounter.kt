package org.polyfrost.compose.profile

public enum class ProfileCounter(public val label: String) {
    DrawOps("drawOps"),
    Batches("batches"),
    Vertices("vertices"),
    TextureUploads("textureUploads"),
    TextureUploadBytes("textureUploadBytes"),
    LayerRecords("layerRecords"),
    Recompositions("recompositions"),
}

internal val COUNTERS: Array<ProfileCounter> = ProfileCounter.entries.toTypedArray()
internal val COUNTER_COUNT: Int = COUNTERS.size
internal val HOST_WIDTH: Int = ProfileHost.PHASES + COUNTER_COUNT
