package com.enuvro.saltykmp

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What Get Info puts on its three lines. Stored timestamps are UTC; the viewer's zone decides. */
class RecipeInfoTest {

    private val chicago = TimeZone.of("America/Chicago")

    @Test
    fun momentsAreShownAsADateAndTimeInTheViewersZone() {
        // 01:41 UTC is still the previous evening in Chicago — the whole reason this converts.
        assertEquals("Sep 10, 2026 at 8:41 PM", formatMoment("2026-09-11T01:41:07.000Z", chicago))
        assertEquals("Sep 11, 2026 at 1:41 AM", formatMoment("2026-09-11T01:41:07.000Z", TimeZone.UTC))
    }

    @Test
    fun middayAndMidnightReadTheWayAClockDoes() {
        assertEquals("Jan 1, 2026 at 12:00 AM", formatMoment("2026-01-01T00:00:00.000Z", TimeZone.UTC))
        assertEquals("Jan 1, 2026 at 12:05 PM", formatMoment("2026-01-01T12:05:00.000Z", TimeZone.UTC))
    }

    @Test
    fun aWholeSecondStampFromTheSwiftAppIsReadToo() {
        assertEquals("Aug 7, 2025 at 8:17 PM", formatMoment("2025-08-07T20:17:17Z", TimeZone.UTC))
    }

    @Test
    fun anAbsentOrUnreadableStampIsNotADate() {
        assertNull(formatMoment(null))
        assertNull(formatMoment("   "))
        assertNull(formatMoment("2026-09-11"))
    }

    @Test
    fun theThreeLinesAreLabelledAndOrderedAsSwiftsInspector() {
        val info = recipeInfoOf(
            created = "2026-08-25T12:34:56.789Z",
            lastModified = "2026-09-11T01:41:07.000Z",
            lastPrepared = "2026-09-01T17:00:00.000Z",
            zone = TimeZone.UTC,
        )
        assertEquals(
            listOf(
                "Created" to "Aug 25, 2026 at 12:34 PM",
                "Last Modified" to "Sep 11, 2026 at 1:41 AM",
                // A calendar day, not a moment: stored at local noon, so no time is shown.
                "Last Prepared" to "Sep 1, 2026",
            ),
            info.rows,
        )
    }

    @Test
    fun aRecipeNeverPreparedSaysSoRatherThanLeavingTheLineBlank() {
        val info = recipeInfoOf("2026-08-25T12:34:56.789Z", "2026-08-25T12:34:56.789Z", null, TimeZone.UTC)
        assertEquals("Not set", info.lastPrepared)
    }

    @Test
    fun aMissingCreatedDateSaysUnknownRatherThanVanishing() {
        val info = recipeInfoOf(null, "nonsense", null, TimeZone.UTC)
        assertEquals("Unknown", info.created)
        assertEquals("Unknown", info.lastModified)
    }
}
