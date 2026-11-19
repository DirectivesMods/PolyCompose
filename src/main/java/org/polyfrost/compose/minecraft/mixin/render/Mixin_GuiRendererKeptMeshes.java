package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
import com.mojang.blaze3d.pipeline.RenderTarget;
//? if < 26.2 {
/*import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
*///?}
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.GuiRenderer;
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
//?} else
//import net.minecraft.client.gui.render.state.GuiElementRenderState;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.polyfrost.compose.minecraft.compat.GnetumCompat;
import org.polyfrost.compose.minecraft.render.GpuBackend;
import org.polyfrost.compose.minecraft.render.blaze.BitmapTextures;
import org.polyfrost.compose.minecraft.render.blaze.KeptMeshScissor;
import org.polyfrost.compose.minecraft.render.blaze.KeptMeshes;
import org.polyfrost.compose.minecraft.render.blaze.LiquidGlass;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.function.Supplier;

@Mixin(GuiRenderer.class)
public class Mixin_GuiRendererKeptMeshes implements KeptMeshScissor {
    @Shadow @Final private List<?> draws;
    @Unique private static int polycompose$renderDepth;
    @Unique private static boolean polycompose$detachedPrepare;

    @Shadow
    private void enableScissor(ScreenRectangle area, RenderPass pass) {
        throw new AssertionError();
    }

    //? if >= 26.2 {
    @Inject(method = "addElementToMesh", at = @At("TAIL"))
    private void polycompose$noteKept(GuiElementRenderState element, CallbackInfo ci) {
        Object draw = draws.get(draws.size() - 1);
        KeptMeshes.added(draw, element);
        LiquidGlass.added(draw, element);
    }

    @Inject(method = "executeDraw", at = @At("HEAD"), cancellable = true)
    private void polycompose$drawKept(@Coerce Object draw, RenderPass pass, CallbackInfo ci) {
        if (KeptMeshes.execute(draw, pass, this)) ci.cancel();
    }
    //?} else {
    /*@Shadow @Final private List<?> meshesToDraw;

    @Inject(method = "addElementToMesh", at = @At("TAIL"))
    private void polycompose$noteKept(GuiElementRenderState element, CallbackInfo ci) {
        KeptMeshes.added(meshesToDraw.size(), element);
        LiquidGlass.added(meshesToDraw.size(), element);
    }

    @Inject(method = "recordDraws", at = @At("TAIL"))
    private void polycompose$bindKept(CallbackInfo ci) {
        KeptMeshes.recorded(draws);
        LiquidGlass.recorded(draws);
    }

    @Inject(method = "executeDraw", at = @At("HEAD"), cancellable = true)
    private void polycompose$drawKept(@Coerce Object draw, RenderPass pass, @Coerce Object indexBuffer, @Coerce Object indexType, CallbackInfo ci) {
        if (!KeptMeshes.execute(draw, pass, this)) return;
        if (GpuBackend.isVulkanMod()) pass.setIndexBuffer(null, null);
        ci.cancel();
    }
    *///?}

    //? if >= 26.2 {
    @WrapMethod(method = "executeDrawRange")
    private void polycompose$splitForGlass(Supplier<String> label, RenderTarget mainRenderTarget, GpuBufferSlice dynamicTransforms, int startIndex, int endIndex, Operation<Void> original) {
        if (!LiquidGlass.has(draws, startIndex, endIndex)) {
            original.call(label, mainRenderTarget, dynamicTransforms, startIndex, endIndex);
            return;
        }
        LiquidGlass.split(draws, startIndex, endIndex, mainRenderTarget, (from, to) -> original.call(label, mainRenderTarget, dynamicTransforms, from, to));
    }
    //?} else {
    /*@WrapMethod(method = "executeDrawRange")
    private void polycompose$splitForGlass(Supplier<String> label, RenderTarget mainRenderTarget, GpuBufferSlice fogBuffer, GpuBufferSlice dynamicTransforms, GpuBuffer indexBuffer, VertexFormat.IndexType indexType, int startIndex, int endIndex, Operation<Void> original) {
        if (!LiquidGlass.has(draws, startIndex, endIndex)) {
            original.call(label, mainRenderTarget, fogBuffer, dynamicTransforms, indexBuffer, indexType, startIndex, endIndex);
            return;
        }
        LiquidGlass.split(draws, startIndex, endIndex, mainRenderTarget, (from, to) -> original.call(label, mainRenderTarget, fogBuffer, dynamicTransforms, indexBuffer, indexType, from, to));
    }
    *///?}

    @Override
    public void polycompose$enableScissor(ScreenRectangle area, RenderPass pass) {
        enableScissor(area, pass);
    }

    @Inject(method = "prepare", at = @At("HEAD"))
    private void polycompose$notePrepare(CallbackInfo ci) {
        if (polycompose$renderDepth == 0) polycompose$detachedPrepare = true;
    }

    @Inject(method = "render", at = @At("HEAD"))
    private void polycompose$beginFrame(CallbackInfo ci) {
        if (polycompose$detachedPrepare) {
            polycompose$detachedPrepare = false;
            KeptMeshes.endFrame(false);
        }
        polycompose$renderDepth++;
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void polycompose$endFrame(CallbackInfo ci) {
        polycompose$renderDepth--;
        KeptMeshes.endFrame(!GnetumCompat.isFlushing());
        if (GuiTargetRedirect.target == null) BitmapTextures.endFrame();
        LiquidGlass.endFrame();
    }
}
//?}
