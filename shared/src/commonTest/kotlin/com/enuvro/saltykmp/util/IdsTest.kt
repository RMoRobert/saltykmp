package com.enuvro.saltykmp.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class IdsTest {

    @Test
    fun assemblesKnownBitsIntoExpectedString() {
        assertEquals(
            "01234567-89AB-7DEF-8123-456789ABCDEF",
            uuidV7(millis = 0x0123456789ABL, randA = 0xDEF, randB = 0x0123456789ABCDEFL),
        )
    }

    @Test
    fun forcesVersionAndVariantBitsRegardlessOfRandomInput() {
        // All-ones random input must not bleed into the version nibble or the variant bits.
        val id = uuidV7(millis = 0L, randA = -1, randB = -1L)
        assertEquals("00000000-0000-7FFF-BFFF-FFFFFFFFFFFF", id)
    }

    @Test
    fun sortsLexicographicallyByTimestamp() {
        // The uppercase-hex encoding preserves timestamp order even against maximal random bits.
        val earlier = uuidV7(millis = 1_000L, randA = 0xFFF, randB = -1L)
        val later = uuidV7(millis = 1_001L, randA = 0, randB = 0L)
        assertTrue(earlier < later)
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun newIdIsUppercaseV7CarryingCurrentTime() {
        val before = Clock.System.now().toEpochMilliseconds()
        val id = newId()
        val after = Clock.System.now().toEpochMilliseconds()

        assertTrue(
            Regex("^[0-9A-F]{8}-[0-9A-F]{4}-7[0-9A-F]{3}-[89AB][0-9A-F]{3}-[0-9A-F]{12}$").matches(id),
            "not an uppercase UUIDv7: $id",
        )
        val embeddedMillis = id.substring(0, 8).plus(id.substring(9, 13)).toLong(16)
        assertTrue(embeddedMillis in before..after, "timestamp $embeddedMillis outside [$before, $after]")
    }

    @Test
    fun newIdDoesNotCollideOverManyMints() {
        val ids = List(1_000) { newId() }
        assertEquals(ids.size, ids.toSet().size)
    }
}
