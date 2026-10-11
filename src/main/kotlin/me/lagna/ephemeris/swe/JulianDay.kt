// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import com.google.protobuf.Timestamp
import kotlin.math.floor
import kotlin.math.roundToLong

/** Conversions between protobuf timestamps (UTC, used as UT1) and Julian days. */
object JulianDay {
    private const val UNIX_EPOCH = 2440587.5
    private const val SECONDS_PER_DAY = 86_400.0
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val NANOS_PER_MILLI = 1_000_000L

    fun of(timestamp: Timestamp): Double = ofEpochSecond(timestamp.seconds, timestamp.nanos)

    fun ofEpochSecond(
        seconds: Long,
        nanos: Int = 0,
    ): Double = UNIX_EPOCH + (seconds + nanos / NANOS_PER_SECOND.toDouble()) / SECONDS_PER_DAY

    /** Rounded to whole milliseconds, well inside the precision a Julian day double carries. */
    fun toTimestamp(jd: Double): Timestamp {
        val millis = ((jd - UNIX_EPOCH) * SECONDS_PER_DAY * 1_000.0).roundToLong()
        val seconds = floor(millis / 1_000.0).toLong()
        val nanos = (millis - seconds * 1_000L) * NANOS_PER_MILLI
        return Timestamp
            .newBuilder()
            .setSeconds(seconds)
            .setNanos(nanos.toInt())
            .build()
    }
}
