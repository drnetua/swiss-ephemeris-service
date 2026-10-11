// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.search.LongitudeTargets
import me.lagna.ephemeris.search.Motion
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.search.MotionSource
import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.swe.SweSession
import me.lagna.ephemeris.timestamp
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The coarse production sampling steps find exactly what sampling every 0.1 day finds. */
class SearchStepTest {
    private val from = JulianDay.of(timestamp("2000-01-01T00:00:00Z"))
    private val to = JulianDay.of(timestamp("2040-01-01T00:00:00Z"))
    private val padas = LongitudeTargets.Grid(360.0 / 108)

    @ParameterizedTest
    @ValueSource(ints = [SweConstants.SE_MERCURY, SweConstants.SE_VENUS, SweConstants.SE_MARS, SweConstants.SE_JUPITER, SweConstants.SE_CHIRON])
    fun `station and crossing searches match a fine step`(body: Int) {
        TestEphemeris.instance.sessionBlocking {
            val coarse = search(body, ProtoMapping.stationStep(body))
            val fine = search(body, FINE_STEP)

            val expectedStations = fine.stations(from, to)
            val stations = coarse.stations(from, to)
            stations shouldHaveSize expectedStations.size
            stations.zip(expectedStations).forEach { (actual, expected) ->
                actual.turnsRetrograde shouldBe expected.turnsRetrograde
                actual.jd shouldBe (expected.jd plusOrMinus TIME_TOLERANCE)
            }

            assertSameCrossings(coarse, fine)
        }
    }

    @org.junit.jupiter.api.Test
    fun `true node crossings with the coarser crossing step match a fine step`() {
        val body = SweConstants.SE_TRUE_NODE
        TestEphemeris.instance.sessionBlocking {
            assertSameCrossings(search(body, ProtoMapping.crossingStationStep(body)), search(body, FINE_STEP))
        }
    }

    private fun assertSameCrossings(
        coarse: MotionSearch,
        fine: MotionSearch,
    ) {
        val expected = fine.crossings(from, to, padas)
        val actual = coarse.crossings(from, to, padas)
        actual shouldHaveSize expected.size
        actual.zip(expected).forEach { (a, e) ->
            a.longitude shouldBe e.longitude
            a.retrograde shouldBe e.retrograde
            a.jd shouldBe (e.jd plusOrMinus TIME_TOLERANCE)
        }
    }

    private fun SweSession.search(
        body: Int,
        stationStep: Double,
    ) = MotionSearch(
        source =
            object : MotionSource {
                override fun at(jd: Double): Motion {
                    val p = position(jd, body, Frame.TROPICAL)
                    return Motion(p.longitude, p.longitudeSpeed)
                }

                override fun longitudeAt(jd: Double): Double = longitude(jd, body, Frame.TROPICAL)
            },
        stationStep = stationStep,
        canStation = ProtoMapping.canStation(body),
    )

    private companion object {
        const val FINE_STEP = 0.1

        /** About 0.9 s: both searches refine the same instants to 1e-6 day. */
        const val TIME_TOLERANCE = 1e-5
    }
}
