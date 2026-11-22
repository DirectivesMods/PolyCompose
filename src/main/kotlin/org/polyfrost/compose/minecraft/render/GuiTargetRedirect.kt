package org.polyfrost.compose.minecraft.render

import com.mojang.blaze3d.pipeline.RenderTarget

object GuiTargetRedirect {
    fun interface ScissorTransform {
        fun map(left: Int, top: Int, right: Int, bottom: Int): IntArray
    }

    @JvmField
    var target: RenderTarget? = null

    @JvmField
    var scissorTransform: ScissorTransform? = null

    @JvmField
    var itemRenderSizePx: Int = 0

    @JvmField
    var suppressItemGlint: Boolean = false

    @JvmField
    var itemGlintSuppressed: Boolean = false
}
