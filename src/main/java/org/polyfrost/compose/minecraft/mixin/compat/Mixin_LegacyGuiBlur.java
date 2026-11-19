package org.polyfrost.compose.minecraft.mixin.compat;

//? if = 1.8.9 {
/*import net.minecraft.client.gui.screen.Screen;
import org.polyfrost.compose.minecraft.screen.ComposeScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "eu.midnightdust.blur.BlurRenderer", remap = false)
public class Mixin_LegacyGuiBlur {
    @Inject(method = "shouldBlur", at = @At("HEAD"), cancellable = true)
    private static void polycompose$skipComposeScreens(Screen screen, CallbackInfoReturnable<Boolean> cir) {
        if (screen instanceof ComposeScreen) cir.setReturnValue(false);
    }
}
*///?}
