package org.polyfrost.compose.minecraft.profile

import net.minecraft.client.KeyMapping
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.screen.isKeyDown

//? if > 1.8.9
import com.mojang.blaze3d.platform.InputConstants
//? if = 1.8.9
//import org.polyfrost.compose.minecraft.legacy.InputConstants

//? if >= 1.21.9 {
import net.minecraft.resources.Identifier
//?} elif > 1.8.9 {
/*import org.polyfrost.compose.minecraft.mixin.profile.KeyMappingAccessor
*///?} else {
/*import net.minecraft.client.Minecraft
import net.ornithemc.osl.keybinds.api.KeybindEvents
import org.polyfrost.compose.minecraft.legacy.KeyCodes
import net.ornithemc.osl.keybinds.api.KeybindRegistry
*///?}

internal object ProfilerKeybind {
    private const val NAME = "key.polycompose.profiler"

    private var mapping: KeyMapping? = null
    private var rawHeld = false

    private val useMapping: Boolean = System.getProperty("polycompose.profiler.key")?.toBoolean() ?: true

    //? if > 1.8.9 {
    @JvmStatic
    fun append(mappings: Array<KeyMapping>): Array<KeyMapping> {
        if (!useMapping) return mappings
        val key = mapping ?: create().also { mapping = it }
        for (existing in mappings) if (existing === key) return mappings
        return mappings + key
    }

    //? if >= 1.21.9 {
    private fun create(): KeyMapping {
        val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("polycompose", "main"))
        return KeyMapping(NAME, InputConstants.KEY_F9, category)
    }
    //?} else {
    /*private const val CATEGORY = "key.categories.polycompose"

    private fun create(): KeyMapping {
        val order = KeyMappingAccessor.`polycompose$categorySortOrder`()
        if (CATEGORY !in order) order[CATEGORY] = (order.values.maxOrNull() ?: 0) + 1
        return KeyMapping(NAME, InputConstants.KEY_F9, CATEGORY)
    }
    *///?}
    //?} else {
    /*private const val CATEGORY = "key.categories.polycompose"
    private const val F9 = 67

    @Suppress("USELESS_ELVIS", "SENSELESS_COMPARISON")
    fun register() {
        if (mapping != null || !useMapping) return
        val minecraft = Minecraft.getInstance()
        if (minecraft == null || minecraft.options == null) {
            KeybindEvents.REGISTER_KEYBINDS.register { mapping = KeybindRegistry.register(NAME, F9, CATEGORY) }
        } else {
            mapping = KeybindRegistry.register(NAME, F9, CATEGORY)
        }
    }
    *///?}

    //? if > 1.8.9 {
    fun matches(key: Int): Boolean {
        val bound = mapping ?: return !useMapping && key == InputConstants.KEY_F9
        return bound.saveString() == InputConstants.UNKNOWN.type.getOrCreate(key).name
    }
    //?} else {
    /*fun matches(key: Int): Boolean = mapping?.let { KeyCodes.fromLegacy(it.keyCode).value } == key || (mapping == null && !useMapping && key == InputConstants.KEY_F9)
    *///?}

    fun poll() {
        val key = mapping
        if (key == null) {
            pollRaw()
            return
        }
        while (key.consumeClick()) ProfilerControl.toggleOverlay()
    }

    private fun pollRaw() {
        if (useMapping) return
        val down = McScreens.current() == null && isKeyDown(InputConstants.KEY_F9)
        if (down && !rawHeld) ProfilerControl.toggleOverlay()
        rawHeld = down
    }
}
