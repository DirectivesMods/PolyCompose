package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.ARBFramebufferObject
import org.lwjgl.opengl.EXTFramebufferBlit
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL12
import org.lwjgl.opengl.GL30
import java.nio.ByteBuffer
import java.nio.FloatBuffer

internal object GlFbo {
    private const val UNKNOWN = -1
    private const val CORE = 0
    private const val ARB = 1
    private const val EXT = 2
    private const val NONE = 3

    private var api = UNKNOWN
    private var separate = false
    private var clearBuffer = false
    private val transparent: FloatBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createFloatBuffer(4) }
    private val previousClear: FloatBuffer by lazy(LazyThreadSafetyMode.NONE) { BufferUtils.createFloatBuffer(16) }

    private fun api(): Int {
        if (api == UNKNOWN) {
            val caps = GL.getCapabilities()
            api = when {
                caps.OpenGL30 -> CORE
                caps.GL_ARB_framebuffer_object -> ARB
                caps.GL_EXT_framebuffer_object -> EXT
                else -> NONE
            }
            separate = api == CORE || api == ARB || (api == EXT && caps.GL_EXT_framebuffer_blit)
            clearBuffer = caps.OpenGL30
        }
        return api
    }

    fun clearTransparent() {
        api()
        if (clearBuffer) {
            GL30.glClearBufferfv(GL11.GL_COLOR, 0, transparent)
            return
        }
        val previous = previousClear
        previous.clear()
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, previous)
        GL11.glClearColor(0f, 0f, 0f, 0f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        GL11.glClearColor(previous.get(0), previous.get(1), previous.get(2), previous.get(3))
    }

    val supported: Boolean get() = api() != NONE

    val canBlit: Boolean get() = api() != NONE && separate

    fun gen(): Int = when (api()) {
        CORE -> GL30.glGenFramebuffers()
        ARB -> ARBFramebufferObject.glGenFramebuffers()
        EXT -> EXTFramebufferObject.glGenFramebuffersEXT()
        else -> 0
    }

    fun delete(fbo: Int) {
        when (api()) {
            CORE -> GL30.glDeleteFramebuffers(fbo)
            ARB -> ARBFramebufferObject.glDeleteFramebuffers(fbo)
            EXT -> EXTFramebufferObject.glDeleteFramebuffersEXT(fbo)
        }
    }

    fun bind(target: Int, fbo: Int) {
        when (api()) {
            CORE -> GL30.glBindFramebuffer(target, fbo)
            ARB -> ARBFramebufferObject.glBindFramebuffer(target, fbo)
            EXT -> EXTFramebufferObject.glBindFramebufferEXT(if (separate) target else GL30.GL_FRAMEBUFFER, fbo)
        }
    }

    fun binding(target: Int): Int {
        if (api() == NONE) return 0
        val pname = when {
            !separate -> GL30.GL_FRAMEBUFFER_BINDING
            target == GL30.GL_READ_FRAMEBUFFER -> GL30.GL_READ_FRAMEBUFFER_BINDING
            else -> GL30.GL_DRAW_FRAMEBUFFER_BINDING
        }
        return GL11.glGetInteger(pname)
    }

    fun attachColor(texture: Int) {
        when (api()) {
            CORE -> GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
            ARB -> ARBFramebufferObject.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
            EXT -> EXTFramebufferObject.glFramebufferTexture2DEXT(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0)
        }
    }

    fun status(): Int = when (api()) {
        CORE -> GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        ARB -> ARBFramebufferObject.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER)
        EXT -> EXTFramebufferObject.glCheckFramebufferStatusEXT(GL30.GL_FRAMEBUFFER)
        else -> 0
    }

    fun blit(sx0: Int, sy0: Int, sx1: Int, sy1: Int, dx0: Int, dy0: Int, dx1: Int, dy1: Int, mask: Int, filter: Int) {
        when (api()) {
            CORE -> GL30.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter)
            ARB -> ARBFramebufferObject.glBlitFramebuffer(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter)
            EXT -> if (separate) EXTFramebufferBlit.glBlitFramebufferEXT(sx0, sy0, sx1, sy1, dx0, dy0, dx1, dy1, mask, filter)
        }
    }
}

internal class GlTarget {
    var fbo = 0
        private set
    var tex = 0
        private set
    var width = 0
        private set
    var height = 0
        private set

    fun ensure(w: Int, h: Int): Boolean {
        if (fbo != 0 && width == w && height == h) return true
        if (!GlFbo.supported) return false
        if (fbo == 0) {
            fbo = GlFbo.gen()
            tex = GL11.glGenTextures()
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, null as ByteBuffer?)
        GlFbo.bind(GL30.GL_FRAMEBUFFER, fbo)
        GlFbo.attachColor(tex)
        if (GlFbo.status() != GL30.GL_FRAMEBUFFER_COMPLETE) {
            release()
            return false
        }
        width = w
        height = h
        return true
    }

    fun release() {
        if (fbo != 0) GlFbo.delete(fbo)
        if (tex != 0) GL11.glDeleteTextures(tex)
        fbo = 0
        tex = 0
        width = 0
        height = 0
    }
}
