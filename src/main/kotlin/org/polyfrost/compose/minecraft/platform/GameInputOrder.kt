package org.polyfrost.compose.minecraft.platform

import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.GameInputLayer
import org.polyfrost.compose.host.HostCallbacks

internal class GameInputSnapshot(val layers: List<GameInputLayer>, private val orders: IntArray) {
    val size: Int
        get() = layers.size

    fun order(index: Int): Int = orders[index]

    fun aboveHud(index: Int): Boolean = orders[index] > GameInputLayer.HUD_LAYERS
}

internal object GameInputOrder {
    private val empty = GameInputSnapshot(emptyList(), IntArray(0))
    private var source: List<GameInputLayer> = emptyList()
    private var snapshot = empty

    @JvmStatic
    fun current(): GameInputSnapshot {
        val layers = ComposeHost.gameInput
        if (matches(layers)) return snapshot
        val copy = ArrayList(layers)
        val keyed = copy.filterNot(HostCallbacks::isDisabled).map { it to orderOf(it) }
        val next = if (keyed.size > 1) keyed.sortedByDescending { it.second } else keyed
        source = copy
        snapshot = if (next.isEmpty()) empty else GameInputSnapshot(next.map { it.first }, IntArray(next.size) { next[it].second })
        return snapshot
    }

    private fun orderOf(layer: GameInputLayer): Int =
        HostCallbacks.call(layer, "GameInputLayer.order", GameInputLayer.BELOW_HUD_LAYERS) { layer.order }

    private fun matches(layers: List<GameInputLayer>): Boolean {
        val source = source
        if (layers.size != source.size) return false
        var i = 0
        for (layer in layers) {
            if (i >= source.size || layer !== source[i]) return false
            i++
        }
        if (i != source.size) return false
        val snapshot = snapshot
        for (j in 0 until snapshot.size) {
            val layer = snapshot.layers[j]
            if (HostCallbacks.isDisabled(layer) || orderOf(layer) != snapshot.order(j)) return false
        }
        return true
    }
}
