package com.enuvro.saltykmp

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Wire timestamp for client-written edits (ISO-8601; the server and Swift app both parse it). Stamped at
 * MILLISECOND precision: the wire/server contract is `...SSS'Z'` (ms), and `Instant.toString()` would
 * emit nanoseconds — which the server truncates to ms, making the reconciler treat local as perpetually
 * newer and re-upload every sync.
 */
@OptIn(ExperimentalTime::class)
fun nowTimestamp(): String =
    Clock.System.now().let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }.toString()

/** Uppercase UUIDv7, matching the Swift app's `UUIDV7().uuidString`; see the shared [com.enuvro.saltykmp.util.newId]. */
fun newId(): String = com.enuvro.saltykmp.util.newId()
