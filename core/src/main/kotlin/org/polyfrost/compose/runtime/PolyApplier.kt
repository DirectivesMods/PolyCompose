package org.polyfrost.compose.runtime

import androidx.compose.runtime.AbstractApplier
import org.polyfrost.compose.node.ChangeTracker
import org.polyfrost.compose.node.PolyNode

public class PolyApplier(root: PolyNode) : AbstractApplier<PolyNode>(root) {
    public var changes: Long = 0L
        private set

    internal val tracker: ChangeTracker = ChangeTracker()

    init {
        root.attach(tracker)
    }

    override fun onEndChanges() {
        changes++
        if (tracker.untracked > 0) tracker.layout++
    }

    override fun insertTopDown(index: Int, instance: PolyNode) {
        instance.parent = current
        current.children.add(index, instance)
        instance.attach(tracker)
        tracker.layout++
    }

    override fun insertBottomUp(index: Int, instance: PolyNode) {}

    override fun remove(index: Int, count: Int) {
        repeat(count) {
            current.children.removeAt(index).also {
                it.parent = null
                it.detach()
            }
        }
        tracker.layout++
    }

    override fun move(from: Int, to: Int, count: Int) {
        val children = current.children
        val dest = if (to > from) to - count else to
        val moved = ArrayList(children.subList(from, from + count))
        repeat(count) { children.removeAt(from) }
        children.addAll(dest, moved)
        tracker.layout++
    }

    override fun onClear() {
        root.children.forEach {
            it.parent = null
            it.detach()
        }
        root.children.clear()
        tracker.layout++
    }
}
