@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package org.polyfrost.compose.minecraft.hud

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import java.util.IdentityHashMap
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.host.LocalWheelRouter
import org.polyfrost.compose.minecraft.canvas.InlineCanvasSurface
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.screen.ComposeSupport
import org.polyfrost.compose.minecraft.screen.SceneHandle
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}

internal class SharedHudScene private constructor(options: ComposeOptions) :
    HudSceneBase(if (options === ComposeOptions.Default) "hud/@shared" else "hud/@shared/${options.javaClass.name}", options) {

    private val slotOrder = compareBy<HudSlot>({ it.order }, { it.seq })
    private val slots = mutableStateListOf<HudSlot>()
    private val attachedSlots = ArrayList<HudSlot>()
    private var inputWanted = false
    private var frame = 0L
    private var advancedFrame = -1L
    private var advanced = false
    private var recovering = false
    private var lastAdmitted: HudSlot? = null
    private var lastAdmittedAt = 0L
    private var raised: HudSlot? = null

    init {
        HostEvents.onFrameEnd {
            frame++
            if (attachedSlots.isNotEmpty() && advancedFrame < frame - 2) detachIdle(System.nanoTime())
        }
    }

    private fun detachIdle(now: Long) {
        for (i in attachedSlots.indices.reversed()) {
            val slot = attachedSlots.getOrNull(i) ?: continue
            if (now - slot.calledAt > IDLE_DETACH_NANOS) detach(slot)
        }
    }

    private var lastCall: HudSlot? = null
    private var run: List<HudSlot> = emptyList()
    private var runNext = 0
    private var runResult = false
    private var collectToken = 0L

    override val routesInput: Boolean
        get() = inputWanted

    override val acceptsPointer: Boolean
        get() = inputWanted

    //? if > 1.8.9 {
    fun draw(slot: HudSlot, graphics: GuiGraphicsExtractor): Boolean =
        drawSlot(slot, { untouched(graphics) }, { mark(graphics) }) { surface, redraw, block -> surface.draw(graphics, redraw, block) }
    //?} else {
    /*
    fun draw(slot: HudSlot): Boolean = drawSlot(slot, { GuiDrawMarker.unchanged() }, { GuiDrawMarker.take() }) { surface, redraw, block -> surface.draw(redraw, block) }
    *///?}

    //? if > 1.8.9 {
    private fun untouched(graphics: GuiGraphicsExtractor): Boolean = GuiDrawMarker.unchanged(graphics)

    private fun mark(graphics: GuiGraphicsExtractor) = GuiDrawMarker.take(graphics)
    //?}

    private fun clearMark() = GuiDrawMarker.clear()

    override fun collectLayers(into: MutableList<HudLayer>) {
        for (slot in attachedSlots) if (slot.shown) into.add(slot.layer)
    }

    override fun claims(layer: HudLayer, position: Offset): Boolean {
        val slot = layer.slot
        if (!slot.attached || !slot.shown || !slot.gate.hits(position)) return false
        raise(slot)
        return true
    }

    private fun raise(slot: HudSlot?) {
        val previous = raised
        if (previous === slot) return
        previous?.gate?.raised = false
        slot?.gate?.raised = true
        raised = slot
        Snapshot.sendApplyNotifications()
    }

    fun detach(slot: HudSlot) {
        if (!slot.attached) return
        slot.attached = false
        slot.shown = false
        if (raised === slot) raise(null)
        slots.remove(slot)
        attachedSlots.remove(slot)
        slot.closeSurface()
        dirty = true
        if (slots.isEmpty()) releaseScene()
    }

    fun admit(slot: HudSlot): Boolean {
        if (slot.attached) return true
        if (slot.exiles > 0) recovering = true
        if (!recovering) return true
        val now = System.nanoTime()
        if (lastAdmitted != null && now - lastAdmittedAt < ADMIT_SPACING_NANOS) return false
        lastAdmitted = slot
        lastAdmittedAt = now
        return true
    }

    private fun attach(slot: HudSlot) {
        if (slot.attached) return
        slot.attached = true
        slots.add(slot)
        attachedSlots.add(slot)
        dirty = true
    }

    private inline fun drawSlot(
        slot: HudSlot,
        untouched: () -> Boolean,
        mark: () -> Unit,
        paint: (InlineCanvasSurface, Boolean, (PolyCanvas) -> Unit) -> Boolean,
    ): Boolean {
        attach(slot)
        slot.requestedFrame = frame
        if (advancedFrame != frame) {
            advancedFrame = frame
            startFrame()
            advanced = advance()
        }
        if (!advanced) return false
        val previous = lastCall
        val adjacent = previous != null && previous !== slot && untouched()
        if (adjacent) previous.learnedNext = slot
        lastCall = slot
        if (adjacent && runNext < run.size && run[runNext] === slot) {
            runNext++
            return runResult
        }
        if (slot.paintedFrame == frame) return runResult
        val handle = handle ?: return false
        return try {
            val drawn = paintRun(slot, paint)
            run = slot.run
            runNext = 1
            runResult = drawn
            mark()
            handle.rethrow()
            drawn
        } catch (t: Throwable) {
            fail(t)
            false
        }
    }

    private inline fun paintRun(head: HudSlot, paint: (InlineCanvasSurface, Boolean, (PolyCanvas) -> Unit) -> Boolean): Boolean {
        collectRun(head)
        val members = head.run
        val surface = head.surface(frame)
        var redraw = head.runChanged()
        for (member in members) {
            if (member.dirty) redraw = true
            member.painting = true
        }
        val drawn = try {
            profile().measure(ProfilePhase.Record) { paint(surface, redraw, paintScene) }
        } finally {
            for (member in members) member.painting = false
        }
        head.recorded(drawn)
        if (drawn) {
            for (member in members) {
                member.dirty = false
                member.paintedFrame = frame
            }
            lastDrawn = System.nanoTime()
        }
        return drawn
    }

    private fun collectRun(head: HudSlot) {
        val members = head.run
        members.clear()
        val token = ++collectToken
        head.collectedBy = token
        members += head
        var next = head.runNext
        while (next != null && members.size < MAX_RUN && next.attached && next.paintedFrame != frame && next.collectedBy != token) {
            next.collectedBy = token
            members += next
            next = next.runNext
        }
    }

    private fun startFrame() {
        lastCall = null
        run = emptyList()
        runNext = 0
        clearMark()
        for (slot in attachedSlots) {
            slot.runNext = slot.learnedNext
            slot.learnedNext = null
            slot.releaseIdleSurface(frame)
        }
    }

    private fun advance(): Boolean {
        advanceFrame({ hudBaseDensity() }, { base, now -> prepareSlots(base, now) }) ?: return false
        if (dirty) {
            dirty = false
            for (slot in attachedSlots) slot.dirty = true
        }
        afterFrame(lastDrawn)
        return true
    }

    private fun prepareSlots(base: Float, now: Long) {
        if (syncSlots(base, now)) dirty = true
        if (dirty) Snapshot.sendApplyNotifications()
    }

    override fun afterFrame(now: Long) {
        super.afterFrame(now)
        if (recovering && now - builtAt > HEALTHY_NANOS && now - lastAdmittedAt > HEALTHY_NANOS) {
            recovering = false
            lastAdmitted = null
        }
    }

    private fun syncSlots(base: Float, now: Long): Boolean {
        var changed = false
        var wantsInput = false
        var idle: ArrayList<HudSlot>? = null
        val previous = frame - 1
        for (slot in attachedSlots) {
            val layer = slot.layer
            val shown = layer.visible && slot.requestedFrame >= previous
            if (slot.shown != shown) {
                slot.shown = shown
                changed = true
            }
            if (now - slot.calledAt > IDLE_DETACH_NANOS) {
                (idle ?: ArrayList<HudSlot>().also { idle = it }).add(slot)
            }
            val density = base * layer.scaleFactor
            if (slot.density != density) {
                slot.density = density
                changed = true
            }
            if (slot.order != layer.order) {
                slot.order = layer.order
                changed = true
            }
            if (shown) slot.tickActivity.resume()
            if (shown && layer.acceptsInput) wantsInput = true
        }
        inputWanted = wantsInput
        idle?.forEach {
            LOGGER.debug("HUD layer '{}' was not drawn for a while; its composition was released", it.layer.id)
            detach(it)
            changed = true
        }
        return changed
    }

    override fun ensureScene(): SceneHandle? {
        handle?.let { return it }
        if (!ComposeSupport.isAvailable) return null
        return openScene {
            CompositionLocalProvider(pointer.scrollConfig.provided(), LocalWheelRouter provides pointer.wheelRouter) {
                for (slot in slots.sortedWith(slotOrder)) {
                    key(slot) { slot.host() }
                }
            }
        }
    }

    override fun fail(cause: Throwable) {
        val now = System.nanoTime()
        val suspect = lastAdmitted?.takeIf { recovering && it.attached && now - lastAdmittedAt < SUSPECT_NANOS }
        val until = if (suspect != null) {
            suspect.exiles++
            suspect.probationUntil = now + (HEALTHY_NANOS shl (suspect.exiles - 1).coerceIn(0, MAX_PROBATION_SHIFT))
            HostErrors.error(
                "Compose HUD layer '${suspect.layer.id}' (${suspect.layer.ownerName}) broke the shared HUD scene; " +
                    "it runs in its own scene for a while",
                cause,
                LOGGER,
            )
            now + ADMIT_SPACING_NANOS
        } else {
            failures++
            HostErrors.error(
                "The shared Compose HUD scene failed; its layers run in their own scenes and rejoin one at a time. Layers: " +
                    slots.joinToString { "'${it.layer.id}' (${it.layer.ownerName})" },
                cause,
                LOGGER,
            )
            now + (REJOIN_NANOS shl (failures - 1).coerceIn(0, MAX_PROBATION_SHIFT))
        }
        recovering = true
        lastAdmitted = null
        raise(null)
        for (slot in slots.toList()) {
            if (slot !== suspect) slot.probationUntil = until
            slot.attached = false
            slot.shown = false
            slot.closeSurface()
        }
        slots.clear()
        attachedSlots.clear()
        releaseScene()
    }

    override fun releaseScene() {
        inputWanted = false
        super.releaseScene()
    }

    companion object {
        private const val MAX_PROBATION_SHIFT = 4
        private const val REJOIN_NANOS = 5_000_000_000L
        private const val ADMIT_SPACING_NANOS = 1_000_000_000L
        private const val SUSPECT_NANOS = 5_000_000_000L
        private const val IDLE_DETACH_NANOS = 10_000_000_000L
        private const val MAX_RUN = 256

        private val scenes = IdentityHashMap<ComposeOptions, SharedHudScene>()

        fun of(options: ComposeOptions): SharedHudScene = scenes.getOrPut(options) { SharedHudScene(options) }
    }
}
