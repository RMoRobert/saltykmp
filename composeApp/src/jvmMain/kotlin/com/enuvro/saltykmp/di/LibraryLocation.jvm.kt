package com.enuvro.saltykmp.di

import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import java.io.File

/**
 * Where the library bundle goes on desktop when Settings holds no user-chosen location.
 *
 *   macOS    `~/Documents/Salty`
 *   Windows  `%LOCALAPPDATA%\Salty`
 *   Linux    `$XDG_DATA_HOME/salty`, i.e. `~/.local/share/salty`
 *
 * macOS gets Documents because the bundle is a *document* — the same `SaltyRecipeLibrary.saltyRecipeLibrary`
 * Salty for Mac opens — and Finder should show it. Windows deliberately does NOT: OneDrive's Known Folder
 * Move redirects Documents into the cloud mirror on a great many installs, and a live SQLite database with
 * a WAL beside it is exactly what a continuously-syncing client corrupts. LocalAppData never roams or syncs.
 * Linux follows XDG, which has no convention for an app folder under Documents and puts an application's
 * own data in `~/.local/share`.
 *
 * Two rules keep this from disturbing anyone who already runs Salty:
 *  - **An existing install never moves.** If `~/.salty` already holds a library it stays the default,
 *    so only a first run lands in the new place. Nothing is copied, and nothing has to be.
 *  - **An unusable folder falls back to `~/.salty`**, where every desktop install lived before this.
 *    That covers a macOS user who denies the Documents-folder prompt (TCC asks the first time the app
 *    touches `~/Documents`) and a read-only home.
 *
 * Resolved once per launch, because the probe below writes a file.
 */
internal val defaultLibraryParent: File by lazy { resolveLibraryParent() }

/** `~/.salty`, the default every desktop install used before platform locations existed. */
private fun legacyLibraryParent(): File = File(System.getProperty("user.home").orEmpty(), ".salty")

private fun resolveLibraryParent(): File = chooseLibraryParent(legacyLibraryParent(), platformLibraryParent())

/**
 * The two rules from the comment above, in order: an install that already lives in [legacy] stays
 * there, and a [candidate] we cannot write to loses to [legacy] as well.
 */
internal fun chooseLibraryParent(legacy: File, candidate: File?): File =
    if (legacy.holdsLibrary()) legacy else candidate?.takeIf { it.isUsable() } ?: legacy

/**
 * The platform's folder for Salty, or null on an OS with no convention worth guessing at. Pure path
 * arithmetic — [resolveLibraryParent] decides whether the answer is actually usable.
 */
internal fun platformLibraryParent(
    osName: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    env: (String) -> String? = System::getenv,
): File? = when {
    osName.startsWith("Mac", ignoreCase = true) -> File(File(home, "Documents"), "Salty")
    osName.startsWith("Windows", ignoreCase = true) -> File(windowsLocalAppData(home, env), "Salty")
    osName.startsWith("Linux", ignoreCase = true) -> File(linuxDataHome(home, env), "salty")
    else -> null
}

/** `%LOCALAPPDATA%`, or its standard place under the profile when the variable is missing. */
private fun windowsLocalAppData(home: String, env: (String) -> String?): File =
    env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let(::File) ?: File(home, "AppData/Local")

/** `$XDG_DATA_HOME`, or `~/.local/share`. The spec says a *relative* value must be ignored, not resolved. */
private fun linuxDataHome(home: String, env: (String) -> String?): File =
    env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let(::File)
        ?: File(home, ".local/share")

/**
 * The library bundle inside [parent], created if it isn't there yet.
 *
 * This is what makes "point Salty at an empty folder" produce a working, empty library: SQLite will not
 * create missing parent directories, so the bundle has to exist before the driver opens the file in it.
 * Everything below it — the `.sqlite` file, `recipeImages/` — is then created on demand by
 * `createAppDatabase` and `createImageFiles`.
 */
internal fun libraryDirIn(parent: File): File = File(parent, SALTY_LIBRARY_DIR).apply { mkdirs() }

/** True when this folder already contains a library bundle with something in it. */
private fun File.holdsLibrary(): Boolean =
    runCatching { File(this, SALTY_LIBRARY_DIR).list()?.isNotEmpty() == true }.getOrDefault(false)

/**
 * Create the folder and prove we can write in it. The write matters as much as the mkdirs: on macOS
 * this is the call that raises the Documents-folder consent prompt, and a denial surfaces as a failure
 * here rather than as a broken database later.
 */
private fun File.isUsable(): Boolean = runCatching {
    if (!isDirectory && !mkdirs()) return false
    val probe = File(this, ".salty-write-probe")
    probe.writeBytes(ByteArray(0))
    probe.delete()
    true
}.getOrDefault(false)

/**
 * Ready a folder the user picked in Settings, and say what it turned out to be — so choosing a folder
 * reports "found a library" versus "made you an empty one" straight away, instead of the difference
 * only showing up as an empty recipe list after the restart.
 */
actual fun prepareLibraryLocation(path: String): LibraryLocationOutcome {
    val parent = File(path)
    if (parent.holdsLibrary()) return LibraryLocationOutcome.ExistingLibrary
    return runCatching {
        if (libraryDirIn(parent).isDirectory) LibraryLocationOutcome.NewLibrary else LibraryLocationOutcome.Unusable
    }.getOrDefault(LibraryLocationOutcome.Unusable)
}
