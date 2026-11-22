package org.polyfrost.compose.minecraft.mc

import kotlin.jvm.optionals.getOrNull
import net.minecraft.client.Minecraft
import org.polyfrost.compose.render.PolyBitmap

//? if > 1.8.9 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.minecraft.server.packs.resources.ResourceManager
import net.ornithemc.osl.core.api.util.NamespacedIdentifiers
*///?}

internal object McResources {
    const val DEFAULT_NAMESPACE = "minecraft"

    fun namespace(id: String): String = if (':' in id) id.substringBefore(':') else DEFAULT_NAMESPACE

    fun path(id: String): String = id.substringAfter(':')

    fun read(namespace: String, path: String): ByteArray? = runCatching {
        //~ if = 1.8.9 'Minecraft.getInstance().resourceManager' -> 'ResourceManager.client()'
        val manager = Minecraft.getInstance().resourceManager
        //~ if = 1.8.9 'Identifier.fromNamespaceAndPath' -> 'NamespacedIdentifiers.from'
        manager.getResource(Identifier.fromNamespaceAndPath(namespace, path)).getOrNull()?.open()?.use { it.readAllBytes() }
    }.getOrNull()

    fun image(namespace: String, path: String): PolyBitmap? = read(namespace, path)?.let(PolyBitmap::decode)

    fun crop(source: PolyBitmap, x: Int, y: Int, w: Int, h: Int): PolyBitmap? {
        if (w <= 0 || h <= 0 || x < 0 || y < 0 || x + w > source.width || y + h > source.height) return null
        if (x == 0 && y == 0 && w == source.width && h == source.height) return source
        val out = ByteArray(w * h * 4)
        for (row in 0 until h) System.arraycopy(source.pixels, ((y + row) * source.width + x) * 4, out, row * w * 4, w * 4)
        return PolyBitmap(w, h, out)
    }
}
