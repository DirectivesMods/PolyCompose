package org.polyfrost.compose.minecraft.platform

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

object BackgroundTasks {
    private val counter = AtomicInteger()

    private val threads = ThreadFactory { task ->
        Thread(task, "PolyCompose-${counter.incrementAndGet()}").apply { isDaemon = true }
    }

    private val pool: ExecutorService by lazy { Executors.newCachedThreadPool(threads) }

    private val scheduler: ScheduledExecutorService by lazy { Executors.newSingleThreadScheduledExecutor(threads) }

    @JvmStatic
    fun submit(task: Runnable): Future<*> = pool.submit(task)

    @JvmStatic
    fun schedule(task: Runnable, delay: Long, unit: TimeUnit): ScheduledFuture<*> = scheduler.schedule(task, delay, unit)
}
