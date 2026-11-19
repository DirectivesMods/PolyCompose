package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL20
import org.lwjgl.opengl.KHRParallelShaderCompile

internal class GlShaderJob(private val what: String, vs: String, fs: String, vararg attributes: String) {
    private val program = GL20.glCreateProgram()
    private val vertex = GL20.glCreateShader(GL20.GL_VERTEX_SHADER)
    private val fragment = GL20.glCreateShader(GL20.GL_FRAGMENT_SHADER)

    init {
        GL20.glShaderSource(vertex, vs)
        GL20.glCompileShader(vertex)
        GL20.glShaderSource(fragment, fs)
        GL20.glCompileShader(fragment)
        GL20.glAttachShader(program, vertex)
        GL20.glAttachShader(program, fragment)
        for (i in attributes.indices) GL20.glBindAttribLocation(program, i, attributes[i])
        GL20.glLinkProgram(program)
    }

    val ready: Boolean get() = !parallel || GL20.glGetProgrami(program, KHRParallelShaderCompile.GL_COMPLETION_STATUS_KHR) != 0

    fun finish(): Int {
        try {
            check(GL20.glGetShaderi(vertex, GL20.GL_COMPILE_STATUS) != 0) { "PolyCompose $what shader: " + GL20.glGetShaderInfoLog(vertex, LOG_LENGTH) }
            check(GL20.glGetShaderi(fragment, GL20.GL_COMPILE_STATUS) != 0) { "PolyCompose $what shader: " + GL20.glGetShaderInfoLog(fragment, LOG_LENGTH) }
            check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != 0) { "PolyCompose $what program: " + GL20.glGetProgramInfoLog(program, LOG_LENGTH) }
        } catch (e: IllegalStateException) {
            GL20.glDeleteProgram(program)
            GL20.glDeleteShader(vertex)
            GL20.glDeleteShader(fragment)
            throw e
        }
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)
        return program
    }

    private companion object {
        const val LOG_LENGTH = 4096

        val parallel: Boolean by lazy {
            val caps = GL.getCapabilities()
            caps.GL_KHR_parallel_shader_compile || caps.GL_ARB_parallel_shader_compile
        }
    }
}
