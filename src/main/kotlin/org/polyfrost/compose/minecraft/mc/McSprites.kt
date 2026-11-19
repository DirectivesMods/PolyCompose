package org.polyfrost.compose.minecraft.mc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.composables.NodeCanvas
import org.polyfrost.compose.composables.PolyModifier
import org.polyfrost.compose.composables.size
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.render.FilterQuality
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext

//? if > 1.8.9 {
import com.google.gson.JsonObject
import com.google.gson.JsonParser
//?}

class McSpriteRegion @JvmOverloads constructor(
    val u: Float = 0f,
    val v: Float = 0f,
    val width: Float = 0f,
    val height: Float = 0f,
    val textureWidth: Float = 0f,
    val textureHeight: Float = 0f,
) {
    companion object {
        @JvmField
        val FULL: McSpriteRegion = McSpriteRegion()
    }
}

internal sealed class GuiSpriteScaling {
    object Stretch : GuiSpriteScaling()

    class Tile(val width: Int, val height: Int) : GuiSpriteScaling()

    class NineSlice(
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
        val stretchInner: Boolean,
    ) : GuiSpriteScaling()
}

private class GuiSpriteEntry(val bitmap: PolyBitmap, val scaling: GuiSpriteScaling)

object McSprites {
    private const val MAX_ENTRIES = 256
    private const val MAX_TILES = 4096

    private val revisionState = mutableIntStateOf(0)
    private val cache = object : LinkedHashMap<String, PolyBitmap?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, PolyBitmap?>): Boolean = size > MAX_ENTRIES
    }
    private val guiCache = object : LinkedHashMap<String, GuiSpriteEntry?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, GuiSpriteEntry?>): Boolean = size > MAX_ENTRIES
    }
    private var reloadHook: AutoCloseable? = null

    @JvmStatic
    val revision: Int
        get() = revisionState.intValue

    @JvmStatic
    val guiSprites: Boolean
        //? if > 1.8.9 {
        get() = true
        //?} else
        //get() = false

    @JvmStatic
    fun texture(id: String): PolyBitmap? = load("t|$id") {
        val path = McResources.path(id)
        McResources.image(McResources.namespace(id), if (path.endsWith(".png")) path else "$path.png")
    }

    @JvmStatic
    fun guiSprite(id: String): PolyBitmap? = guiSpriteEntry(id)?.bitmap

    @JvmStatic
    @JvmOverloads
    fun drawGuiSprite(
        ctx: RenderContext,
        id: String,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        pixelScale: Float = 1f,
        tint: Int = -1,
        filter: FilterQuality = FilterQuality.None,
    ): Boolean {
        val entry = guiSpriteEntry(id) ?: return false
        if (w <= 0f || h <= 0f) return true
        val unit = if (pixelScale > 0f) pixelScale else 1f
        val bitmap = entry.bitmap
        val color = PolyColor(tint)
        when (val scaling = entry.scaling) {
            GuiSpriteScaling.Stretch -> ctx.image(bitmap, x, y, w, h, 0f, 0f, 1f, 1f, color, filter)
            is GuiSpriteScaling.Tile -> {
                val tw = scaling.width.toFloat()
                val th = scaling.height.toFloat()
                tiled(ctx, bitmap, color, filter, x, y, unit, 0f, 0f, w / unit, h / unit, 0f, 0f, tw, th, tw, th)
            }
            is GuiSpriteScaling.NineSlice -> nineSlice(ctx, bitmap, color, filter, x, y, unit, w / unit, h / unit, scaling)
        }
        return true
    }

    @JvmStatic
    fun atlasSprite(id: String): PolyBitmap? = load("a|$id") {
        firstFrame(McResources.namespace(id), "textures/${McResources.path(id)}.png")
    }

    @JvmStatic
    fun mobEffect(id: String): PolyBitmap? = atlasSprite("${McResources.namespace(id)}:mob_effect/${McResources.path(id)}")

    @JvmStatic
    fun invalidate() {
        synchronized(cache) { cache.clear() }
        synchronized(guiCache) { guiCache.clear() }
        Snapshot.withMutableSnapshot { revisionState.intValue++ }
    }

    @JvmStatic
    @JvmOverloads
    fun draw(
        ctx: RenderContext,
        bitmap: PolyBitmap,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        region: McSpriteRegion = McSpriteRegion.FULL,
        tint: Int = -1,
        filter: FilterQuality = FilterQuality.None,
    ) {
        if (w <= 0f || h <= 0f) return
        val tw = if (region.textureWidth > 0f) region.textureWidth else bitmap.width.toFloat()
        val th = if (region.textureHeight > 0f) region.textureHeight else bitmap.height.toFloat()
        val rw = if (region.width > 0f) region.width else tw - region.u
        val rh = if (region.height > 0f) region.height else th - region.v
        ctx.image(bitmap, x, y, w, h, region.u / tw, region.v / th, (region.u + rw) / tw, (region.v + rh) / th, PolyColor(tint), filter)
    }

    private inline fun load(key: String, read: () -> PolyBitmap?): PolyBitmap? {
        ensureReloadHook()
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val bitmap = runCatching(read).getOrNull()
        synchronized(cache) { cache[key] = bitmap }
        return bitmap
    }

    private fun guiSpriteEntry(id: String): GuiSpriteEntry? {
        if (!guiSprites) return null
        ensureReloadHook()
        synchronized(guiCache) { if (guiCache.containsKey(id)) return guiCache[id] }
        val entry = runCatching { readGuiSprite(id) }.getOrNull()
        synchronized(guiCache) { guiCache[id] = entry }
        return entry
    }

    private fun readGuiSprite(id: String): GuiSpriteEntry? {
        val namespace = McResources.namespace(id)
        val path = "textures/gui/sprites/${McResources.path(id)}.png"
        val image = McResources.image(namespace, path) ?: return null
        val meta = McResources.read(namespace, "$path.mcmeta")?.toString(Charsets.UTF_8)
        return GuiSpriteEntry(firstFrame(image, meta), parseScaling(meta))
    }

    private fun parseScaling(meta: String?): GuiSpriteScaling {
        if (meta == null || !meta.contains("\"scaling\"")) return GuiSpriteScaling.Stretch
        //? if > 1.8.9 {
        val scaling = runCatching {
            JsonParser.parseString(meta).asJsonObject.getAsJsonObject("gui")?.getAsJsonObject("scaling")
        }.getOrNull() ?: return GuiSpriteScaling.Stretch
        return runCatching { readScaling(scaling) }.getOrNull() ?: GuiSpriteScaling.Stretch
        //?} else
        //return GuiSpriteScaling.Stretch
    }

    //? if > 1.8.9 {
    private fun readScaling(json: JsonObject): GuiSpriteScaling? {
        val type = json.get("type")?.asString ?: return null
        if (type == "stretch") return GuiSpriteScaling.Stretch
        val width = json.get("width").asInt
        val height = json.get("height").asInt
        if (width <= 0 || height <= 0) return null
        if (type == "tile") return GuiSpriteScaling.Tile(width, height)
        if (type != "nine_slice") return null
        val border = json.get("border")
        val stretchInner = json.get("stretch_inner")?.asBoolean ?: false
        if (border.isJsonPrimitive) {
            val size = border.asInt
            return GuiSpriteScaling.NineSlice(width, height, size, size, size, size, stretchInner)
        }
        val sides = border.asJsonObject
        return GuiSpriteScaling.NineSlice(
            width,
            height,
            sides.get("left").asInt,
            sides.get("top").asInt,
            sides.get("right").asInt,
            sides.get("bottom").asInt,
            stretchInner,
        )
    }
    //?}

    @Suppress("LongParameterList")
    private fun nineSlice(
        ctx: RenderContext,
        bitmap: PolyBitmap,
        color: PolyColor,
        filter: FilterQuality,
        ox: Float,
        oy: Float,
        unit: Float,
        w: Float,
        h: Float,
        slice: GuiSpriteScaling.NineSlice,
    ) {
        val sw = slice.width.toFloat()
        val sh = slice.height.toFloat()
        val left = minOf(slice.left.toFloat(), w / 2f)
        val right = minOf(slice.right.toFloat(), w / 2f)
        val top = minOf(slice.top.toFloat(), h / 2f)
        val bottom = minOf(slice.bottom.toFloat(), h / 2f)
        val innerW = w - right - left
        val innerH = h - bottom - top
        val innerU = sw - right - left
        val innerV = sh - bottom - top
        val inner = slice.stretchInner
        blit(ctx, bitmap, color, filter, ox, oy, unit, 0f, 0f, left, top, 0f, 0f, sw, sh)
        segment(ctx, bitmap, color, filter, ox, oy, unit, inner, left, 0f, innerW, top, left, 0f, innerU, top, sw, sh)
        blit(ctx, bitmap, color, filter, ox, oy, unit, w - right, 0f, right, top, sw - right, 0f, sw, sh)
        blit(ctx, bitmap, color, filter, ox, oy, unit, 0f, h - bottom, left, bottom, 0f, sh - bottom, sw, sh)
        segment(ctx, bitmap, color, filter, ox, oy, unit, inner, left, h - bottom, innerW, bottom, left, sh - bottom, innerU, bottom, sw, sh)
        blit(ctx, bitmap, color, filter, ox, oy, unit, w - right, h - bottom, right, bottom, sw - right, sh - bottom, sw, sh)
        segment(ctx, bitmap, color, filter, ox, oy, unit, inner, 0f, top, left, innerH, 0f, top, left, innerV, sw, sh)
        segment(ctx, bitmap, color, filter, ox, oy, unit, inner, left, top, innerW, innerH, left, top, innerU, innerV, sw, sh)
        segment(ctx, bitmap, color, filter, ox, oy, unit, inner, w - right, top, right, innerH, sw - right, top, right, innerV, sw, sh)
    }

    @Suppress("LongParameterList")
    private fun segment(
        ctx: RenderContext,
        bitmap: PolyBitmap,
        color: PolyColor,
        filter: FilterQuality,
        ox: Float,
        oy: Float,
        unit: Float,
        stretch: Boolean,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        u: Float,
        v: Float,
        uw: Float,
        vh: Float,
        sw: Float,
        sh: Float,
    ) {
        if (w <= 0f || h <= 0f) return
        if (stretch) {
            ctx.image(bitmap, ox + x * unit, oy + y * unit, w * unit, h * unit, u / sw, v / sh, (u + uw) / sw, (v + vh) / sh, color, filter)
        } else {
            tiled(ctx, bitmap, color, filter, ox, oy, unit, x, y, w, h, u, v, uw, vh, sw, sh)
        }
    }

    @Suppress("LongParameterList")
    private fun tiled(
        ctx: RenderContext,
        bitmap: PolyBitmap,
        color: PolyColor,
        filter: FilterQuality,
        ox: Float,
        oy: Float,
        unit: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        u: Float,
        v: Float,
        tileW: Float,
        tileH: Float,
        sw: Float,
        sh: Float,
    ) {
        if (w <= 0f || h <= 0f || tileW <= 0f || tileH <= 0f) return
        if ((w / tileW) * (h / tileH) > MAX_TILES) {
            ctx.image(bitmap, ox + x * unit, oy + y * unit, w * unit, h * unit, u / sw, v / sh, (u + tileW) / sw, (v + tileH) / sh, color, filter)
            return
        }
        var i = 0f
        while (i < w) {
            val tw = minOf(tileW, w - i)
            var j = 0f
            while (j < h) {
                val th = minOf(tileH, h - j)
                blit(ctx, bitmap, color, filter, ox, oy, unit, x + i, y + j, tw, th, u, v, sw, sh)
                j += tileH
            }
            i += tileW
        }
    }

    @Suppress("LongParameterList")
    private fun blit(
        ctx: RenderContext,
        bitmap: PolyBitmap,
        color: PolyColor,
        filter: FilterQuality,
        ox: Float,
        oy: Float,
        unit: Float,
        x: Float,
        y: Float,
        w: Float,
        h: Float,
        u: Float,
        v: Float,
        sw: Float,
        sh: Float,
    ) {
        if (w <= 0f || h <= 0f) return
        ctx.image(bitmap, ox + x * unit, oy + y * unit, w * unit, h * unit, u / sw, v / sh, (u + w) / sw, (v + h) / sh, color, filter)
    }

    private fun ensureReloadHook() {
        if (reloadHook == null) reloadHook = HostEvents.onResourcesReloaded(::invalidate)
    }

    private fun firstFrame(namespace: String, path: String): PolyBitmap? {
        val image = McResources.image(namespace, path) ?: return null
        return firstFrame(image, McResources.read(namespace, "$path.mcmeta")?.toString(Charsets.UTF_8))
    }

    private fun firstFrame(image: PolyBitmap, meta: String?): PolyBitmap {
        if (meta == null || !meta.contains("\"animation\"")) return image
        val animation = animationBlock(meta)
        val frameW = FRAME_WIDTH.find(animation)?.groupValues?.get(1)?.toIntOrNull()
        val frameH = FRAME_HEIGHT.find(animation)?.groupValues?.get(1)?.toIntOrNull()
        val side = minOf(image.width, image.height)
        return McResources.crop(image, 0, 0, frameW ?: frameH ?: side, frameH ?: frameW ?: side) ?: image
    }

    private fun animationBlock(meta: String): String {
        val key = meta.indexOf("\"animation\"")
        val start = meta.indexOf('{', key)
        if (key < 0 || start < 0) return ""
        var depth = 0
        for (i in start until meta.length) {
            when (meta[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return meta.substring(start, i + 1)
            }
        }
        return meta.substring(start)
    }

    private val FRAME_WIDTH = Regex("\"width\"\\s*:\\s*(\\d+)")
    private val FRAME_HEIGHT = Regex("\"height\"\\s*:\\s*(\\d+)")
}

enum class McSpriteKind { Texture, GuiSprite, AtlasSprite }

private fun resolveSprite(kind: McSpriteKind, id: String): PolyBitmap? = when (kind) {
    McSpriteKind.Texture -> McSprites.texture(id)
    McSpriteKind.GuiSprite -> McSprites.guiSprite(id)
    McSpriteKind.AtlasSprite -> McSprites.atlasSprite(id)
}

@Composable
fun McSprite(
    id: String,
    modifier: Modifier = Modifier,
    kind: McSpriteKind = McSpriteKind.Texture,
    region: McSpriteRegion = McSpriteRegion.FULL,
    size: Dp = Dp.Unspecified,
    tint: Int = -1,
    filter: FilterQuality = FilterQuality.None,
) {
    val sized = if (size != Dp.Unspecified) modifier.size(size) else modifier
    Box(
        sized.drawBehind {
            McSprites.revision
            val w = this.size.width
            val h = this.size.height
            if (kind == McSpriteKind.GuiSprite && region === McSpriteRegion.FULL) {
                val unit = density
                drawPoly { McSprites.drawGuiSprite(it, id, 0f, 0f, w, h, unit, tint, filter) }
                return@drawBehind
            }
            val bitmap = resolveSprite(kind, id) ?: return@drawBehind
            drawPoly { McSprites.draw(it, bitmap, 0f, 0f, w, h, region, tint, filter) }
        },
    )
}

@Composable
fun McGuiSprite(id: String, modifier: Modifier = Modifier, size: Dp = 16.dp, tint: Int = -1) {
    McSprite(id, modifier, McSpriteKind.GuiSprite, size = size, tint = tint)
}

@Composable
fun NodeMcSprite(
    id: String,
    width: Float,
    height: Float,
    modifier: PolyModifier = PolyModifier,
    kind: McSpriteKind = McSpriteKind.Texture,
    region: McSpriteRegion = McSpriteRegion.FULL,
    tint: Int = -1,
    filter: FilterQuality = FilterQuality.None,
) {
    val revision = McSprites.revision
    val onDraw: RenderContext.(Float, Float, Float, Float) -> Unit = remember(revision, id, kind, region, tint, filter) {
        { x, y, w, h ->
            if (kind == McSpriteKind.GuiSprite && region === McSpriteRegion.FULL) {
                McSprites.drawGuiSprite(this, id, x, y, w, h, 1f, tint, filter)
            } else {
                val bitmap = resolveSprite(kind, id)
                if (bitmap != null) McSprites.draw(this, bitmap, x, y, w, h, region, tint, filter)
            }
        }
    }
    NodeCanvas(modifier.size(width, height), onDraw = onDraw)
}
