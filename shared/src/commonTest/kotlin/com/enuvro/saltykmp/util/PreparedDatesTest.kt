package com.enuvro.saltykmp.util

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the local-noon convention in [PreparedDates]. This code writes the same "last prepared" column as the
 * Swift app's `RecipeNavigationSplitViewModel.localNoon(on:)`, so a silent shift here desynchronises the two
 * clients rather than failing loudly.
 *
 * Added when `LocalDate.monthNumber` (Int) was migrated to `month` (Month enum) for kotlinx-datetime 0.8.0 —
 * the month is what indexes [PreparedDates] month abbreviations, so an off-by-one would surface only as a
 * mislabelled date in the UI.
 */
class PreparedDatesTest {

    private val utc = TimeZone.UTC
    private val westOfUtc = TimeZone.of("America/Los_Angeles") // UTC-7/-8
    private val eastOfUtc = TimeZone.of("Pacific/Kiritimati")  // UTC+14, the extreme case

    /** Picker reports UTC midnight; the stored instant must land on local noon of that same calendar day. */
    @Test
    fun pickerSelectionIsStoredAtLocalNoonOfTheChosenDay() {
        // 2026-08-14T00:00:00Z — UTC midnight of 14 Aug, as a Material3 picker reports it.
        val utcMidnight = 1786665600000L
        for (zone in listOf(utc, westOfUtc, eastOfUtc)) {
            val wire = PreparedDates.pickerMillisToWire(utcMidnight, zone)
            assertEquals(
                "Aug 14, 2026",
                PreparedDates.formatForDisplay(wire, zone),
                "chosen day must survive the round trip in $zone",
            )
        }
    }

    /** The picker round-trip must be lossless: pick a day, store it, seed the picker again. */
    @Test
    fun pickerRoundTripReturnsTheSameUtcMidnight() {
        val utcMidnight = 1786665600000L
        for (zone in listOf(utc, westOfUtc, eastOfUtc)) {
            val wire = PreparedDates.pickerMillisToWire(utcMidnight, zone)
            assertEquals(utcMidnight, PreparedDates.wireToPickerMillis(wire, zone), "round trip in $zone")
        }
    }

    /**
     * Every month must map to its own abbreviation. This is the direct regression guard for the
     * `monthNumber` -> `month.number` migration: a 0-based reading would shift every label by one.
     */
    @Test
    fun everyMonthRendersItsOwnAbbreviation() {
        val expected = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
        for ((index, abbreviation) in expected.withIndex()) {
            val month = (index + 1).toString().padStart(2, '0')
            val wire = "2026-$month-15T12:00:00Z"
            assertEquals(
                "$abbreviation 15, 2026",
                PreparedDates.formatForDisplay(wire, utc),
                "month $month must render as $abbreviation",
            )
        }
    }

    /** Boundary months are where an off-by-one would run off either end of the abbreviation table. */
    @Test
    fun januaryAndDecemberDoNotFallOffTheAbbreviationTable() {
        assertEquals("Jan 1, 2026", PreparedDates.formatForDisplay("2026-01-01T12:00:00Z", utc))
        assertEquals("Dec 31, 2026", PreparedDates.formatForDisplay("2026-12-31T12:00:00Z", utc))
    }

    @Test
    fun absentOrUnparseableWireYieldsNull() {
        assertNull(PreparedDates.formatForDisplay(null, utc))
        assertNull(PreparedDates.formatForDisplay("", utc))
        assertNull(PreparedDates.formatForDisplay("not-a-timestamp", utc))
        assertNull(PreparedDates.wireToPickerMillis(null, utc))
    }
}
