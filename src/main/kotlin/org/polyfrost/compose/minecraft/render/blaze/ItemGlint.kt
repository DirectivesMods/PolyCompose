package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.RenderPipeline
//?} else {
/*import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
*///?}
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFactor as BlendSrc
import com.mojang.renderpearl.api.pipeline.BlendFactor as BlendDst
//?} elif 26.2 {
/*import com.mojang.blaze3d.platform.BlendFactor as BlendSrc
import com.mojang.blaze3d.platform.BlendFactor as BlendDst
*///?} else {
/*import com.mojang.blaze3d.platform.SourceFactor as BlendSrc
import com.mojang.blaze3d.platform.DestFactor as BlendDst
*///?}
//? if >= 26.1 && < 26.3
//import com.mojang.blaze3d.pipeline.ColorTargetState
//? if >= 26.2
import net.minecraft.client.renderer.BindGroupLayouts
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.renderpearl.api.textures.GpuTextureView
import java.util.IdentityHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
//?} else
//import net.minecraft.client.gui.render.state.GuiElementRenderState
import net.minecraft.resources.Identifier
import org.joml.Matrix3x2f
import org.polyfrost.compose.minecraft.mc.McItemGlint
import org.polyfrost.compose.minecraft.mixin.render.RenderPipelinesAccessor
import org.polyfrost.compose.minecraft.profile.RenderCounters
import org.polyfrost.compose.minecraft.render.ColorMath

internal object ItemGlint {
    class Key(val atlas: GpuTextureView, val cells: Int)

    private const val MAX_KEYS = 32
    private val TEXTURE: Identifier = Identifier.withDefaultNamespace(McItemGlint.TEXTURE)
    private val keys = IdentityHashMap<GpuTextureView, Key>()

    val pipeline: RenderPipeline = RenderPipeline.builder(RenderPipelinesAccessor.`polycompose$getGuiTexturedSnippet`())
        .withLocation(Identifier.fromNamespaceAndPath("polycompose", "pipeline/item_glint"))
        //? if >= 26.3 {
        .withVertexShader(Identifier.fromNamespaceAndPath("polycompose", "core/item_glint"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/item_glint"))
        //?} else {
        /*.withVertexShader(Identifier.fromNamespaceAndPath("polycompose", "core/item_glint_legacy"))
        .withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/item_glint_legacy"))
        *///?}
        //? if >= 26.2 {
        .withBindGroupLayout(BindGroupLayouts.SAMPLER1)
        //?} else
        //.withSampler("Sampler1")
        //? if >= 26.1 {
        .withColorTargetState(ColorTargetState(BlendFunction(BlendSrc.ONE, BlendDst.ONE, BlendSrc.ZERO, BlendDst.ONE)))
        //?} else
        //.withBlend(BlendFunction(BlendSrc.ONE, BlendDst.ONE, BlendSrc.ZERO, BlendDst.ONE))
        .build()

    @Volatile
    var usable = true
        private set

    fun key(atlas: GpuTextureView, cells: Int): Key {
        keys[atlas]?.let { if (it.cells == cells) return it }
        if (keys.size >= MAX_KEYS) keys.entries.removeIf { it.key.isClosed }
        return Key(atlas, cells).also { keys[atlas] = it }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun glintView(): GpuTextureView? = try {
        Minecraft.getInstance().textureManager.getTexture(TEXTURE).textureView
    } catch (e: RuntimeException) {
        usable = false
        null
    }

    private fun pack(u: Float, v: Float): Int {
        val pu = (u.coerceIn(0f, 1f) * 65535f).roundToInt()
        val pv = (v.coerceIn(0f, 1f) * 65535f).roundToInt()
        return ((pv and 0xFF) shl 24) or ((pu ushr 8) shl 16) or ((pu and 0xFF) shl 8) or (pv ushr 8)
    }

    class Op(
        private val key: Key, private val pose: Matrix3x2f, private val verts: FloatArray, private val colors: IntArray, val bounds: ScreenRectangle,
    ) : (GuiGraphicsExtractor) -> Unit {
        private val packed = IntArray(colors.size) { pack(verts[it * 4 + 2], verts[it * 4 + 3]) }
        private val cells = FloatArray(colors.size * 2).also { out ->
            val n = key.cells.toFloat()
            for (i in colors.indices) {
                val v = verts[i * 4 + 3]
                out[i * 2] = verts[i * 4 + 2] * n
                out[i * 2 + 1] = (if (topDownTargets) v else 1f - v) * n
            }
        }
        private val coverage = FloatArray(colors.size) { sqrt((colors[it] ushr 24) / 255f) }
        private var setup: TextureSetup? = null
        private var setupGlint: GpuTextureView? = null
        private var fadedOp: Op? = null
        private var fadedAlpha = Float.NaN

        val atlas: GpuTextureView get() = key.atlas
        val closed: Boolean get() = key.atlas.isClosed

        fun faded(alpha: Float): Op {
            fadedOp?.let { if (fadedAlpha == alpha) return it }
            return Op(key, pose, verts, IntArray(colors.size) { ColorMath.fadeColor(colors[it], alpha, true) }, bounds)
                .also { fadedOp = it; fadedAlpha = alpha }
        }

        fun shifted(dx: Int, dy: Int) = Op(
            key, Matrix3x2f().translation(-dx.toFloat(), -dy.toFloat()).mul(pose), verts, colors,
            ScreenRectangle(bounds.left() - dx, bounds.top() - dy, bounds.width(), bounds.height()),
        )

        fun moved(gx: Float, gy: Float): Op {
            val l = floor(bounds.left() + gx).toInt()
            val t = floor(bounds.top() + gy).toInt()
            val box = ScreenRectangle(l, t, ceil(bounds.right() + gx).toInt() - l, ceil(bounds.bottom() + gy).toInt() - t)
            return Op(key, Matrix3x2f().translation(gx, gy).mul(pose), verts, colors, box)
        }

        override fun invoke(g: GuiGraphicsExtractor) {
            if (closed || !usable) return
            val glint = glintView() ?: return
            val textures = setup?.takeIf { setupGlint === glint } ?: TextureSetup.doubleTexture(
                key.atlas, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR),
                glint, RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR),
            ).also {
                setup = it
                setupGlint = glint
            }
            val m = McItemGlint.update()
            RenderCounters.batch(colors.size)
            //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
            g.guiRenderState.addGuiElement(Element(this, textures, m[0], m[1], m[2], m[3], m[4], m[5], m[6]))
        }

        fun build(consumer: VertexConsumer, e: Element) {
            val p = pose
            val m00 = p.m00(); val m01 = p.m01(); val m10 = p.m10(); val m11 = p.m11(); val m20 = p.m20(); val m21 = p.m21()
            for (i in colors.indices) {
                val x = verts[i * 4]
                val y = verts[i * 4 + 1]
                val cx = cells[i * 2]
                val cy = cells[i * 2 + 1]
                consumer.addVertex(m00 * x + m10 * y + m20, m01 * x + m11 * y + m21, e.strength * coverage[i])
                    .setUv(e.a00 * cx + e.a01 * cy + e.b0, e.a10 * cx + e.a11 * cy + e.b1)
                    .setColor(packed[i])
            }
        }
    }

    class Element(
        private val op: Op, private val textures: TextureSetup,
        val a00: Float, val a01: Float, val a10: Float, val a11: Float, val b0: Float, val b1: Float, val strength: Float,
    ) : GuiElementRenderState {
        override fun buildVertices(consumer: VertexConsumer) = op.build(consumer, this)
        override fun pipeline() = ItemGlint.pipeline
        override fun textureSetup() = textures
        override fun scissorArea(): ScreenRectangle? = null
        override fun bounds() = op.bounds
    }
}
//?}
