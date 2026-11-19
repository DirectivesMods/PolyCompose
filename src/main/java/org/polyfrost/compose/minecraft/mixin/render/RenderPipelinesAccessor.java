package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
//?} else
//import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.minecraft.client.renderer.RenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderPipelines.class)
public interface RenderPipelinesAccessor {
    @Accessor("GUI_TEXTURED_SNIPPET")
    static RenderPipeline.Snippet polycompose$getGuiTexturedSnippet() {
        throw new AssertionError();
    }

    @Accessor("GUI_TEXT_SNIPPET")
    static RenderPipeline.Snippet polycompose$getGuiTextSnippet() {
        throw new AssertionError();
    }
    //? if < 26.2 {

    /*    @Accessor("FOG_SNIPPET")
    static RenderPipeline.Snippet polycompose$getFogSnippet() {
        throw new AssertionError();
    }
    *///?}
}
//?}
