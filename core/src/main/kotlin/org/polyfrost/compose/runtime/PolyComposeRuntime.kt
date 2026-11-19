package org.polyfrost.compose.runtime

import androidx.compose.runtime.*
import org.polyfrost.compose.layout.PolyLayoutEngine
import org.polyfrost.compose.node.PolyNode
import org.polyfrost.compose.node.RootNode
import org.polyfrost.compose.render.FontManager

public class PolyComposeRuntime(private val host: PolyComposeClock = PolyComposeHost.huds) {
    public val root: RootNode = RootNode()

    private val applier = PolyApplier(root)
    private val composition = Composition(applier, host.recomposer)

    public var revision: Long = 0L
        private set

    public fun setContent(content: @Composable () -> Unit) {
        laidOutChanges = -1L
        composition.setContent(content)
    }

    public fun frame(parentWidth: Float, parentHeight: Float, nanos: Long = System.nanoTime()) {
        host.frame(nanos)
        layout(parentWidth, parentHeight)
    }

    @Volatile private var laidOutChanges = -1L
    private var laidOutW = Float.NaN
    private var laidOutH = Float.NaN
    private var laidOutFonts = -1

    @Volatile private var contentStale = false
    private var hasLiveContent = false

    private val changes: Long get() = applier.changes

    public fun layout(parentWidth: Float, parentHeight: Float) {
        revision++
        contentStale = false
        laidOutChanges = changes
        laidOutW = parentWidth
        laidOutH = parentHeight
        laidOutFonts = FontManager.revision
        PolyLayoutEngine.layout(root, parentWidth, parentHeight)
        hasLiveContent = anyLiveContent(root)
    }

    public fun layoutIfChanged(parentWidth: Float, parentHeight: Float): Boolean {
        if (laidOutChanges == changes && laidOutW == parentWidth && laidOutH == parentHeight &&
            laidOutFonts == FontManager.revision
        ) {
            if (!contentStale) {
                if (!hasLiveContent) return false
                return when (remeasureContent(root)) {
                    SIZE_CHANGED -> {
                        layout(parentWidth, parentHeight)
                        true
                    }
                    CONTENT_CHANGED -> {
                        revision++
                        true
                    }
                    else -> false
                }
            }
            contentStale = false
            if (remeasureContent(root) == SIZE_CHANGED) {
                layout(parentWidth, parentHeight)
            } else {
                hasLiveContent = anyLiveContent(root)
                revision++
            }
            return true
        }
        layout(parentWidth, parentHeight)
        return true
    }

    public fun contentChanged() {
        contentStale = true
    }

    private fun remeasureContent(node: PolyNode): Int {
        val w = node.style.width
        val h = node.style.height
        val iw = node.intrinsicWidth
        val ih = node.intrinsicHeight
        val rev = node.contentRevision
        node.measureContent()
        var result = NONE
        if (node.style.width != w || node.style.height != h || node.intrinsicWidth != iw || node.intrinsicHeight != ih) {
            result = SIZE_CHANGED
        } else if (node.contentRevision != rev) {
            result = CONTENT_CHANGED
        }
        val children = node.children
        for (i in children.indices) result = maxOf(result, remeasureContent(children[i]))
        return result
    }

    private fun anyLiveContent(node: PolyNode): Boolean {
        if (node.hasLiveContent) return true
        val children = node.children
        for (i in children.indices) if (anyLiveContent(children[i])) return true
        return false
    }

    public fun invalidateLayout() {
        laidOutChanges = -1L
    }

    public fun dispose() {
        composition.dispose()
    }

    private companion object {
        const val NONE = 0
        const val CONTENT_CHANGED = 1
        const val SIZE_CHANGED = 2
    }
}
