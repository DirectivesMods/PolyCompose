package org.polyfrost.compose.minecraft.mixin.compat;

//? if >= 26.2 {
import java.lang.reflect.Field;
import net.minecraft.client.gui.render.GuiRenderer;
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GuiRenderer.class, priority = 1100)
public class Mixin_ImmediatelyFastItemAtlas {
    @Unique private static Field polycompose$animatedAtlasFlag;
    @Unique private static boolean polycompose$flagResolved;

    @Inject(
            method = "prepareItemElements",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;prepareItemAtlas(Ljava/util/Set;I)Lnet/minecraft/client/gui/render/GuiItemAtlas;",
                    shift = At.Shift.AFTER
            )
    )
    private void polycompose$skipAnimatedItemAtlas(CallbackInfo ci) {
        if (GuiTargetRedirect.itemRenderSizePx <= 0) return;
        Field flag = polycompose$animatedAtlasFlag();
        if (flag == null) return;
        try {
            flag.setBoolean(this, false);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            polycompose$animatedAtlasFlag = null;
        }
    }

    @Unique
    private static Field polycompose$animatedAtlasFlag() {
        if (polycompose$flagResolved) return polycompose$animatedAtlasFlag;
        polycompose$flagResolved = true;
        try {
            Field flag = GuiRenderer.class.getDeclaredField("immediatelyFast$useAnimatedItemAtlas");
            if (flag.getType() != boolean.class) return null;
            flag.setAccessible(true);
            polycompose$animatedAtlasFlag = flag;
        } catch (NoSuchFieldException | SecurityException e) {
            polycompose$animatedAtlasFlag = null;
        }
        return polycompose$animatedAtlasFlag;
    }
}
//?}
