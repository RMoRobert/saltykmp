package com.enuvro.saltykmp.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ContentHashTest {

    @Test fun matchesReferenceFnv1a64Vectors() {
        // Standard FNV-1a 64 test vectors.
        assertEquals("cbf29ce484222325", ContentHash.of(ByteArray(0)))
        assertEquals("af63dc4c8601ec8c", ContentHash.of("a".encodeToByteArray()))
        assertEquals("85944171f73967e8", ContentHash.of("foobar".encodeToByteArray()))
    }

    @Test fun chunkingDoesNotChangeTheDigest() {
        val whole = "saltyRecipeDB.sqlite".encodeToByteArray()
        val split = ContentHash.of("saltyRecipe".encodeToByteArray(), "DB.sqlite".encodeToByteArray())
        assertEquals(ContentHash.of(whole), split)
    }

    @Test fun appendingAWalChunkChangesTheDigest() {
        val db = "db-bytes".encodeToByteArray()
        assertNotEquals(ContentHash.of(db), ContentHash.of(db, "wal-bytes".encodeToByteArray()))
    }

    @Test fun highBytesHashAsUnsigned() {
        // 0xFF must not sign-extend; a negative Kotlin Byte hashes like the unsigned byte 255.
        assertNotEquals(ContentHash.of(byteArrayOf(-1)), ContentHash.of(byteArrayOf(1)))
        assertEquals(16, ContentHash.of(byteArrayOf(-1, -128, 0)).length)
    }
}
