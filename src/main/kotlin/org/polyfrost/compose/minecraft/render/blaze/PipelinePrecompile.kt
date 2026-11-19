package org.polyfrost.compose.minecraft.render.blaze

//? if >= 26.3 {
import com.mojang.blaze3d.pipeline.PipelineCache
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import com.mojang.renderpearl.api.pipeline.ShaderSource
import java.lang.reflect.Field
import java.util.concurrent.CompletableFuture
import net.minecraft.util.Util
import org.slf4j.LoggerFactory

internal class PipelinePrecompile(private val pipelines: List<RenderPipeline>) {
    private class Job(val pipeline: RenderPipeline, val future: CompletableFuture<CompiledRenderPipeline.Pending>)

    private var cache: PipelineCache? = null
    private var jobs: ArrayList<Job>? = null

    fun step(): Boolean {
        val running = jobs ?: return start()
        for (i in running.indices) {
            val job = running[i]
            if (!job.future.isDone) continue
            running.removeAt(i)
            finish(job)
            break
        }
        return running.isEmpty()
    }

    private fun start(): Boolean {
        val current = currentCache()
        val source = current?.let(::sourceOf)
        if (current == null || source == null) {
            for (p in pipelines) RenderSystem.getCompiledPipeline(p)
            return true
        }
        cache = current
        val device = RenderSystem.getDevice()
        val started = ArrayList<Job>(pipelines.size)
        for (p in pipelines) {
            if (!isCached(current, p)) started.add(Job(p, device.compilePipeline(p, source, Util.backgroundExecutor())))
        }
        jobs = started
        return started.isEmpty()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun finish(job: Job) {
        val compiled = try {
            job.future.join().finishCompile()
        } catch (e: RuntimeException) {
            LOG.debug("Background compile of {} failed", job.pipeline.location, e)
            null
        } ?: return
        val target = cache
        if (target != null && target === currentCache() && !isCached(target, job.pipeline)) target.insert(job.pipeline, compiled) else compiled.close()
    }

    private companion object {
        val LOG = LoggerFactory.getLogger("PolyCompose/Warmup")

        val currentField: Field? by lazy { reflect { RenderSystem::class.java.getDeclaredField("currentPipelineCache") } }
        val sourceField: Field? by lazy { reflect { PipelineCache::class.java.declaredFields.first { it.type == ShaderSource::class.java } } }
        val mapField: Field? by lazy { reflect { PipelineCache::class.java.declaredFields.first { Map::class.java.isAssignableFrom(it.type) } } }

        fun reflect(find: () -> Field): Field? = runCatching { find().also { it.isAccessible = true } }
            .onFailure { LOG.debug("Background pipeline compiling is unavailable", it) }
            .getOrNull()

        fun currentCache(): PipelineCache? = currentField?.let { runCatching { it.get(null) as? PipelineCache }.getOrNull() }

        fun sourceOf(cache: PipelineCache): ShaderSource? =
            if (mapField == null) null else sourceField?.let { runCatching { it.get(cache) as? ShaderSource }.getOrNull() }

        fun isCached(cache: PipelineCache, pipeline: RenderPipeline): Boolean =
            (mapField?.get(cache) as? Map<*, *>)?.containsKey(pipeline) == true
    }
}
//?}
