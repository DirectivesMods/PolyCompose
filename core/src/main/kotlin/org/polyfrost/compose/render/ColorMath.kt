package org.polyfrost.compose.render

import kotlin.math.roundToInt

internal fun alphaByte(alpha: Float): Int = (alpha * 255f).roundToInt().coerceIn(0, 255)
