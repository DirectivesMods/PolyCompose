package org.polyfrost.compose.minecraft.mixin;

import net.minecraft.client.Minecraft;
import org.polyfrost.compose.minecraft.platform.HostEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class Mixin_HostTick {

    @Inject(method = "tick", at = @At("TAIL"))
    private void polycompose$tickEnd(CallbackInfo ci) {
        HostEvents.postClientTick();
    }
}
