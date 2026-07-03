package com.enuvro.saltykmp.api

import kotlinx.serialization.json.Json

/** JSON config for the sync client — tolerant of extra/missing fields, matches the server's. */
val apiJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
