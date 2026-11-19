@file:OptIn(ExperimentalVersionOverloading::class)

package org.polyfrost.compose.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import org.polyfrost.compose.node.McTextNode
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.runtime.NodeApplier

@Composable
public fun NodeMcText(
    text: String,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: NodeModifier = NodeModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    NodeMcText(text, null, color, shadow, scale, modifier, literal)
}

@Composable
public fun NodeMcText(
    text: String,
    outlineColor: PolyColor?,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: NodeModifier = NodeModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    ComposeNode<McTextNode, NodeApplier>(
        factory = { McTextNode() },
        update = {
            set(literal) { this.literal = it }
            set(text) { this.text = it }
            set(scale) { this.scale = it }
            set(color) { this.color = it }
            set(shadow) { this.shadow = it }
            set(outlineColor) { this.outlineColor = it }
            set(modifier) { modifier.applyTo(style) }
        },
    )
}

@Composable
public fun NodeMcText(
    text: () -> String,
    outlineColor: PolyColor?,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: NodeModifier = NodeModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    ComposeNode<McTextNode, NodeApplier>(
        factory = { McTextNode() },
        update = {
            set(literal) { this.literal = it }
            set(text) { textProvider = it; this.text = it() }
            set(scale) { this.scale = it }
            set(color) { this.color = it }
            set(shadow) { this.shadow = it }
            set(outlineColor) { this.outlineColor = it }
            set(modifier) { modifier.applyTo(style) }
        },
    )
}
