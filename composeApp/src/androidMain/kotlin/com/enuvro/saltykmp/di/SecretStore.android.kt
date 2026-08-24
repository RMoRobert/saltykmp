package com.enuvro.saltykmp.di

/**
 * Android keeps the existing obfuscated store for now. The follow-up is an AndroidKeyStore-backed
 * AES-GCM wrapper (rather than androidx.security-crypto, which is no longer maintained), storing the
 * ciphertext in the same SharedPreferences.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore = ObfuscatedSecretStore(fallback)
