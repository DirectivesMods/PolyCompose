package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiRenderState;
//?} else
//import net.minecraft.client.gui.render.state.GuiRenderState;
import org.polyfrost.compose.minecraft.render.blaze.UiBlur;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiRenderState.class)
public class Mixin_GuiRenderStateSharedBlur {
    @Shadow private int firstStratumAfterBlur;

    @Inject(method = "blurBeforeThisStratum", at = @At("HEAD"), cancellable = true)
    private void polycompose$mergeBlur(CallbackInfo ci) {
        if (firstStratumAfterBlur != Integer.MAX_VALUE && UiBlur.mergeForeignBlur(this)) ci.cancel();
    }
}
//?}
