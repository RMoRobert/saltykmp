package com.enuvro.saltykmp.util

import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** JSON config shared by Ktor content negotiation and the recipe JSON blob columns. */
val appJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The wire timestamp format the Swift app expects: `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` (UTC). All server
 * timestamps are stored as UTC LocalDateTime and formatted/parsed through here so the contract holds.
 */
object WireDate {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")

    fun format(value: LocalDateTime?): String? = value?.let { FORMAT.format(it) }

    /** Parse an ISO-8601 instant ("...Z") or bare local-date-time into a UTC LocalDateTime. */
    fun parse(value: String?): LocalDateTime? {
        if (value.isNullOrBlank()) return null
        return runCatching { LocalDateTime.ofInstant(Instant.parse(value), ZoneOffset.UTC) }
            .getOrElse { LocalDateTime.parse(value) } // throws if also invalid → caller maps to 400
    }

    fun nowUtc(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
}
