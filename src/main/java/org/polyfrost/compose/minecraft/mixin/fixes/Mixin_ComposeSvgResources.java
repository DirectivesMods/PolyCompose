package org.polyfrost.compose.minecraft.mixin.fixes;

import androidx.compose.ui.graphics.painter.Painter;
import androidx.compose.ui.unit.Density;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.IOException;
import java.io.InputStream;
import org.polyfrost.compose.host.OversampledSvgPainter;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "androidx.compose.ui.res.DesktopSvgResources_desktopKt", remap = false)
public class Mixin_ComposeSvgResources {
    @WrapMethod(method = "loadSvgPainter")
    private static Painter polycompose$loadSvgPainter(InputStream inputStream, Density density, Operation<Painter> original) throws IOException {
        return new OversampledSvgPainter(inputStream.readAllBytes(), 1f, null, density.getDensity());
    }
}
