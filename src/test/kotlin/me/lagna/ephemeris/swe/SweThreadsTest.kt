// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class SweThreadsTest {
    @Test
    fun `sessions beyond the queue capacity are rejected at once`() {
        val release = CountDownLatch(1)
        SweThreads(size = 1, queueCapacity = 1) {}.use { threads ->
            val running = threads.submit { release.await(10, TimeUnit.SECONDS) }
            val queued = threads.submit { "queued" }
            shouldThrow<RejectedExecutionException> { threads.submit { "rejected" } }
            release.countDown()
            running.get(10, TimeUnit.SECONDS) shouldBe true
            queued.get(10, TimeUnit.SECONDS) shouldBe "queued"
        }
    }
}
