package com.enuvro.saltykmp.di

import com.enuvro.saltykmp.SimpleEncoderDecoder

/**
 * Storage for secrets (currently just the sync-server password), backed by the OS credential store
 * where one is reachable and by [ObfuscatedSecretStore] everywhere else.
 *
 * Separate from [KeyValueStore] because the backends are genuinely different: settings are a bag of
 * strings in one place, whereas a secret lives in a per-platform vault with its own naming, lifetime,
 * and failure modes. Every method is best-effort — a vault that is locked, absent, or refusing access
 * must degrade to "no secret stored", never crash the app.
 */
interface SecretStore {
    /** The stored secret, or null when absent (including when the backend failed to read it). */
    fun get(key: String): String?

    /**
     * Store [value] under [key]. [account] is a display-only hint (the sync username) shown by vaults
     * that have a user-name field, so the entry is recognizable in the OS UI; backends without one
     * ignore it.
     */
    fun put(key: String, value: String, account: String? = null)

    /** Remove the secret, so the OS vault stops listing an entry the app no longer uses. */
    fun clear(key: String)

    /**
     * True when an OS-provided vault is doing the work. Drives the one-time migration off the legacy
     * obfuscated pref (see SettingsState) and the backend name shown in Settings — on a fallback store
     * there is nothing to migrate and nothing worth claiming in the UI.
     */
    val isPlatformBacked: Boolean

    /** Short human-readable backend name for Settings, e.g. "Windows Credential Manager". */
    val backendName: String
}

/** Key under which the sync-server password is stored. Also the legacy [KeyValueStore] pref name. */
const val SECRET_KEY_PASSWORD = "password"

/**
 * The pre-existing behaviour, kept as the fallback: XOR obfuscation in the ordinary settings store.
 * Not real protection (the key ships in the app) — it only keeps the password from sitting in the
 * prefs file as plaintext. Deliberately uses the same keys and encoding as before, so on platforms
 * that land here nothing needs migrating.
 */
class ObfuscatedSecretStore(private val store: KeyValueStore) : SecretStore {
    override fun get(key: String): String? =
        store.getString(key, "").takeIf { it.isNotEmpty() }
            ?.let { SimpleEncoderDecoder.decode(it) }
            ?.takeIf { it.isNotEmpty() }

    override fun put(key: String, value: String, account: String?) {
        store.putString(key, SimpleEncoderDecoder.encode(value))
    }

    override fun clear(key: String) = store.putString(key, "")

    override val isPlatformBacked: Boolean = false
    override val backendName: String = "app settings (obfuscated)"
}

/**
 * Build the best secret store this platform offers, falling back to [ObfuscatedSecretStore] over
 * [fallback] when there is no vault (or it cannot be reached).
 */
expect fun createSecretStore(fallback: KeyValueStore): SecretStore
