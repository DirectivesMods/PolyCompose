package org.polyfrost.compose.minecraft.mixin.host;

import org.spongepowered.asm.mixin.Mixin;

//? if < 1.21.8 {
/*import org.polyfrost.compose.minecraft.hud.GuiDrawCount;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

//? if > 1.8.9 {
import com.mojang.blaze3d.vertex.BufferBuilder;
//?} else {
/*import net.minecraft.client.render.TextRenderer;
import net.minecraft.client.render.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

//? if > 1.8.9 {
@Mixin(BufferBuilder.class)
//?} else
//@Mixin({BufferBuilder.class, TextRenderer.class})
public class Mixin_HudDrawCounter {
    //? if > 1.8.9 && < 1.21.8 {
    /*@Inject(method = "<init>", at = @At("RETURN"))
    private void polycompose$countBuilder(CallbackInfo ci) {
        if (GuiDrawCount.counting) GuiDrawCount.value++;
    }
    *///?}

    //? if = 1.8.9 {
    /*@Inject(method = "begin", at = @At("HEAD"))
    private void polycompose$countBegin(CallbackInfo ci) {
        if (GuiDrawCount.counting) GuiDrawCount.value++;
    }

    @Inject(method = {"draw(Ljava/lang/String;FFIZ)I", "drawLayerAligned"}, at = @At("HEAD"))
    private void polycompose$countText(CallbackInfoReturnable<Integer> cir) {
        if (GuiDrawCount.counting) GuiDrawCount.value++;
    }
    *///?}
}
