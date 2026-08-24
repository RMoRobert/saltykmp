package com.enuvro.saltykmp

import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.ObfuscatedSecretStore
import com.enuvro.saltykmp.di.SECRET_KEY_PASSWORD
import com.enuvro.saltykmp.di.SecretStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
    fun legacy_obfuscated_password_migrates_into_the_vault() {
        val store = FakeStore(
            mapOf(
                "username" to "rob",
                SECRET_KEY_PASSWORD to SimpleEncoderDecoder.encode("old-password"),
            ),
        )
        val vault = FakeVault()
        val settings = SettingsState(store, vault)

        assertEquals("old-password", settings.password)
        assertEquals("old-password", vault.entries[SECRET_KEY_PASSWORD])
        assertEquals("rob", vault.accounts[SECRET_KEY_PASSWORD])
        assertEquals("", store.values[SECRET_KEY_PASSWORD], "the obfuscated copy must not survive migration")
    }

    @Test
    fun migration_does_not_clobber_a_password_already_in_the_vault() {
        val store = FakeStore(mapOf(SECRET_KEY_PASSWORD to SimpleEncoderDecoder.encode("stale")))
        val vault = FakeVault().apply { put(SECRET_KEY_PASSWORD, "current") }
        val settings = SettingsState(store, vault)

        assertEquals("current", settings.password)
        assertEquals("", store.values[SECRET_KEY_PASSWORD])
    }

    @Test
    fun fallback_store_keeps_working_and_skips_migration() {
        // The fallback *is* the legacy location, so a "migration" there would wipe the password.
        val store = FakeStore(mapOf(SECRET_KEY_PASSWORD to SimpleEncoderDecoder.encode("old-password")))
        val settings = SettingsState(store, ObfuscatedSecretStore(store))

        assertEquals("old-password", settings.password)
        settings.password = "new-password"
        assertEquals("new-password", settings.password)
        // Still obfuscated at rest, not plaintext.
        assertTrue(store.values.getValue(SECRET_KEY_PASSWORD).startsWith("enc1:"))
    }

    @Test
    fun unset_password_reads_as_empty() {
        assertEquals("", SettingsState(FakeStore(), FakeVault()).password)
        assertNull(FakeVault().get(SECRET_KEY_PASSWORD))
    }
}
