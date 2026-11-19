package org.polyfrost.compose.minecraft.render

import org.polyfrost.compose.minecraft.platform.LoadedMods

//? if >= 26.1 {
import org.polyfrost.compose.minecraft.mixin.blaze3d.GlDeviceAccessor
import org.polyfrost.compose.minecraft.mixin.blaze3d.GpuDeviceAccessor
//?}

//? if >= 1.21.5 && < 26.1 {
/*import com.mojang.renderpearl.backend.opengl.GlDevice
*///?}

//? if >= 1.21.5 {
import com.mojang.blaze3d.systems.RenderSystem
//?}

object GpuBackend {
    enum class Kind {
        OPENGL,
        VANILLA_VULKAN,
        POLYDIUM_VULKAN,
        VULKAN_MOD,
        OTHER,
    }

    private const val POLYDIUM_PACKAGE = "org.polyfrost.polydium."
    private const val VULKAN_MOD_ENTRYPOINT = "net.vulkanmod.Initializer"

    @Volatile
    private var detected: Kind? = null

    @Volatile
    private var structural: Boolean? = null

    @JvmStatic
    val isVulkanMod: Boolean by lazy {
        try {
            LoadedMods.isLoaded("vulkanmod")
        } catch (_: Throwable) {
            runCatching { Class.forName(VULKAN_MOD_ENTRYPOINT, false, GpuBackend::class.java.classLoader) }.isSuccess
        }
    }

    @JvmStatic
    val kind: Kind
        get() = detected ?: detect()

    @JvmStatic
    val isVulkan: Boolean
        get() = structural ?: deviceIsVulkan()

    @JvmStatic
    val isPolydiumVulkan: Boolean
        get() = kind == Kind.POLYDIUM_VULKAN

    private fun detect(): Kind {
        if (isVulkanMod) return Kind.VULKAN_MOD.also { detected = it }
        val backend = backendObject() ?: return Kind.OPENGL
        val result = classify(backend)
        detected = result
        return result
    }

    private fun classify(backend: Any): Kind {
        if (isGl(backend)) return Kind.OPENGL
        val name = backend.javaClass.name
        return when {
            name.startsWith(POLYDIUM_PACKAGE) -> Kind.POLYDIUM_VULKAN
            name.startsWith("com.mojang.") && name.contains(".vulkan.") -> Kind.VANILLA_VULKAN
            else -> Kind.OTHER
        }
    }

    private fun deviceIsVulkan(): Boolean {
        val backend = backendObject() ?: return false
        val result = !isGl(backend)
        structural = result
        return result
    }

    private fun backendObject(): Any? {
        //? if >= 26.1 {
        return runCatching { (RenderSystem.getDevice() as GpuDeviceAccessor).`polycompose$getBackend`() }.getOrNull()
        //?} else if >= 1.21.5 {
        /*return runCatching { RenderSystem.getDevice() }.getOrNull()
        *///?} else
        //return null
    }

    private fun isGl(backend: Any): Boolean {
        //? if >= 26.1 {
        return backend is GlDeviceAccessor
        //?} else if >= 1.21.5 {
        /*return backend is GlDevice
        *///?} else
        //return true
    }
}
