package org.polyfrost.compose.minecraft.platform

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import java.awt.datatransfer.Transferable
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostErrors

private val LOGGER = LogManager.getLogger("PolyCompose/Clipboard")

@Suppress("DEPRECATION")
internal object SystemClipboardManager : ClipboardManager {
    override fun getText(): AnnotatedString? {
        return try {
            val data = HostClipboard.getString() ?: return null
            AnnotatedString(data)
        } catch (t: Throwable) {
            HostErrors.warn("Failed to read the clipboard", t, LOGGER)
            null
        }
    }

    override fun setText(annotatedString: AnnotatedString) {
        try {
            HostClipboard.setString(annotatedString.text)
        } catch (t: Throwable) {
            HostErrors.warn("Failed to write the clipboard", t, LOGGER)
        }
    }

    override fun hasText(): Boolean {
        return HostClipboard.hasString()
    }
}

internal object SystemClipboard : Clipboard {
    @Suppress("OVERRIDE_DEPRECATION")
    override val nativeClipboard: Any = Unit

    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun getClipEntry(): ClipEntry? {
        val transferable = HostClipboard.getTransferable() ?: return null
        return ClipEntry(transferable)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        try {
            if (clipEntry == null) {
                HostClipboard.clear()
            } else {
                (clipEntry.nativeClipEntry as? Transferable)?.let(HostClipboard::setTransferable)
            }
        } catch (t: Throwable) {
            HostErrors.warn("Failed to write the clipboard", t, LOGGER)
        }
    }
}
