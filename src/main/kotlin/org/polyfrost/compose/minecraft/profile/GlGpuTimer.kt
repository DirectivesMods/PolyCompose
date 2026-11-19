package org.polyfrost.compose.minecraft.profile

import org.lwjgl.opengl.ARBTimerQuery
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL33
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.GpuTiming
import org.polyfrost.compose.profile.ProfileHost

internal object GlGpuTimer {
    private const val SLOTS = 64

    private val ids = IntArray(SLOTS)
    private val hosts = arrayOfNulls<ProfileHost>(SLOTS)
    private val frames = LongArray(SLOTS)
    private var head = 0L
    private var tail = 0L
    private var open = -1
    private var support = 0
    private var core = false

    fun begin(host: ProfileHost): Int {
        if (open >= 0 || head - tail >= SLOTS || !supported()) return -1
        if (GL15.glGetQueryi(GL33.GL_TIME_ELAPSED, GL15.GL_CURRENT_QUERY) != 0) return -1
        val slot = (head % SLOTS).toInt()
        if (ids[slot] == 0) ids[slot] = GL15.glGenQueries()
        GL15.glBeginQuery(GL33.GL_TIME_ELAPSED, ids[slot])
        hosts[slot] = host
        frames[slot] = host.frame
        head++
        open = slot
        return slot
    }

    fun end(slot: Int) {
        if (slot < 0 || slot != open) return
        GL15.glEndQuery(GL33.GL_TIME_ELAPSED)
        open = -1
    }

    fun poll() {
        while (tail < head) {
            val slot = (tail % SLOTS).toInt()
            if (slot == open) return
            val id = ids[slot]
            if (GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) return
            val nanos = if (core) GL33.glGetQueryObjecti64(id, GL15.GL_QUERY_RESULT)
            else ARBTimerQuery.glGetQueryObjecti64(id, GL15.GL_QUERY_RESULT)
            hosts[slot]?.gpu(frames[slot], nanos)
            hosts[slot] = null
            tail++
        }
    }

    private fun supported(): Boolean {
        if (support == 0) {
            val caps = runCatching { GL.getCapabilities() }.getOrNull()
            core = caps?.OpenGL33 == true
            val available = core || caps?.GL_ARB_timer_query == true
            support = if (available) 1 else -1
            FrameProfiler.gpuTiming = if (available) GpuTiming.Available else GpuTiming.Unavailable
        }
        return support > 0
    }
}
