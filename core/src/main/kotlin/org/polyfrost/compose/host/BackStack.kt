package org.polyfrost.compose.host

import java.lang.ref.WeakReference

public object BackStack {
    private class Closer(owner: Any?, val close: () -> Unit) {
        private val owner = owner?.let(::WeakReference)

        val dead: Boolean
            get() = owner != null && owner.get() == null

        fun ownedBy(current: Any?): Boolean = if (owner == null) current == null else current != null && owner.get() === current
    }

    private val closers = ArrayList<Closer>()

    @Volatile
    @JvmStatic
    public var owner: () -> Any? = { null }

    @JvmStatic
    public val hasOpen: Boolean
        get() = top() != null

    @JvmStatic
    public fun dispatch(): Boolean = closeTop()

    @JvmStatic
    public fun closeTop(): Boolean {
        val closer = top() ?: return false
        val closed = HostCallbacks.call(closer.close, "BackStack closer", false) {
            closer.close()
            true
        }
        if (!closed) synchronized(closers) { closers.remove(closer) }
        return true
    }

    @JvmStatic
    public fun push(close: () -> Unit) {
        register(close)
    }

    @JvmStatic
    public fun register(close: () -> Unit): AutoCloseable {
        val closer = Closer(currentOwner(), close)
        synchronized(closers) {
            closers.removeAll { it.dead }
            closers += closer
        }
        return AutoCloseable { synchronized(closers) { closers.remove(closer) } }
    }

    @JvmStatic
    public fun remove(close: () -> Unit) {
        synchronized(closers) { closers.removeAll { it.close === close } }
    }

    private fun top(): Closer? {
        val current = currentOwner()
        return synchronized(closers) { closers.lastOrNull { it.ownedBy(current) } }
    }

    private fun currentOwner(): Any? = HostCallbacks.call(owner, "BackStack owner", null) { owner() }
}
