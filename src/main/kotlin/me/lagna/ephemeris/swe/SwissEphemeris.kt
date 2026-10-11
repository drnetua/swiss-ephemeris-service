// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import kotlinx.coroutines.future.await
import java.lang.foreign.Arena
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletionException

/**
 * libswe with the bundled `.se1` files. Every calculation runs inside a [session] on a dedicated worker
 * thread, so a whole request (or a whole search) costs one thread hand-off rather than one per call.
 */
class SwissEphemeris internal constructor(
    private val native: SweNative,
    private val threads: SweThreads,
) : AutoCloseable {
    /** Engine name and version, e.g. `Swiss Ephemeris 2.10.03`. */
    val version: String by lazy { "Swiss Ephemeris " + threads.submit { native.version() }.join() }

    suspend fun <T> session(block: SweSession.() -> T): T = threads.submit { runSession(block) }.await()

    /** Blocking variant for startup checks and tests. */
    fun <T> sessionBlocking(block: SweSession.() -> T): T =
        try {
            threads.submit { runSession(block) }.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }

    override fun close() = threads.close()

    private fun <T> runSession(block: SweSession.() -> T): T =
        Arena.ofConfined().use { arena -> SweSession(native, arena).block() }

    companion object {
        /** J2000.0, a date every bundled ephemeris file covers. */
        private const val SELF_CHECK_JD = 2451545.0

        /**
         * Opens libswe and fails fast when the library or the `.se1` files are unusable, so the service never
         * silently runs on the less precise Moshier fallback.
         */
        fun open(
            library: Path,
            epheDirectory: Path,
            threads: Int = SweThreads.defaultSize(),
        ): SwissEphemeris {
            if (!Files.isRegularFile(library)) throw EphemerisException("libswe not found at $library")
            if (!Files.isDirectory(epheDirectory)) {
                throw EphemerisException("ephemeris directory not found: $epheDirectory")
            }
            val native = SweNative(library)
            val ephemeris = SwissEphemeris(native, SweThreads(threads) { native.setEphePath(epheDirectory) })
            try {
                ephemeris.sessionBlocking { position(SELF_CHECK_JD, SweConstants.SE_MOON) }
            } catch (e: EphemerisException) {
                ephemeris.close()
                throw EphemerisException("Swiss Ephemeris self-check failed with files in $epheDirectory", e)
            }
            return ephemeris
        }
    }
}
