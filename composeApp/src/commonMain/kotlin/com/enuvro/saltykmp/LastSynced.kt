package com.enuvro.saltykmp

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wording for the "Last synced:" line in Sync settings — a port of the Swift app's
 * LastSyncedDescription, phrase for phrase.
 *
 * Relative phrasing is only useful for a few minutes ("3 minutes ago" reads fine, "17 hours ago" makes
 * you do arithmetic), so it switches to a clock time once a sync stops being recent. Pure and injectable
 * (`nowMillis`) so the boundaries can be unit-tested without waiting for the clock.
 */
@OptIn(ExperimentalTime::class)
internal object LastSyncedDescription {
    /** How recent a sync has to be to still be described relative to now, rather than by clock time. */
    private const val RELATIVE_CUTOFF_MILLIS = 6 * 60 * 1000L

    private val timeFormat = LocalTime.Format {
        amPmHour(padding = Padding.NONE); char(':'); minute(); char(' '); amPmMarker("AM", "PM")
    }
    private val monthDayFormat = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(padding = Padding.NONE)
    }
    private val monthDayYearFormat = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED); char(' '); day(padding = Padding.NONE); chars(", "); year()
    }

    fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

    /**
     * Phrase describing when [epochMillis] was, e.g. "Just now", "3 minutes ago", "Today at 4:05 PM",
     * "Yesterday at 11:32 PM", "Jul 12 at 8:15 AM", "Dec 30, 2025 at 8:15 AM".
     */
    fun text(
        epochMillis: Long,
        nowMillis: Long = nowMillis(),
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): String {
        val elapsed = nowMillis - epochMillis

        // A negative interval means the clock moved backwards (time zone change, manual clock edit).
        // Fall through to the absolute form rather than claiming a sync happened in the future.
        if (elapsed in 0 until RELATIVE_CUTOFF_MILLIS) {
            val seconds = elapsed / 1000
            if (seconds < 15) return "Just now"
            if (seconds < 60) return "Less than a minute ago"
            val minutes = seconds / 60
            return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        }

        // Day comparisons are made against the injected `nowMillis`, not the system clock, so the
        // boundaries stay testable.
        val then = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
        val now = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
        val time = timeFormat.format(then.time)
        return when {
            then.date == now.date -> "Today at $time"
            then.date == now.date.minus(1, DateTimeUnit.DAY) -> "Yesterday at $time"
            then.year == now.year -> "${monthDayFormat.format(then.date)} at $time"
            else -> "${monthDayYearFormat.format(then.date)} at $time"
        }
    }

    /**
     * How long until [text] would produce different wording, so the UI can refresh exactly when the
     * phrasing goes stale instead of polling. Capped at a minute once the answer stops changing quickly.
     */
    fun refreshIntervalMillis(epochMillis: Long, nowMillis: Long = nowMillis()): Long {
        val elapsed = nowMillis - epochMillis
        if (elapsed < 0 || elapsed >= RELATIVE_CUTOFF_MILLIS) return 60_000
        if (elapsed < 15_000) return 15_000 - elapsed
        if (elapsed < 60_000) return 60_000 - elapsed
        return 60_000 - elapsed % 60_000
    }
}
