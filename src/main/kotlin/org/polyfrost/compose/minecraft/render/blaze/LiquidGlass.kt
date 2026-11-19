//~ main_render_target
package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
import com.mojang.blaze3d.pipeline.RenderTarget
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline
//?} else
//import com.mojang.blaze3d.pipeline.RenderPipeline
//? if < 26.2
//import java.lang.reflect.Method
import java.util.IdentityHashMap
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
//?} else
//import net.minecraft.client.gui.render.state.GuiElementRenderState
import net.minecraft.resources.Identifier
import org.polyfrost.compose.minecraft.mixin.render.RenderPipelinesAccessor
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect

internal object LiquidGlass {
    val pipeline: RenderPipeline = RenderPipeline.builder(RenderPipelinesAccessor.`polycompose$getGuiTexturedSnippet`())
        .withLocation(Identifier.fromNamespaceAndPath("polycompose", "pipeline/liquid_glass"))
        //? if >= 26.3 {
        .withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/liquid_glass"))
        //?} else
        //.withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/liquid_glass_legacy"))
        .build()

    private val specs = IdentityHashMap<Any, ArrayList<BackdropSpec>>()
    private var submitted = false

    fun interface Range {
        fun draw(from: Int, to: Int)
    }

    fun submit() {
        submitted = true
    }

    //? if < 26.2 {
    /*private val byIndex = HashMap<Int, ArrayList<BackdropSpec>>()
    private var drawClass: Class<*>? = null
    private var drawPipeline: Method? = null

    @JvmStatic
    fun added(index: Int, element: GuiElementRenderState) {
        if (!submitted) return
        val spec = (element as? BlazeRenderer.Mesh)?.backdrop ?: return
        byIndex.getOrPut(index) { ArrayList(1) }.add(spec)
    }

    private fun pipelineOf(draw: Any): Any? {
        val cls = draw.javaClass
        if (cls !== drawClass) {
            drawClass = cls
            drawPipeline = runCatching { cls.recordComponents?.firstOrNull { it.type === RenderPipeline::class.java }?.accessor?.also { it.isAccessible = true } }.getOrNull()
        }
        return drawPipeline?.invoke(draw)
    }

    @JvmStatic
    fun recorded(draws: List<*>) {
        if (byIndex.isEmpty()) return
        for ((i, list) in byIndex) {
            val draw = draws.getOrNull(i) ?: continue
            val p = pipelineOf(draw)
            if (p != null && p !== pipeline) continue
            specs.getOrPut(draw) { ArrayList(list.size) }.addAll(list)
        }
        byIndex.clear()
    }
    *///?} else {
    @JvmStatic
    fun added(draw: Any, element: GuiElementRenderState) {
        if (!submitted) return
        val spec = (element as? BlazeRenderer.Mesh)?.backdrop ?: return
        specs.getOrPut(draw) { ArrayList(1) }.add(spec)
    }
    //?}

    @JvmStatic
    fun has(draws: List<*>, start: Int, end: Int): Boolean {
        if (specs.isEmpty()) return false
        for (i in start until end) if (specs.containsKey(draws[i])) return true
        return false
    }

    @JvmStatic
    fun split(draws: List<*>, start: Int, end: Int, target: RenderTarget, range: Range) {
        var from = start
        for (i in start until end) {
            val list = specs[draws[i]] ?: continue
            if (i > from) range.draw(from, i)
            from = i + 1
            for (k in list.indices) BlazeBackdrop.execute(list[k], target)
        }
        if (end > from) range.draw(from, end)
    }

    @JvmStatic
    fun endFrame() {
        if (GuiTargetRedirect.target != null) return
        submitted = false
        specs.clear()
        BlazeBackdrop.endFrame()
    }
}
//?}
