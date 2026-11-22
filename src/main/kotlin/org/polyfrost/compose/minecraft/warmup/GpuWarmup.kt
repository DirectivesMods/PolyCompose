package org.polyfrost.compose.minecraft.warmup

import kotlin.math.max
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL20
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.render.gl.GlBlur
import org.polyfrost.compose.minecraft.render.gl.GlEffects
import org.polyfrost.compose.minecraft.render.gl.GlGlass
import org.polyfrost.compose.minecraft.render.gl.GlGlint
import org.polyfrost.compose.minecraft.render.gl.GlHost
import org.polyfrost.compose.minecraft.render.gl.GlProgram
import org.slf4j.LoggerFactory
//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.RenderPipeline
import org.polyfrost.compose.minecraft.render.blaze.PipelinePrecompile
//?} else {
/*import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.systems.RenderSystem
*///?}
import org.polyfrost.compose.minecraft.mixin.render.PostChainAccessor
import org.polyfrost.compose.minecraft.mixin.render.PostPassAccessor
import org.polyfrost.compose.minecraft.render.blaze.BlazeBackdrop
import org.polyfrost.compose.minecraft.render.blaze.BlendPipelines
import org.polyfrost.compose.minecraft.render.blaze.ItemGlint
import org.polyfrost.compose.minecraft.render.blaze.KeptMeshes
import org.polyfrost.compose.minecraft.render.blaze.UiBlur
//?}

internal object GpuWarmup {
    class Result(val steps: Int, val nanos: Long, val longestNanos: Long)

    private fun interface Step {
        fun run(): Boolean
    }

    private val LOG = LoggerFactory.getLogger("PolyCompose/Warmup")
    private const val FRAMES_BETWEEN_STEPS = 2
    private const val FRAMES_BETWEEN_POLLS = 1

    private var running = false
    private var again = false

    fun run(done: (Result) -> Unit) {
        if (running) {
            again = true
            return
        }
        val steps = steps()
        if (steps.isEmpty()) return done(Result(0, 0L, 0L))
        running = true
        Chain(steps, done).next(FRAMES_BETWEEN_STEPS)
    }

    private fun finished(result: Result, done: (Result) -> Unit) {
        running = false
        done(result)
        if (again) {
            again = false
            run(done)
        }
    }

    private class Chain(val steps: MutableList<Step>, val done: (Result) -> Unit) {
        private var index = 0
        private var nanos = 0L
        private var longest = 0L

        fun next(frames: Int) {
            HostEvents.afterFrames(frames, ::step)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun step() {
            val begin = System.nanoTime()
            val complete = try {
                steps[index].run()
            } catch (failure: Exception) {
                LOG.debug("A GPU warm-up step failed", failure)
                true
            }
            val spent = System.nanoTime() - begin
            nanos += spent
            longest = max(longest, spent)
            if (!complete) return next(FRAMES_BETWEEN_POLLS)
            if (++index < steps.size) return next(FRAMES_BETWEEN_STEPS)
            finished(Result(steps.size, nanos, longest), done)
        }
    }

    private fun steps(): MutableList<Step> {
        val steps = ArrayList<Step>()
        if (GlHost.legacyGl && !GlHost.vanillaPipeline) {
            steps += Step { GlProgram.warm() }
            steps += Step { GlBlur.warm() }
            steps += Step { GlGlass.warm() }
            steps += Step { keepingProgram { GlEffects.warmPasses() } }
            steps += Step { GlGlint.warm() }
        }
        //? if >= 1.21.11 {
        steps += compile(KeptMeshes.pipelines)
        steps += compile(BlendPipelines.pipelines)
        steps += compile(listOf(ItemGlint.pipeline))
        steps += compile(listOf(BlazeBackdrop.composite))
        steps += compile(listOf(BlazeBackdrop.blur))
        steps += Step {
            val chain = UiBlur.chain() ?: return@Step true
            for (pass in (chain as PostChainAccessor).`polycompose$getPasses`()) {
                val pipeline = (pass as PostPassAccessor).`polycompose$getPipeline`()
                steps += compile(listOf(pipeline))
            }
            true
        }
        //?}
        return steps
    }

    private inline fun keepingProgram(block: () -> Boolean): Boolean {
        val previous = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        try {
            return block()
        } finally {
            GL20.glUseProgram(previous)
        }
    }

    //? if >= 1.21.11 {
    private fun compile(pipelines: List<RenderPipeline>): Step {
        //? if >= 26.3 {
        val precompile = PipelinePrecompile(pipelines)
        return Step { precompile.step() }
        //?} else {
        /*return Step {
            for (pipeline in pipelines) RenderSystem.getDevice().precompilePipeline(pipeline)
            true
        }
        *///?}
    }
    //?}
}
