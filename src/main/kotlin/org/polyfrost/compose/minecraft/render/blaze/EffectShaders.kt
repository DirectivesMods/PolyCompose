package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderType
//?} else {
/*import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.shaders.ShaderType
*///?}
import java.util.concurrent.ConcurrentHashMap
//? if < 26.3
//import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.ShaderManager
import net.minecraft.resources.Identifier
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.render.PolyEffectShader
import org.slf4j.LoggerFactory

internal object EffectShaders {
    private const val PROBE = "effect/probe"
    private const val PROBE_SOURCE = "void main() {}"
    private const val CUSTOM_PREFIX = "effect/custom/"
    private const val INCLUDE = "/assets/polycompose/shaders/include/poly_effect.glsl"
    //? if >= 26.3 {
    private const val HEADER = "#version 330\n#extension GL_ARB_separate_shader_objects : require\n"
    private const val INPUT = "layout(location = 0) in"
    private const val OUTPUT = "layout(location = 0) out"
    //?} else {
    /*private const val HEADER = "#version 330\n"
    private const val INPUT = "in"
    private const val OUTPUT = "out"
    *///?}
    private val LOG = LoggerFactory.getLogger("PolyCompose/EffectShaders")
    private val sources = ConcurrentHashMap<String, String>()
    private val pipelines = HashMap<PolyEffectShader, RenderPipeline?>()
    private var hook = -1
    private var include: String? = null
    private var warned = false

    init {
        HostEvents.onResourcesReloaded { pipelines.clear() }
    }

    @JvmStatic
    fun source(path: String): String? = if (path == PROBE) PROBE_SOURCE else sources[path]

    @Suppress("TooGenericExceptionCaught")
    fun hookActive(): Boolean {
        if (hook >= 0) return hook == 1
        val result = try {
            probe(Identifier.fromNamespaceAndPath("polycompose", PROBE))
        } catch (e: Exception) {
            LOG.debug("PolyCompose effect shader hook probe failed", e)
            null
        } ?: return false
        hook = if (result) 1 else 0
        if (!result) LOG.warn("PolyCompose custom effect shaders are unavailable: the shader source hook is not active")
        return result
    }

    private fun probe(id: Identifier): Boolean {
        //? if >= 26.3 {
        return ShaderManager.Configs.EMPTY.getShader(id, ShaderType.FRAGMENT) == PROBE_SOURCE
        //?} else
        //return Minecraft.getInstance().shaderManager.getShader(id, ShaderType.FRAGMENT) == PROBE_SOURCE
    }

    fun pipeline(shader: PolyEffectShader): RenderPipeline? {
        val p = if (pipelines.containsKey(shader)) pipelines[shader] else build(shader).also { pipelines[shader] = it }
        if (p != null && BlazeBackdrop.ready(p)) return p
        if (!warned) {
            warned = true
            LOG.warn("PolyCompose custom effect {} could not be compiled and is drawn unchanged", shader.key)
        }
        return null
    }

    private fun build(shader: PolyEffectShader): RenderPipeline? {
        if (!hookActive()) return null
        val text = include() ?: return null
        val name = shader.key.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val path = CUSTOM_PREFIX + name + "_" + Integer.toHexString(shader.hashCode())
        sources[path] = HEADER + "\n#define PE_UNIFORM_BLOCK\n#define PE_CUSTOM\n\nuniform sampler2D Source;\n\n" +
            "layout(std140) uniform PolyTarget {\n    vec4 ptSize;\n    vec4 ptMap;\n    vec4 ptClamp;\n    vec4 ptClip;\n};\n\n" +
            "$INPUT vec2 texCoord;\n\n$OUTPUT vec4 fragColor;\n\n" +
            "vec2 ptFrag() {\n    return ptMap.xy + texCoord * ptSize.xy * ptMap.zw;\n}\n\n" +
            "#define PE_SOURCE Source\n#define PE_TEX(uv) textureLod(Source, clamp(uv, ptClamp.xy, ptClamp.zw), 0.0)\n#define PE_FRAG ptFrag()\n\n" +
            text + "\n\nvec4 polyEffect(vec2 uv, vec2 px) {\n" + shader.source + "\n}\n\nvoid main() {\n    fragColor = peCustom();\n}\n"
        return BlazeBackdrop.pipeline("pipeline/$path", path, false, "PolyEffect", "PolyTarget")
    }

    private fun include(): String? {
        include?.let { return it }
        val text = EffectShaders::class.java.getResourceAsStream(INCLUDE)?.use { it.readBytes().toString(Charsets.UTF_8) }
        if (text == null) LOG.warn("PolyCompose could not read {}", INCLUDE)
        include = text
        return text
    }
}
//?}
