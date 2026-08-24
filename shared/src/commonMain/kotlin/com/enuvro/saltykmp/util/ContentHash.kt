package com.enuvro.saltykmp.util

/**
 * Streaming 64-bit FNV-1a hash, used as a *change-detection* fingerprint (not a security hash) for the
 * linked-folder library sync. Pure Kotlin so it runs on every target without a crypto dependency; a
 * 64-bit digest is plenty to tell "this file's bytes changed" apart from "only its mtime moved".
 *
 * Feed one or more byte chunks with [update] (e.g. the `.sqlite` file followed by its `-wal`, so a
 * not-yet-checkpointed write still changes the fingerprint), then read [hex].
 */
class ContentHash {
    private var h: Long = FNV_OFFSET_BASIS

    fun update(bytes: ByteArray): ContentHash {
        var x = h
        for (b in bytes) {
            x = (x xor (b.toLong() and 0xff)) * FNV_PRIME
        }
        h = x
        return this
    }

    /** Fixed-width 16-char lowercase hex digest. */
    val hex: String get() = h.toULong().toString(16).padStart(16, '0')

    companion object {
        private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
        private const val FNV_PRIME = 0x100000001b3L

        fun of(vararg chunks: ByteArray): String = ContentHash().apply { chunks.forEach { update(it) } }.hex
    }
}
