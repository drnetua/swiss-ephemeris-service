// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.search

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class MotionSearchTest {
    /** 1 degree per day starting at 355 degrees: crosses 0 at day 5, 30 at day 35. */
    private val linear = MotionSource { jd -> Motion(MotionSearch.normalize(355.0 + jd), 1.0) }

    /** Moves forward with a backward loop: speed 1 + 2 cos(2 pi t / 20) changes sign twice per 20 days. */
    private val looping =
        MotionSource { jd ->
            val omega = 2 * PI / 20
            Motion(MotionSearch.normalize(jd + 2 / omega * sin(omega * jd)), 1 + 2 * cos(omega * jd))
        }

    @org.junit.jupiter.api.Test
    fun `finds grid crossings across 360 degrees`() {
        val hits = MotionSearch(linear, stationStep = 1.0, canStation = false).crossings(0.0, 40.0, LongitudeTargets.Grid(30.0))
        hits.map { it.longitude } shouldBe listOf(0.0, 30.0)
        hits[0].jd shouldBe (5.0 plusOrMinus 1e-5)
        hits[1].jd shouldBe (35.0 plusOrMinus 1e-5)
        hits.none { it.retrograde } shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `finds explicit longitudes and ignores the start value`() {
        val hits =
            MotionSearch(linear, stationStep = 1.0, canStation = false)
                .crossings(0.0, 40.0, LongitudeTargets.Explicit(listOf(355.0, 10.0)))
        hits.map { it.longitude } shouldBe listOf(10.0)
    }

    @org.junit.jupiter.api.Test
    fun `stations split the motion so each target is crossed in both directions`() {
        val search = MotionSearch(looping, stationStep = 0.5, canStation = true)
        val stations = search.stations(0.0, 20.0)
        stations shouldHaveSize 2
        stations[0].turnsRetrograde shouldBe true
        stations[1].turnsRetrograde shouldBe false
        // speed = 0 where cos = -1/2: t = 20/3 and 40/3
        stations[0].jd shouldBe (20.0 / 3 plusOrMinus 1e-5)
        stations[1].jd shouldBe (40.0 / 3 plusOrMinus 1e-5)

        val target = looping.at(10.0).longitude // inside the retrograde loop
        val hits = search.crossings(0.0, 20.0, LongitudeTargets.Explicit(listOf(target)))
        hits.map { it.retrograde } shouldBe listOf(false, true, false)
        hits[1].jd shouldBe (10.0 plusOrMinus 1e-5)
    }

    @org.junit.jupiter.api.Test
    fun `crossing searches ask only for longitudes when the body cannot station`() {
        var fullCalls = 0
        var longitudeCalls = 0
        val counting =
            object : MotionSource {
                override fun at(jd: Double): Motion {
                    fullCalls++
                    return linear.at(jd)
                }

                override fun longitudeAt(jd: Double): Double {
                    longitudeCalls++
                    return linear.at(jd).longitude
                }
            }
        MotionSearch(counting, stationStep = 1.0, canStation = false).crossings(0.0, 40.0, LongitudeTargets.Grid(30.0))
        fullCalls shouldBe 0
        (longitudeCalls > 0) shouldBe true
    }

    @org.junit.jupiter.api.Test
    fun `stops at the result limit`() {
        shouldThrow<MotionSearch.TooManyResultsException> {
            MotionSearch(linear, stationStep = 1.0, canStation = false).crossings(0.0, 400.0, LongitudeTargets.Grid(1.0), limit = 10)
        }
    }

    @org.junit.jupiter.api.Test
    fun `signed delta folds angles`() {
        MotionSearch.signedDelta(350.0) shouldBe -10.0
        MotionSearch.signedDelta(-350.0) shouldBe 10.0
        MotionSearch.normalize(-30.0) shouldBe 330.0
    }
}
