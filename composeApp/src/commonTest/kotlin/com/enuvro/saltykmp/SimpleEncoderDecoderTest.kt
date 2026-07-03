package com.enuvro.saltykmp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimpleEncoderDecoderTest {

    @Test
    fun roundTripsArbitraryPassword() {
        val pw = "s3cr3t-päss word!~"
        val encoded = SimpleEncoderDecoder.encode(pw)
        assertTrue(encoded.startsWith("enc1:"))
        assertNotEquals(pw, encoded)
        assertEquals(pw, SimpleEncoderDecoder.decode(encoded))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", SimpleEncoderDecoder.encode(""))
        assertEquals("", SimpleEncoderDecoder.decode(""))
    }

    @Test
    fun legacyPlaintextPassesThrough() {
        assertEquals("plain-old-password", SimpleEncoderDecoder.decode("plain-old-password"))
    }

    @Test
    fun nonceMakesRepeatedEncodingsDiffer() {
        assertNotEquals(SimpleEncoderDecoder.encode("same"), SimpleEncoderDecoder.encode("same"))
        // …but both still decode back to the original.
        assertEquals("same", SimpleEncoderDecoder.decode(SimpleEncoderDecoder.encode("same")))
    }
}
