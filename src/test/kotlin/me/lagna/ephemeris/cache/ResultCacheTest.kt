// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.cache

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.StationsRequest
import me.lagna.ephemeris.v1.StationsResponse
import org.junit.jupiter.api.Test

class ResultCacheTest {
    private fun request(body: Body) =
        StationsRequest
            .newBuilder()
            .setBody(body)
            .setFrom(timestamp("2024-01-01T00:00:00Z"))
            .setTo(timestamp("2025-01-01T00:00:00Z"))
            .build()

    @Test
    fun `equal requests share a key and a digest`() {
        CacheKey("FindStations", request(Body.MARS)) shouldBe CacheKey("FindStations", request(Body.MARS))
        CacheKey("FindStations", request(Body.MARS)).digest() shouldBe CacheKey("FindStations", request(Body.MARS)).digest()
        CacheKey("FindStations", request(Body.MARS)).digest() shouldNotBe CacheKey("FindStations", request(Body.VENUS)).digest()
        CacheKey("FindStations", request(Body.MARS)).digest() shouldNotBe CacheKey("FindCrossings", request(Body.MARS)).digest()
    }

    @Test
    fun `computes once per key`() {
        runBlocking {
            val cache = CaffeineResultCache(maxBytes = 100_000)
            var computed = 0
            repeat(3) {
                cache.getOrCompute(CacheKey("FindStations", request(Body.MARS)), StationsResponse.parser()) {
                    computed++
                    StationsResponse.getDefaultInstance()
                }
            }
            computed shouldBe 1
            cache.hitCount() shouldBe 2L
        }
    }

    @Test
    fun `the no-op cache always computes`() {
        runBlocking {
            var computed = 0
            repeat(2) {
                NoopResultCache.getOrCompute(CacheKey("FindStations", request(Body.MARS)), StationsResponse.parser()) {
                    computed++
                    StationsResponse.getDefaultInstance()
                }
            }
            computed shouldBe 2
        }
    }
}
