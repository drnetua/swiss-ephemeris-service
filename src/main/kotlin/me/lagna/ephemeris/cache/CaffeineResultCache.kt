// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.cache

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Weigher
import com.google.protobuf.Message
import com.google.protobuf.Parser
import java.time.Duration

/**
 * In-process cache bounded by the approximate size of the stored responses. Concurrent misses for the same
 * key may both compute; results are deterministic, so the second write is harmless.
 */
class CaffeineResultCache(
    maxBytes: Long,
    expireAfterAccess: Duration = Duration.ofHours(DEFAULT_TTL_HOURS),
) : ResultCache {
    private val cache: Cache<CacheKey, Message> =
        Caffeine
            .newBuilder()
            .maximumWeight(maxBytes)
            .weigher(Weigher<CacheKey, Message> { key, value -> key.request.serializedSize + value.serializedSize + OVERHEAD })
            .expireAfterAccess(expireAfterAccess)
            .recordStats()
            .build()

    override suspend fun <T : Message> getOrCompute(
        key: CacheKey,
        parser: Parser<T>,
        compute: suspend () -> T,
    ): T {
        @Suppress("UNCHECKED_CAST") // only this method writes, always with the type the caller reads back
        cache.getIfPresent(key)?.let { return it as T }
        return compute().also { cache.put(key, it) }
    }

    fun hitCount(): Long = cache.stats().hitCount()

    fun estimatedSize(): Long = cache.estimatedSize()

    private companion object {
        const val DEFAULT_TTL_HOURS = 24L
        const val OVERHEAD = 64
    }
}
