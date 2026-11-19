package org.polyfrost.compose.minecraft.mixin;

import org.polyfrost.compose.minecraft.PolyComposeMixinPlugin;
import org.polyfrost.compose.minecraft.platform.HostEvents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

//? if > 1.8.9 {
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;

@Mixin(ReloadableResourceManager.class)
public class Mixin_HostReload {

    @Inject(method = "createReload", at = @At("RETURN"))
    private void polycompose$onReload(CallbackInfoReturnable<ReloadInstance> cir) {
        cir.getReturnValue().done().whenComplete((result, failure) -> {
            if (failure != null) return;
            PolyComposeMixinPlugin.reportMissingPseudoTargets();
            HostEvents.postResourcesReloaded();
        });
    }
}
//?} else {
/*import net.ornithemc.osl.resource.loader.api.resource.reload.ResourceReload;
import net.ornithemc.osl.resource.loader.impl.resource.manager.SimpleReloadableResourceManager;

@Mixin(value = SimpleReloadableResourceManager.class, remap = false)
public class Mixin_HostReload {

    @Inject(method = "startReload", at = @At("RETURN"))
    private void polycompose$onReload(CallbackInfoReturnable<ResourceReload> cir) {
        if ((Object) this != SimpleReloadableResourceManager.client()) return;
        cir.getReturnValue().result().whenComplete((result, failure) -> {
            if (failure != null) return;
            PolyComposeMixinPlugin.reportMissingPseudoTargets();
            HostEvents.postResourcesReloaded();
        });
    }
}
*///?}
