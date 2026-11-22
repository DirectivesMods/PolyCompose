package org.polyfrost.compose.minecraft.mixin.render;

//? if = 1.8.9 {
/*import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.render.entity.ItemRenderer;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemRenderer.class)
public class Mixin_ItemAtlasGlint {
    @ModifyExpressionValue(
        method = "renderItem(Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/block/BakedModel;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/item/ItemStack;hasEnchantmentGlint()Z")
    )
    private boolean polycompose$bakeWithoutGlint(boolean original) {
        if (!original || !GuiTargetRedirect.suppressItemGlint) return original;
        GuiTargetRedirect.itemGlintSuppressed = true;
        return false;
    }
}
*///?}
