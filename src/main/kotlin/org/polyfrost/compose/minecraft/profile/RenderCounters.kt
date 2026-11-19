package org.polyfrost.compose.minecraft.profile

import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileCounter

@Suppress("NOTHING_TO_INLINE")
internal object RenderCounters {
    inline fun op() {
        if (FrameProfiler.enabled) FrameProfiler.count(ProfileCounter.DrawOps, 1L)
    }

    inline fun batch(vertices: Int) {
        if (!FrameProfiler.enabled) return
        FrameProfiler.count(ProfileCounter.Batches, 1L)
        FrameProfiler.count(ProfileCounter.Vertices, vertices.toLong())
    }

    inline fun upload(bytes: Long) {
        if (!FrameProfiler.enabled) return
        FrameProfiler.count(ProfileCounter.TextureUploads, 1L)
        FrameProfiler.count(ProfileCounter.TextureUploadBytes, bytes)
    }
}
