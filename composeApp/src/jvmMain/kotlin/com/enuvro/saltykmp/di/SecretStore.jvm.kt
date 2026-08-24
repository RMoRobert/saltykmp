package com.enuvro.saltykmp.di

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * Desktop secret storage: Windows Credential Manager where available, obfuscated prefs elsewhere.
 *
 * macOS and Linux stay on the fallback for now — Keychain (`security` CLI) and libsecret
 * (`secret-tool`) are the obvious follow-ups and need no new dependency.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore =
    WindowsCredentialStore.createOrNull() ?: ObfuscatedSecretStore(fallback)

/**
 * Generic credentials in the Windows Credential Manager, one entry per key, named "Salty:<key>".
 *
 * Chosen over DPAPI (`CryptProtectData`) because the entry is *visible*: the user can see it under
 * Control Panel -> Credential Manager -> Windows Credentials -> Generic Credentials, and delete it
 * there without touching the app. Protection is otherwise comparable — both bind the secret to the
 * Windows user account, and neither defends against code already running as that user.
 */
private class WindowsCredentialStore private constructor(private val lib: Advapi32Cred) : SecretStore {

    override val isPlatformBacked: Boolean = true
    override val backendName: String = "Windows Credential Manager"

    override fun get(key: String): String? = runCatching {
        val out = PointerByReference()
        if (!lib.CredReadW(WString(target(key)), CRED_TYPE_GENERIC, 0, out)) return null // incl. ERROR_NOT_FOUND
        val handle = out.value ?: return null
        try {
            val cred = CREDENTIALW(handle)
            val blob = cred.credentialBlob ?: return null
            val size = cred.credentialBlobSize
            if (size <= 0) return null
            // Windows' own credential UI renders a generic blob as UTF-16LE, so writing it that way is
            // what makes the "Show" link display the real password rather than mojibake.
            String(blob.getByteArray(0, size), Charsets.UTF_16LE).takeIf { it.isNotEmpty() }
        } finally {
            lib.CredFree(handle)
        }
    }.getOrNull()

    override fun put(key: String, value: String, account: String?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        runCatching {
            val bytes = value.toByteArray(Charsets.UTF_16LE)
            // CRED_MAX_CREDENTIAL_BLOB_SIZE. A password this long is a caller bug, not a user action.
            require(bytes.size <= 2560) { "secret too large for Credential Manager" }
            // Held in a local so the native buffer outlives the CredWriteW call.
            val blob = Memory(bytes.size.toLong()).apply { write(0, bytes, 0, bytes.size) }
            val cred = CREDENTIALW().apply {
                type = CRED_TYPE_GENERIC
                targetName = WString(target(key))
                comment = WString("Salty recipe app - sync server credential")
                credentialBlobSize = bytes.size
                credentialBlob = blob
                // LOCAL_MACHINE, not ENTERPRISE: the credential survives reboots but is not roamed to
                // the user's other Windows machines, which would silently copy the password around.
                persist = CRED_PERSIST_LOCAL_MACHINE
                userName = WString(account?.takeIf { it.isNotBlank() } ?: "Salty")
            }
            lib.CredWriteW(cred, 0)
        }
    }

    override fun clear(key: String) {
        runCatching { lib.CredDeleteW(WString(target(key)), CRED_TYPE_GENERIC, 0) }
    }

    private fun target(key: String) = "Salty:$key"

    companion object {
        private const val CRED_TYPE_GENERIC = 1
        private const val CRED_PERSIST_LOCAL_MACHINE = 2

        /**
         * Null on non-Windows, and also when advapi32 can't be bound — JNA unpacks a native stub to a
         * temp dir, which a locked-down machine can refuse. A user with no credential vault should get
         * the fallback, not a crash on first launch.
         */
        fun createOrNull(): SecretStore? {
            if (!System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return null
            return runCatching {
                WindowsCredentialStore(Native.load("advapi32", Advapi32Cred::class.java))
            }.getOrNull()
        }
    }
}

/**
 * Minimal hand-rolled binding for the four wincred.h entry points we need, so the desktop app pulls in
 * JNA core only (jna-platform would add ~1.5 MB of Win32 wrappers for these same four calls).
 */
private interface Advapi32Cred : StdCallLibrary {
    fun CredReadW(targetName: WString, type: Int, flags: Int, credential: PointerByReference): Boolean
    fun CredWriteW(credential: CREDENTIALW, flags: Int): Boolean
    fun CredDeleteW(targetName: WString, type: Int, flags: Int): Boolean
    fun CredFree(buffer: Pointer)
}

/**
 * wincred.h `CREDENTIALW`. Field order and types must match the C struct exactly — JNA computes the
 * offsets from them. `FILETIME LastWritten` is modelled as its two DWORDs, which lays out identically
 * and saves a nested Structure class.
 */
@Structure.FieldOrder(
    "flags", "type", "targetName", "comment", "lastWrittenLow", "lastWrittenHigh",
    "credentialBlobSize", "credentialBlob", "persist", "attributeCount", "attributes",
    "targetAlias", "userName",
)
private class CREDENTIALW : Structure {
    @JvmField var flags: Int = 0
    @JvmField var type: Int = 0
    @JvmField var targetName: WString? = null
    @JvmField var comment: WString? = null
    @JvmField var lastWrittenLow: Int = 0
    @JvmField var lastWrittenHigh: Int = 0
    @JvmField var credentialBlobSize: Int = 0
    @JvmField var credentialBlob: Pointer? = null
    @JvmField var persist: Int = 0
    @JvmField var attributeCount: Int = 0
    @JvmField var attributes: Pointer? = null
    @JvmField var targetAlias: WString? = null
    @JvmField var userName: WString? = null

    constructor() : super()

    /** Wraps the buffer CredReadW allocated; [read] pulls the native fields into this instance. */
    constructor(memory: Pointer) : super(memory) {
        read()
    }
}
