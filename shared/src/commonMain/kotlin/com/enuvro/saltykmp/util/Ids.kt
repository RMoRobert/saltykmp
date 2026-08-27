package com.enuvro.saltykmp.util

import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Mints entity ids as UPPERCASE UUIDv7 strings, matching what the Swift app writes
 * (`UUIDV7().uuidString`). Ids are opaque TEXT to the database and server, so this is convention
 * rather than correctness — but one convention beats two: same casing everywhere, and the leading
 * 48-bit timestamp means ids minted by either app sort consistently by creation time.
 */
@OptIn(ExperimentalTime::class)
fun newId(): String = uuidV7(
    millis = Clock.System.now().toEpochMilliseconds(),
    randA = Random.nextBits(12),
    randB = Random.nextLong(),
)

/**
 * Pure RFC 9562 UUIDv7 assembly — [millis] in the top 48 bits, version nibble 7, 12 random bits,
 * variant bits `10`, 62 random bits. Split from [newId] so tests can pin the inputs.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun uuidV7(millis: Long, randA: Int, randB: Long): String {
    val msb = (millis shl 16) or 0x7000L or (randA.toLong() and 0xFFFL)
    val lsb = (randB and 0x3FFFFFFFFFFFFFFFL) or Long.MIN_VALUE // forces the top two bits to "10"
    return Uuid.fromLongs(msb, lsb).toString().uppercase()
}
