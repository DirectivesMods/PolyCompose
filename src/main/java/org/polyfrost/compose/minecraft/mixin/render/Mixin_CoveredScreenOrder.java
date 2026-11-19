package org.polyfrost.compose.minecraft.mixin.render;

//? if > 1.8.9 && < 1.21.11 {
/*import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.polyfrost.compose.minecraft.render.gl.GlHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public class Mixin_CoveredScreenOrder {
    //~ if >= 1.21.10 'renderWithTooltip' -> 'renderWithTooltipAndSubtitles'
    @Inject(method = "renderWithTooltipAndSubtitles", at = @At("HEAD"), cancellable = true)
    private void polycompose$drawAfterCoveredScreen(GuiGraphics graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (GlHost.INSTANCE.deferCover((Screen) (Object) this, mouseX, mouseY, delta)) ci.cancel();
    }
}
*///?}
