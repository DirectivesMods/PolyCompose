package org.polyfrost.compose.minecraft.mixin.fixes;

import androidx.compose.ui.graphics.ImageBitmap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.io.IOException;
import java.io.InputStream;
import org.polyfrost.compose.backend.PolyImageBitmap;
import org.polyfrost.compose.render.PolyBitmap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "androidx.compose.ui.res.ImageResources_desktopKt", remap = false)
public class Mixin_ComposeImageResources {
    @WrapMethod(method = "loadImageBitmap")
    private static ImageBitmap polycompose$loadImageBitmap(InputStream inputStream, Operation<ImageBitmap> original) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        PolyBitmap bitmap = PolyBitmap.decode(bytes);
        if (bitmap == null) throw new IOException("cannot decode image (" + bytes.length + " bytes)");
        return new PolyImageBitmap(bitmap);
    }
}
