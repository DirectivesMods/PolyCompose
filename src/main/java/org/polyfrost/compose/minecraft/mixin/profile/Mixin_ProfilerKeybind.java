package org.polyfrost.compose.minecraft.mixin.profile;

//? if > 1.8.9 {
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.polyfrost.compose.minecraft.profile.ProfilerKeybind;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
public class Mixin_ProfilerKeybind {
    @Shadow
    @Final
    @Mutable
    public KeyMapping[] keyMappings;

    @Inject(method = "load", at = @At("HEAD"))
    private void polycompose$addProfilerKey(CallbackInfo ci) {
        keyMappings = ProfilerKeybind.append(keyMappings);
    }
}
//?}
