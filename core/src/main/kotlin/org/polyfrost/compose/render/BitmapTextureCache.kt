package org.polyfrost.compose.render

import java.util.IdentityHashMap
import kotlin.math.min
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
public class BitmapTextureCache<T : Any>(private val releaseFrames: Long) {
    @ApiStatus.Internal
    public class Entry<T : Any> internal constructor(bitmap: PolyBitmap, public var texture: T, internal var lastUsed: Long) {
        public var bitmap: PolyBitmap = bitmap
            internal set
        public var generation: Int = 0
            internal set
        public var released: Boolean = false
            internal set
        internal var retains = 0
    }

    @ApiStatus.Internal
    public inner class Pin internal constructor(private val entries: List<Entry<T>>, private val held: Boolean) {
        private val generations = IntArray(entries.size) { entries[it].generation }
        private var dropped = false

        public fun touch() {
            for (i in entries.indices) entries[i].lastUsed = frame
        }

        public fun intact(): Boolean {
            for (i in entries.indices) {
                val e = entries[i]
                if (e.released || e.generation != generations[i]) return false
            }
            return true
        }

        public fun release() {
            if (!held || dropped) return
            dropped = true
            for (i in entries.indices) entries[i].retains--
        }
    }

    private val cache = IdentityHashMap<PolyBitmap, Entry<T>>()
    private val lineages = IdentityHashMap<Any, ArrayList<Entry<T>>>()
    private var scanAt = 0L

    public var frame: Long = 0L
        private set

    public val size: Int get() = cache.size

    public fun peek(bitmap: PolyBitmap): Entry<T>? = cache[bitmap]

    public fun get(bitmap: PolyBitmap): Entry<T>? = cache[bitmap]?.also { it.lastUsed = frame }

    public fun adopt(bitmap: PolyBitmap): Entry<T>? {
        val pool = lineages[bitmap.lineage ?: return null] ?: return null
        for (i in pool.indices) {
            val e = pool[i]
            val old = e.bitmap
            if (!reusable(e) || old.width != bitmap.width || old.height != bitmap.height || cache[old] !== e) continue
            cache.remove(old)
            cache[bitmap] = e
            e.bitmap = bitmap
            e.generation++
            e.lastUsed = frame
            return e
        }
        return null
    }

    private fun reusable(e: Entry<T>): Boolean = e.retains <= 0 && frame - e.lastUsed >= REUSE_GAP

    public fun add(bitmap: PolyBitmap, texture: T): Entry<T> {
        val e = Entry(bitmap, texture, frame)
        cache.put(bitmap, e)?.let(::forget)
        bitmap.lineage?.let { lineages.getOrPut(it) { ArrayList(2) }.add(e) }
        scanAt = min(scanAt, frame + releaseFrames + 1)
        return e
    }

    public fun pin(bitmaps: Collection<PolyBitmap>, hold: Boolean): Pin {
        val entries = ArrayList<Entry<T>>(bitmaps.size)
        for (b in bitmaps) cache[b]?.let(entries::add)
        if (hold) for (i in entries.indices) entries[i].retains++
        return Pin(entries, hold)
    }

    public fun endFrame(close: (T) -> Unit) {
        frame++
        if (frame < scanAt) return
        var next = Long.MAX_VALUE
        val it = cache.values.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (frame - e.lastUsed > releaseFrames) {
                it.remove()
                forget(e)
                close(e.texture)
            } else {
                next = min(next, e.lastUsed + releaseFrames + 1)
            }
        }
        scanAt = next
    }

    private fun forget(e: Entry<T>) {
        e.released = true
        val lineage = e.bitmap.lineage ?: return
        val pool = lineages[lineage] ?: return
        pool.remove(e)
        if (pool.isEmpty()) lineages.remove(lineage)
    }

    private companion object {
        const val REUSE_GAP = 2L
    }
}
