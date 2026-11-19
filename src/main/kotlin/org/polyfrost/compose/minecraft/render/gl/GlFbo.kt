package org.polyfrost.compose.minecraft.render.gl

import org.lwjgl.opengl.ARBFramebufferObject
import org.lwjgl.opengl.EXTFramebufferBlit
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL30

internal object GlFbo {
    private const val UNKNOWN = -1
    private const val CORE = 0
    private const val ARB = 1
    private const val EXT = 2
    private const val NONE = 3

    private var api = UNKNOWN
    private var separate = false

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
        }
        return api
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
