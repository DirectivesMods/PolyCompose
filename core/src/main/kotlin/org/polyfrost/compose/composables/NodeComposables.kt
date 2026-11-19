@file:OptIn(ExperimentalVersionOverloading::class)

package org.polyfrost.compose.composables

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ComposeNode
import org.polyfrost.compose.layout.LayoutType
import org.polyfrost.compose.layout.PolySize
import org.polyfrost.compose.node.BaseNode
import org.polyfrost.compose.node.CanvasNode
import org.polyfrost.compose.node.ImageNode
import org.polyfrost.compose.node.SpacerNode
import org.polyfrost.compose.node.TextNode
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.compose.runtime.NodeApplier

public typealias NodeModifier = PolyModifier

@Composable
public fun NodeBox(
    modifier: NodeModifier = NodeModifier,
    content: @Composable () -> Unit = {},
) {
    ComposeNode<BaseNode, NodeApplier>(
        factory = { BaseNode().also { it.style.layoutType = LayoutType.Box } },
        update = { set(modifier) { modifier.applyTo(style) } },
        content = content,
    )
}

@Composable
public fun NodeImage(
    modifier: NodeModifier = NodeModifier,
    image: PolyBitmap,
) {
    ComposeNode<ImageNode, NodeApplier>(
        factory = { ImageNode(image).also { it.style.layoutType = LayoutType.Box; it.remeasure() } },
        update = {
            set(modifier) { modifier.applyTo(style) }
            set(image) { setImage(it) }
        },
    )
}

@Composable
public fun NodeRow(
    gap: Float = 0f,
    modifier: NodeModifier = NodeModifier,
    content: @Composable () -> Unit = {},
) {
    ComposeNode<BaseNode, NodeApplier>(
        factory = { BaseNode().also { it.style.layoutType = LayoutType.Row } },
        update = {
            set(gap) { style.gap = it }
            set(modifier) { modifier.applyTo(style) }
        },
        content = content,
    )
}

@Composable
public fun NodeColumn(
    gap: Float = 0f,
    modifier: NodeModifier = NodeModifier,
    content: @Composable () -> Unit = {},
) {
    ComposeNode<BaseNode, NodeApplier>(
        factory = { BaseNode().also { it.style.layoutType = LayoutType.Column } },
        update = {
            set(gap) { style.gap = it }
            set(modifier) { modifier.applyTo(style) }
        },
        content = content,
    )
}

@Composable
public fun NodeText(
    text: String,
    color: PolyColor = PolyColor.WHITE,
    fontSize: Float = 12f,
    shadow: Boolean = false,
    shadowColor: PolyColor = PolyColor(0x80000000.toInt()),
    shadowOffset: Float = 1f,
    font: String? = null,
    modifier: NodeModifier = NodeModifier,
) {
    ComposeNode<TextNode, NodeApplier>(
        factory = { TextNode() },
        update = {
            set(text) { this.text = it }
            set(fontSize) { this.fontSize = it }
            set(color) { this.color = it }
            set(shadow) { this.shadow = it }
            set(shadowColor) { this.shadowColor = it }
            set(shadowOffset) { this.shadowOffset = it }
            set(font) { this.fontName = it }
            set(modifier) { modifier.applyTo(style) }
        },
    )
}

@Composable
public fun NodeSpacer(width: Float = 0f, height: Float = 0f) {
    ComposeNode<SpacerNode, NodeApplier>(
        factory = { SpacerNode() },
        update = {
            set(width) { style.width = PolySize.Px(it) }
            set(height) { style.height = PolySize.Px(it) }
        },
    )
}

@Composable
public fun NodeCanvas(
    modifier: NodeModifier,
    @IntroducedAt("1.6.0") live: Boolean = false,
    onDraw: RenderContext.(x: Float, y: Float, width: Float, height: Float) -> Unit,
) {
    ComposeNode<CanvasNode, NodeApplier>(
        factory = { CanvasNode() },
        update = {
            set(live) { this.live = it }
            set(modifier) { modifier.applyTo(style) }
            set(onDraw) { this.onDraw = it }
        },
    )
}

@Composable
public fun NodeRect(
    color: PolyColor,
    modifier: NodeModifier = NodeModifier,
) {
    ComposeNode<BaseNode, NodeApplier>(
        factory = { BaseNode().also { it.style.layoutType = LayoutType.Box } },
        update = {
            set(modifier) { modifier.applyTo(style); style.background = color }
            set(color) { style.background = it }
        },
    )
}
