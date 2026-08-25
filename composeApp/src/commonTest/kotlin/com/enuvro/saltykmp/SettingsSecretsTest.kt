package com.enuvro.saltykmp

import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.ObfuscatedSecretStore
import com.enuvro.saltykmp.di.SECRET_KEY_PASSWORD
import com.enuvro.saltykmp.di.SecretStore
import com.enuvro.saltykmp.di.verifiedOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private class FakeStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {
    val values = initial.toMutableMap()
    override fun getString(key: String, default: String): String = values[key] ?: default
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

/** Stands in for a real OS vault: separate storage, and [isPlatformBacked]. */
private class FakeVault : SecretStore {
    val entries = mutableMapOf<String, String>()
    val accounts = mutableMapOf<String, String?>()
    override fun get(key: String): String? = entries[key]
    override fun put(key: String, value: String, account: String?) {
        entries[key] = value; accounts[key] = account
    }
    override fun clear(key: String) {
        entries.remove(key)
    }
    override val isPlatformBacked = true
    override val backendName = "Fake Vault"
}

class SettingsSecretsTest {

    @Test
    fun password_round_trips_through_the_vault() {
        val vault = FakeVault()
        val settings = SettingsState(FakeStore(), vault)
        settings.username = "rob"
        settings.password = "hunter2"

        assertEquals("hunter2", settings.password)
        assertEquals("hunter2", vault.entries[SECRET_KEY_PASSWORD])
        // The username rides along so the Credential Manager entry is recognizable.
        assertEquals("rob", vault.accounts[SECRET_KEY_PASSWORD])
    }

    @Test
    fun clearing_the_password_removes_the_vault_entry() {
        val vault = FakeVault()
        val settings = SettingsState(FakeStore(), vault)
        settings.password = "hunter2"
        settings.password = ""

        assertEquals("", settings.password)
        assertTrue(vault.entries.isEmpty(), "the OS vault should not keep an entry for an empty password")
    }

    @Test
    fun fallback_store_round_trips_and_stays_obfuscated_at_rest() {
        val store = FakeStore()
        val settings = SettingsState(store, ObfuscatedSecretStore(store))

        settings.password = "new-password"
        assertEquals("new-password", settings.password)
        // Obfuscated at rest, never plaintext.
        val atRest = store.values.getValue(SECRET_KEY_PASSWORD)
        assertTrue(atRest.startsWith("enc1:"))
        assertFalse(atRest.contains("new-password"))
    }

    /**
     * A pref that some other backend wrote (or that got corrupted) must read as "no password", not as the
     * raw characters. Without this the fallback would hand the sync server a base64 blob to log in with.
     */
    @Test
    fun fallback_store_ignores_a_value_it_did_not_write() {
        val store = FakeStore(mapOf(SECRET_KEY_PASSWORD to "aesgcm1:bm90LWEtcGFzc3dvcmQ="))

        assertEquals("", SettingsState(store, ObfuscatedSecretStore(store)).password)
    }

    @Test
    fun unset_password_reads_as_empty() {
        assertEquals("", SettingsState(FakeStore(), FakeVault()).password)
        assertNull(FakeVault().get(SECRET_KEY_PASSWORD))
    }
}

/**
 * [verifiedOrNull] is the guard added after the Windows binding shipped a vault that reported itself
 * available and then silently discarded every write. These pin the three behaviours that matter.
 */
class SecretStoreVerificationTest {

    /** The exact failure that shipped: writes vanish, reads return null, nothing throws. */
    private class SilentlyBrokenVault : SecretStore {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String, account: String?) = Unit
        override fun clear(key: String) = Unit
        override val isPlatformBacked = true
        override val backendName = "Broken Vault"
    }

    private class ThrowingVault : SecretStore {
        override fun get(key: String): String? = error("vault unreachable")
        override fun put(key: String, value: String, account: String?) = error("vault unreachable")
        override fun clear(key: String) = Unit
        override val isPlatformBacked = true
        override val backendName = "Throwing Vault"
    }

    @Test
    fun a_working_vault_verifies_and_leaves_no_probe_behind() {
        val vault = FakeVault()
        assertSame(vault, vault.verifiedOrNull())
        assertTrue(vault.entries.isEmpty(), "the probe entry must be removed after the check")
    }

    @Test
    fun a_vault_that_silently_drops_writes_is_rejected() {
        assertNull(SilentlyBrokenVault().verifiedOrNull())
    }

    @Test
    fun a_vault_that_throws_is_rejected_rather_than_propagating() {
        assertNull(ThrowingVault().verifiedOrNull())
    }
}
