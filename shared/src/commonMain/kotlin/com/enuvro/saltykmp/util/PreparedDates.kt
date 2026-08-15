package com.enuvro.saltykmp.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Helpers for the "last made on" date. They live in `shared` because that's where kotlinx-datetime is on
 * the classpath, and because the local-noon convention below has to match the Swift app's
 * `RecipeNavigationSplitViewModel.localNoon(on:)` exactly — the two write the same column in the same DB.
 *
 * The column is a UTC timestamp that every client renders in local time, so a user-picked calendar day is
 * stored at LOCAL NOON: local midnight would render as the PREVIOUS day for anyone west of UTC, while
 * noon stays on the right day across every real-world offset (UTC-12…UTC+14) and DST shift.
 *
 * The API deliberately speaks only in primitives — kotlinx-datetime is an `implementation` dependency of
 * `shared`, so its types aren't nameable from composeApp.
 */
object PreparedDates {

    /**
     * Converts a Material3 DatePicker selection into the wire timestamp to store. The picker reports UTC
     * midnight of the chosen day, so the calendar day is read back in UTC and re-anchored at local noon —
     * reading it in local time would slide the day for offsets past ±12h.
     */
    @OptIn(ExperimentalTime::class)
    fun pickerMillisToWire(utcMillis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
        val day = Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date
        return LocalDateTime(day.year, day.monthNumber, day.day, 12, 0, 0)
            .toInstant(zone)
            .let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }
            .toString()
    }

    /**
     * The inverse: UTC midnight of the LOCAL calendar day a stored timestamp falls on, ready to seed the
     * picker. Null when there's no date or it can't be parsed.
     */
    @OptIn(ExperimentalTime::class)
    fun wireToPickerMillis(wire: String?, zone: TimeZone = TimeZone.currentSystemDefault()): Long? {
        val day = localDateOf(wire, zone) ?: return null
        return LocalDateTime(day.first, day.second, day.third, 0, 0, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
    }

    /** The current year in the viewer's zone — for capping a "past dates only" picker. */
    @OptIn(ExperimentalTime::class)
    fun currentLocalYear(zone: TimeZone = TimeZone.currentSystemDefault()): Int =
        kotlin.time.Clock.System.now().toLocalDateTime(zone).year

    /** "Now" as epoch millis — for capping a "past dates only" picker without pulling kotlin.time into
     *  the UI module. */
    @OptIn(ExperimentalTime::class)
    fun nowEpochMillis(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /** Short display form ("Aug 14, 2026") in the viewer's local time, or null when never made. */
    fun formatForDisplay(wire: String?, zone: TimeZone = TimeZone.currentSystemDefault()): String? =
        localDateOf(wire, zone)?.let { (year, month, day) ->
            "${MONTH_ABBREVIATIONS[month - 1]} $day, $year"
        }

    /** (year, month, day) of [wire] in [zone], or null if absent/unparseable. */
    @OptIn(ExperimentalTime::class)
    private fun localDateOf(wire: String?, zone: TimeZone): Triple<Int, Int, Int>? =
        wire?.takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?.toLocalDateTime(zone)
            ?.date
            ?.let { Triple(it.year, it.monthNumber, it.day) }

    private val MONTH_ABBREVIATIONS = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
