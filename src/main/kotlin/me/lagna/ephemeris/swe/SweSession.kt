// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import me.lagna.ephemeris.swe.SweConstants.SEFLG_SIDEREAL
import me.lagna.ephemeris.swe.SweConstants.SEFLG_SPEED
import me.lagna.ephemeris.swe.SweConstants.SEFLG_SWIEPH
import me.lagna.ephemeris.swe.SweConstants.SEFLG_TOPOCTR
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.JAVA_DOUBLE

/** Tropical when [siderealMode] is null, otherwise sidereal with that `SE_SIDM_*` mode. */
data class Frame(
    val siderealMode: Int? = null,
) {
    companion object {
        val TROPICAL = Frame()
    }
}

data class Place(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
)

data class Position(
    val longitude: Double,
    val latitude: Double,
    val distance: Double,
    val longitudeSpeed: Double,
    val latitudeSpeed: Double,
    val distanceSpeed: Double,
)

class HouseCusps(
    val ascendant: Double,
    val midheaven: Double,
    val armc: Double,
    val vertex: Double,
    /** 12 cusps, house 1 first; null when the system is undefined at this latitude. */
    val cusps: List<Double>?,
)

/**
 * Direct libswe calls for one task on one [SweThreads] worker. Not thread-safe: obtain it through
 * [SwissEphemeris.session] and do not let it escape the block. Every call sets the modes it depends on
 * (sidereal mode, topocentric observer), so one call's settings never leak into another.
 */
class SweSession internal constructor(
    private val native: SweNative,
    arena: Arena,
) {
    private val xx = arena.allocate(JAVA_DOUBLE, XX_SIZE)
    private val serr = arena.allocate(SweConstants.ERROR_BUFFER.toLong())
    private val cusps = arena.allocate(JAVA_DOUBLE, SweConstants.CUSPS.toLong())
    private val ascmc = arena.allocate(JAVA_DOUBLE, SweConstants.ASCMC.toLong())
    private val geopos = arena.allocate(JAVA_DOUBLE, GEOPOS_SIZE)
    private val tret = arena.allocate(JAVA_DOUBLE, TRET_SIZE)
    private val ayanamsaOut = arena.allocate(JAVA_DOUBLE)

    fun position(
        jdUt: Double,
        body: Int,
        frame: Frame = Frame.TROPICAL,
        observer: Place? = null,
    ): Position {
        var flags = SEFLG_SWIEPH or SEFLG_SPEED or frameFlags(frame)
        if (observer != null) {
            native.setTopo(observer.longitude, observer.latitude, observer.altitude)
            flags = flags or SEFLG_TOPOCTR
        }
        val returned = native.calcUt(jdUt, body, flags, xx, serr)
        requireFiles(returned, "swe_calc_ut(body $body)")
        return Position(xx.at(0), xx.at(1), xx.at(2), xx.at(3), xx.at(4), xx.at(5))
    }

    /**
     * Longitude alone, without `SEFLG_SPEED`: about 40% cheaper and identical to [position]'s longitude to
     * within 1e-13 degrees. Meant for searches that evaluate many instants and need no speed.
     */
    fun longitude(
        jdUt: Double,
        body: Int,
        frame: Frame = Frame.TROPICAL,
    ): Double {
        val returned = native.calcUt(jdUt, body, SEFLG_SWIEPH or frameFlags(frame), xx, serr)
        requireFiles(returned, "swe_calc_ut(body $body)")
        return xx.at(0)
    }

    fun ayanamsa(
        jdUt: Double,
        siderealMode: Int,
    ): Double {
        native.setSidMode(siderealMode)
        val returned = native.ayanamsaExUt(jdUt, SEFLG_SWIEPH, ayanamsaOut, serr)
        requireFiles(returned, "swe_get_ayanamsa_ex_ut(mode $siderealMode)")
        return ayanamsaOut.at(0)
    }

    fun houses(
        jdUt: Double,
        place: Place,
        system: Char,
        frame: Frame = Frame.TROPICAL,
    ): HouseCusps {
        val returned =
            native.housesEx2(jdUt, frameFlags(frame), place.latitude, place.longitude, system.code, cusps, ascmc, serr)
        // A negative result means the system is undefined here (e.g. Placidus beyond the polar circles);
        // libswe then fills Porphyry cusps, which this service does not pass off as the requested system.
        return HouseCusps(
            ascendant = ascmc.at(SweConstants.ASCMC_ASC),
            midheaven = ascmc.at(SweConstants.ASCMC_MC),
            armc = ascmc.at(SweConstants.ASCMC_ARMC),
            vertex = ascmc.at(SweConstants.ASCMC_VERTEX),
            cusps = if (returned < 0) null else (1..HOUSES).map { cusps.at(it) },
        )
    }

    /** First rise, set or transit after [jdUt] as a Julian day, or null when it does not happen (polar day/night). */
    fun riseTrans(
        jdUt: Double,
        body: Int,
        place: Place,
        mode: Int,
    ): Double? {
        geopos.setAtIndex(JAVA_DOUBLE, 0L, place.longitude)
        geopos.setAtIndex(JAVA_DOUBLE, 1L, place.latitude)
        geopos.setAtIndex(JAVA_DOUBLE, 2L, place.altitude)
        return when (val returned = native.riseTrans(jdUt, body, SEFLG_SWIEPH, mode, geopos, tret, serr)) {
            0 -> tret.at(0)
            SweConstants.CIRCUMPOLAR -> null
            else -> throw EphemerisException("swe_rise_trans(body $body) failed ($returned): ${serr.text()}")
        }
    }

    /** Sets the sidereal mode right before the calculation that needs it. */
    private fun frameFlags(frame: Frame): Int =
        when (val mode = frame.siderealMode) {
            null -> 0
            else -> SEFLG_SIDEREAL.also { native.setSidMode(mode) }
        }

    private fun requireFiles(
        returnedFlags: Int,
        call: String,
    ) {
        if (returnedFlags < 0) throw EphemerisException("$call failed: ${serr.text()}")
        if (returnedFlags and SEFLG_SWIEPH == 0) {
            throw EphemerisException("$call fell back from the Swiss Ephemeris files: ${serr.text()}")
        }
    }

    private companion object {
        const val XX_SIZE = 6L
        const val GEOPOS_SIZE = 3L
        const val TRET_SIZE = 10L
        const val HOUSES = 12

        fun MemorySegment.at(index: Int): Double = getAtIndex(JAVA_DOUBLE, index.toLong())

        fun MemorySegment.text(): String = getString(0).trim()
    }
}
