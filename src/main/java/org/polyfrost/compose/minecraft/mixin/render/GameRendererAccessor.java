package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.8 {
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("guiRenderer")
    GuiRenderer polycompose$getGuiRenderer();

    @Accessor("fogRenderer")
    FogRenderer polycompose$getFogRenderer();
}
//? }
