// SPDX-License-Identifier: AGPL-3.0-or-later
package me.lagna.ephemeris.swe

import io.kotest.matchers.shouldBe
import me.lagna.ephemeris.J2000
import me.lagna.ephemeris.timestamp
import org.junit.jupiter.api.Test

class JulianDayTest {
    @Test
    fun `J2000 converts both ways`() {
        JulianDay.of(timestamp("2000-01-01T12:00:00Z")) shouldBe J2000
        JulianDay.toTimestamp(J2000) shouldBe timestamp("2000-01-01T12:00:00Z")
    }

    @Test
    fun `instants before 1970 round to milliseconds`() {
        val ts = timestamp("1850-06-30T17:45:12.250Z")
        JulianDay.toTimestamp(JulianDay.of(ts)) shouldBe ts
    }
}
