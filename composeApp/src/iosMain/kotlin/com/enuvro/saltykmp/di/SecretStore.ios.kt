package com.enuvro.saltykmp.di

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData

/**
 * iOS secret storage: the Keychain, falling back to obfuscated prefs only if it cannot be round-tripped.
 *
 * Needs no cinterop and no dependency — `platform.Security` ships with Kotlin/Native, commonized across
 * both iOS targets.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore =
    KeychainSecretStore().verifiedOrNull() ?: ObfuscatedSecretStore(fallback)

/**
 * One generic-password item per key, under the service "Salty" with the key as the account, so the entries
 * are recognizable to anything that inspects the app's keychain.
 *
 * Accessibility is [kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly], chosen the same way the Windows
 * store chose LOCAL_MACHINE over ENTERPRISE:
 *  - *AfterFirstUnlock* rather than *WhenUnlocked*, because the debounced background auto-sync has to read
 *    the password while the phone is locked; WhenUnlocked would make it fail whenever the screen is off.
 *  - *ThisDeviceOnly* so the sync password is never copied to the user's other devices via iCloud Keychain,
 *    and never lands in an encrypted device backup.
 */
@OptIn(ExperimentalForeignApi::class)
private class KeychainSecretStore : SecretStore {

    override val isPlatformBacked: Boolean = true
    override val backendName: String = "iOS Keychain"

    override fun get(key: String): String? = cfOwned {
        val query = own(
            cfDictionary(
                kSecClass to kSecClassGenericPassword,
                kSecAttrService to own(cfString(SERVICE)),
                kSecAttrAccount to own(cfString(key)),
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ),
        ) ?: return@cfOwned null
        memScoped {
            val out = alloc<CFTypeRefVar>()
            // Any non-success status — most often errSecItemNotFound — simply means "no secret stored".
            if (SecItemCopyMatching(query, out.ptr) != errSecSuccess) return@cfOwned null
            // kSecReturnData hands back a +1 reference, so this one is ours to release too.
            val data: CFDataRef = own(out.value)?.reinterpret() ?: return@cfOwned null
            val length = CFDataGetLength(data).toInt()
            if (length <= 0) return@cfOwned null
            CFDataGetBytePtr(data)?.readBytes(length)?.decodeToString()?.takeIf { it.isNotEmpty() }
        }
    }

    override fun put(key: String, value: String, account: String?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        // Delete-then-add rather than SecItemUpdate: one code path, and it cannot leave a duplicate item
        // behind if an earlier write was interrupted between the two.
        clear(key)
        cfOwned {
            val bytes = value.encodeToByteArray()
            // CFDataCreate copies the buffer, so the scoped array can go away with the memScope.
            val data = own(
                memScoped {
                    CFDataCreate(kCFAllocatorDefault, allocArrayOf(bytes).reinterpret(), bytes.size.convert())
                },
            ) ?: return@cfOwned
            val item = own(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to own(cfString(SERVICE)),
                    kSecAttrAccount to own(cfString(key)),
                    kSecValueData to data,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ),
            ) ?: return@cfOwned
            SecItemAdd(item, null)
            Unit
        }
    }

    override fun clear(key: String) {
        cfOwned {
            val query = own(
                cfDictionary(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to own(cfString(SERVICE)),
                    kSecAttrAccount to own(cfString(key)),
                ),
            ) ?: return@cfOwned
            SecItemDelete(query)
            Unit
        }
    }

    private companion object {
        const val SERVICE = "Salty"
    }
}

/**
 * Tracks the CoreFoundation objects created inside [block] and releases them all when it ends, however it
 * ends.
 *
 * Worth the ceremony for two reasons that a plain try/finally per call got wrong: `CFRelease(null)` is a
 * crash on Apple platforms rather than a no-op, so nulls must never reach it; and an early return on a
 * failed create would otherwise leak every reference made before it.
 */
@OptIn(ExperimentalForeignApi::class)
private class CfOwner {
    private val owned = mutableListOf<CFTypeRef>()

    /** Take ownership of a +1 reference (or ignore a null one) and hand it straight back. */
    fun <T : CFTypeRef> own(ref: T?): T? {
        if (ref != null) owned += ref
        return ref
    }

    fun releaseAll() {
        owned.forEach { CFRelease(it) }
        owned.clear()
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> cfOwned(block: CfOwner.() -> R): R {
    val owner = CfOwner()
    return try {
        owner.block()
    } finally {
        owner.releaseAll()
    }
}

/** A +1 CFString. Caller owns it — pass it through [CfOwner.own]. */
@OptIn(ExperimentalForeignApi::class)
private fun cfString(value: String): CFStringRef? =
    CFStringCreateWithCString(kCFAllocatorDefault, value, kCFStringEncodingUTF8)

/**
 * A +1 immutable CFDictionary. The standard CFType callbacks make it retain its keys and values for its
 * own lifetime, which is why the strings and data handed to it can be released as soon as the SecItem call
 * returns. The scoped key/value arrays are copied by CFDictionaryCreate, so they can go too.
 */
@OptIn(ExperimentalForeignApi::class)
private fun cfDictionary(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef? = memScoped {
    CFDictionaryCreate(
        kCFAllocatorDefault,
        allocArrayOf(pairs.map { it.first }).reinterpret(),
        allocArrayOf(pairs.map { it.second }).reinterpret(),
        pairs.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
}
