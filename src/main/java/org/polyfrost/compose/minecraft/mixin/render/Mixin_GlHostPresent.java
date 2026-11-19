package org.polyfrost.compose.minecraft.mixin.render;

//? if > 1.8.9 && < 1.21.11 {
/*import net.minecraft.client.Minecraft;
import org.polyfrost.compose.minecraft.render.gl.GlHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class Mixin_GlHostPresent {
    //~ if < 1.21.5 'blitToScreen()V' -> 'blitToScreen(II)V'
    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;blitToScreen()V"), require = 0)
    private void polycompose$beforeBlit(boolean tick, CallbackInfo ci) {
        GlHost.INSTANCE.drawBeforeBlit();
    }
}
*///?}
