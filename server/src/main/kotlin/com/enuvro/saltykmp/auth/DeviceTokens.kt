package com.enuvro.saltykmp.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Ktor provider name for `Authorization: Bearer salty_…` device tokens. */
const val DEVICE_TOKEN_AUTH = "device-token-auth"

/**
 * Marks a token as ours on sight.
 *
 * Two jobs. It lets the bearer provider decline anything that is not ours without attempting to
 * verify it, so a route can accept this credential alongside another and try them in order. And it
 * makes a leaked token recognisable — greppable in a log, and matchable by secret scanners — which a
 * bare base64 blob is not.
 */
const val DEVICE_TOKEN_PREFIX = "salty_"

/** Who a device token identifies. Deliberately not a [UserSession]: it can only sync. */
data class DeviceTokenPrincipal(val userId: String, val deviceId: String)

/**
 * Mints and verifies the per-device sync credential.
 *
 * The token is 32 bytes from [SecureRandom], base64url without padding, behind [DEVICE_TOKEN_PREFIX].
 * It is shown to the client exactly once; only its hash is stored.
 *
 * **Hashed with HMAC-SHA256, not bcrypt, and that is on purpose.** Bcrypt's cost factor buys
 * resistance to *guessing*, which matters for passwords because they are low-entropy and chosen by
 * humans. A 256-bit random token has nothing to guess: an attacker who cannot read the database
 * gains nothing from a slow hash, and one who can read it still faces 2^256. All bcrypt would do
 * here is burn CPU on every single synced request. The keyed HMAC additionally means a stolen
 * database alone is not enough to forge a lookup — the secret is needed too.
 *
 * The key comes from `SALTY_TOKEN_SECRET` (formerly `SALTY_JWT_SECRET`, still read as a fallback so
 * the rename does not invalidate anything). Rotating it invalidates every device token at once —
 * a reasonable emergency lever, and the only one that signs every client out without touching
 * anyone's password.
 */
class DeviceTokenService(secret: String) {

    private val key = SecretKeySpec(secret.toByteArray(), MAC_ALGORITHM)
    private val random = SecureRandom()

    /** A fresh token. The caller stores [hash] of it and returns this string to the client once. */
    fun generate(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return DEVICE_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** What goes in the database. Hex, 64 characters, which is what the column is sized for. */
    fun hash(token: String): String {
        val mac = Mac.getInstance(MAC_ALGORITHM).apply { init(key) }
        return mac.doFinal(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Cheap shape check so an obvious non-token never reaches the database. */
    fun looksLikeToken(candidate: String): Boolean =
        candidate.startsWith(DEVICE_TOKEN_PREFIX) && candidate.length > DEVICE_TOKEN_PREFIX.length

    companion object {
        private const val MAC_ALGORITHM = "HmacSHA256"
        private const val TOKEN_BYTES = 32

        /**
         * Constant-time comparison, for anywhere two hashes are compared in application code.
         *
         * Lookups go through an indexed WHERE, which is not constant-time — but that leaks timing
         * about a *hash*, and an attacker who can compute the hash of a guess already holds the
         * token. This exists for the comparisons where that reasoning does not apply.
         */
        fun constantTimeEquals(a: String, b: String): Boolean =
            MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
