package com.enuvro.saltykmp.di

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore
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

/**
 * The Android Keystore secret store, exercised on a device or emulator (the AndroidKeyStore provider has no
 * JVM equivalent). Everything goes through [createSecretStore], the factory the app itself calls, so these
 * cover the real selection path -- including the [verifiedOrNull] round trip that decides whether the
 * keystore is trusted at all -- rather than a store constructed by hand.
 *
 * Format facts pinned here, from SecretStore.android.kt: the ciphertext lives under "<key>.aesgcm" (never the
 * plain key, which belongs to the obfuscated fallback), prefixed "aesgcm1:", as base64 of a 12-byte GCM IV
 * followed by ciphertext and a 16-byte tag.
 */
@RunWith(AndroidJUnit4::class)
class AndroidKeystoreSecretStoreTest {

    private val password = SECRET_KEY_PASSWORD
    private val entry = "$password.aesgcm"

    @Test
    fun the_keystore_is_what_a_real_device_gets() {
        val prefs = MemoryPrefs()
        val store = createSecretStore(prefs)

        assertTrue(store.isPlatformBacked, "fell back to ${store.backendName}")
        assertEquals("Android Keystore", store.backendName)
        // The startup probe cleans up after itself: nothing it wrote is left holding a value.
        assertTrue(prefs.values.values.all { it.isEmpty() }, "probe left behind: ${prefs.values}")
    }

    @Test
    fun password_round_trips() {
        val store = createSecretStore(MemoryPrefs())
        store.put(password, "hunter2")
        assertEquals("hunter2", store.get(password))
    }

    @Test
    fun what_lands_on_disk_is_not_the_password() {
        val prefs = MemoryPrefs()
        createSecretStore(prefs).put(password, "hunter2")

        val stored = prefs.values.getValue(entry)
        assertTrue(stored.startsWith("aesgcm1:"), stored)
        assertFalse(stored.contains("hunter2"))
        // The plain key is the obfuscated fallback's; the keystore store never writes it.
        assertNull(prefs.values[password])
    }

    @Test
    fun each_write_uses_a_fresh_iv() {
        val prefs = MemoryPrefs()
        val store = createSecretStore(prefs)
        store.put(password, "same")
        val first = prefs.values.getValue(entry)
        store.put(password, "same")
        assertNotEquals(first, prefs.values.getValue(entry))
    }

    @Test
    fun a_second_store_reads_what_the_first_wrote() {
        // The key lives in the Keystore, not the instance -- this is the next-launch path.
        val prefs = MemoryPrefs()
        createSecretStore(prefs).put(password, "hunter2")
        assertEquals("hunter2", createSecretStore(prefs).get(password))
    }

    @Test
    fun clearing_removes_the_password() {
        val store = createSecretStore(MemoryPrefs())
        store.put(password, "hunter2")
        store.clear(password)
        assertNull(store.get(password))
    }

    @Test
    fun storing_an_empty_value_clears_it() {
        val prefs = MemoryPrefs()
        val store = createSecretStore(prefs)
        store.put(password, "hunter2")
        store.put(password, "")
        assertNull(store.get(password))
        assertEquals("", prefs.values[entry])
    }

    @Test
    fun a_value_this_build_did_not_write_reads_as_absent() {
        val prefs = MemoryPrefs().apply { values[entry] = "enc1:1OWFL9IgV4UYjTtBpA==" }
        val store = createSecretStore(prefs)

        assertNull(store.get(password))
        // Left in place; the next save overwrites it.
        assertEquals("enc1:1OWFL9IgV4UYjTtBpA==", prefs.values[entry])
    }

    @Test
    fun the_fallback_stores_value_is_neither_read_nor_overwritten() {
        // Both stores sit on the same prefs, and a keystore that stops working demotes to the fallback.
        // Separate keys are what let each find its own value again.
        val prefs = MemoryPrefs()
        ObfuscatedSecretStore(prefs).put(password, "from-the-fallback")
        val store = createSecretStore(prefs)

        assertNull(store.get(password))
        store.put(password, "from-the-keystore")
        assertEquals("from-the-fallback", ObfuscatedSecretStore(prefs).get(password))
        assertEquals("from-the-keystore", store.get(password))
    }

    @Test
    fun tampered_ciphertext_is_rejected_rather_than_returned() {
        val prefs = MemoryPrefs()
        createSecretStore(prefs).put(password, "hunter2")
        // Flip one bit inside the sealed bytes (not the base64 text, whose last character can be padding):
        // GCM's tag check has to catch it.
        val sealed = Base64.decode(prefs.values.getValue(entry).removePrefix("aesgcm1:"), Base64.NO_WRAP)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()
        prefs.values[entry] = "aesgcm1:" + Base64.encodeToString(sealed, Base64.NO_WRAP)

        assertNull(createSecretStore(prefs).get(password))
    }

    @Test
    fun truncated_ciphertext_is_rejected() {
        // Three bytes: shorter than the 12-byte IV alone.
        val prefs = MemoryPrefs().apply { values[entry] = "aesgcm1:AAAA" }
        assertNull(createSecretStore(prefs).get(password))
    }

    @Test
    fun a_lost_keystore_key_reads_as_absent_and_recovers_on_the_next_save() {
        // What an app-data restore onto a new device looks like: the ciphertext survives, the key does not.
        // The instrumented test runs as its own app, so this deletes the test's key, not Salty's.
        val prefs = MemoryPrefs()
        createSecretStore(prefs).put(password, "hunter2")
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("salty_secret_store")

        val store = createSecretStore(prefs)
        assertNull(store.get(password))
        store.put(password, "new-password")
        assertEquals("new-password", createSecretStore(prefs).get(password))
    }
}
