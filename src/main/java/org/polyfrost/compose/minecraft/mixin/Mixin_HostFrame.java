package org.polyfrost.compose.minecraft.mixin;

import net.minecraft.client.Minecraft;
import org.polyfrost.compose.minecraft.platform.HostEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class Mixin_HostFrame {

    //? if > 1.8.9 {
    //~ if >= 26.1 'runTick' -> 'renderFrame'
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
    //?} else
    //@Inject(method = "updateDisplay", at = @At(value = "INVOKE",
            //? if >= 26.2 {
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"
            //?} elif 26.1 {
            /*target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            *///?} elif >= 1.21.4 {
            /*target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V"
            *///?} elif > 1.8.9 {
            /*target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V"
            *///?} else
            //target = "Lorg/lwjgl/opengl/Display;update()V"
    ))
    private void polycompose$frameStart(CallbackInfo ci) {
        HostEvents.postFrameStart();
    }

    //? if > 1.8.9 {
    //~ if >= 26.1 'runTick' -> 'renderFrame'
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
    //?} else
    //@Inject(method = "updateDisplay", at = @At(value = "INVOKE",
            //? if >= 26.2 {
            target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V",
            //?} elif 26.1 {
            /*target = "Lcom/mojang/blaze3d/systems/RenderSystem;flipFrame(Lcom/mojang/blaze3d/TracyFrameCapture;)V",
            *///?} elif >= 1.21.4 {
            /*target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay(Lcom/mojang/blaze3d/TracyFrameCapture;)V",
            *///?} elif > 1.8.9 {
            /*target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V",
            *///?} else
            //target = "Lorg/lwjgl/opengl/Display;update()V",
            shift = At.Shift.AFTER
    ))
    private void polycompose$frameEnd(CallbackInfo ci) {
        HostEvents.postFrameEnd();
    }
}
