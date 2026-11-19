package org.polyfrost.compose.minecraft.mixin.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//? if > 1.8.9 {
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
//?} else {
/*import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.living.LivingEntity;
*///?}
import org.polyfrost.compose.minecraft.preview.PreviewNameTag;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = LivingEntityRenderer.class, priority = 2000)
public class Mixin_PreviewNameTag {

    //? if >= 1.21.4 {
    @WrapMethod(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;D)Z")
    private boolean polycompose$previewNameTag(LivingEntity entity, double distance, Operation<Boolean> original) {
        if (PreviewNameTag.rendering) return PreviewNameTag.show;
        return original.call(entity, distance);
    }
    //?} elif > 1.8.9 {
    /*@WrapMethod(method = "shouldShowName(Lnet/minecraft/world/entity/LivingEntity;)Z")
    private boolean polycompose$previewNameTag(LivingEntity entity, Operation<Boolean> original) {
        if (PreviewNameTag.rendering) return PreviewNameTag.show;
        return original.call(entity);
    }
    *///?} else {
    /*@WrapMethod(method = "shouldRenderNameTag(Lnet/minecraft/entity/living/LivingEntity;)Z")
    private boolean polycompose$previewNameTag(LivingEntity entity, Operation<Boolean> original) {
        if (PreviewNameTag.rendering) return PreviewNameTag.show;
        return original.call(entity);
    }
    *///?}

}
