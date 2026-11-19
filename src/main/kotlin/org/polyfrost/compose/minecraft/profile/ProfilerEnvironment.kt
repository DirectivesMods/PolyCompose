package org.polyfrost.compose.minecraft.profile

import net.fabricmc.loader.api.FabricLoader
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.profile.ProfileEnvironment

//? if >= 1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem
//?} elif > 1.8.9 {
/*import com.mojang.blaze3d.platform.GlUtil
*///?} else {
/*import org.lwjgl.opengl.GL11
*///?}

internal object ProfilerEnvironment {
    private var gpuFilled = false
    private var lastWidth = -1
    private var lastHeight = -1
    private var lastScale = -1

    fun install() {
        val loader = FabricLoader.getInstance()
        loader.getModContainer("minecraft").ifPresent { ProfileEnvironment.put("minecraft", it.metadata.version.friendlyString) }
        //? if > 1.8.9 {
        val name = "fabric"
        //?} else
        //val name = "ornithe"
        val version = loader.getModContainer("fabricloader").map { it.metadata.version.friendlyString }.orElse("unknown")
        ProfileEnvironment.put("loader", "$name $version")
        //? if >= 1.21.11 {
        ProfileEnvironment.put("renderer", "blaze3d")
        //?} else
        //ProfileEnvironment.put("renderer", "gl")
    }

    fun fillGpu() {
        if (gpuFilled) return
        gpuFilled = true
        runCatching {
            //? if >= 26.2 {
            val info = RenderSystem.getDevice().deviceInfo
            put("gpuVendor", info.vendorName())
            put("gpu", info.name())
            put("graphicsBackend", info.backendName())
            //?} elif >= 1.21.5 {
            /*val device = RenderSystem.getDevice()
            put("gpuVendor", device.vendor)
            put("gpu", device.renderer)
            put("graphicsBackend", device.backendName)
            *///?} elif > 1.8.9 {
            /*put("gpuVendor", GlUtil.getVendor())
            put("gpu", GlUtil.getRenderer())
            put("graphicsBackend", "OpenGL")
            *///?} else {
            /*put("gpuVendor", GL11.glGetString(GL11.GL_VENDOR))
            put("gpu", GL11.glGetString(GL11.GL_RENDERER))
            put("graphicsBackend", "OpenGL")
            *///?}
        }
    }

    fun fillFrame() {
        val width = McWindow.viewportWidth()
        val height = McWindow.viewportHeight()
        val scale = McWindow.guiScale()
        if (width == lastWidth && height == lastHeight && scale == lastScale) return
        lastWidth = width
        lastHeight = height
        lastScale = scale
        ProfileEnvironment.put("framebuffer", "${width}x$height")
        ProfileEnvironment.put("guiScale", scale.toString())
    }

    private fun put(key: String, value: String?) {
        if (!value.isNullOrBlank()) ProfileEnvironment.put(key, value)
    }
}
