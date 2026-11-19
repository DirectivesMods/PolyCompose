package org.polyfrost.compose.minecraft.mixin.host;

import org.polyfrost.compose.host.KeyState;
import org.polyfrost.compose.minecraft.platform.GameKeyRouter;
import org.polyfrost.compose.minecraft.screen.RawKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;

//? if > 1.8.9 {
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import org.polyfrost.compose.minecraft.platform.McWindow;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//?}

//? if >= 1.21.10 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
//?}

//? if = 1.8.9 {
/*import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.lwjgl.input.Keyboard;
import org.polyfrost.compose.minecraft.legacy.KeyCodes;
import org.polyfrost.compose.minecraft.legacy.LegacyInputDrain;
import org.polyfrost.compose.minecraft.platform.LegacyInputDispatch;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
*///?}

//? if > 1.8.9 {
@Mixin(value = KeyboardHandler.class, priority = 900)
//?} else
//@Mixin(value = Keyboard.class, priority = 900, remap = false)
public class Mixin_HostKeyboard {
    //? if >= 1.21.10 {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (handle == McWindow.handle() && polycompose$consumed(event.key(), action)) ci.cancel();
    }
    //?} elif > 1.8.9 {
    /*@Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeKey(long handle, int key, int scancode, int action, int mods, CallbackInfo ci) {
        if (handle == McWindow.handle() && polycompose$consumed(key, action)) ci.cancel();
    }
    *///?}

    //? if >= 1.21.10 {
    @Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeChar(long handle, CharacterEvent event, CallbackInfo ci) {
        if (handle == McWindow.handle() && GameKeyRouter.consumeChar(event.codepoint())) ci.cancel();
    }
    //?} elif > 1.8.9 {
    /*@Inject(method = "charTyped", at = @At("HEAD"), cancellable = true)
    private void polycompose$routeChar(long handle, int codepoint, int modifiers, CallbackInfo ci) {
        if (handle == McWindow.handle() && GameKeyRouter.consumeChar(codepoint)) ci.cancel();
    }
    *///?} else {
    /*@WrapOperation(method = "next", at = @At(value = "INVOKE", target = "Lorg/lwjgl/input/Keyboard;readNext()Z"))
    private static boolean polycompose$skipConsumed(Operation<Boolean> original) {
        boolean read;
        do {
            read = original.call();
        } while (read && polycompose$legacyConsumed());
        return read;
    }

    @Inject(method = "next", at = @At("RETURN"))
    private static void polycompose$passed(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && !LegacyInputDrain.active) LegacyInputDispatch.keyboardPassed();
    }

    @Unique
    private static boolean polycompose$legacyConsumed() {
        int legacyKey = Keyboard.getEventKey();
        if (legacyKey <= 0) return false;
        int key = KeyCodes.fromLegacy(legacyKey).getValue();
        KeyState state = GameKeyRouter.legacyKey(key, Keyboard.getEventKeyState(), Keyboard.isRepeatEvent());
        if (LegacyInputDrain.active) return false;
        if (state == KeyState.Repeated && !Keyboard.areRepeatEventsEnabled()) return false;
        return polycompose$consumed(key, state);
    }
    *///?}

    //? if > 1.8.9 {
    @Unique
    private static boolean polycompose$consumed(int key, int action) {
        KeyState state;
        if (action == InputConstants.PRESS) state = KeyState.Pressed;
        else if (action == InputConstants.RELEASE) state = KeyState.Released;
        else if (action == InputConstants.REPEAT) state = KeyState.Repeated;
        else return false;
        return polycompose$consumed(key, state);
    }
    //?}

    @Unique
    private static boolean polycompose$consumed(int key, KeyState state) {
        RawKeys.onKey(key, state);
        return GameKeyRouter.consume(key, state);
    }
}
