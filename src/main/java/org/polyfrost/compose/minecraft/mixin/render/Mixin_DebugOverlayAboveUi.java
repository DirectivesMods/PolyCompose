package org.polyfrost.compose.minecraft.mixin.render;

//? if < 1.21.11 {
/*import org.polyfrost.compose.minecraft.render.gl.GlHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenOverlay.class)
public class Mixin_DebugOverlayAboveUi {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void polycompose$deferAboveUi(GuiGraphics graphics, CallbackInfo ci) {
        if (GlHost.INSTANCE.deferDebugScreen((DebugScreenOverlay) (Object) this)) ci.cancel();
    }
}
//?} else {
/^import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.client.gui.GameGui;
import net.minecraft.client.gui.overlay.DebugOverlay;
import net.minecraft.client.render.Window;

@Mixin(GameGui.class)
public class Mixin_DebugOverlayAboveUi {
    @WrapWithCondition(
            method = "render",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/overlay/DebugOverlay;render(Lnet/minecraft/client/render/Window;)V")
    )
    private boolean polycompose$deferAboveUi(DebugOverlay overlay, Window window) {
        return !GlHost.INSTANCE.deferDebugScreen(overlay);
    }
}
^///?}
*///?}
