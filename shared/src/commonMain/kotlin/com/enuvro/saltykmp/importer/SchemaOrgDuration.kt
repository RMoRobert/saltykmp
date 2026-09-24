package com.enuvro.saltykmp.importer

import kotlin.math.floor

/**
 * schema.org `Duration` values — ISO 8601 durations — as the preparation-time text Salty shows.
 * Rule: salty-contract SPEC.md WEB-028. Mirrors SaltyCore's `SchemaOrgDuration`.
 */
internal object SchemaOrgDuration {

    private const val NUMBER = """(\d+(?:[.,]\d+)?)"""
    private val pattern = Regex(
        "^P(?:${NUMBER}Y)?(?:${NUMBER}M)?(?:${NUMBER}W)?(?:${NUMBER}D)?(?:T(?:${NUMBER}H)?(?:${NUMBER}M)?(?:${NUMBER}S)?)?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * [text] shown as "1 hr 30 min": its total, rounded to the nearest minute (half up), in days, hours and
     * minutes with zero parts left out. Under a minute is "N sec"; nothing at all is "0 min".
     *
     * Anything that isn't an ISO 8601 duration comes back as it was, as does one with a non-zero year or
     * month, whose length depends on the calendar: an odd label beats a time with nothing in it.
     */
    fun display(text: String): String {
        val seconds = totalSeconds(text.trim()) ?: return text
        if (seconds == 0.0) return "0 min"
        if (seconds < 60) return "${halfUp(seconds)} sec"

        val minutes = halfUp(seconds / 60)
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val remainder = minutes % 60
        return buildList {
            if (days > 0) add(if (days == 1L) "1 day" else "$days days")
            if (hours > 0) add("$hours hr")
            if (remainder > 0) add("$remainder min")
        }.joinToString(" ")
    }

    /** Nil when the text isn't a duration, names no component, or has years or months in it. */
    private fun totalSeconds(text: String): Double? {
        val match = pattern.matchEntire(text) ?: return null
        val values = (1..7).map { index ->
            match.groupValues[index].takeIf { it.isNotEmpty() }?.replace(',', '.')?.toDoubleOrNull()
        }
        // "P" alone, or a "T" with no time after it, is not a duration.
        if (values.all { it == null }) return null
        if (text.uppercase().contains('T') && values.subList(4, 7).all { it == null }) return null
        // Years and months have no fixed length.
        if ((values[0] ?: 0.0) != 0.0 || (values[1] ?: 0.0) != 0.0) return null

        return (values[2] ?: 0.0) * 604_800 + (values[3] ?: 0.0) * 86_400 +
            (values[4] ?: 0.0) * 3_600 + (values[5] ?: 0.0) * 60 + (values[6] ?: 0.0)
    }

    /** Half up — `kotlin.math.round` rounds half to even, and Swift's `.toNearestOrAwayFromZero` doesn't. */
    private fun halfUp(value: Double): Long = floor(value + 0.5).toLong()
}
