@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.ViewConfiguration
import kotlinx.coroutines.awaitCancellation
import net.minecraft.client.gui.screens.ChatScreen
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.TextInput
import org.polyfrost.compose.minecraft.screen.ScaledViewConfiguration
import org.polyfrost.compose.minecraft.screen.ScenePlatformContext

internal class HudTextContext : ScenePlatformContext() {
    var sessions = 0
        private set

    private var inputActive = false

    var density = 1f

    override val viewConfiguration: ViewConfiguration = ScaledViewConfiguration { density }

    override val isClearFocusOnMouseDownEnabled: Boolean
        get() = true

    override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
        sessions++
        try {
            awaitCancellation()
        } finally {
            sessions--
        }
    }

    fun sync(allowed: Boolean) {
        if (!inputActive && sessions == 0) return
        val wanted = allowed && sessions > 0 && runCatching { McScreens.current() }.getOrNull() !is ChatScreen
        if (wanted == inputActive) return
        inputActive = wanted
        if (wanted) TextInput.start(this) else TextInput.stop(this)
    }
}
