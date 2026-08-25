package com.enuvro.saltykmp.di

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android secret storage: AES-GCM under a hardware-backed Android Keystore key, falling back to
 * obfuscated prefs when the keystore is unusable.
 *
 * Deliberately NOT `androidx.security:security-crypto`: EncryptedSharedPreferences would do the same job,
 * but the library has spent years in alpha, and the four platform calls below need no dependency at all.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore =
    AndroidKeystoreSecretStore.createOrNull(fallback)?.verifiedOrNull() ?: ObfuscatedSecretStore(fallback)

/**
 * The secret is encrypted with a 256-bit AES key that never leaves the Android Keystore (on most devices,
 * never leaves secure hardware), and the IV+ciphertext is parked in the ordinary [KeyValueStore]. Unlike
 * the Windows/iOS vaults there is no OS-provided UI listing the entry — clearing app data is the
 * equivalent gesture, and it destroys both halves.
 *
 * No `setUserAuthenticationRequired`: the entry has to be readable while the screen is locked, or the
 * debounced background auto-sync would prompt for biometrics or simply fail.
 */
private class AndroidKeystoreSecretStore private constructor(
    private val store: KeyValueStore,
    private val keyStore: KeyStore,
) : SecretStore {

    override val isPlatformBacked: Boolean = true
    override val backendName: String = "Android Keystore"

    override fun get(key: String): String? = runCatching {
        val stored = store.getString(entry(key), "")
        if (!stored.startsWith(MARKER)) return null // absent, or a value this build didn't write
        val raw = Base64.decode(stored.substring(MARKER.length), Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) return null
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
        }
        // A key invalidated out from under us (app data restored to a new device, keystore reset) throws
        // here. Returning null degrades to "no secret stored", which re-prompts, rather than crashing.
        cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES).decodeToString().takeIf { it.isNotEmpty() }
    }.getOrNull()

    override fun put(key: String, value: String, account: String?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
            val sealed = cipher.iv + cipher.doFinal(value.encodeToByteArray())
            store.putString(entry(key), MARKER + Base64.encodeToString(sealed, Base64.NO_WRAP))
        }
    }

    override fun clear(key: String) {
        store.putString(entry(key), "")
    }

    /**
     * A DIFFERENT pref key from the plain [key] that [ObfuscatedSecretStore] writes. Both back onto the
     * same prefs, and either can be live on one install — a keystore that stops working demotes to the
     * fallback. Both stores check their own marker, so a shared key would be *safe* to cross-read (each
     * returns "no secret" for the other's encoding), but a write by one would silently destroy the other's
     * value. Separate keys mean a demotion, and a later recovery, each still find their own.
     */
    private fun entry(key: String) = "$key.aesgcm"

    /** The keystore entry, minted on first use. Kept unauthenticated so background sync can read it. */
    private fun secretKey(): SecretKey {
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
        }.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "salty_secret_store"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val MARKER = "aesgcm1:"
        private const val IV_BYTES = 12 // GCM standard nonce; Android's provider always emits this length
        private const val TAG_BITS = 128

        /** Null when the keystore provider is missing or refuses to load — the caller then falls back. */
        fun createOrNull(store: KeyValueStore): SecretStore? = runCatching {
            AndroidKeystoreSecretStore(store, KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) })
        }.getOrNull()
    }
}
