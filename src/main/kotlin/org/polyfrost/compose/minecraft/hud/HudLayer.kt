package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.Composable
import java.util.concurrent.atomic.AtomicInteger
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.minecraft.screen.positiveOrOne

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}

open class HudLayer(
    val id: String,
    val options: ComposeOptions = ComposeOptions.Default,
    private val content: @Composable () -> Unit,
) : AutoCloseable {

    var visible: Boolean = true
        set(value) {
            field = value
            if (!value) RenderThread.execute(::stopInput)
        }

    var acceptsInput: Boolean = true
        set(value) {
            field = value
            if (!value) RenderThread.execute(::stopInput)
        }

    var scale: Float = 1f

    var isolated: Boolean = false

    var order: Int = REGISTRATIONS.getAndIncrement()

    val density: Float
        get() = hudBaseDensity() * scaleFactor

    val pixelRatio: Float
        get() = try {
            positiveOrOne(McWindow.pixelRatio())
        } catch (_: Throwable) {
            1f
        }

    internal val scaleFactor: Float
        get() {
            val scale = scale
            return if (scale > 0f && scale.isFinite()) scale else 1f
        }

    internal val sharedScene: SharedHudScene get() = SharedHudScene.of(options)

    internal val slot = HudSlot(this, order)
    private var isolatedScene: IsolatedHudScene? = null

    @Volatile
    private var closed = false
    private var warnedClosed = false

    internal val ownerName: String
        get() = if (javaClass == HudLayer::class.java) content.javaClass.name else javaClass.name

    internal var drawSeq = 0L
        private set

    @Composable
    protected open fun decorate(content: @Composable () -> Unit) {
        options.Decorate(this, content)
    }

    @Composable
    internal fun layerContent() {
        decorate { content() }
    }

    //? if > 1.8.9 {
    fun draw(graphics: GuiGraphicsExtractor): Boolean {
        if (!drawable()) return false
        drawSeq = HudInput.nextDrawSeq()
        return if (joinShared()) sharedScene.draw(slot, graphics) else isolatedScene().draw(graphics)
    }
    //?} else {
    /*
    fun draw(): Boolean {
        if (!drawable()) return false
        drawSeq = HudInput.nextDrawSeq()
        return if (joinShared()) sharedScene.draw(slot) else isolatedScene().draw()
    }
    *///?}

    override fun close() {
        closed = true
        RenderThread.execute {
            closeIsolated()
            sharedScene.detach(slot)
        }
    }

    private fun drawable(): Boolean {
        if (closed) {
            if (!warnedClosed) {
                warnedClosed = true
                HudSceneBase.LOGGER.warn("HUD layer '{}' ({}) was drawn after close(); the draw was skipped", id, ownerName)
            }
            return false
        }
        if (!RenderThread.check(this, "HudLayer.draw")) return false
        slot.calledAt = System.nanoTime()
        return visible
    }

    private fun joinShared(): Boolean {
        val join = shared && !isolated && probationOver() && sharedScene.admit(slot)
        if (join) closeIsolated() else sharedScene.detach(slot)
        return join
    }

    private fun probationOver(): Boolean {
        val until = slot.probationUntil
        if (until == 0L) return true
        if (System.nanoTime() - until < 0 || isolatedScene?.failing == true) return false
        slot.probationUntil = 0L
        return true
    }

    private fun isolatedScene(): IsolatedHudScene = isolatedScene ?: IsolatedHudScene(this).also { isolatedScene = it }

    private fun closeIsolated() {
        val scene = isolatedScene ?: return
        isolatedScene = null
        scene.close()
    }

    internal val inputTarget: HudInputTarget?
        get() = if (slot.attached) sharedScene else isolatedScene

    private fun stopInput() {
        val isolated = isolatedScene
        isolated?.stopInput()
        if (isolated?.textFocused == true) isolated.releaseFocus()
        if (slot.attached && slot.hasFocus && sharedScene.textFocused) sharedScene.releaseFocus()
    }

    internal val takingInput: Boolean
        get() = visible && acceptsInput && inputTarget?.takingInput == true

    interface InputScreen

    companion object {
        private val REGISTRATIONS = AtomicInteger()

        @JvmStatic
        var shared: Boolean = System.getProperty("polycompose.hud.shared")?.toBoolean() ?: true
    }
}
