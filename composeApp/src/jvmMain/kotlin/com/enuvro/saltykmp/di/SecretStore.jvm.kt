package com.enuvro.saltykmp.di

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.TimeUnit

/**
 * Desktop secret storage: Windows Credential Manager, macOS Keychain, or Linux libsecret — whichever the
 * host offers and can actually round-trip a value through — and obfuscated prefs otherwise.
 *
 * Every candidate goes through [verifiedOrNull], so a vault that is present but non-functional (no keyring
 * daemon on a headless Linux box; the JNA fault that once made the Windows one silently discard writes)
 * demotes itself to the fallback instead of pretending to store anything.
 */
actual fun createSecretStore(fallback: KeyValueStore): SecretStore {
    val os = System.getProperty("os.name").orEmpty()
    val platform = when {
        os.startsWith("Windows", ignoreCase = true) -> WindowsCredentialStore.createOrNull()
        os.startsWith("Mac", ignoreCase = true) -> MacKeychainStore()
        os.startsWith("Linux", ignoreCase = true) -> LinuxSecretToolStore()
        else -> null
    }
    return platform?.verifiedOrNull() ?: ObfuscatedSecretStore(fallback)
}

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
 * macOS Keychain, via the `security` CLI — one generic password per key, service "Salty:<key>", visible
 * and deletable in Keychain Access just as the Windows entries are in Credential Manager.
 *
 * KNOWN WEAKNESS: `add-generic-password -w <secret>` puts the password in the process argument list, where
 * any other process running as this user can see it in `ps` for the lifetime of the call. That is strictly
 * better than the obfuscated fallback (which leaves it readable at rest, forever) and strictly worse than
 * the Windows and iOS stores, which never expose it. Removing it means binding Security.framework's
 * SecItemAdd through JNA — the same shape as [WindowsCredentialStore], and the natural next step here.
 */
private class MacKeychainStore : SecretStore {

    override val isPlatformBacked: Boolean = true
    override val backendName: String = "macOS Keychain"

    override fun get(key: String): String? =
        runTool("/usr/bin/security", "find-generic-password", "-s", service(key), "-w")
            ?.removeSuffix("\n")?.takeIf { it.isNotEmpty() }

    override fun put(key: String, value: String, account: String?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        runTool(
            "/usr/bin/security", "add-generic-password",
            "-s", service(key),
            // Display-only, exactly as on Windows: lookups key off the service alone, so changing the sync
            // username later can never orphan the entry.
            "-a", account?.takeIf { it.isNotBlank() } ?: "Salty",
            "-w", value,
            "-U", // update in place instead of failing when the item already exists
        )
    }

    override fun clear(key: String) {
        runTool("/usr/bin/security", "delete-generic-password", "-s", service(key))
    }

    private fun service(key: String) = "Salty:$key"
}

/**
 * Linux keyring through libsecret's `secret-tool`, which talks to whatever Secret Service provider is
 * running (GNOME Keyring, KWallet).
 *
 * Two things have to be true, and neither is guaranteed: `secret-tool` installed (`libsecret-tools`), and a
 * provider actually running — a headless box or a bare window manager has neither. Both failures look the
 * same from here, and both are caught by the [verifiedOrNull] round trip, which demotes this to the
 * obfuscated fallback rather than dropping secrets on the floor.
 *
 * Unlike the macOS tool, `secret-tool store` reads the secret from STDIN, so it never reaches argv.
 */
private class LinuxSecretToolStore : SecretStore {

    override val isPlatformBacked: Boolean = true
    override val backendName: String = "Linux keyring (libsecret)"

    override fun get(key: String): String? =
        runTool("secret-tool", "lookup", ATTR_APP, APP, ATTR_KEY, key)
            ?.removeSuffix("\n")?.takeIf { it.isNotEmpty() }

    override fun put(key: String, value: String, account: String?) {
        if (value.isEmpty()) {
            clear(key)
            return
        }
        runTool("secret-tool", "store", "--label=$APP", ATTR_APP, APP, ATTR_KEY, key, stdin = value)
    }

    override fun clear(key: String) {
        runTool("secret-tool", "clear", ATTR_APP, APP, ATTR_KEY, key)
    }

    private companion object {
        const val APP = "Salty"
        const val ATTR_APP = "application"
        const val ATTR_KEY = "key"
    }
}

/** How long a keyring helper gets before it is assumed stuck and killed. */
private const val TOOL_TIMEOUT_SECONDS = 10L

/**
 * Run a keyring helper, optionally feeding it [stdin], and return its stdout — or null for any failure at
 * all, including the binary not existing.
 *
 * stderr is discarded rather than merged, so a tool that warns on the way to succeeding cannot corrupt a
 * returned secret.
 */
private fun runTool(vararg command: String, stdin: String? = null): String? = runCatching {
    val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    process.outputStream.use { out -> stdin?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
    // Wait BEFORE reading: every output here is one short line, far inside the pipe buffer, so the child can
    // exit without us draining it. Reading first would instead hang forever on a tool that stops to prompt
    // — a keychain unlock dialog, or a Secret Service that never answers — and this is called from the UI.
    if (!process.waitFor(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        return null
    }
    if (process.exitValue() != 0) return null // includes "no such item", which is a normal miss
    process.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
}.getOrNull()

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
 *
 * `internal`, NOT `private`: a private top-level class is package-private in the bytecode, and JNA only
 * calls setAccessible() on fields it sees as non-public. These fields *are* public, so it reads them
 * directly — and the JVM refuses, because the declaring class is not. That threw inside Structure's
 * constructor, so every CredWriteW/CredReadW died before reaching the native call and the runCatching
 * in [WindowsCredentialStore] swallowed it: nothing written, no error, an empty password sent to sync.
 */
@Structure.FieldOrder(
    "flags", "type", "targetName", "comment", "lastWrittenLow", "lastWrittenHigh",
    "credentialBlobSize", "credentialBlob", "persist", "attributeCount", "attributes",
    "targetAlias", "userName",
)
internal class CREDENTIALW : Structure {
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
