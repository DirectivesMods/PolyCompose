package org.polyfrost.compose.minecraft.mixin.compat;

//? if >= 1.21.11 {
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.minecraft.client.renderer.state.gui.GuiItemRenderState;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.GuiTextRenderState;
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState;
//?} else {
/*import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.render.state.GuiItemRenderState;
import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.GuiTextRenderState;
import net.minecraft.client.gui.render.state.pip.PictureInPictureRenderState;
*///?}
import org.polyfrost.compose.minecraft.compat.GnetumCompat;
import org.polyfrost.compose.minecraft.compat.GnetumOffscreenState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = GuiRenderState.class, priority = 900)
public abstract class Mixin_GnetumOffscreenState implements GnetumOffscreenState {
    @Unique private boolean polycompose$offscreen;

    @Override
    public void polycompose$markOffscreen() {
        polycompose$offscreen = true;
    }

    //? if >= 26.1 {
    @WrapMethod(method = "addGuiElement")
    //?} else
    //@WrapMethod(method = "submitGuiElement")
    private void polycompose$bypassGnetumElement(GuiElementRenderState state, Operation<Void> original) {
        if (!polycompose$offscreen) {
            original.call(state);
            return;
        }
        GnetumCompat.suppress();
        try {
            original.call(state);
        } finally {
            GnetumCompat.restore();
        }
    }

    //? if >= 26.1 {
    @WrapMethod(method = "addText")
    //?} else
    //@WrapMethod(method = "submitText")
    private void polycompose$bypassGnetumText(GuiTextRenderState state, Operation<Void> original) {
        if (!polycompose$offscreen) {
            original.call(state);
            return;
        }
        GnetumCompat.suppress();
        try {
            original.call(state);
        } finally {
            GnetumCompat.restore();
        }
    }

    //? if >= 26.1 {
    @WrapMethod(method = "addItem")
    //?} else
    //@WrapMethod(method = "submitItem")
    private void polycompose$bypassGnetumItem(GuiItemRenderState state, Operation<Void> original) {
        if (!polycompose$offscreen) {
            original.call(state);
            return;
        }
        GnetumCompat.suppress();
        try {
            original.call(state);
        } finally {
            GnetumCompat.restore();
        }
    }

    //? if >= 26.1 {
    @WrapMethod(method = "addPicturesInPictureState")
    //?} else
    //@WrapMethod(method = "submitPicturesInPictureState")
    private void polycompose$bypassGnetumPicture(PictureInPictureRenderState state, Operation<Void> original) {
        if (!polycompose$offscreen) {
            original.call(state);
            return;
        }
        GnetumCompat.suppress();
        try {
            original.call(state);
        } finally {
            GnetumCompat.restore();
        }
    }
}
//?}
