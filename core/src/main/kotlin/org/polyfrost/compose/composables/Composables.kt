@file:OptIn(ExperimentalVersionOverloading::class)

package org.polyfrost.compose.composables

import androidx.compose.runtime.Composable
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.RenderContext

@Deprecated(
    "Use NodeBox",
    ReplaceWith("NodeBox(modifier, content)", "org.polyfrost.compose.composables.NodeBox"),
)
@Composable
public fun PolyBox(
    modifier: PolyModifier = PolyModifier,
    content: @Composable () -> Unit = {},
) {
    NodeBox(modifier, content)
}

@Deprecated(
    "Use NodeImage",
    ReplaceWith("NodeImage(modifier, image)", "org.polyfrost.compose.composables.NodeImage"),
)
@Composable
public fun PolyImage(
    modifier: PolyModifier = PolyModifier,
    image: PolyBitmap,
) {
    NodeImage(modifier, image)
}

@Deprecated(
    "Use NodeRow",
    ReplaceWith("NodeRow(gap, modifier, content)", "org.polyfrost.compose.composables.NodeRow"),
)
@Composable
public fun PolyRow(
    gap: Float = 0f,
    modifier: PolyModifier = PolyModifier,
    content: @Composable () -> Unit = {},
) {
    NodeRow(gap, modifier, content)
}

@Deprecated(
    "Use NodeColumn",
    ReplaceWith("NodeColumn(gap, modifier, content)", "org.polyfrost.compose.composables.NodeColumn"),
)
@Composable
public fun PolyColumn(
    gap: Float = 0f,
    modifier: PolyModifier = PolyModifier,
    content: @Composable () -> Unit = {},
) {
    NodeColumn(gap, modifier, content)
}

@Deprecated(
    "Use NodeText",
    ReplaceWith(
        "NodeText(text, color, fontSize, shadow, shadowColor, shadowOffset, font, modifier)",
        "org.polyfrost.compose.composables.NodeText",
    ),
)
@Composable
public fun PolyText(
    text: String,
    color: PolyColor = PolyColor.WHITE,
    fontSize: Float = 12f,
    shadow: Boolean = false,
    shadowColor: PolyColor = PolyColor(0x80000000.toInt()),
    shadowOffset: Float = 1f,
    font: String? = null,
    modifier: PolyModifier = PolyModifier,
) {
    NodeText(text, color, fontSize, shadow, shadowColor, shadowOffset, font, modifier)
}

@Deprecated(
    "Use NodeMcText",
    ReplaceWith("NodeMcText(text, color, shadow, scale, modifier, literal)", "org.polyfrost.compose.composables.NodeMcText"),
)
@Composable
public fun PolyMcText(
    text: String,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: PolyModifier = PolyModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    NodeMcText(text, null, color, shadow, scale, modifier, literal)
}

@Deprecated(
    "Use NodeMcText",
    ReplaceWith("NodeMcText(text, outlineColor, color, shadow, scale, modifier, literal)", "org.polyfrost.compose.composables.NodeMcText"),
)
@Composable
public fun PolyMcText(
    text: String,
    outlineColor: PolyColor?,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: PolyModifier = PolyModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    NodeMcText(text, outlineColor, color, shadow, scale, modifier, literal)
}

@Deprecated(
    "Use NodeMcText",
    ReplaceWith("NodeMcText(text, outlineColor, color, shadow, scale, modifier, literal)", "org.polyfrost.compose.composables.NodeMcText"),
)
@Composable
public fun PolyMcText(
    text: () -> String,
    outlineColor: PolyColor?,
    color: PolyColor = PolyColor.WHITE,
    shadow: Boolean = true,
    scale: Float = 1f,
    modifier: PolyModifier = PolyModifier,
    @IntroducedAt("1.8.0") literal: Boolean = false,
) {
    NodeMcText(text, outlineColor, color, shadow, scale, modifier, literal)
}

@Deprecated(
    "Use NodeSpacer",
    ReplaceWith("NodeSpacer(width, height)", "org.polyfrost.compose.composables.NodeSpacer"),
)
@Composable
public fun PolySpacer(width: Float = 0f, height: Float = 0f) {
    NodeSpacer(width, height)
}

@Deprecated(
    "Use NodeCanvas",
    ReplaceWith("NodeCanvas(modifier, live, onDraw)", "org.polyfrost.compose.composables.NodeCanvas"),
)
@Composable
public fun PolyCanvas(
    modifier: PolyModifier,
    @IntroducedAt("1.6.0") live: Boolean = false,
    onDraw: RenderContext.(x: Float, y: Float, width: Float, height: Float) -> Unit,
) {
    NodeCanvas(modifier, live, onDraw)
}

@Deprecated(
    "Use NodeRect",
    ReplaceWith("NodeRect(color, modifier)", "org.polyfrost.compose.composables.NodeRect"),
)
@Composable
public fun PolyRect(
    color: PolyColor,
    modifier: PolyModifier = PolyModifier,
) {
    NodeRect(color, modifier)
}
