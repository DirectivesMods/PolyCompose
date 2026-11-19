package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.PostChain;
import org.polyfrost.compose.minecraft.render.blaze.UiBlur;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class Mixin_UiBlur {
    //? if >= 26.2 {
    @Unique private static final String GUI_RENDER = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V";
    //?} else
    //@Unique private static final String GUI_RENDER = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V";

    @WrapOperation(method = "processBlurEffect", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/PostChain;process(Lcom/mojang/blaze3d/pipeline/RenderTarget;Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;)V"))
    private void polycompose$uiBlur(PostChain chain, RenderTarget target, GraphicsResourceAllocator allocator, Operation<Void> original) {
        if (UiBlur.processAtBlur()) return;
        original.call(chain, target, allocator);
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI_RENDER))
    private void polycompose$uiBlurBeforeGui(CallbackInfo ci) {
        UiBlur.beforeGuiRender();
    }

    @Inject(method = "render", at = @At(value = "INVOKE", target = GUI_RENDER, shift = At.Shift.AFTER))
    private void polycompose$uiBlurAfterGui(CallbackInfo ci) {
        UiBlur.afterGuiRender();
    }
}
//?}
