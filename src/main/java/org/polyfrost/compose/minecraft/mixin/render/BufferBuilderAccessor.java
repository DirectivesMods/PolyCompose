package org.polyfrost.compose.minecraft.mixin.render;

//? if >= 1.21.11 {
import com.mojang.blaze3d.vertex.BufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BufferBuilder.class)
public interface BufferBuilderAccessor {
    @Accessor("vertexPointer")
    long polycompose$getVertexPointer();

    @Accessor("vertices")
    int polycompose$getVertices();

    @Accessor("vertexSize")
    int polycompose$getVertexSize();

    @Accessor("elementsToFill")
    int polycompose$getElementsToFill();
}
//?}
