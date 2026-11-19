@file:Suppress("INVISIBLE_REFERENCE")

package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.HitTestResult
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.node.requireLayoutNode
import androidx.compose.ui.node.requireOwner
import androidx.compose.ui.unit.Constraints

internal class HudSlotGate {
    var raised by mutableStateOf(false)

    var painting = false
        set(value) {
            field = value
            node?.layer?.alpha = alphaFor(value)
        }

    private var node: Node? = null
    private val result = HitTestResult()

    val modifier: Modifier = Element(this)

    fun hits(position: Offset): Boolean {
        val node = node ?: return false
        if (!node.isAttached) return false
        val layoutNode = node.requireLayoutNode()
        if (!layoutNode.isPlaced) return false
        val parent = layoutNode.parent ?: return false
        val root = node.requireOwner().root
        val local = parent.innerCoordinator.localPositionOf(root.outerCoordinator, position)
        result.clear()
        return try {
            layoutNode.hitTest(local, result, PointerType.Mouse)
            result.hasHit()
        } finally {
            result.clear()
        }
    }

    private class Element(private val gate: HudSlotGate) : ModifierNodeElement<Node>() {
        override fun create(): Node = Node(gate)

        override fun update(node: Node) {
            node.gate = gate
        }

        override fun equals(other: Any?): Boolean = other is Element && other.gate === gate

        override fun hashCode(): Int = System.identityHashCode(gate)
    }

    private class Node(var gate: HudSlotGate) : Modifier.Node(), LayoutModifierNode {
        var layer: GraphicsLayer? = null

        override fun onAttach() {
            layer = requireGraphicsContext().createGraphicsLayer().also { it.alpha = alphaFor(gate.painting) }
            gate.node = this
        }

        override fun onDetach() {
            if (gate.node === this) gate.node = null
            layer?.let(requireGraphicsContext()::releaseGraphicsLayer)
            layer = null
        }

        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                val zIndex = if (gate.raised) 1f else 0f
                val layer = layer
                if (layer != null) placeable.placeWithLayer(0, 0, layer, zIndex) else placeable.place(0, 0, zIndex)
            }
        }
    }

    private companion object {
        fun alphaFor(painting: Boolean): Float = if (painting) 1f else 0f
    }
}
