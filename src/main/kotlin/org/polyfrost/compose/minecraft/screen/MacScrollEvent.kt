package org.polyfrost.compose.minecraft.screen

import org.lwjgl.system.JNI
import org.lwjgl.system.macosx.ObjCRuntime

//? if sdl {
import org.lwjgl.sdl.SDLEvents.SDL_AddEventWatch
import org.lwjgl.sdl.SDLEvents.SDL_EVENT_MOUSE_WHEEL
import org.lwjgl.sdl.SDL_Event
import org.lwjgl.sdl.SDL_EventFilter
import org.lwjgl.sdl.SDL_MouseWheelEvent
//?}

internal object MacScrollEvent {
    private const val NS_EVENT_TYPE_SCROLL_WHEEL = 22L

    private val msgSend by lazy { ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend") }
    private val nsApplication by lazy { ObjCRuntime.objc_getClass("NSApplication") }
    private val sharedApplication by lazy { ObjCRuntime.sel_getUid("sharedApplication") }
    private val currentEvent by lazy { ObjCRuntime.sel_getUid("currentEvent") }
    private val type by lazy { ObjCRuntime.sel_getUid("type") }
    private val hasPreciseScrollingDeltas by lazy { ObjCRuntime.sel_getUid("hasPreciseScrollingDeltas") }

    fun readScroll(): Boolean? = try {
        val app = JNI.invokePPP(nsApplication, sharedApplication, msgSend)
        val event = JNI.invokePPP(app, currentEvent, msgSend)
        if (event == 0L || JNI.invokePPP(event, type, msgSend) != NS_EVENT_TYPE_SCROLL_WHEEL) null
        else JNI.invokePPZ(event, hasPreciseScrollingDeltas, msgSend)
    } catch (_: Throwable) {
        null
    }

    //? if sdl {
    private const val LABEL_LIFETIME_NANOS = 1_000_000_000L

    private class WheelLabel(val x: Float, val y: Float, val precise: Boolean?, val nanos: Long)

    private val labels = ArrayDeque<WheelLabel>()

    private val wheelWatch by lazy {
        SDL_EventFilter.create { _, event ->
            if (SDL_Event.ntype(event) == SDL_EVENT_MOUSE_WHEEL) {
                val wheel = SDL_MouseWheelEvent.create(event)
                val now = System.nanoTime()
                dropExpiredLabels(now)
                labels.addLast(WheelLabel(wheel.x(), wheel.y(), readScroll(), now))
            }
            true
        }
    }

    private fun dropExpiredLabels(now: Long) {
        while (labels.isNotEmpty() && now - labels.first().nanos > LABEL_LIFETIME_NANOS) labels.removeFirst()
    }

    private var watching = false

    fun watchWheel() {
        if (!watching) watching = SDL_AddEventWatch(wheelWatch, 0L)
    }

    fun takeScroll(x: Double, y: Double): Boolean? {
        dropExpiredLabels(System.nanoTime())
        val index = labels.indexOfFirst {
            //? if = 1.8.9 {
            /*y == it.y.toDouble()
            *///?} else {
            x == it.x.toDouble() && y == it.y.toDouble() || x == 0.0 && y == -it.x.toDouble()
            //?}
        }
        if (index < 0) return null
        repeat(index) { labels.removeFirst() }
        return labels.removeFirst().precise
    }
    //?}
}
