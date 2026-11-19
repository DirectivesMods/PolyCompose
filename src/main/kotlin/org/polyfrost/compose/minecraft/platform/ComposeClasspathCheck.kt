package org.polyfrost.compose.minecraft.platform

import java.nio.file.Files
import net.fabricmc.loader.api.FabricLoader
import net.fabricmc.loader.api.ModContainer

internal object ComposeClasspathCheck {
    private val stockSkikoClasses = arrayOf(
        "org/jetbrains/skia/Canvas.class",
        "org/jetbrains/skiko/Library.class",
        "androidx/compose/ui/platform/ComposeUiSkikoRuntime_nonAndroidKt.class",
    )
    private const val SCENE_OWNER = "androidx/compose/ui/node/RootNodeOwner.class"
    private const val OWN_ID = "polycompose"
    private const val LIBRARY = "PolyCompose (polycompose)"
    private val remappedContext = "org/polyfrost/compose/skiko/SkiaGraphicsContext".toByteArray()

    fun verify() {
        val loader = ComposeClasspathCheck::class.java.classLoader
        for (name in stockSkikoClasses) {
            val source = loader.getResource(name) ?: continue
            val culprit = shippedBy { it.findPath(name).isPresent }
            error(
                "${culprit ?: "Another mod"} ships stock Compose with Skia ($source). " +
                    "PolyCompose replaces Skia and cannot run next to it. " +
                    "Remove ${culprit ?: "that mod"} or ask its author to depend on $LIBRARY instead.",
            )
        }
        for (ownerSource in loader.getResources(SCENE_OWNER)) {
            val owner = ownerSource.openStream().use { it.readBytes() }
            if (owner.contains(remappedContext)) continue
            val culprit = shippedBy { mod ->
                mod.findPath(SCENE_OWNER).map { !Files.readAllBytes(it).contains(remappedContext) }.orElse(false)
            }
            error(
                "A Compose UI copy without PolyCompose's Skia replacement is on the classpath ($ownerSource). " +
                    if (culprit != null) {
                        "It comes from $culprit. Remove it, or update it to a version that depends on $LIBRARY."
                    } else {
                        "Another mod probably bundles stock Compose. Remove that mod or ask its author to depend on $LIBRARY."
                    },
            )
        }
    }

    private fun shippedBy(contains: (ModContainer) -> Boolean): String? = runCatching {
        FabricLoader.getInstance().allMods
            .filter { rootOf(it).metadata.id != OWN_ID }
            .firstOrNull { runCatching { contains(it) }.getOrDefault(false) }
            ?.let { describe(rootOf(it)) }
    }.getOrNull()

    private fun rootOf(mod: ModContainer): ModContainer = generateSequence(mod) { it.containingMod.orElse(null) }.last()

    private fun describe(mod: ModContainer): String {
        val meta = mod.metadata
        return "${meta.name} (${meta.id} ${meta.version.friendlyString})"
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean {
        for (i in 0..size - needle.size) {
            var j = 0
            while (j < needle.size && this[i + j] == needle[j]) j++
            if (j == needle.size) return true
        }
        return false
    }
}
