// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.lagna.ephemeris.ServiceConfig
import me.lagna.ephemeris.cache.CacheKey
import me.lagna.ephemeris.cache.ResultCache
import me.lagna.ephemeris.search.LongitudeTargets
import me.lagna.ephemeris.search.Motion
import me.lagna.ephemeris.search.MotionSearch
import me.lagna.ephemeris.search.MotionSource
import me.lagna.ephemeris.swe.EphemerisException
import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.JulianDay
import me.lagna.ephemeris.swe.Place
import me.lagna.ephemeris.swe.SweSession
import me.lagna.ephemeris.swe.SwissEphemeris
import me.lagna.ephemeris.v1.AyanamsaRequest
import me.lagna.ephemeris.v1.AyanamsaResponse
import me.lagna.ephemeris.v1.BodyPosition
import me.lagna.ephemeris.v1.Crossing
import me.lagna.ephemeris.v1.CrossingsRequest
import me.lagna.ephemeris.v1.CrossingsResponse
import me.lagna.ephemeris.v1.EphemerisGrpcKt
import me.lagna.ephemeris.v1.HousesRequest
import me.lagna.ephemeris.v1.HousesResponse
import me.lagna.ephemeris.v1.HousesResult
import me.lagna.ephemeris.v1.InfoRequest
import me.lagna.ephemeris.v1.InfoResponse
import me.lagna.ephemeris.v1.InstantPositions
import me.lagna.ephemeris.v1.PositionRangeRequest
import me.lagna.ephemeris.v1.PositionsRequest
import me.lagna.ephemeris.v1.PositionsResponse
import me.lagna.ephemeris.v1.RiseSetRequest
import me.lagna.ephemeris.v1.RiseSetResponse
import me.lagna.ephemeris.v1.RiseSetResult
import me.lagna.ephemeris.v1.RiseSetTime
import me.lagna.ephemeris.v1.Station
import me.lagna.ephemeris.v1.StationsRequest
import me.lagna.ephemeris.v1.StationsResponse
import java.time.DateTimeException
import java.time.Instant
import java.util.logging.Level
import java.util.logging.Logger
import me.lagna.ephemeris.v1.Body as ProtoBody

/** The `Ephemeris` gRPC service: validates requests, runs each one in a single libswe session, maps errors. */
class EphemerisService(
    private val ephemeris: SwissEphemeris,
    private val cache: ResultCache,
    private val limits: ServiceConfig.Limits = ServiceConfig.Limits(),
    private val sourceUrl: String = ServiceConfig.DEFAULT_SOURCE_URL,
) : EphemerisGrpcKt.EphemerisCoroutineImplBase() {
    private val supportedFrom = JulianDay.ofEpochSecond(limits.supportedFrom.epochSecond)
    private val supportedTo = JulianDay.ofEpochSecond(limits.supportedTo.epochSecond)

    override suspend fun getPositions(request: PositionsRequest): PositionsResponse =
        guarded {
            requireNotEmpty(request.instantsCount, "instants")
            requireNotEmpty(request.bodiesCount, "bodies")
            requireWithinLimit(request.instantsCount.toLong() * request.bodiesCount, "instants x bodies")
            val instants = request.instantsList.map { it to julianDay(it) }
            val bodies = request.bodiesList.map { it to ProtoMapping.body(it) }
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val observer = if (request.hasObserver()) ProtoMapping.place(request.observer) else null
            ephemeris.session {
                val response = PositionsResponse.newBuilder()
                for ((timestamp, jd) in instants) response.addResults(positionsAt(timestamp, jd, bodies, frame, observer))
                response.build()
            }
        }

    override fun streamPositions(request: PositionRangeRequest): Flow<PositionsResponse> =
        flow {
            val plan =
                guarded {
                    requireNotEmpty(request.bodiesCount, "bodies")
                    val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
                    val step = request.stepDays
                    if (!(step > 0.0) || !step.isFinite()) invalid("step_days must be positive")
                    val samples = ((to - from) / step).toLong() + 1
                    if (samples.toDouble() * request.bodiesCount > limits.maxStreamItems) {
                        invalid("samples x bodies exceeds ${limits.maxStreamItems}; increase step_days or narrow the range")
                    }
                    StreamPlan(
                        from,
                        step,
                        samples,
                        request.bodiesList.map { it to ProtoMapping.body(it) },
                        ProtoMapping.frame(request.zodiac, request.hasZodiac()),
                    )
                }
            var index = 0L
            while (index < plan.samples) {
                val end = minOf(index + STREAM_CHUNK, plan.samples)
                val first = index
                val chunk =
                    guarded {
                        ephemeris.session {
                            val response = PositionsResponse.newBuilder()
                            for (i in first until end) {
                                val jd = plan.from + i * plan.step
                                response.addResults(positionsAt(JulianDay.toTimestamp(jd), jd, plan.bodies, plan.frame, null))
                            }
                            response.build()
                        }
                    }
                emit(chunk)
                index = end
            }
        }

    override suspend fun getAyanamsa(request: AyanamsaRequest): AyanamsaResponse =
        guarded {
            requireNotEmpty(request.instantsCount, "instants")
            requireWithinLimit(request.instantsCount.toLong(), "instants")
            val mode = ProtoMapping.siderealMode(request.ayanamsa)
            val jds = request.instantsList.map { julianDay(it) }
            ephemeris.session {
                AyanamsaResponse.newBuilder().addAllDegrees(jds.map { ayanamsa(it, mode) }).build()
            }
        }

    override suspend fun getHouses(request: HousesRequest): HousesResponse =
        guarded {
            requireNotEmpty(request.queriesCount, "queries")
            requireWithinLimit(request.queriesCount.toLong(), "queries")
            val system = ProtoMapping.houseSystem(request.system)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val queries =
                request.queriesList.map { query ->
                    if (!query.hasLocation()) invalid("every query needs a location")
                    julianDay(query.instant) to ProtoMapping.place(query.location)
                }
            ephemeris.session {
                val response = HousesResponse.newBuilder()
                for ((jd, place) in queries) {
                    val houses = houses(jd, place, system, frame)
                    response.addResults(
                        HousesResult
                            .newBuilder()
                            .setAscendant(houses.ascendant)
                            .setMidheaven(houses.midheaven)
                            .setArmc(houses.armc)
                            .setVertex(houses.vertex)
                            .addAllCusps(houses.cusps.orEmpty())
                            .setCuspsDefined(houses.cusps != null),
                    )
                }
                response.build()
            }
        }

    override suspend fun getRiseSet(request: RiseSetRequest): RiseSetResponse =
        guarded {
            requireNotEmpty(request.queriesCount, "queries")
            requireWithinLimit(request.queriesList.sumOf { it.eventsCount.toLong() }, "query events")
            val body = ProtoMapping.body(request.body)
            val queries =
                request.queriesList.map { query ->
                    if (!query.hasLocation()) invalid("every query needs a location")
                    requireNotEmpty(query.eventsCount, "events")
                    val modes = query.eventsList.map { it to ProtoMapping.riseSetMode(it, request.convention) }
                    Triple(julianDay(query.after), ProtoMapping.place(query.location), modes)
                }
            ephemeris.session {
                val response = RiseSetResponse.newBuilder()
                for ((jd, place, modes) in queries) {
                    val result = RiseSetResult.newBuilder()
                    for ((event, mode) in modes) {
                        val time = RiseSetTime.newBuilder().setEvent(event)
                        riseTrans(jd, body, place, mode)?.let { time.setInstant(JulianDay.toTimestamp(it)) }
                        result.addTimes(time)
                    }
                    response.addResults(result)
                }
                response.build()
            }
        }

    override suspend fun findCrossings(request: CrossingsRequest): CrossingsResponse =
        guarded {
            val body = ProtoMapping.body(request.body)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            val targets = targets(request)
            cache.getOrCompute(CacheKey("FindCrossings", request), CrossingsResponse.parser()) {
                ephemeris.session {
                    val hits = search(body, frame).crossings(from, to, targets, limits.maxItems)
                    val response = CrossingsResponse.newBuilder()
                    for (hit in hits) {
                        response.addCrossings(
                            Crossing
                                .newBuilder()
                                .setInstant(JulianDay.toTimestamp(hit.jd))
                                .setLongitude(hit.longitude)
                                .setRetrograde(hit.retrograde),
                        )
                    }
                    response.build()
                }
            }
        }

    override suspend fun findStations(request: StationsRequest): StationsResponse =
        guarded {
            val body = ProtoMapping.body(request.body)
            val frame = ProtoMapping.frame(request.zodiac, request.hasZodiac())
            val (from, to) = range(request.hasFrom(), request.from, request.hasTo(), request.to)
            cache.getOrCompute(CacheKey("FindStations", request), StationsResponse.parser()) {
                ephemeris.session {
                    val response = StationsResponse.newBuilder()
                    for (hit in search(body, frame).stations(from, to)) {
                        if (response.stationsCount == limits.maxItems) throw MotionSearch.TooManyResultsException(limits.maxItems)
                        response.addStations(
                            Station
                                .newBuilder()
                                .setInstant(JulianDay.toTimestamp(hit.jd))
                                .setLongitude(position(hit.jd, body, frame).longitude)
                                .setTurnsRetrograde(hit.turnsRetrograde),
                        )
                    }
                    response.build()
                }
            }
        }

    override suspend fun getInfo(request: InfoRequest): InfoResponse =
        InfoResponse
            .newBuilder()
            .setEngineVersion(ephemeris.version)
            .setSupportedFrom(timestamp(limits.supportedFrom))
            .setSupportedTo(timestamp(limits.supportedTo))
            .setMaxItemsPerRequest(limits.maxItems)
            .setSourceUrl(sourceUrl)
            .build()

    // ------------------------------------------------------------------------------------------ helpers

    private class StreamPlan(
        val from: Double,
        val step: Double,
        val samples: Long,
        val bodies: List<Pair<ProtoBody, Int>>,
        val frame: Frame,
    )

    private fun SweSession.positionsAt(
        timestamp: Timestamp,
        jd: Double,
        bodies: List<Pair<ProtoBody, Int>>,
        frame: Frame,
        observer: Place?,
    ): InstantPositions {
        val result = InstantPositions.newBuilder().setInstant(timestamp)
        for ((protoBody, seBody) in bodies) {
            val p = position(jd, seBody, frame, observer)
            result.addPositions(
                BodyPosition
                    .newBuilder()
                    .setBody(protoBody)
                    .setLongitude(p.longitude)
                    .setLatitude(p.latitude)
                    .setDistanceAu(p.distance)
                    .setLongitudeSpeed(p.longitudeSpeed)
                    .setLatitudeSpeed(p.latitudeSpeed)
                    .setDistanceSpeed(p.distanceSpeed),
            )
        }
        return result.build()
    }

    private fun SweSession.search(
        seBody: Int,
        frame: Frame,
    ): MotionSearch =
        MotionSearch(
            source =
                MotionSource { jd ->
                    val p = position(jd, seBody, frame)
                    Motion(p.longitude, p.longitudeSpeed)
                },
            step = ProtoMapping.searchStep(seBody),
            canStation = ProtoMapping.canStation(seBody),
        )

    private fun targets(request: CrossingsRequest): LongitudeTargets =
        when (request.targetsCase) {
            CrossingsRequest.TargetsCase.GRID -> {
                val grid = request.grid
                if (!(grid.stepDegrees > 0.0 && grid.stepDegrees <= FULL_CIRCLE)) invalid("step_degrees must be in (0, 360]")
                if (!grid.offsetDegrees.isFinite()) invalid("offset_degrees must be finite")
                LongitudeTargets.Grid(grid.stepDegrees, grid.offsetDegrees)
            }

            CrossingsRequest.TargetsCase.LIST -> {
                val longitudes = request.list.longitudesList
                requireNotEmpty(longitudes.size, "longitudes")
                requireWithinLimit(longitudes.size.toLong(), "longitudes")
                if (longitudes.any { !it.isFinite() }) invalid("longitudes must be finite")
                LongitudeTargets.Explicit(longitudes.map { MotionSearch.normalize(it) }.distinct())
            }

            CrossingsRequest.TargetsCase.TARGETS_NOT_SET -> invalid("set grid or list")
        }

    private fun range(
        hasFrom: Boolean,
        from: Timestamp,
        hasTo: Boolean,
        to: Timestamp,
    ): Pair<Double, Double> {
        if (!hasFrom || !hasTo) invalid("from and to are required")
        val start = julianDay(from)
        val end = julianDay(to)
        if (end <= start) invalid("to must be after from")
        return start to end
    }

    private fun julianDay(timestamp: Timestamp): Double {
        val instant =
            try {
                Instant.ofEpochSecond(timestamp.seconds, timestamp.nanos.toLong())
            } catch (e: DateTimeException) {
                invalid("invalid timestamp: ${e.message}")
            }
        if (instant.isBefore(limits.supportedFrom) || instant.isAfter(limits.supportedTo)) {
            throw OutOfRangeException("$instant is outside ${limits.supportedFrom} .. ${limits.supportedTo}")
        }
        val jd = JulianDay.of(timestamp)
        check(jd in supportedFrom..supportedTo)
        return jd
    }

    private fun requireNotEmpty(
        count: Int,
        what: String,
    ) {
        if (count == 0) invalid("$what must not be empty")
    }

    private fun requireWithinLimit(
        count: Long,
        what: String,
    ) {
        if (count > limits.maxItems) invalid("$what ($count) exceeds the limit of ${limits.maxItems}")
    }

    private class OutOfRangeException(
        message: String,
    ) : RuntimeException(message)

    private suspend fun <T> guarded(block: suspend () -> T): T =
        try {
            block()
        } catch (e: InvalidRequestException) {
            throw status(Status.INVALID_ARGUMENT, e.message)
        } catch (e: MotionSearch.TooManyResultsException) {
            throw status(Status.INVALID_ARGUMENT, e.message)
        } catch (e: OutOfRangeException) {
            throw status(Status.OUT_OF_RANGE, e.message)
        } catch (e: EphemerisException) {
            log.log(Level.WARNING, "libswe call failed", e)
            throw status(Status.INTERNAL, e.message)
        }

    private companion object {
        const val STREAM_CHUNK = 500L
        const val FULL_CIRCLE = 360.0
        val log: Logger = Logger.getLogger(EphemerisService::class.java.name)

        fun status(
            status: Status,
            message: String?,
        ): StatusException = status.withDescription(message).asException()

        fun timestamp(instant: Instant): Timestamp =
            Timestamp
                .newBuilder()
                .setSeconds(instant.epochSecond)
                .setNanos(instant.nano)
                .build()
    }
}
