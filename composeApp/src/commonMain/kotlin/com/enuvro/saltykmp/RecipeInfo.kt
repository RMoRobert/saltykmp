package com.enuvro.saltykmp

import com.enuvro.saltykmp.util.PreparedDates
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * What Get Info shows about a recipe — the Swift app's `RecipeInfoInspectorView`, which is the only place
 * either app shows a recipe's created and modified dates.
 *
 * The dates are the row's own, formatted for the viewer's time zone. Stored timestamps are UTC, so a
 * recipe saved at 11pm must not read as the next day.
 */
internal data class RecipeInfo(val created: String, val lastModified: String, val lastPrepared: String) {
    /** Label/value pairs in the order the dialog lists them. */
    val rows: List<Pair<String, String>>
        get() = listOf("Created" to created, "Last Modified" to lastModified, "Last Prepared" to lastPrepared)
}

/**
 * Builds the three lines from a recipe's wire timestamps. [lastPrepared] carries a calendar day rather
 * than a moment (it is stored at local noon — see [PreparedDates]), so it is shown without a time; the
 * other two are moments and keep theirs. A date that is absent or unreadable says so rather than showing
 * nothing: the row it describes is always there.
 */
internal fun recipeInfoOf(
    created: String?,
    lastModified: String?,
    lastPrepared: String?,
    zone: TimeZone = TimeZone.currentSystemDefault(),
): RecipeInfo = RecipeInfo(
    created = formatMoment(created, zone) ?: UNKNOWN_DATE,
    lastModified = formatMoment(lastModified, zone) ?: UNKNOWN_DATE,
    lastPrepared = PreparedDates.formatForDisplay(lastPrepared, zone) ?: NOT_SET,
)

/** A stored timestamp as "Sep 11, 2026 at 1:41 PM" in [zone]; null when absent or unparseable. */
@OptIn(ExperimentalTime::class)
internal fun formatMoment(wire: String?, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
    val instant = wire?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
    val local = instant.toLocalDateTime(zone)
    return "${DATE_FORMAT.format(local.date)} at ${TIME_FORMAT.format(local.time)}"
}

// "Not set" rather than "Never": the same words the Swift inspector and the web app use for it.
private const val UNKNOWN_DATE = "Unknown"
private const val NOT_SET = "Not set"

// Same shapes as the "Last synced:" line's, so every date in the app reads alike.
private val DATE_FORMAT = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(padding = Padding.NONE); chars(", "); year()
}
private val TIME_FORMAT = LocalTime.Format {
    amPmHour(padding = Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
}
