package com.enuvro.saltykmp

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.ExperimentalTime

/**
 * Pins the wording and boundaries of the "Last synced:" line — the counterpart of the Swift app's
 * LastSyncedDescriptionTests. All in UTC so the day boundaries are deterministic.
 */
@OptIn(ExperimentalTime::class)
class LastSyncedDescriptionTest {

    private val zone = TimeZone.UTC

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        LocalDateTime(year, month, day, hour, minute, second).toInstant(zone).toEpochMilliseconds()

    // A reference "now": June 15, 2026, 2:30:00 PM UTC.
    private val now = millis(2026, 6, 15, 14, 30)

    private fun text(at: Long) = LastSyncedDescription.text(at, now, zone)

    @Test
    fun recent_syncs_read_relative() {
        assertEquals("Just now", text(now))
        assertEquals("Just now", text(now - 14_000))
        assertEquals("Less than a minute ago", text(now - 15_000))
        assertEquals("Less than a minute ago", text(now - 59_000))
        assertEquals("1 minute ago", text(now - 60_000))
        assertEquals("5 minutes ago", text(now - 5 * 60_000))
    }

    @Test
    fun older_syncs_switch_to_clock_time() {
        assertEquals("Today at 8:15 AM", text(millis(2026, 6, 15, 8, 15)))
        assertEquals("Yesterday at 11:32 PM", text(millis(2026, 6, 14, 23, 32)))
        assertEquals("Jun 2 at 12:05 PM", text(millis(2026, 6, 2, 12, 5)))
        assertEquals("Dec 30, 2025 at 8:15 AM", text(millis(2025, 12, 30, 8, 15)))
    }

    /** A sync "in the future" means the clock moved backwards; say when, don't claim "in 3 minutes". */
    @Test
    fun a_future_date_falls_through_to_the_absolute_form() {
        assertEquals("Today at 2:35 PM", text(now + 5 * 60_000))
    }

    @Test
    fun refresh_fires_exactly_when_the_wording_goes_stale() {
        // 5s in: "Just now" flips at the 15s mark, 10s away.
        assertEquals(10_000, LastSyncedDescription.refreshIntervalMillis(now - 5_000, now))
        // 20s in: "Less than a minute ago" flips at the minute, 40s away.
        assertEquals(40_000, LastSyncedDescription.refreshIntervalMillis(now - 20_000, now))
        // 90s in: "1 minute ago" flips on the next minute boundary, 30s away.
        assertEquals(30_000, LastSyncedDescription.refreshIntervalMillis(now - 90_000, now))
        // Past the relative cutoff, wording changes at most once a minute.
        assertEquals(60_000, LastSyncedDescription.refreshIntervalMillis(now - 60 * 60_000, now))
    }
}
