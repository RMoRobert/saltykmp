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
 * Canonical form for an id that arrived from outside — an import, a wire payload. Anything that is not
 * a canonical-form UUID passes through UNTOUCHED: these columns are plain TEXT and no client may start
 * rejecting ids its peers accept.
 *
 * Defensive, not load-bearing. No path in SaltyKMP currently feeds it a foreign id — there is no
 * `.saltyRecipe` importer here, and wire ids originate from a client that already produced them in this
 * shape. It exists so a future import path that DOES preserve ids cannot introduce a casing mismatch.
 * See ID-004 in `salty-contract/SPEC.md`.
 *
 * Only the hyphenated 8-4-4-4-12 form is recognised, matching Swift's `UUID(uuidString:)`. A bare
 * 32-digit hex string is NOT a UUID here, so that all three clients agree on what counts.
 */
fun normalizeId(id: String): String =
    if (UUID_FORM.matches(id)) id.uppercase() else id

private val UUID_FORM =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

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
