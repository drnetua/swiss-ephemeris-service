// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris

import com.google.protobuf.Timestamp
import me.lagna.ephemeris.swe.SwissEphemeris
import java.nio.file.Path
import java.time.Instant

/** One libswe instance per test JVM, from the paths Gradle passes as system properties. */
object TestEphemeris {
    val library: Path get() = Path.of(System.getProperty("swe.lib"))
    val epheDirectory: Path get() = Path.of(System.getProperty("swe.ephe"))

    val instance: SwissEphemeris by lazy { SwissEphemeris.open(library, epheDirectory) }
}

fun timestamp(iso: String): Timestamp {
    val instant = Instant.parse(iso)
    return Timestamp
        .newBuilder()
        .setSeconds(instant.epochSecond)
        .setNanos(instant.nano)
        .build()
}

fun Timestamp.toInstant(): Instant = Instant.ofEpochSecond(seconds, nanos.toLong())

/** J2000.0 (2000-01-01T12:00 UT). */
const val J2000 = 2451545.0
