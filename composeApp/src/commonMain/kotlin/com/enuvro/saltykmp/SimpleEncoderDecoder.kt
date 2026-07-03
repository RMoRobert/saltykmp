package com.enuvro.saltykmp

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * Reversible obfuscation for the stored password, in lieu of platform secure storage.
 *
 * TODO: Move this to (platform-specific?) secure storage in future, like Keychain, Keystore, etc.
 * For now, using simple obfuscation to avoid plain text, at least.
 *
 * Format: "enc1:" + base64( 8-byte random nonce || (plaintext XOR keystream) ), where the keystream
 * is a deterministic kotlin.random sequence seeded from the embedded key XOR the nonce. The nonce makes
 * repeated encodings of the same password differ. Pure commonMain — works on every target.
 */
@OptIn(ExperimentalEncodingApi::class)
object SimpleEncoderDecoder {
    private const val MARKER = "enc1:"

    // Embedded obfuscation key, assembled from parts to avoid one obvious literal.
    private val key: Long = fnv1a64("salty-kmp" + "::v1::" + "pw-obfuscation")

    fun encode(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val data = plaintext.encodeToByteArray()
        val nonce = Random.nextLong()
        val keystream = Random(key xor nonce).nextBytes(data.size)
        val cipher = ByteArray(data.size) { (data[it].toInt() xor keystream[it].toInt()).toByte() }
        return MARKER + Base64.encode(longToBytes(nonce) + cipher)
    }

    fun decode(stored: String): String {
        if (!stored.startsWith(MARKER)) return stored // legacy plaintext / empty → passthrough
        return runCatching {
            val payload = Base64.decode(stored.substring(MARKER.length))
            val nonce = bytesToLong(payload)
            val cipher = payload.copyOfRange(8, payload.size)
            val keystream = Random(key xor nonce).nextBytes(cipher.size)
            ByteArray(cipher.size) { (cipher[it].toInt() xor keystream[it].toInt()).toByte() }.decodeToString()
        }.getOrElse { "" }
    }

    private fun fnv1a64(s: String): Long {
        var h = -3750763034362895579L // FNV offset basis (0xcbf29ce484222325)
        for (b in s.encodeToByteArray()) {
            h = h xor (b.toLong() and 0xff)
            h *= 1099511628211L // FNV prime (0x100000001b3)
        }
        return h
    }

    private fun longToBytes(v: Long): ByteArray = ByteArray(8) { ((v ushr (8 * (7 - it))) and 0xff).toByte() }

    private fun bytesToLong(b: ByteArray): Long {
        var v = 0L
        for (i in 0 until 8) v = (v shl 8) or (b[i].toLong() and 0xff)
        return v
    }
}
