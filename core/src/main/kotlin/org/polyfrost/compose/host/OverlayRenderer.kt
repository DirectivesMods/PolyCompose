package org.polyfrost.compose.host

import org.polyfrost.compose.render.RenderContext

public interface OverlayRenderer {
    public val visible: Boolean
    public fun prepare(guiWidth: Float, guiHeight: Float): Boolean
    public fun draw(ctx: RenderContext)
    public val showWhenGuiHidden: Boolean get() = false
}
