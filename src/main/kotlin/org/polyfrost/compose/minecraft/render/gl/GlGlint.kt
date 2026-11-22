package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL13
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GL20
import org.polyfrost.compose.minecraft.mc.McItemGlint
import org.polyfrost.compose.minecraft.mc.McResources
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.render.PolyBitmap
import org.slf4j.LoggerFactory

internal object GlGlint {
    class Key(val atlas: Int, val cells: Int)

    private val LOG = LoggerFactory.getLogger(GlGlint::class.java)
    private const val MAX_KEYS = 32

    private val keys = HashMap<Long, Key>()
    private var program = 0
    private var job: GlShaderJob? = null
    private var failed = false
    private var uScale = 0
    private var uOffset = 0
    private var uCells = 0
    private var uA0 = 0
    private var uA1 = 0
    private var uB = 0
    private var uTint0 = 0
    private var uTint1 = 0
    private var bitmap: PolyBitmap? = null
    private var loaded = false
    private var previousUnit1 = 0

    val usable: Boolean get() = !failed

    init {
        HostEvents.onResourcesReloaded {
            loaded = false
            bitmap = null
        }
    }

    fun key(atlas: Int, cells: Int): Key {
        val id = (atlas.toLong() shl 32) or cells.toLong()
        keys[id]?.let { return it }
        if (keys.size >= MAX_KEYS) keys.clear()
        return Key(atlas, cells).also { keys[id] = it }
    }

    fun any(batches: List<GlProgram.Batch>): Boolean {
        for (i in batches.indices) if (batches[i].texture is Key) return true
        return false
    }

    fun anySegment(segments: List<GlProgram.Segment>): Boolean {
        for (i in segments.indices) if (any(segments[i].batches)) return true
        return false
    }

    fun warm(): Boolean {
        if (program != 0 || failed) return true
        val pending = job
        if (pending == null) {
            job = runCatching(::start).onFailure(::fail).getOrNull()
            return job == null
        }
        if (!pending.ready) return false
        val previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            ensure()
        } finally {
            GL20.glUseProgram(previous)
        }
        return true
    }

    private fun start(): GlShaderJob {
        val core = GlProgram.isCoreGlsl(GlProgram.glslVersion())
        return GlShaderJob("glint", if (core) VS_150 else VS_120, if (core) FS_150 else FS_120, "aPos", "aUv", "aColor")
    }

    @Suppress("TooGenericExceptionCaught")
    private fun ensure(): Boolean {
        if (program != 0) return true
        if (failed) return false
        try {
            val pending = job ?: start()
            job = null
            val p = pending.finish()
            uScale = GL20.glGetUniformLocation(p, "uScale")
            uOffset = GL20.glGetUniformLocation(p, "uOffset")
            uCells = GL20.glGetUniformLocation(p, "uCells")
            uA0 = GL20.glGetUniformLocation(p, "uA0")
            uA1 = GL20.glGetUniformLocation(p, "uA1")
            uB = GL20.glGetUniformLocation(p, "uB")
            uTint0 = GL20.glGetUniformLocation(p, "uTint0")
            uTint1 = GL20.glGetUniformLocation(p, "uTint1")
            GL20.glUseProgram(p)
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uAtlas"), 0)
            GL20.glUniform1i(GL20.glGetUniformLocation(p, "uGlint"), 1)
            program = p
            return true
        } catch (e: Exception) {
            fail(e)
            return false
        }
    }

    private fun fail(e: Throwable) {
        if (failed) return
        failed = true
        LOG.warn("PolyCompose item glint shader is unavailable; enchanted item icons fall back to baked glint", e)
    }

    private fun glintTexture(): Int {
        if (!loaded) {
            loaded = true
            bitmap = McResources.image(McResources.DEFAULT_NAMESPACE, McItemGlint.TEXTURE)
        }
        val b = bitmap ?: return 0
        return GlTextures.id(GlTextures.bitmap(b))
    }

    fun prepare(key: Key): Boolean {
        if (!ensure()) return false
        val glint = glintTexture()
        if (glint == 0) return false
        val m = McItemGlint.update()
        GL20.glUseProgram(program)
        GL20.glUniform2f(uScale, GlProgram.scaleX, GlProgram.scaleY)
        GL20.glUniform2f(uOffset, GlProgram.offsetX, GlProgram.offsetY)
        GL20.glUniform1f(uCells, key.cells.toFloat())
        val s = McItemGlint.STRIDE
        GL20.glUniform4f(uA0, m[0], m[1], m[2], m[3])
        GL20.glUniform4f(uA1, m[s], m[s + 1], m[s + 2], m[s + 3])
        GL20.glUniform4f(uB, m[4], m[5], m[s + 4], m[s + 5])
        GL20.glUniform3f(uTint0, m[6], m[7], m[8])
        GL20.glUniform3f(uTint1, m[s + 6], m[s + 7], m[s + 8])
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, key.atlas)
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        previousUnit1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, glint)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
        GL14.glBlendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE)
        return true
    }

    fun finish() {
        GL13.glActiveTexture(GL13.GL_TEXTURE1)
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousUnit1)
        GL13.glActiveTexture(GL13.GL_TEXTURE0)
    }

    private const val VS_150 = """#version 150
in vec2 aPos; in vec2 aUv; in vec4 aColor;
uniform vec2 uScale; uniform vec2 uOffset; uniform float uCells;
uniform vec4 uA0; uniform vec4 uA1; uniform vec4 uB;
out vec2 vUv; out vec2 vG0; out vec2 vG1; out float vAlpha;
void main() {
    vec2 c = vec2(aUv.x, 1.0 - aUv.y) * uCells;
    vUv = aUv;
    vG0 = vec2(dot(uA0.xy, c), dot(uA0.zw, c)) + uB.xy;
    vG1 = vec2(dot(uA1.xy, c), dot(uA1.zw, c)) + uB.zw;
    vAlpha = aColor.a;
    gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
}
"""
    private const val FS_150 = """#version 150
in vec2 vUv; in vec2 vG0; in vec2 vG1; in float vAlpha;
uniform sampler2D uAtlas; uniform sampler2D uGlint; uniform vec3 uTint0; uniform vec3 uTint1;
out vec4 fragColor;
void main() {
    vec3 g0 = texture(uGlint, vG0).rgb * uTint0;
    vec3 g1 = texture(uGlint, vG1).rgb * uTint1;
    fragColor = vec4((g0 * g0 + g1 * g1) * (texture(uAtlas, vUv).a * vAlpha), 0.0);
}
"""
    private const val VS_120 = """#version 120
attribute vec2 aPos; attribute vec2 aUv; attribute vec4 aColor;
uniform vec2 uScale; uniform vec2 uOffset; uniform float uCells;
uniform vec4 uA0; uniform vec4 uA1; uniform vec4 uB;
varying vec2 vUv; varying vec2 vG0; varying vec2 vG1; varying float vAlpha;
void main() {
    vec2 c = vec2(aUv.x, 1.0 - aUv.y) * uCells;
    vUv = aUv;
    vG0 = vec2(dot(uA0.xy, c), dot(uA0.zw, c)) + uB.xy;
    vG1 = vec2(dot(uA1.xy, c), dot(uA1.zw, c)) + uB.zw;
    vAlpha = aColor.a;
    gl_Position = vec4(aPos * uScale + uOffset, 0.0, 1.0);
}
"""
    private const val FS_120 = """#version 120
varying vec2 vUv; varying vec2 vG0; varying vec2 vG1; varying float vAlpha;
uniform sampler2D uAtlas; uniform sampler2D uGlint; uniform vec3 uTint0; uniform vec3 uTint1;
void main() {
    vec3 g0 = texture2D(uGlint, vG0).rgb * uTint0;
    vec3 g1 = texture2D(uGlint, vG1).rgb * uTint1;
    gl_FragColor = vec4((g0 * g0 + g1 * g1) * (texture2D(uAtlas, vUv).a * vAlpha), 0.0);
}
"""
}
