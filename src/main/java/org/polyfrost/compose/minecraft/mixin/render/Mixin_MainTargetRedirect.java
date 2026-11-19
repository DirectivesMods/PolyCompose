package org.polyfrost.compose.minecraft.mixin.render;

//? if < 1.21.8 {
/*import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class Mixin_MainTargetRedirect {
    @Inject(method = "getMainRenderTarget", at = @At("HEAD"), cancellable = true)
    private void polycompose$redirectMainTarget(CallbackInfoReturnable<RenderTarget> cir) {
        RenderTarget override = GuiTargetRedirect.target;
        if (override != null && ((Minecraft) (Object) this).isSameThread()) {
            cir.setReturnValue(override);
        }
    }
}
*///? }
