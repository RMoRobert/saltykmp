package com.enuvro.saltykmp.di

/**
 * iOS keeps the existing obfuscated store for now. The follow-up is the Keychain via platform.Security
 * (SecItemAdd / SecItemCopyMatching), which needs no extra dependency and matches the Swift app.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore = ObfuscatedSecretStore(fallback)
