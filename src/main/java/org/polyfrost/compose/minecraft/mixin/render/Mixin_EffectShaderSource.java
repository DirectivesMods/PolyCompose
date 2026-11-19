package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.ShaderType;
//?} else
//import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.resources.Identifier;
import org.polyfrost.compose.minecraft.render.blaze.EffectShaders;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
//? if >= 26.3 {
@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$Configs")
//?} else
//@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$CompilationCache")
public class Mixin_EffectShaderSource {
    //? if >= 26.3 {
    @Inject(method = "getShader", at = @At("HEAD"), cancellable = true, require = 0)
    //?} else
    //@Inject(method = "getShaderSource", at = @At("HEAD"), cancellable = true, require = 0)
    private void polycompose$effectSource(Identifier id, ShaderType type, CallbackInfoReturnable<String> cir) {
        if (type != ShaderType.FRAGMENT || !"polycompose".equals(id.getNamespace())) return;
        String source = EffectShaders.source(id.getPath());
        if (source != null) cir.setReturnValue(source);
    }
}
//?}
