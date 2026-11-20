package org.polyfrost.compose.minecraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import org.polyfrost.compose.host.ComposeHost;
import org.polyfrost.compose.minecraft.platform.McScreens;
import org.polyfrost.compose.minecraft.platform.McWindow;
import org.polyfrost.compose.minecraft.screen.ComposeScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//? if >= 1.21.4 {
import com.mojang.blaze3d.platform.FramerateLimitTracker;
//?}

//? if >=1.21.4 {
@Mixin(value = FramerateLimitTracker.class, priority = 1500)
//?} else {
/*@Mixin(value = Minecraft.class, priority = 1500)
*///?}
public class Mixin_MainMenuFpsUncap {
    private static final int MAIN_MENU_FPS_HEADROOM = 60;
    private static final int FALLBACK_MONITOR_REFRESH_RATE = 60;

    //? if > 1.8.9 {
    @ModifyExpressionValue(method = "getFramerateLimit", at = @At(value = "CONSTANT", args = "intValue=60"))
    //?} else
    //@ModifyExpressionValue(method = "getFramerateLimit", at = @At(value = "CONSTANT", args = "intValue=30"))
    private int polycompose$uncapMainMenu(int menuCap) {
        if (Minecraft.getInstance().level != null || McScreens.current() == null) return menuCap;
        Integer limit = ComposeHost.requestedFramerate();
        if (limit != null) return limit;
        if (!(McScreens.current() instanceof ComposeScreen)) return menuCap;
        int refreshRate = McWindow.refreshRate();
        if (refreshRate <= 0) refreshRate = FALLBACK_MONITOR_REFRESH_RATE;
        return Math.max(menuCap, refreshRate + MAIN_MENU_FPS_HEADROOM);
    }

    @ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
    private int polycompose$capFramerate(int original) {
        Integer limit = ComposeHost.requestedFramerate();
        return limit != null ? Math.min(original, limit) : original;
    }
}
