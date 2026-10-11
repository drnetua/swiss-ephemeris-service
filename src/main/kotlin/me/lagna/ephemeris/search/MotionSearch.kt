// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.search

import kotlin.math.ceil
import kotlin.math.floor

/** Longitude (degrees) and its speed (degrees per day) of one body at one instant. */
data class Motion(
    val longitude: Double,
    val speed: Double,
)

/** Motion of one body in one frame as a function of the Julian day (UT). */
fun interface MotionSource {
    fun at(jd: Double): Motion

    /**
     * Longitude alone. Crossing searches only need this; a source can override it with a cheaper
     * calculation (libswe without `SEFLG_SPEED`), while station searches still use [at].
     */
    fun longitudeAt(jd: Double): Double = at(jd).longitude
}

data class StationHit(
    val jd: Double,
    val turnsRetrograde: Boolean,
)

data class CrossingHit(
    val jd: Double,
    val longitude: Double,
    val retrograde: Boolean,
)

/** Longitudes a body is searched for: a regular grid or an explicit list, both taken modulo 360. */
sealed interface LongitudeTargets {
    /** Target values v with [lower] <= v <= [upper] (unwrapped, [lower] < [upper]). */
    fun valuesIn(
        lower: Double,
        upper: Double,
    ): List<Double>

    data class Grid(
        val step: Double,
        val offset: Double = 0.0,
    ) : LongitudeTargets {
        init {
            require(step > 0.0 && step <= FULL_CIRCLE) { "step must be in (0, 360]" }
        }

        override fun valuesIn(
            lower: Double,
            upper: Double,
        ): List<Double> {
            val first = ceil((lower - offset) / step).toLong()
            val last = floor((upper - offset) / step).toLong()
            return (first..last).map { offset + it * step }
        }
    }

    data class Explicit(
        val longitudes: List<Double>,
    ) : LongitudeTargets {
        override fun valuesIn(
            lower: Double,
            upper: Double,
        ): List<Double> =
            longitudes
                .flatMap { g ->
                    val first = ceil((lower - g) / FULL_CIRCLE).toLong()
                    val last = floor((upper - g) / FULL_CIRCLE).toLong()
                    (first..last).map { g + it * FULL_CIRCLE }
                }.sorted()
    }
}

/**
 * Exact instants of stations and longitude crossings.
 *
 * Stations are the sign changes of the speed: the speed is sampled every [stationStep] days, which must be
 * shorter than the shortest gap between two stations of the body, and each sign change is refined by
 * bisection. Crossings need only longitudes: they are sampled every [crossingStep] days (the body must move
 * well under 180 degrees in that time) with the stations added as extra samples, so the longitude is
 * monotonic between consecutive samples and every target in between is crossed exactly once and refined by
 * bisection as well.
 */
class MotionSearch(
    private val source: MotionSource,
    private val stationStep: Double,
    private val canStation: Boolean,
    private val crossingStep: Double = DEFAULT_CROSSING_STEP_DAYS,
    private val tolerance: Double = DEFAULT_TOLERANCE_DAYS,
) {
    init {
        require(stationStep > 0.0) { "stationStep must be positive" }
        require(crossingStep > 0.0) { "crossingStep must be positive" }
    }

    fun stations(
        from: Double,
        to: Double,
    ): List<StationHit> {
        if (!canStation || to <= from) return emptyList()
        val hits = mutableListOf<StationHit>()
        var a = from
        var speedA = source.at(a).speed
        while (a < to) {
            val c = minOf(a + stationStep, to)
            val speedC = source.at(c).speed
            if (isNegative(speedA) != isNegative(speedC)) {
                hits += StationHit(refineStation(a, c, speedA), turnsRetrograde = isNegative(speedC))
            }
            a = c
            speedA = speedC
        }
        return hits
    }

    /**
     * Crossings of [targets] in chronological order. Stops after [limit] hits and throws
     * [TooManyResultsException] when there would be more.
     */
    fun crossings(
        from: Double,
        to: Double,
        targets: LongitudeTargets,
        limit: Int = Int.MAX_VALUE,
    ): List<CrossingHit> {
        if (to <= from) return emptyList()
        val knots = sampleTimes(from, to, stations(from, to).map { it.jd })
        val hits = mutableListOf<CrossingHit>()
        var a = knots.first()
        var longitudeA = source.longitudeAt(a)
        for (c in knots.drop(1)) {
            val longitudeC = source.longitudeAt(c)
            val delta = signedDelta(longitudeC - longitudeA)
            if (delta != 0.0) {
                val lower = minOf(longitudeA, longitudeA + delta)
                val upper = maxOf(longitudeA, longitudeA + delta)
                for (value in targets.valuesIn(lower, upper)) {
                    if (value == longitudeA) continue // counted in the previous interval
                    if (hits.size == limit) throw TooManyResultsException(limit)
                    val target = normalize(value)
                    hits += CrossingHit(refineCrossing(a, c, longitudeA, target), target, retrograde = delta < 0.0)
                }
            }
            a = c
            longitudeA = longitudeC
        }
        return hits
    }

    private fun sampleTimes(
        from: Double,
        to: Double,
        stations: List<Double>,
    ): List<Double> {
        val times = sortedSetOf(from, to)
        var t = from + crossingStep
        while (t < to) {
            times += t
            t += crossingStep
        }
        times += stations
        return times.toList()
    }

    private fun refineStation(
        start: Double,
        end: Double,
        speedAtStart: Double,
    ): Double {
        var lo = start
        var hi = end
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2
            if (isNegative(source.at(mid).speed) == isNegative(speedAtStart)) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    private fun refineCrossing(
        start: Double,
        end: Double,
        longitudeAtStart: Double,
        target: Double,
    ): Double {
        val startSide = isNegative(signedDelta(longitudeAtStart - target))
        var lo = start
        var hi = end
        while (hi - lo > tolerance) {
            val mid = (lo + hi) / 2
            val offset = signedDelta(source.longitudeAt(mid) - target)
            if (offset != 0.0 && isNegative(offset) == startSide) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    class TooManyResultsException(
        val limit: Int,
    ) : RuntimeException("more than $limit results; narrow the range")

    companion object {
        /** About 0.09 s. */
        const val DEFAULT_TOLERANCE_DAYS = 1e-6

        /** The Moon, the fastest body, moves about 15 degrees a day. */
        const val DEFAULT_CROSSING_STEP_DAYS = 1.0

        private fun isNegative(value: Double) = value < 0.0

        /** Angle difference folded into [-180, 180). */
        fun signedDelta(degrees: Double): Double =
            ((degrees + HALF_CIRCLE) % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE - HALF_CIRCLE

        fun normalize(degrees: Double): Double = (degrees % FULL_CIRCLE + FULL_CIRCLE) % FULL_CIRCLE
    }
}

private const val FULL_CIRCLE = 360.0
private const val HALF_CIRCLE = 180.0
