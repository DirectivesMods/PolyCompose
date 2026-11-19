package org.polyfrost.compose.minecraft.screen

import java.util.Collections
import java.util.IdentityHashMap
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.minecraft.render.gl.GlHost

object ComposeSupport {
    private val LOG = LogManager.getLogger("PolyCompose/Support")

    private val disableProperties = listOf("polycompose.ui.disable", "oneconfig.ui.disable")

    private enum class Failure { Disabled, AwtMissing }

    @Volatile
    private var recordedFailure: Failure? = null

    private val probed: Failure? by lazy { probe() }

    fun unavailableReason(): String? = (recordedFailure ?: probed)?.let(::describe) ?: GlHost.unavailableReason()

    val isAvailable: Boolean get() = unavailableReason() == null

    fun recordSceneFailure(error: Throwable) {
        if (recordedFailure != null) return
        val fatal = classify(error)
        if (fatal == null) {
            LOG.error("Compose scene creation failed; will retry on the next open.", error)
            return
        }
        recordedFailure = fatal
        LOG.error("Compose is unavailable on this system; the UI has been disabled.", error)
    }

    private fun describe(failure: Failure): String {
        val messages = ComposeHost.settings.messages
        return when (failure) {
            Failure.Disabled -> messages.unavailable(messages.disabledByFlag(setDisableProperty() ?: disableProperties.first()))
            Failure.AwtMissing -> messages.awtMissing
        }
    }

    private fun classify(error: Throwable): Failure? {
        var e: Throwable? = error
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        while (e != null && seen.add(e)) {
            if (isAwtFailure(e)) return Failure.AwtMissing
            e = e.cause
        }
        return null
    }

    private fun isAwtFailure(e: Throwable): Boolean {
        val type = e.javaClass.name
        if (type == "java.awt.AWTError" || type == "java.awt.HeadlessException") return true
        if (e !is LinkageError) return false
        val message = e.message.orEmpty()
        return "java.awt" in message || "java/awt" in message || "Toolkit" in message || "libawt" in message
    }

    private val osLabel: String
        get() = "${System.getProperty("os.name", "?")} ${System.getProperty("os.arch", "?")}"

    private fun setDisableProperty(): String? = disableProperties.firstOrNull { System.getProperty(it) != null }

    private fun probe(): Failure? {
        if (setDisableProperty() != null) {
            return Failure.Disabled
        }
        if (!classExists("java.awt.Toolkit")) {
            LOG.error("AWT is missing from this Java runtime on {}; the UI has been disabled.", osLabel)
            return Failure.AwtMissing
        }
        return awtInitFailure()
    }

    private fun awtInitFailure(): Failure? = try {
        Class.forName("java.awt.event.KeyEvent", true, ComposeSupport::class.java.classLoader)
        Class.forName("androidx.compose.ui.input.pointer.PointerIcon", true, ComposeSupport::class.java.classLoader)
        null
    } catch (t: Throwable) {
        LOG.error("AWT failed to initialize on {}; the UI has been disabled.", osLabel, t)
        Failure.AwtMissing
    }

    private fun classExists(name: String): Boolean = runCatching {
        Class.forName(name, false, ComposeSupport::class.java.classLoader)
    }.isSuccess
}
