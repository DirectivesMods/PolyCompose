package org.polyfrost.compose.minecraft.platform

import androidx.compose.ui.platform.UriHandler
import java.net.URI
import java.net.URISyntaxException
import org.apache.logging.log4j.LogManager

//? if > 1.8.9 {
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import org.polyfrost.compose.minecraft.screen.ComposeScreen
//?} else {
/*import java.awt.Desktop
*///?}

internal object HostUriHandler : UriHandler {
    private val LOGGER = LogManager.getLogger("PolyCompose/Links")

    private val BLOCKED_SCHEMES = setOf("file", "javascript", "data", "vbscript", "jar")

    //? if = 1.8.9 {
    /*private val DIRECT_SCHEMES = setOf("http", "https", "mailto")
    *///?}

    override fun openUri(uri: String) {
        val parsed = try {
            URI(uri.trim())
        } catch (failure: URISyntaxException) {
            LOGGER.debug("Ignoring malformed link {}", uri, failure)
            return
        }
        if (!allowed(parsed.scheme?.lowercase())) {
            LOGGER.debug("Ignoring link with unsupported scheme {}", uri)
            return
        }
        RenderThread.execute { open(parsed) }
    }

    private fun allowed(scheme: String?): Boolean {
        if (scheme == null || scheme in BLOCKED_SCHEMES) return false
        //? if > 1.8.9 {
        return true
        //?} else {
        /*return scheme in DIRECT_SCHEMES
        *///?}
    }

    private fun open(uri: URI) {
        //? if > 1.8.9 {
        val parent = McScreens.current() ?: return
        val show = { ConfirmLinkScreen.confirmLinkNow(parent, uri, false) }
        val screen = parent as? ComposeScreen
        if (screen != null) screen.openCovered(show) else show()
        //?} else {
        /*browse(uri)
        *///?}
    }

    //? if = 1.8.9 {
    /*private fun browse(uri: URI) {
        val thread = Thread({
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(uri)
            } catch (failure: Throwable) {
                LOGGER.debug("Failed to open {}", uri, failure)
            }
        }, "PolyCompose-OpenLink")
        thread.isDaemon = true
        thread.start()
    }
    *///?}
}
