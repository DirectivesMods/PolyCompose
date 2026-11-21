package org.polyfrost.compose.minecraft.screen

internal object ScrollMath {
    fun notches(scroll: Double): Float = (-scroll).toFloat()

    fun horizontal(shift: Boolean, x: Float, y: Float): Float = if (shift && y != 0f) y else x

    fun vertical(shift: Boolean, y: Float): Float = if (shift) 0f else y
}
