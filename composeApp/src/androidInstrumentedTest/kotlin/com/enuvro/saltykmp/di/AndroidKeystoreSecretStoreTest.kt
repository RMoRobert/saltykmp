package com.enuvro.saltykmp.di

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Prefs in memory, so these tests measure the crypto and nothing else. */
private class MemoryPrefs : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String, default: String): String = values[key] ?: default
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {

    @Test
    fun password_round_trips() {
        val store = AndroidKeystoreSecretStore(MemoryPrefs())
        store.put("password", "hunter2")
        assertNull(store.lastError, "unexpected error: ${store.lastError}")
        assertEquals("hunter2", store.get("password"))
    }

    @Test
    fun what_lands_on_disk_is_not_the_password() {
        val prefs = MemoryPrefs()
        AndroidKeystoreSecretStore(prefs).put("password", "hunter2")

        val stored = prefs.values.getValue("password")
        assertTrue(stored.startsWith("ks1:"), stored)
        assertFalse(stored.contains("hunter2"))
    }

    @Test
    fun each_write_uses_a_fresh_iv() {
        val a = MemoryPrefs().also { AndroidKeystoreSecretStore(it).put("password", "same") }
        val b = MemoryPrefs().also { AndroidKeystoreSecretStore(it).put("password", "same") }
        assertNotEquals(a.values["password"], b.values["password"])
    }

    @Test
    fun a_second_store_reads_what_the_first_wrote() {
        // The key lives in the Keystore, not the instance -- this is the next-launch path.
        val prefs = MemoryPrefs()
        AndroidKeystoreSecretStore(prefs).put("password", "hunter2")
        assertEquals("hunter2", AndroidKeystoreSecretStore(prefs).get("password"))
    }

    @Test
    fun clearing_removes_the_password() {
        val store = AndroidKeystoreSecretStore(MemoryPrefs())
        store.put("password", "hunter2")
        store.clear("password")
        assertNull(store.get("password"))
    }

    @Test
    fun a_value_written_by_an_older_build_reads_as_absent() {
        val prefs = MemoryPrefs().apply { values["password"] = "enc1:1OWFL9IgV4UYjTtBpA==" }
        val store = AndroidKeystoreSecretStore(prefs)

        assertNull(store.get("password"))
        assertNull(store.lastError, "an old-format value is expected, not an error")
        // Left in place; the next save overwrites it.
        assertEquals("enc1:1OWFL9IgV4UYjTtBpA==", prefs.values["password"])
    }

    @Test
    fun tampered_ciphertext_is_rejected_rather_than_returned() {
        val prefs = MemoryPrefs()
        AndroidKeystoreSecretStore(prefs).put("password", "hunter2")
        // Flip the last character of the base64 payload: GCM's tag check must catch it.
        val stored = prefs.values.getValue("password")
        val flipped = stored.dropLast(1) + if (stored.last() == 'A') 'B' else 'A'
        prefs.values["password"] = flipped

        val store = AndroidKeystoreSecretStore(prefs)
        assertNull(store.get("password"))
        assertTrue(store.lastError?.contains("decryption failed") == true, "${store.lastError}")
    }

    @Test
    fun truncated_ciphertext_is_rejected() {
        val prefs = MemoryPrefs().apply { values["password"] = "ks1:AAAA" }
        val store = AndroidKeystoreSecretStore(prefs)
        assertNull(store.get("password"))
        assertTrue(store.lastError?.contains("truncated") == true, "${store.lastError}")
    }

    @Test
    fun self_test_passes_on_a_real_keystore() {
        assertTrue(AndroidKeystoreSecretStore(MemoryPrefs()).selfTest())
    }
}
