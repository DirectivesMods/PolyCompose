package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//?} else
//import net.minecraft.client.gui.render.state.GuiRenderState;

@Mixin(GuiRenderState.class)
public interface GuiRenderStateBlurAccessor {
    @Accessor("firstStratumAfterBlur")
    int polycompose$getFirstStratumAfterBlur();
}
//?}
