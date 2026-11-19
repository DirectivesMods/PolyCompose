package org.polyfrost.compose.minecraft.mixin.compat;

//? if > 1.8.9 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.gui.screens.Screen;
import org.polyfrost.compose.minecraft.compat.controlify.ControllerBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(targets = "dev.isxander.controlify.screenop.ScreenProcessor", remap = false)
public class Mixin_ControlifyBack {
    @WrapOperation(
            method = "handleButtons",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/screens/Screen;onClose()V", remap = true)
    )
    private void polycompose$routeBack(Screen screen, Operation<Void> original) {
        if (ControllerBridge.consumeBack(screen)) return;
        original.call(screen);
    }
}
//?}
