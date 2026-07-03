package com.enuvro.saltykmp

import java.util.Properties

/**
 * Build metadata baked in at compile time via `version.properties` (populated by the `processResources`
 * task from the Gradle project version + build time). Falls back to safe placeholders if the resource
 * is missing or hasn't been filtered (e.g. running unprocessed resources from an IDE).
 */
object BuildInfo {
    val version: String
    val buildTime: String

    init {
        val props = Properties()
        BuildInfo::class.java.getResourceAsStream("/version.properties")?.use { props.load(it) }
        version = props.getProperty("version").orElseDev()
        buildTime = props.getProperty("buildTime").orUnknown()
    }

    // Guard against an unfiltered template (raw "${...}") or a missing value.
    private fun String?.orElseDev(): String =
        this?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: "dev"

    private fun String?.orUnknown(): String =
        this?.takeIf { it.isNotBlank() && !it.contains("\${") } ?: "unknown"
}
