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
     * True when an OS-provided vault is doing the work, rather than the obfuscated fallback. Only ever set
     * on a store that has passed [verifiedOrNull], so it means "really storing secrets", not "was
     * available at startup".
     */
    val isPlatformBacked: Boolean

    /** Short human-readable backend name for Settings, e.g. "Windows Credential Manager". */
    val backendName: String
}

/** Key under which the sync-server password is stored. */
const val SECRET_KEY_PASSWORD = "password"

/**
 * Key under which the per-device sync token is stored.
 *
 * It lives in the same vault as the password but replaces it: once enrolled, this is the only
 * credential the app keeps, and unlike a password it can do nothing but sync — so a device holding
 * it cannot change the account's password or revoke other devices.
 */
const val SECRET_KEY_SYNC_TOKEN = "syncToken"

/**
 * The fallback for platforms with no reachable OS vault: XOR obfuscation in the ordinary settings store.
 * Not real protection (the key ships in the app) — it only keeps the password from sitting in the prefs
 * file as plaintext.
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

/** Key used only by [verifiedOrNull]. Never holds a real secret, and is removed as soon as it is read. */
private const val PROBE_KEY = "self-test"
private const val PROBE_VALUE = "salty-secret-store-probe"

/**
 * Return this store only if it demonstrably works — write a probe secret, read it back, remove it.
 *
 * Reachable is not the same as working, and the difference is not theoretical: the Windows binding once
 * loaded cleanly, reported itself platform-backed, and then silently discarded every write, because a JNA
 * field-visibility fault was swallowed by `runCatching` (see SecretStore.jvm.kt). Settings claimed
 * "Windows Credential Manager" while sync sent an empty password. The CLI-backed vaults fail the same
 * shape when the tool is installed but no keyring daemon is running.
 *
 * A round trip is the only claim worth making. It costs one write/read/delete at startup, and it is what
 * makes [SecretStore.isPlatformBacked], and the backend name Settings shows the user, trustworthy.
 */
internal fun SecretStore.verifiedOrNull(): SecretStore? = runCatching {
    try {
        put(PROBE_KEY, PROBE_VALUE)
        takeIf { get(PROBE_KEY) == PROBE_VALUE }
    } finally {
        clear(PROBE_KEY)
    }
}.getOrNull()

/**
 * Build the best secret store this platform offers, falling back to [ObfuscatedSecretStore] over
 * [fallback] when there is no vault (or it cannot be reached).
 */
expect fun createSecretStore(fallback: KeyValueStore): SecretStore
