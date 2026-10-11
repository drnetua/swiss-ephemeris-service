// SPDX-License-Identifier: AGPL-3.0-or-later
@file:Suppress("MagicNumber") // values copied verbatim from swephexp.h

package me.lagna.ephemeris.swe

/** Values from `swephexp.h` (Swiss Ephemeris 2.10.03). */
object SweConstants {
    const val SE_SUN = 0
    const val SE_MOON = 1
    const val SE_MERCURY = 2
    const val SE_VENUS = 3
    const val SE_MARS = 4
    const val SE_JUPITER = 5
    const val SE_SATURN = 6
    const val SE_URANUS = 7
    const val SE_NEPTUNE = 8
    const val SE_PLUTO = 9
    const val SE_MEAN_NODE = 10
    const val SE_TRUE_NODE = 11
    const val SE_MEAN_APOG = 12
    const val SE_CHIRON = 15

    const val SE_SIDM_FAGAN_BRADLEY = 0
    const val SE_SIDM_LAHIRI = 1
    const val SE_SIDM_RAMAN = 3
    const val SE_SIDM_KRISHNAMURTI = 5
    const val SE_SIDM_YUKTESHWAR = 7
    const val SE_SIDM_TRUE_CITRA = 27
    const val SE_SIDM_TRUE_REVATI = 28
    const val SE_SIDM_TRUE_PUSHYA = 29

    const val SEFLG_SWIEPH = 2
    const val SEFLG_SPEED = 256
    const val SEFLG_TOPOCTR = 32 * 1024
    const val SEFLG_SIDEREAL = 64 * 1024

    const val SE_CALC_RISE = 1
    const val SE_CALC_SET = 2
    const val SE_CALC_MTRANSIT = 4
    const val SE_CALC_ITRANSIT = 8
    const val SE_BIT_DISC_CENTER = 256
    const val SE_BIT_NO_REFRACTION = 512

    internal const val ERROR_BUFFER = 256
    internal const val CUSPS = 13
    internal const val ASCMC = 10
    internal const val ASCMC_ASC = 0
    internal const val ASCMC_MC = 1
    internal const val ASCMC_ARMC = 2
    internal const val ASCMC_VERTEX = 3

    /** `swe_rise_trans` result when the body does not cross the horizon (polar day or night). */
    internal const val CIRCUMPOLAR = -2
}
