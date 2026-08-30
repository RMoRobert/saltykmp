package com.enuvro.saltykmp.util

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The wire timestamp writer: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` — UTC, milliseconds ALWAYS present
 * (DATE-002/DATE-004 in salty-contract/SPEC.md).
 *
 * Formatted by hand rather than via `Instant.toString()`, which omits the fraction whenever it is zero
 * and so wrote a 19-character stamp the other clients never produce. That was not the rare case it
 * looked like: `PreparedDates.pickerMillisToWire` anchors at local NOON, whose fraction is zero every
 * single time, so every "last prepared" date picked in this app violated the wire shape. Reading stays
 * permissive (DATE-005 covers second precision), so nothing broke — but writing is strict, and this is
 * the strict writer. Pinned by corpus DATE-FMT-003 and DATE-FMT-007.
 */
@OptIn(ExperimentalTime::class)
fun wireIso(instant: Instant): String {
    val truncated = Instant.fromEpochMilliseconds(instant.toEpochMilliseconds())
    val utc = truncated.toLocalDateTime(TimeZone.UTC)
    fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')
    return pad(utc.year, 4) + "-" + pad(utc.month.number, 2) + "-" + pad(utc.day, 2) +
        "T" + pad(utc.hour, 2) + ":" + pad(utc.minute, 2) + ":" + pad(utc.second, 2) +
        "." + pad(utc.nanosecond / 1_000_000, 3) + "Z"
}

/** [wireIso] of the current instant — what every client-written edit is stamped with. */
@OptIn(ExperimentalTime::class)
fun nowWireIso(): String = wireIso(Clock.System.now())
