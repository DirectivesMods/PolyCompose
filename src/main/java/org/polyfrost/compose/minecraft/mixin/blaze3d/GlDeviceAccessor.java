package org.polyfrost.compose.minecraft.mixin.blaze3d;

//? if >= 26.1 {
import com.mojang.renderpearl.backend.opengl.DirectStateAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//? if >= 26.2 {
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
//?}

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlDevice")
public interface GlDeviceAccessor {
    @Invoker("directStateAccess")
    DirectStateAccess polycompose$getDirectStateAccess();

    //? if >= 26.2 {
    @Invoker("frameBufferCache")
    FrameBufferCache polycompose$getFrameBufferCache();
    //? }
}
//? }
