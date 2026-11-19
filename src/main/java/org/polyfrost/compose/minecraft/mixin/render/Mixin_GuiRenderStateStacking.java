package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
import net.minecraft.client.gui.navigation.ScreenRectangle;
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import net.minecraft.client.renderer.state.gui.ScreenArea;
//?} else {
/*import net.minecraft.client.gui.render.state.GuiRenderState;
import net.minecraft.client.gui.render.state.ScreenArea;
*///?}
import org.jspecify.annotations.Nullable;
import org.polyfrost.compose.minecraft.render.blaze.BlazeRenderer;
import org.polyfrost.compose.minecraft.render.blaze.StackingGuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(GuiRenderState.class)
public abstract class Mixin_GuiRenderStateStacking implements StackingGuiRenderState {
    @Shadow private @Nullable ScreenRectangle lastElementBounds;

    @Shadow public abstract void up();

    @Unique private int polycompose$stacked = -1;
    @Unique private int polycompose$left;
    @Unique private int polycompose$top;
    @Unique private int polycompose$right;
    @Unique private int polycompose$bottom;
    @Unique private boolean polycompose$barrier;

    @Override
    public void polycompose$setStacking(boolean stacking) {
        polycompose$stacked = stacking ? 0 : -1;
    }

    @Inject(method = "findAppropriateNode", at = @At("HEAD"), cancellable = true)
    private void polycompose$stack(ScreenArea area, CallbackInfoReturnable<Boolean> cir) {
        if (polycompose$stacked < 0) return;
        ScreenRectangle bounds = area.bounds();
        if (bounds == null) return;
        boolean barrier = area instanceof BlazeRenderer.Mesh mesh && mesh.getGlass();
        if (polycompose$stacked++ == 0) {
            polycompose$reset(bounds, barrier);
            return;
        }
        if (barrier || polycompose$barrier || polycompose$overlaps(bounds)) {
            up();
            polycompose$reset(bounds, barrier);
        } else {
            polycompose$left = Math.min(polycompose$left, bounds.left());
            polycompose$top = Math.min(polycompose$top, bounds.top());
            polycompose$right = Math.max(polycompose$right, bounds.right());
            polycompose$bottom = Math.max(polycompose$bottom, bounds.bottom());
        }
        lastElementBounds = bounds;
        cir.setReturnValue(true);
    }

    @Unique
    private boolean polycompose$overlaps(ScreenRectangle b) {
        return polycompose$left < b.right() && b.left() < polycompose$right && polycompose$top < b.bottom() && b.top() < polycompose$bottom;
    }

    @Unique
    private void polycompose$reset(ScreenRectangle b, boolean barrier) {
        polycompose$left = b.left();
        polycompose$top = b.top();
        polycompose$right = b.right();
        polycompose$bottom = b.bottom();
        polycompose$barrier = barrier;
    }
}
//?}
