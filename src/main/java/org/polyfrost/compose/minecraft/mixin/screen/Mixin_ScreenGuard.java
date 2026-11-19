package org.polyfrost.compose.minecraft.mixin.screen;

import net.minecraft.client.gui.screens.Screen;
import org.polyfrost.compose.minecraft.platform.ScreenGuards;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if >= 26.2 {
import net.minecraft.client.gui.Gui;

@Mixin(value = Gui.class, priority = 500)
//?} else {
/*import net.minecraft.client.Minecraft;

@Mixin(value = Minecraft.class, priority = 500)
*///?}
public class Mixin_ScreenGuard {

    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void polycompose$guardScreen(Screen next, CallbackInfo ci) {
        if (!ScreenGuards.allows(next)) ci.cancel();
    }
}
