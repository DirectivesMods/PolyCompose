package org.polyfrost.compose.minecraft.mixin.profile;

//? if > 1.8.9 && < 1.21.9 {
/*import java.util.Map;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("CATEGORY_SORT_ORDER")
    static Map<String, Integer> polycompose$categorySortOrder() {
        throw new AssertionError();
    }
}
*///?}
