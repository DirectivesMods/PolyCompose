package org.polyfrost.compose.minecraft.mc

import kotlin.math.ceil
import kotlin.math.floor
//? if > 1.8.9 {
import net.minecraft.world.item.ItemStack
//?} else
//import net.minecraft.item.ItemStack
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext

//? if > 1.8.9
import net.minecraft.client.Minecraft

internal object McItemDecorations {
    private const val ITEM = 16f
    private const val BAR_X = 2f
    private const val BAR_Y = 13f
    private const val BAR_WIDTH = 13f
    private const val COUNT_RIGHT = 17f
    private const val COUNT_TOP = 9f
    private const val WHITE = -1
    private val BAR_BACKGROUND = PolyColor(0xFF000000.toInt())
    //? if > 1.8.9
    private val COOLDOWN = PolyColor(0x7FFFFFFF)

    fun key(stack: ItemStack): Long {
        if (isEmpty(stack)) return 0L
        var key = count(stack).toLong()
        key = key * 31 + (if (barVisible(stack)) 1 else 0)
        key = key * 31 + barWidth(stack)
        key = key * 31 + barColor(stack)
        key = key * 31 + (if (cooldown(stack) > 0f) 1 else 0)
        return key
    }

    fun draw(ctx: RenderContext, stack: ItemStack, x: Float, y: Float, size: Float, countOverride: String?) {
        if (size <= 0f) return
        val empty = isEmpty(stack)
        val scale = size / ITEM
        if (!empty) drawBar(ctx, stack, x, y, scale)
        val text = countOverride ?: if (!empty && count(stack) > 1) count(stack).toString() else null
        if (!text.isNullOrEmpty()) {
            val width = McFontQueue.measureTextWidth(text, 1f)
            ctx.mcText(text, x + (COUNT_RIGHT - width) * scale, y + COUNT_TOP * scale, WHITE, true, scale)
        }
        //? if > 1.8.9 {
        if (!empty && cooldown(stack) > 0f) {
            ctx.live {
                val progress = cooldown(stack)
                if (progress > 0f) {
                    val top = floor(ITEM * (1f - progress))
                    val height = ceil(ITEM * progress)
                    rect(x, y + top * scale, size, height * scale, COOLDOWN)
                }
            }
        }
        //?}
    }

    private fun drawBar(ctx: RenderContext, stack: ItemStack, x: Float, y: Float, scale: Float) {
        if (!barVisible(stack)) return
        ctx.rect(x + BAR_X * scale, y + BAR_Y * scale, BAR_WIDTH * scale, 2f * scale, BAR_BACKGROUND)
        //? if = 1.8.9 {
        /*val health = legacyHealth(stack)
        ctx.rect(x + BAR_X * scale, y + BAR_Y * scale, (BAR_WIDTH - 1f) * scale, scale, PolyColor(0xFF000000.toInt() or ((255 - health) / 4 shl 16) or (64 shl 8)))
        *///?}
        ctx.rect(x + BAR_X * scale, y + BAR_Y * scale, barWidth(stack) * scale, scale, PolyColor(0xFF000000.toInt() or (barColor(stack) and 0xFFFFFF)))
    }

    fun isEmpty(stack: ItemStack): Boolean {
        //? if > 1.8.9 {
        return stack.isEmpty
        //?} else
        //return stack.item == null || stack.count <= 0
    }

    private fun count(stack: ItemStack): Int = stack.count

    private fun barVisible(stack: ItemStack): Boolean {
        //? if > 1.8.9 {
        return stack.isBarVisible
        //?} else
        //return stack.isDamaged
    }

    private fun barWidth(stack: ItemStack): Int {
        //? if > 1.8.9 {
        return if (stack.isBarVisible) stack.barWidth else 0
        //?} else {
        /*if (!stack.isDamaged) return 0
        return Math.round(13.0 - stack.damage * 13.0 / stack.maxDamage).toInt()
        *///?}
    }

    private fun barColor(stack: ItemStack): Int {
        //? if > 1.8.9 {
        return if (stack.isBarVisible) stack.barColor else 0
        //?} else {
        /*if (!stack.isDamaged) return 0
        val health = legacyHealth(stack)
        return (255 - health) shl 16 or (health shl 8)
        *///?}
    }

    //? if = 1.8.9 {
    /*private fun legacyHealth(stack: ItemStack): Int = Math.round(255.0 - stack.damage * 255.0 / stack.maxDamage).toInt()
    *///?}

    fun cooldown(stack: ItemStack): Float {
        //? if > 1.8.9 {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return 0f
        //? if >= 1.21.4 {
        return player.cooldowns.getCooldownPercent(stack, mc.deltaTracker.getGameTimeDeltaPartialTick(true))
        //?} else
        //return player.cooldowns.getCooldownPercent(stack.item, mc.timer.getGameTimeDeltaPartialTick(true))
        //?} else
        //return 0f
    }
}
