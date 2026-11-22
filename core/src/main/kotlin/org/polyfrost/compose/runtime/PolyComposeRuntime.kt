package org.polyfrost.compose.runtime

import androidx.compose.runtime.*
import org.polyfrost.compose.layout.PolyLayoutEngine
import org.polyfrost.compose.node.ChangeTracker
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
    private var laidOutLayout = -1L
    private var laidOutPaint = 0L
    private var laidOutW = Float.NaN
    private var laidOutH = Float.NaN
    private var laidOutFonts = -1

    @Volatile private var contentStale = false

    private val changes: Long get() = applier.changes
    private val tracker: ChangeTracker get() = applier.tracker

    public fun layout(parentWidth: Float, parentHeight: Float) {
        revision++
        contentStale = false
        laidOutChanges = changes
        laidOutW = parentWidth
        laidOutH = parentHeight
        laidOutFonts = FontManager.revision
        PolyLayoutEngine.layout(root, parentWidth, parentHeight)
        val t = tracker
        t.clearStyles()
        laidOutLayout = t.layout
        laidOutPaint = t.paint
    }

    public fun layoutIfChanged(parentWidth: Float, parentHeight: Float): Boolean {
        val seen = laidOutChanges
        val t = tracker
        if (seen == -1L || t.settle() != laidOutLayout || laidOutW != parentWidth || laidOutH != parentHeight ||
            laidOutFonts != FontManager.revision
        ) {
            layout(parentWidth, parentHeight)
            return true
        }
        val current = changes
        val paint = t.paint
        val painted = seen != current || paint != laidOutPaint
        if (painted) {
            laidOutChanges = current
            laidOutPaint = paint
        }
        if (contentStale) {
            contentStale = false
            if (remeasureContent(root) == SIZE_CHANGED) layout(parentWidth, parentHeight) else revision++
            return true
        }
        val live = t.live
        val result = when {
            live.isEmpty() -> NONE
            t.untracked > 0 -> remeasureContent(root)
            else -> remeasureLive(live)
        }
        if (result == SIZE_CHANGED) {
            layout(parentWidth, parentHeight)
            return true
        }
        if (result == CONTENT_CHANGED || painted) {
            revision++
            return true
        }
        return false
    }

    public fun contentChanged() {
        contentStale = true
    }

    private fun remeasureContent(node: PolyNode): Int {
        var result = remeasureNode(node)
        val children = node.children
        for (i in children.indices) result = maxOf(result, remeasureContent(children[i]))
        return result
    }

    private fun remeasureLive(live: List<PolyNode>): Int {
        var result = NONE
        var i = 0
        while (i < live.size) {
            result = maxOf(result, remeasureNode(live[i]))
            i++
        }
        return result
    }

    private fun remeasureNode(node: PolyNode): Int {
        val s = node.style
        val w = s.width
        val h = s.height
        val iw = node.intrinsicWidth
        val ih = node.intrinsicHeight
        val rev = node.contentRevision
        node.measureContent()
        if (s.width != w || s.height != h || node.intrinsicWidth != iw || node.intrinsicHeight != ih) {
            return SIZE_CHANGED
        }
        if (node.contentRevision != rev) return CONTENT_CHANGED
        return NONE
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
