package org.polyfrost.compose.node

public class RootNode : PolyNode() {
    override val tracksChanges: Boolean get() = true
}
