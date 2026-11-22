package org.polyfrost.compose.node

import org.polyfrost.compose.render.RenderContext

public class SpacerNode : PolyNode() {
    override val tracksChanges: Boolean get() = true

    override fun render(ctx: RenderContext) {}
}
