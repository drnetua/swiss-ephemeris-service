// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.cache

import com.google.protobuf.CodedOutputStream
import com.google.protobuf.Message
import com.google.protobuf.Parser
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Cache of whole responses, keyed by the RPC method and its request. Requests carry every input and the
 * service keeps no other state, so equal requests always have equal responses.
 *
 * Implementations decide where results live: [CaffeineResultCache] in this process, [NoopResultCache]
 * nowhere. A shared cache (e.g. Redis) can implement the same interface: [CacheKey.digest] gives a stable
 * string key and [Parser] turns stored bytes back into the response.
 */
interface ResultCache {
    suspend fun <T : Message> getOrCompute(
        key: CacheKey,
        parser: Parser<T>,
        compute: suspend () -> T,
    ): T
}

/** Method name plus request. In-process caches compare requests by value; shared caches use [digest]. */
data class CacheKey(
    val method: String,
    val request: Message,
) {
    /** SHA-256 over the method and the deterministic serialisation of the request, as lowercase hex. */
    fun digest(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(method.toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(deterministicBytes(request))
        return HexFormat.of().formatHex(digest.digest())
    }

    private companion object {
        fun deterministicBytes(message: Message): ByteArray {
            val bytes = ByteArrayOutputStream(message.serializedSize)
            val output = CodedOutputStream.newInstance(bytes)
            output.useDeterministicSerialization()
            message.writeTo(output)
            output.flush()
            return bytes.toByteArray()
        }
    }
}

object NoopResultCache : ResultCache {
    override suspend fun <T : Message> getOrCompute(
        key: CacheKey,
        parser: Parser<T>,
        compute: suspend () -> T,
    ): T = compute()
}
