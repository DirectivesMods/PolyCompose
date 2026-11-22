package org.polyfrost.compose.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.SnapshotMutationPolicy
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.structuralEqualityPolicy
import org.polyfrost.compose.host.HostCallbacks

public enum class TickCadence { Tick, Frame }

public class TickActivity(private val isActive: () -> Boolean) {
    internal var dormant = 0

    public val active: Boolean
        get() = isActive()

    public fun resume() {
        if (dormant > 0) TickStates.resume(this)
    }
}

public val LocalTickActivity: ProvidableCompositionLocal<TickActivity?> = staticCompositionLocalOf { null }

@Composable
public fun <T> rememberTickState(
    policy: SnapshotMutationPolicy<T> = structuralEqualityPolicy(),
    cadence: TickCadence = TickCadence.Tick,
    read: () -> T,
): State<T> {
    val currentRead = rememberUpdatedState(read)
    val activity = LocalTickActivity.current
    val entry = remember(policy, cadence, activity) { TickStateEntry(read(), policy, cadence, currentRead, activity) }
    DisposableEffect(entry) {
        TickStates.register(entry)
        onDispose { TickStates.unregister(entry) }
    }
    return entry.state
}

public object TickStates {
    private val tickEntries = ArrayList<TickStateEntry<*>>()
    private val frameEntries = ArrayList<TickStateEntry<*>>()

    @JvmStatic
    public fun onClientTick() {
        poll(tickEntries)
    }

    @JvmStatic
    public fun onFrame() {
        poll(frameEntries)
    }

    internal fun register(entry: TickStateEntry<*>) {
        entries(entry.cadence).add(entry)
    }

    internal fun unregister(entry: TickStateEntry<*>) {
        entries(entry.cadence).remove(entry)
        entry.wake()
    }

    internal fun resume(activity: TickActivity) {
        val changed = sampleDormant(tickEntries, activity) or sampleDormant(frameEntries, activity)
        if (!changed) return
        Snapshot.withMutableSnapshot {
            commitAll(tickEntries)
            commitAll(frameEntries)
        }
    }

    private fun sampleDormant(entries: ArrayList<TickStateEntry<*>>, activity: TickActivity): Boolean {
        var changed = false
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.activity !== activity || !entry.dormant) continue
            entry.wake()
            changed = entry.sample() || changed
        }
        return changed
    }

    private fun commitAll(entries: ArrayList<TickStateEntry<*>>) {
        for (i in entries.indices) entries[i].commit()
    }

    private fun entries(cadence: TickCadence): ArrayList<TickStateEntry<*>> =
        if (cadence == TickCadence.Tick) tickEntries else frameEntries

    private fun poll(entries: ArrayList<TickStateEntry<*>>) {
        if (entries.isEmpty()) return
        var changed = false
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.awake()) changed = entry.sample() || changed
        }
        if (!changed) return
        Snapshot.withMutableSnapshot { commitAll(entries) }
    }
}

internal class TickStateEntry<T>(
    initial: T,
    private val policy: SnapshotMutationPolicy<T>,
    val cadence: TickCadence,
    private val read: State<() -> T>,
    val activity: TickActivity?,
) {
    val state = mutableStateOf(initial, policy)
    private var committed: T = initial
    private var pending: T? = null
    private var hasPending = false

    var dormant = false
        private set

    fun awake(): Boolean {
        val activity = activity ?: return true
        if (activity.active) {
            wake()
            return true
        }
        if (!dormant) {
            dormant = true
            activity.dormant++
        }
        return false
    }

    fun wake() {
        if (!dormant) return
        dormant = false
        activity?.let { it.dormant-- }
    }

    fun sample(): Boolean {
        val reader = read.value
        return HostCallbacks.call(reader, "A tick state read", false) {
            val next = reader()
            if (policy.equivalent(committed, next)) return false
            pending = next
            hasPending = true
            true
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun commit() {
        if (!hasPending) return
        val next = pending as T
        state.value = next
        committed = next
        pending = null
        hasPending = false
    }
}
