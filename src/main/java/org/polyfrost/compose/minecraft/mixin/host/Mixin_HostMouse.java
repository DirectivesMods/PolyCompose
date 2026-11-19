package org.polyfrost.compose.minecraft.mixin.host;

import org.polyfrost.compose.minecraft.platform.GameKeyRouter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

//? if > 1.8.9 {
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import org.polyfrost.compose.minecraft.platform.McWindow;
import org.polyfrost.compose.minecraft.screen.RawScroll;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

//? if >= 1.21.10 {
import net.minecraft.client.input.MouseButtonInfo;
//?}

//? if = 1.8.9 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.input.Mouse;
import org.polyfrost.compose.minecraft.legacy.KeyCodes;
import org.polyfrost.compose.minecraft.legacy.LegacyInputDrain;
import org.polyfrost.compose.minecraft.platform.LegacyInputDispatch;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import pl.tomgirl.pylon.window.MouseSdl;
*///?}

//? if > 1.8.9 {
@Mixin(value = MouseHandler.class, priority = 900)
//?} else
//@Mixin(value = Mouse.class, priority = 900, remap = false)
public class Mixin_HostMouse {
    //? if >= 1.21.10 {
    @Inject(method = "onButton", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeButton(long window, MouseButtonInfo buttonInfo, int action, CallbackInfo ci) {
        if (window == McWindow.handle() && polycompose$buttonConsumed(buttonInfo.button(), action)) ci.cancel();
    }
    //?} elif > 1.8.9 {
    /*@Inject(method = "onPress", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeButton(long handle, int button, int action, int mods, CallbackInfo ci) {
        if (handle == McWindow.handle() && polycompose$buttonConsumed(button, action)) ci.cancel();
    }
    *///?}

    //? if > 1.8.9 {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeScroll(long window, double x, double y, CallbackInfo ci) {
        if (!GameKeyRouter.getScrollAfterOthers()) polycompose$scroll(window, x, y, ci);
    }

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true, order = 1100)
    private void polycompose$routeScrollAfterOthers(long window, double x, double y, CallbackInfo ci) {
        if (GameKeyRouter.getScrollAfterOthers()) polycompose$scroll(window, x, y, ci);
    }

    @Unique
    private static void polycompose$scroll(long window, double x, double y, CallbackInfo ci) {
        if (window != McWindow.handle()) return;
        RawScroll.x = x;
        RawScroll.y = y;
        if (polycompose$scrollConsumed(x, y)) ci.cancel();
    }

    @Unique
    private static boolean polycompose$buttonConsumed(int button, int action) {
        if (action != InputConstants.PRESS && action != InputConstants.RELEASE) return false;
        return polycompose$buttonConsumed(button, action == InputConstants.PRESS);
    }
    //?} else {
    /*@WrapOperation(method = "next", at = @At(value = "INVOKE", target = "Lpl/tomgirl/pylon/window/MouseSdl;nextMouseEvent()Lpl/tomgirl/pylon/window/MouseSdl$Event;"))
    private static MouseSdl.Event polycompose$skipConsumed(MouseSdl sdl, Operation<MouseSdl.Event> original) {
        MouseSdl.Event event;
        do {
            event = original.call(sdl);
        } while (event != null && !LegacyInputDrain.active && polycompose$legacyConsumed(event));
        return event;
    }

    @Inject(method = "next", at = @At("RETURN"))
    private static void polycompose$passed(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && !LegacyInputDrain.active) LegacyInputDispatch.mousePassed();
    }

    @Unique
    private static boolean polycompose$legacyConsumed(MouseSdl.Event event) {
        if (event.button >= 0) return polycompose$buttonConsumed(KeyCodes.mouseFromLegacy(event.button), event.state);
        return event.wheelDelta != 0.0 && polycompose$scrollConsumed(0.0, event.wheelDelta);
    }
    *///?}

    @Unique
    private static boolean polycompose$buttonConsumed(int button, boolean pressed) {
        return GameKeyRouter.consumeButton(button, pressed);
    }

    @Unique
    private static boolean polycompose$scrollConsumed(double x, double y) {
        return GameKeyRouter.consumeScroll(x, y);
    }
}
