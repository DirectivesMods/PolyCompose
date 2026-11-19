package org.polyfrost.compose.minecraft.platform

import net.fabricmc.loader.api.FabricLoader

object LoadedMods {
    @JvmStatic
    fun isLoaded(id: String): Boolean = FabricLoader.getInstance().isModLoaded(id)
}
