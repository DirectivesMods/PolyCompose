@file:OptIn(InternalComposeTracingApi::class)

package org.polyfrost.compose.profile

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi

internal class RecompositionTracer(private val owner: Thread) : CompositionTracer {
    @Volatile
    var installed = false
        private set

    var unattributed = 0L

    override fun isTraceInProgress(): Boolean {
        if (FrameProfiler.enabled && FrameProfiler.trackRecompositions) return Thread.currentThread() === owner
        if (installed) uninstall()
        return false
    }

    override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
        if (Thread.currentThread() !== owner) return
        val host = FrameProfiler.active
        if (host == null) {
            unattributed++
            return
        }
        if (host.ignored) return
        val entry = host.names[info] ?: NameCount(FrameProfiler.nameId(info)).also { host.names[info] = it }
        if (entry.count++ == 0) host.touchedNames += entry
        host.count(RECOMPOSITIONS, 1L)
    }

    override fun traceEventEnd() = Unit

    fun install() {
        Composer.setTracer(this)
        installed = true
    }

    fun uninstall() {
        installed = false
        Composer.setTracer(null)
    }

    private companion object {
        val RECOMPOSITIONS = ProfileCounter.Recompositions.ordinal
    }
}
