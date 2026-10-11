// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import me.lagna.ephemeris.TestEphemeris
import me.lagna.ephemeris.cache.NoopResultCache
import me.lagna.ephemeris.timestamp
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.EphemerisGrpcKt
import me.lagna.ephemeris.v1.LongitudeGrid
import me.lagna.ephemeris.v1.Sidereal
import me.lagna.ephemeris.v1.Zodiac
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * Compares FindCrossings with and without `SEFLG_SPEED` (SEARCH_WITHOUT_SPEED). Runs with `./gradlew perfTest`,
 * prints a table and writes it to the file in the `perf.out` system property. Both modes must find the same
 * crossings; the mode without speed must be faster overall.
 */
@Tag("perf")
class SearchPerfTest {
    private class Endpoint(
        searchWithoutSpeed: Boolean,
    ) {
        private val name = InProcessServerBuilder.generateName()
        val server: Server =
            InProcessServerBuilder
                .forName(name)
                .directExecutor()
                .addService(EphemerisService(TestEphemeris.instance, NoopResultCache, searchWithoutSpeed = searchWithoutSpeed))
                .build()
                .start()
        val channel: ManagedChannel = InProcessChannelBuilder.forName(name).directExecutor().build()
        val client = EphemerisGrpcKt.EphemerisCoroutineStub(channel)

        fun close() {
            channel.shutdownNow()
            server.shutdownNow()
        }
    }

    private class Scenario(
        val label: String,
        val request: CrossingsRequest,
    )

    private val withSpeed = Endpoint(searchWithoutSpeed = false)
    private val withoutSpeed = Endpoint(searchWithoutSpeed = true)

    private val lahiri =
        Zodiac
            .newBuilder()
            .setSidereal(Sidereal.newBuilder().setAyanamsa(Ayanamsa.LAHIRI))
            .build()

    private val scenarios =
        listOf(
            Scenario("Mercury, nakshatras, Lahiri, 2000-2100", request(Body.MERCURY, 2000, 2100, 360.0 / 27, lahiri)),
            Scenario("Moon, nakshatras, Lahiri, 2000-2010", request(Body.MOON, 2000, 2010, 360.0 / 27, lahiri)),
            Scenario("Sun, every degree, tropical, 2000-2010", request(Body.SUN, 2000, 2010, 1.0, null)),
            Scenario("True node, signs, tropical, 2000-2050", request(Body.TRUE_NODE, 2000, 2050, 30.0, null)),
        )

    @AfterEach
    fun close() {
        withSpeed.close()
        withoutSpeed.close()
    }

    @Test
    fun `crossings without speed are as exact and faster`() {
        runBlocking {
            val lines = mutableListOf<String>()
            var totalWith = 0.0
            var totalWithout = 0.0
            for (scenario in scenarios) {
                val expected = withSpeed.client.findCrossings(scenario.request).crossingsList
                withoutSpeed.client.findCrossings(scenario.request).crossingsList shouldBe expected

                repeat(WARMUP) {
                    withSpeed.client.findCrossings(scenario.request)
                    withoutSpeed.client.findCrossings(scenario.request)
                }
                val timesWith = mutableListOf<Double>()
                val timesWithout = mutableListOf<Double>()
                repeat(ROUNDS) {
                    timesWith += millis { withSpeed.client.findCrossings(scenario.request) }
                    timesWithout += millis { withoutSpeed.client.findCrossings(scenario.request) }
                }
                val medianWith = timesWith.sorted()[ROUNDS / 2]
                val medianWithout = timesWithout.sorted()[ROUNDS / 2]
                totalWith += medianWith
                totalWithout += medianWithout
                lines +=
                    "${scenario.label} (${expected.size} crossings): with speed ${format(medianWith)} ms, " +
                    "without ${format(medianWithout)} ms (${change(medianWith, medianWithout)})"
            }
            lines += "Total: with speed ${format(totalWith)} ms, without ${format(totalWithout)} ms " +
                "(${change(totalWith, totalWithout)}), median of $ROUNDS rounds, ${Runtime.getRuntime().availableProcessors()} CPUs"
            report(lines)
            (totalWithout < totalWith) shouldBe true
        }
    }

    private fun request(
        body: Body,
        fromYear: Int,
        toYear: Int,
        step: Double,
        zodiac: Zodiac?,
    ): CrossingsRequest {
        val request =
            CrossingsRequest
                .newBuilder()
                .setBody(body)
                .setFrom(timestamp("$fromYear-01-01T00:00:00Z"))
                .setTo(timestamp("$toYear-01-01T00:00:00Z"))
                .setGrid(LongitudeGrid.newBuilder().setStepDegrees(step))
        zodiac?.let { request.setZodiac(it) }
        return request.build()
    }

    private suspend fun millis(block: suspend () -> Unit): Double {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / NANOS_PER_MILLI
    }

    private fun report(lines: List<String>) {
        lines.forEach(::println)
        System.getProperty("perf.out")?.let { out ->
            val path = Path.of(out)
            Files.createDirectories(path.parent)
            Files.write(path, lines)
        }
    }

    private companion object {
        const val WARMUP = 2
        const val ROUNDS = 5
        const val NANOS_PER_MILLI = 1_000_000.0

        fun format(ms: Double): String = "%.1f".format(ms)

        fun change(
            before: Double,
            after: Double,
        ): String {
            val percent = ((after - before) / before * 100).roundToInt()
            return if (percent <= 0) "$percent%" else "+$percent%"
        }
    }
}
