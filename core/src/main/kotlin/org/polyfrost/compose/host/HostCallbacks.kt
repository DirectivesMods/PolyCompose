package org.polyfrost.compose.host

import java.lang.ref.WeakReference
import org.apache.logging.log4j.LogManager
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
public object HostCallbacks {
    private val LOGGER = LogManager.getLogger("PolyCompose")

    private const val MAX_FAILURES = 5

    private const val FAILURE_WINDOW_NANOS = 60_000_000_000L

    private const val MAX_TRACKED = 1024

    private class Strikes(callback: Any) : WeakReference<Any>(callback) {
        var count = 0
        var since = 0L
        var disabled = false
    }

    private val strikes = ArrayList<Strikes>()

    @Volatile
    private var anyDisabled = false

    @JvmStatic
    public fun isDisabled(callback: Any): Boolean {
        if (!anyDisabled) return false
        synchronized(strikes) {
            for (i in strikes.indices) {
                val entry = strikes[i]
                if (entry.get() === callback) return entry.disabled
            }
        }
        return false
    }

    @JvmStatic
    public fun failed(callback: Any, what: String, failure: Throwable) {
        if (failure is VirtualMachineError) throw failure
        val name = callback.javaClass.name
        HostErrors.warn("$what failed in $name", failure)
        if (strike(callback)) {
            LOGGER.error("{} in {} failed {} times within a minute and is disabled for this session", what, name, MAX_FAILURES)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    public inline fun <T> call(callback: Any, what: String, fallback: T, block: () -> T): T {
        if (isDisabled(callback)) return fallback
        return try {
            block()
        } catch (failure: Throwable) {
            failed(callback, what, failure)
            fallback
        }
    }

    public inline fun <T : Any> each(callbacks: Iterable<T>, what: String, action: (T) -> Unit) {
        for (callback in callbacks) call(callback, what, Unit) { action(callback) }
    }

    public inline fun <T : Any> any(callbacks: Iterable<T>, what: String, predicate: (T) -> Boolean): Boolean {
        for (callback in callbacks) {
            if (call(callback, what, false) { predicate(callback) }) return true
        }
        return false
    }

    private fun strike(callback: Any): Boolean = synchronized(strikes) {
        strikes.removeAll { it.get() == null }
        if (strikes.size >= MAX_TRACKED) strikes.removeAll { !it.disabled }
        val entry = strikes.firstOrNull { it.get() === callback } ?: Strikes(callback).also { strikes += it }
        if (entry.disabled) return false
        val now = System.nanoTime()
        if (entry.count == 0 || now - entry.since > FAILURE_WINDOW_NANOS) {
            entry.count = 1
            entry.since = now
        } else {
            entry.count++
        }
        if (entry.count < MAX_FAILURES) return false
        entry.disabled = true
        anyDisabled = true
        true
    }
}
