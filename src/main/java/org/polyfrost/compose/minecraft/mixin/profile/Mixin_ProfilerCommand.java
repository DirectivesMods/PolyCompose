package org.polyfrost.compose.minecraft.mixin.profile;

import org.polyfrost.compose.minecraft.profile.ProfilerCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//? if > 1.8.9 {
import net.minecraft.client.multiplayer.ClientPacketListener;
//?} else
//import net.minecraft.client.player.LocalPlayer;

//? if > 1.8.9 {
@Mixin(ClientPacketListener.class)
public class Mixin_ProfilerCommand {
    @Inject(method = "sendCommand", at = @At("HEAD"), cancellable = true)
    private void polycompose$clientCommand(String command, CallbackInfo ci) {
        if (ProfilerCommand.handle(command)) ci.cancel();
    }
}
//?} else {
/*@Mixin(LocalPlayer.class)
public class Mixin_ProfilerCommand {
    @Inject(method = "sendChat", at = @At("HEAD"), cancellable = true)
    private void polycompose$clientCommand(String message, CallbackInfo ci) {
        if (message.startsWith("/") && ProfilerCommand.handle(message.substring(1))) ci.cancel();
    }
}
*///?}
