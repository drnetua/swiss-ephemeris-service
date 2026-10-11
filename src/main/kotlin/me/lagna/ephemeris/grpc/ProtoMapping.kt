// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.grpc

import me.lagna.ephemeris.swe.Frame
import me.lagna.ephemeris.swe.Place
import me.lagna.ephemeris.swe.SweConstants
import me.lagna.ephemeris.v1.Ayanamsa
import me.lagna.ephemeris.v1.Body
import me.lagna.ephemeris.v1.DiscConvention
import me.lagna.ephemeris.v1.GeoLocation
import me.lagna.ephemeris.v1.HouseSystem
import me.lagna.ephemeris.v1.RiseSetEvent
import me.lagna.ephemeris.v1.Zodiac

/** Thrown for a malformed request; reported as INVALID_ARGUMENT. */
class InvalidRequestException(
    message: String,
) : RuntimeException(message)

internal fun invalid(message: String): Nothing = throw InvalidRequestException(message)

/** Maps protobuf values to libswe identifiers, rejecting unspecified and unknown values. */
internal object ProtoMapping {
    private const val MAX_LATITUDE = 90.0
    private const val MAX_LONGITUDE = 180.0

    fun body(body: Body): Int =
        when (body) {
            Body.SUN -> SweConstants.SE_SUN
            Body.MOON -> SweConstants.SE_MOON
            Body.MERCURY -> SweConstants.SE_MERCURY
            Body.VENUS -> SweConstants.SE_VENUS
            Body.MARS -> SweConstants.SE_MARS
            Body.JUPITER -> SweConstants.SE_JUPITER
            Body.SATURN -> SweConstants.SE_SATURN
            Body.URANUS -> SweConstants.SE_URANUS
            Body.NEPTUNE -> SweConstants.SE_NEPTUNE
            Body.PLUTO -> SweConstants.SE_PLUTO
            Body.MEAN_NODE -> SweConstants.SE_MEAN_NODE
            Body.TRUE_NODE -> SweConstants.SE_TRUE_NODE
            Body.MEAN_APOGEE -> SweConstants.SE_MEAN_APOG
            Body.CHIRON -> SweConstants.SE_CHIRON
            Body.BODY_UNSPECIFIED, Body.UNRECOGNIZED -> invalid("body must be specified")
        }

    fun siderealMode(ayanamsa: Ayanamsa): Int =
        when (ayanamsa) {
            Ayanamsa.LAHIRI -> SweConstants.SE_SIDM_LAHIRI
            Ayanamsa.RAMAN -> SweConstants.SE_SIDM_RAMAN
            Ayanamsa.KRISHNAMURTI -> SweConstants.SE_SIDM_KRISHNAMURTI
            Ayanamsa.FAGAN_BRADLEY -> SweConstants.SE_SIDM_FAGAN_BRADLEY
            Ayanamsa.YUKTESHWAR -> SweConstants.SE_SIDM_YUKTESHWAR
            Ayanamsa.TRUE_CHITRAPAKSHA -> SweConstants.SE_SIDM_TRUE_CITRA
            Ayanamsa.TRUE_REVATI -> SweConstants.SE_SIDM_TRUE_REVATI
            Ayanamsa.TRUE_PUSHYA -> SweConstants.SE_SIDM_TRUE_PUSHYA
            Ayanamsa.AYANAMSA_UNSPECIFIED, Ayanamsa.UNRECOGNIZED -> invalid("ayanamsa must be specified")
        }

    /** Tropical when the zodiac is absent or empty. */
    fun frame(
        zodiac: Zodiac,
        present: Boolean,
    ): Frame =
        if (!present) {
            Frame.TROPICAL
        } else {
            when (zodiac.frameCase) {
                Zodiac.FrameCase.SIDEREAL -> Frame(siderealMode(zodiac.sidereal.ayanamsa))
                Zodiac.FrameCase.TROPICAL, Zodiac.FrameCase.FRAME_NOT_SET -> Frame.TROPICAL
            }
        }

    fun houseSystem(system: HouseSystem): Char =
        when (system) {
            HouseSystem.WHOLE_SIGN -> 'W'
            HouseSystem.EQUAL -> 'E'
            HouseSystem.PLACIDUS -> 'P'
            HouseSystem.SRIPATI -> 'S'
            HouseSystem.KOCH -> 'K'
            HouseSystem.PORPHYRY -> 'O'
            HouseSystem.REGIOMONTANUS -> 'R'
            HouseSystem.CAMPANUS -> 'C'
            HouseSystem.HOUSE_SYSTEM_UNSPECIFIED, HouseSystem.UNRECOGNIZED -> invalid("house system must be specified")
        }

    fun riseSetMode(
        event: RiseSetEvent,
        convention: DiscConvention,
    ): Int {
        val kind =
            when (event) {
                RiseSetEvent.RISE -> SweConstants.SE_CALC_RISE
                RiseSetEvent.SET -> SweConstants.SE_CALC_SET
                RiseSetEvent.UPPER_TRANSIT -> return SweConstants.SE_CALC_MTRANSIT
                RiseSetEvent.LOWER_TRANSIT -> return SweConstants.SE_CALC_ITRANSIT
                RiseSetEvent.RISE_SET_EVENT_UNSPECIFIED, RiseSetEvent.UNRECOGNIZED -> invalid("event must be specified")
            }
        return when (convention) {
            DiscConvention.UPPER_LIMB_REFRACTED, DiscConvention.DISC_CONVENTION_UNSPECIFIED -> kind
            DiscConvention.CENTRE_NO_REFRACTION ->
                kind or SweConstants.SE_BIT_DISC_CENTER or SweConstants.SE_BIT_NO_REFRACTION
            DiscConvention.CENTRE_REFRACTED -> kind or SweConstants.SE_BIT_DISC_CENTER
            DiscConvention.UNRECOGNIZED -> invalid("unknown disc convention")
        }
    }

    fun place(location: GeoLocation): Place {
        if (location.latitude !in -MAX_LATITUDE..MAX_LATITUDE) invalid("latitude must be within [-90, 90]")
        if (location.longitude !in -MAX_LONGITUDE..MAX_LONGITUDE) invalid("longitude must be within [-180, 180]")
        if (!location.altitudeM.isFinite()) invalid("altitude must be finite")
        return Place(location.latitude, location.longitude, location.altitudeM)
    }

    /** Bodies whose geocentric motion never reverses, so a station search can skip them. */
    fun canStation(seBody: Int): Boolean =
        seBody !in setOf(SweConstants.SE_SUN, SweConstants.SE_MOON, SweConstants.SE_MEAN_NODE, SweConstants.SE_MEAN_APOG)

    /**
     * Speed sampling step (days) for station searches, at least 4 times shorter than the shortest gap between
     * two stations of the body over 1800-2400 (Mercury 19.7 days, Venus 40.8, Mars 59.8, slower bodies 117+).
     * The true node is sampled every 0.1 day; its stations come in clusters and pairs closer than that are not
     * resolved. Neptune and Pluto occasionally station two or three times within an hour, wobbling by under
     * 0.5 arcseconds; such clusters are reported as one station.
     */
    fun stationStep(seBody: Int): Double =
        when (seBody) {
            SweConstants.SE_MERCURY -> MERCURY_STATION_STEP
            SweConstants.SE_TRUE_NODE -> TRUE_NODE_STATION_STEP
            else -> DEFAULT_STATION_STEP
        }

    /**
     * Station step used to split crossing searches. For the true node a coarser step only skips loops of at most
     * 0.014 degrees; over 1900-2100 it missed none of the sign, nakshatra or pada crossings found by sampling every
     * 0.005 day, with 2.3 times fewer libswe calls.
     */
    fun crossingStationStep(seBody: Int): Double =
        if (seBody == SweConstants.SE_TRUE_NODE) TRUE_NODE_CROSSING_STATION_STEP else stationStep(seBody)

    private const val MERCURY_STATION_STEP = 4.0
    private const val DEFAULT_STATION_STEP = 10.0
    private const val TRUE_NODE_STATION_STEP = 0.1
    private const val TRUE_NODE_CROSSING_STATION_STEP = 0.5
}
