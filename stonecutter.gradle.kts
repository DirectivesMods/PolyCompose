plugins {
    id("dev.kikugie.stonecutter")
    kotlin("jvm") apply false
    kotlin("plugin.compose") apply false
}

stonecutter active "26.3-fabric"

stonecutter handlers {
    inherit("aw", "classtweaker")
}

stonecutter parameters {
    constants {
        match(current.project.substringAfterLast("-"), "fabric", "ornithe")
        this["sdl"] = eval(current.version, ">= 26.3") || eval(current.version, "= 1.8.9")
    }
    replacements {
        string(eval(current.version, "< 26.1")) {
            replace("classTweaker v1 official", "classTweaker v1 named")
        }

        string(eval(current.version, ">= 26.1"), "gui_graphics") {
            replace("GuiGraphics", "GuiGraphicsExtractor")
        }

        string(eval(current.version, "< 26.1")) {
            replace(
                "import net.minecraft.client.gui.GuiGraphicsExtractor\n",
                "import net.minecraft.client.gui.GuiGraphics as GuiGraphicsExtractor\n",
            )
        }

        string(eval(current.version, ">= 26.2"), "main_render_target") {
            replace("mainRenderTarget", "gameRenderer.mainRenderTarget()")
        }

        string(eval(current.version, "< 1.21.11", "> 1.8.9")) {
            replace(
                "import net.minecraft.resources.Identifier\n",
                "import net.minecraft.resources.ResourceLocation as Identifier\n",
            )
        }

        string(eval(current.version, "= 1.8.9")) {
            replace(
                "import net.minecraft.resources.Identifier\n",
                "import net.minecraft.resource.Identifier\n",
            )
        }

        string(eval(current.version, "< 1.21.2")) {
            replace("Minecraft.getInstance().schedule(", "Minecraft.getInstance().tell(")
        }

        string(eval(current.version, "= 1.8.9")) {
            replace("com.mojang.blaze3d.platform.InputConstants", "org.polyfrost.compose.minecraft.legacy.InputConstants")
            replace("com.mojang.blaze3d.platform.NativeImage", "org.polyfrost.compose.minecraft.legacy.NativeImage")
            replace("net.minecraft.client.gui.screens.Screen", "net.minecraft.client.gui.screen.Screen")
            replace("net.minecraft.client.gui.screens.TitleScreen", "net.minecraft.client.gui.screen.menu.TitleScreen")
            replace("net.minecraft.client.gui.screens.ChatScreen", "net.minecraft.client.gui.screen.game.ChatScreen")
            replace("com.mojang.blaze3d.pipeline.RenderTarget", "net.minecraft.client.render.pipeline.RenderTarget")
            replace("com.mojang.blaze3d.platform.GlStateManager", "net.minecraft.client.render.platform.GlStateManager")
            replace("net.minecraft.client.gui.components.DebugScreenOverlay", "net.minecraft.client.gui.overlay.DebugOverlay")
            replace("net.minecraft.client.renderer.texture.AbstractTexture", "net.minecraft.client.render.texture.Texture")
            replace("net.minecraft.client.renderer.texture.DynamicTexture", "net.minecraft.client.render.texture.HttpTexture")
            replace(
                "net.minecraft.server.packs.resources.ResourceManager",
                "net.ornithemc.osl.resource.loader.api.resource.manager.ResourceManager",
            )
        }

        string(eval(current.version, ">= 26.3")) {
            replace("com.mojang.blaze3d.opengl", "com.mojang.renderpearl.backend.opengl")
            replace("com.mojang.blaze3d.vulkan", "com.mojang.renderpearl.backend.vulkan")
            replace("com.mojang.blaze3d.textures", "com.mojang.renderpearl.api.textures")
            replace("com.mojang.blaze3d.buffers", "com.mojang.renderpearl.api.buffers")
            replace("com.mojang.blaze3d.GpuFormat", "com.mojang.renderpearl.api.GpuFormat")
            replace("com.mojang.blaze3d.systems.RenderPass", "com.mojang.renderpearl.api.commands.RenderPass")
            replace("com/mojang/blaze3d/systems/GpuSurface", "com/mojang/renderpearl/api/device/GpuSurface")
            replace("com/mojang/blaze3d/systems/CommandEncoder", "com/mojang/renderpearl/api/commands/CommandEncoder")
            replace("com/mojang/blaze3d/systems/RenderPass", "com/mojang/renderpearl/api/commands/RenderPass")
            replace("com/mojang/blaze3d/textures/GpuTextureView", "com/mojang/renderpearl/api/textures/GpuTextureView")
        }
    }
}

stonecutter tasks {
    order("runClient")
    order("publishModrinth")
    order("publishCurseforge")
}
