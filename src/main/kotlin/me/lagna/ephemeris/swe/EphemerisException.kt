// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

/** A libswe call failed, or would have silently fallen back from the `.se1` files to the Moshier model. */
class EphemerisException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
