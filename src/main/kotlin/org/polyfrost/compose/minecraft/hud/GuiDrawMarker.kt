package org.polyfrost.compose.minecraft.hud

//? if >= 1.21.8 {
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
//?}

//? if > 1.8.9 && < 1.21.8 {
/*import net.minecraft.client.gui.GuiGraphicsExtractor
*///?}

//? if < 1.21.8 {
/*import org.polyfrost.compose.minecraft.platform.HostEvents
*///?}

//? if >= 1.21.8 && < 1.21.11 {
/*import net.minecraft.client.gui.render.state.GuiRenderState
*///?}

object GuiDrawCount {
    @JvmField
    var value = 0L

    @JvmField
    var counting = false
}

//? if >= 1.21.8 {
internal object GuiDrawMarker {
    private var disabled = false
    private var probedType: Class<*>? = null
    //? if < 1.21.11
    //private var probedGraphics: Class<*>? = null

    private var state: Any? = null
    private var bounds: Any? = null
    private var node: Any? = null

    fun take(graphics: GuiGraphicsExtractor) {
        state = null
        val current = stateOf(graphics) ?: return
        try {
            bounds = StateFields.bounds!!.invokeExact(current)
            node = StateFields.node!!.invokeExact(current)
            state = current
        } catch (t: Throwable) {
            disable(t)
        }
    }

    fun unchanged(graphics: GuiGraphicsExtractor): Boolean {
        val marked = state ?: return false
        val current = stateOf(graphics) ?: return false
        if (current !== marked) return false
        return try {
            StateFields.bounds!!.invokeExact(current) === bounds && StateFields.node!!.invokeExact(current) === node
        } catch (t: Throwable) {
            disable(t)
            false
        }
    }

    fun clear() {
        state = null
        bounds = null
        node = null
    }

    private fun stateOf(graphics: GuiGraphicsExtractor): Any? {
        if (disabled) return null
        return try {
            //? if >= 1.21.11 {
            val current: Any = graphics.guiRenderState
            //?} else {
            /*if (probedGraphics == null) probedGraphics = graphics.javaClass
            val current = GraphicsField.state?.invoke(graphics) ?: return null
            *///?}
            if (probedType == null) probedType = current.javaClass
            if (StateFields.bounds == null || StateFields.node == null) null else current
        } catch (t: Throwable) {
            disable(t)
            null
        }
    }

    //? if < 1.21.11 {
    /*private object GraphicsField {
        val state: MethodHandle? = probedGraphics?.findField { GuiRenderState::class.java.isAssignableFrom(it.type) }?.let(::getter)
    }
    *///?}

    private object StateFields {
        val bounds: MethodHandle?
        val node: MethodHandle?

        init {
            val boundsField = probedType?.findField { it.type == ScreenRectangle::class.java }
            val owner = boundsField?.declaringClass
            val nodeField = owner?.declaredFields?.firstOrNull { it.type.enclosingClass == owner }?.also { it.isAccessible = true }
            if (boundsField != null && nodeField == null) HudSceneBase.LOGGER.debug("GUI render state layout not recognised; shared HUD layers paint one surface each")
            bounds = boundsField?.let(::getter)
            node = nodeField?.let(::getter)
        }
    }

    private fun getter(field: Field): MethodHandle =
        MethodHandles.lookup().unreflectGetter(field).asType(MethodType.methodType(Any::class.java, Any::class.java))

    private fun Class<*>.findField(match: (Field) -> Boolean): Field? {
        var type: Class<*>? = this
        while (type != null && type != Any::class.java) {
            type.declaredFields.firstOrNull(match)?.let {
                it.isAccessible = true
                return it
            }
            type = type.superclass
        }
        return null
    }

    private fun disable(cause: Throwable) {
        disabled = true
        clear()
        HudSceneBase.LOGGER.debug("GUI render state probe failed; shared HUD layers paint one surface each", cause)
    }
}
//?} elif > 1.8.9 {
/*internal object GuiDrawMarker {
    private var mark = 0L
    private var marked = false

    init {
        HostEvents.onFrameEnd { clear() }
    }

    fun take(graphics: GuiGraphicsExtractor) {
        mark = GuiDrawCount.value
        marked = true
        GuiDrawCount.counting = true
    }

    fun unchanged(graphics: GuiGraphicsExtractor): Boolean = marked && GuiDrawCount.value == mark

    fun clear() {
        marked = false
        GuiDrawCount.counting = false
    }
}
*///?} else {
/*internal object GuiDrawMarker {
    private var mark = 0L
    private var marked = false

    init {
        HostEvents.onFrameEnd { clear() }
    }

    fun take() {
        mark = GuiDrawCount.value
        marked = true
        GuiDrawCount.counting = true
    }

    fun unchanged(): Boolean = marked && GuiDrawCount.value == mark

    fun clear() {
        marked = false
        GuiDrawCount.counting = false
    }
}
*///?}
