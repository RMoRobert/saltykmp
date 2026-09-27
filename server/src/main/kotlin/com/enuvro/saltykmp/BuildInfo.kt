package com.enuvro.saltykmp

import java.util.Properties

/**
 * Build metadata baked in at build time via `version.properties` (written by the server build's
 * `generateVersionProperties` task from the Gradle project version + build time). Falls back to safe
 * placeholders if the resource is missing or still holds an unexpanded template (an older build's output).
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
