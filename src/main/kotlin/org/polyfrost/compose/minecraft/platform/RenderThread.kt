package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.CompletableFuture
import java.util.function.Supplier
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import net.minecraft.client.Minecraft
import org.polyfrost.compose.host.HostErrors

//? if = 1.8.9 {
/*import java.util.concurrent.FutureTask
*///?}

object RenderThread {
    @JvmStatic
    fun isCurrent(): Boolean {
        @Suppress("USELESS_ELVIS")
        val minecraft = Minecraft.getInstance() ?: return true
        //? if = 1.8.9 {
        /*return minecraft.isSameThread
        *///?} else
        return minecraft.isSameThread
    }

    @JvmStatic
    fun check(caller: Any, what: String): Boolean {
        if (isCurrent()) return true
        HostErrors.warn(
            "$what was called off the render thread by ${caller.javaClass.name}; the call was skipped",
            IllegalStateException("Called from thread ${Thread.currentThread().name}"),
        )
        return false
    }

    @JvmStatic
    fun execute(task: Runnable) {
        if (isCurrent()) {
            task.run()
            return
        }
        //? if = 1.8.9 {
        /*Minecraft.getInstance().executeTask(task)
        *///?} else
        Minecraft.getInstance().execute(task)
    }

    @JvmStatic
    fun schedule(task: Runnable) {
        //? if = 1.8.9 {
        /*val tasks = Minecraft.getInstance().tasks
        synchronized(tasks) {
            tasks.add(FutureTask<Unit>(task, Unit))
        }
        *///?} else
        Minecraft.getInstance().schedule(task)
    }

    @JvmStatic
    fun <T> call(task: Supplier<T>): T {
        if (isCurrent()) return task.get()
        return CompletableFuture.supplyAsync(task, ::execute).join()
    }
}

object RenderThreadDispatcher : CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = !RenderThread.isCurrent()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        RenderThread.execute(block)
    }
}
