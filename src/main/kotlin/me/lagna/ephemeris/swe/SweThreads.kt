// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs every libswe call on a small pool of dedicated platform threads.
 *
 * libswe keeps its state (ephemeris path, sidereal mode, topocentric observer) in thread-local storage on
 * Linux, so each worker initialises itself once and every call sets the modes it needs. On macOS libswe is
 * built without thread-local storage (`sweodef.h` excludes `__APPLE__`), so the pool shrinks to one thread
 * there; that only affects local development.
 */
internal class SweThreads(
    size: Int,
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val initialise: () -> Unit,
) : AutoCloseable {
    private val counter = AtomicInteger()

    /**
     * At most [queueCapacity] sessions wait for a worker; beyond that [submit] throws
     * [java.util.concurrent.RejectedExecutionException] at once instead of queueing without bound.
     */
    private val executor: ExecutorService =
        ThreadPoolExecutor(
            size,
            size,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue<Runnable>(queueCapacity),
            threadFactory(),
            ThreadPoolExecutor.AbortPolicy(),
        )

    fun <T> submit(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    override fun close() = executor.close()

    private fun threadFactory() =
        ThreadFactory { task ->
            Thread
                .ofPlatform()
                .name("libswe-${counter.incrementAndGet()}")
                .daemon(true)
                .unstarted {
                    initialise()
                    task.run()
                }
        }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 256

        /** Thread-local libswe state exists everywhere except macOS builds. */
        fun defaultSize(): Int =
            if (System.getProperty("os.name").startsWith("Mac")) 1 else Runtime.getRuntime().availableProcessors()
    }
}
