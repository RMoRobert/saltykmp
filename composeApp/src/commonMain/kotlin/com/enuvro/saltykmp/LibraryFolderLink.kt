package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_IMAGES_DIR
import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import com.enuvro.saltykmp.db.checkpointWal
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.linkedFolderSyncSupported
import com.enuvro.saltykmp.di.localLibraryDbPath
import com.enuvro.saltykmp.di.localLibraryImagesDir
import com.enuvro.saltykmp.sync.LibraryFileState
import com.enuvro.saltykmp.sync.LibraryFolderSync
import com.enuvro.saltykmp.sync.LibrarySyncAction
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.bookmarkData
import io.github.vinceglb.filekit.createDirectories
import io.github.vinceglb.filekit.delete
import io.github.vinceglb.filekit.exists
import io.github.vinceglb.filekit.fromBookmarkData
import io.github.vinceglb.filekit.lastModified
import io.github.vinceglb.filekit.list
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import io.github.vinceglb.filekit.resolve
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.startAccessingSecurityScopedResource
import io.github.vinceglb.filekit.stopAccessingSecurityScopedResource
import io.github.vinceglb.filekit.write
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime

/** Outcome of a linked-folder reconcile/push, surfaced to Settings so the user can see what happened. */
enum class LibraryFolderSyncResult {
    NOT_LINKED,   // no folder linked (or unsupported platform)
    NO_CHANGE,    // already in sync
    PUSHED,       // local → folder
    PULLED,       // folder → local (applied at startup; UI should suggest the data is now current)
    SEEDED,       // folder was empty; local copied out to initialize it
    CONFLICT,     // both sides changed (or contradictory timestamps) — nothing copied, ask the user
    ERROR,        // bookmark unresolved / I/O failure
}

/**
 * Copy-based library sync to a user-linked folder (OneDrive/Nextcloud/… via SAF on Android). SQLite can't
 * run live on a cloud document, so the whole DB (+ `-wal`/`-shm` sidecars + images) is copied in/out and
 * reconciled by [LibraryFolderSync]. The chosen folder is persisted as a FileKit bookmark (a SAF
 * persistable URI on Android / a security-scoped bookmark on iOS) so access survives restarts.
 *
 * Safety model (per product decision):
 *  - COPY-IN (folder → local) is performed ONLY by [reconcileAtStartup], before the DB is opened, so the
 *    SQLite file is never swapped under a live connection. A folder that changes mid-session is reported
 *    via [LibraryFolderSyncResult.PULLED]/[CONFLICT] for the UI to prompt a restart.
 *  - COPY-OUT (local → folder) is always safe; it checkpoints the WAL first so the `.sqlite` is complete.
 *
 * NOTE: compiles but is not yet device-tested — SAF child-file create/write and bookmark round-trips vary
 * by OEM/provider and must be validated on a real device before relying on it.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LibraryFolderLink(private val store: KeyValueStore) {

    /**
     * Detail of the most recent failure — the step that died plus the underlying exception — so the
     * Settings UI (and logs) can show *why* a link/sync failed instead of the generic ERROR. Null when OK.
     * Diagnostic aid for the not-yet-device-tested iOS/SAF bookmark round-trip.
     */
    var lastError: String? = null
        private set

    /** Platform offers copy-based linked folders AND we know where the local DB lives. */
    val isSupported: Boolean get() = linkedFolderSyncSupported && localLibraryDbPath() != null

    fun isLinked(): Boolean = isSupported && store.getString(KEY_BOOKMARK, "").isNotEmpty()

    /** Human-readable name of the linked folder (for Settings), or "" if none. */
    fun linkedLabel(): String = store.getString(KEY_LABEL, "")

    /** Persist a newly-picked folder, then reconcile (seeds the folder if empty). */
    suspend fun link(folder: PlatformFile): LibraryFolderSyncResult {
        lastError = null
        val result = runCatching { folder.bookmarkData().bytes }
        val bytes = result.getOrNull() ?: return fail("bookmarkData (create)", result.exceptionOrNull())
        store.putString(KEY_BOOKMARK, Base64.encode(bytes))
        store.putString(KEY_LABEL, folder.name)
        clearTokens() // unknown history → reconcile decides (may CONFLICT if both have data)
        return reconcileAtStartup()
    }

    fun unlink() {
        store.putString(KEY_BOOKMARK, "")
        store.putString(KEY_LABEL, "")
        clearTokens()
    }

    /**
     * Decide and act. Safe to call only when the DB is NOT open (app startup), because a [LibrarySyncAction.COPY_IN]
     * replaces the local DB file. Returns what happened so the caller can prompt on CONFLICT.
     */
    suspend fun reconcileAtStartup(): LibraryFolderSyncResult = withFolder { root ->
        val folderDb = root.resolve(SALTY_LIBRARY_DIR).resolve(SALTY_DB_FILE)
        val localDb = PlatformFile(localLibraryDbPath()!!)
        val lastLocal = store.getString(KEY_LAST_LOCAL_TOKEN, "").ifEmpty { null }
        val lastFolder = store.getString(KEY_LAST_FOLDER_TOKEN, "").ifEmpty { null }
        val localState = stateOf(localDb)
        val folderState = stateOf(folderDb)
        val action = LibraryFolderSync.decide(localState, folderState, lastLocal, lastFolder)
        println(
            "LibraryFolderLink reconcile: action=$action lastLocal=$lastLocal lastFolder=$lastFolder\n" +
                "  local : path=${localLibraryDbPath()} exists=${localState.exists} size=${localState.size} mtime=${localState.lastModifiedMillis}\n" +
                "  folder: $SALTY_LIBRARY_DIR/$SALTY_DB_FILE exists=${folderState.exists} size=${folderState.size} mtime=${folderState.lastModifiedMillis}",
        )
        when (action) {
            LibrarySyncAction.NONE -> LibraryFolderSyncResult.NO_CHANGE
            LibrarySyncAction.CONFLICT -> LibraryFolderSyncResult.CONFLICT
            LibrarySyncAction.COPY_IN -> { copyIn(root); LibraryFolderSyncResult.PULLED }
            LibrarySyncAction.COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.PUSHED }
            LibrarySyncAction.INITIAL_COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.SEEDED }
        }
    }

    /** Push local → folder. Always safe (never writes local). Use after server sync, on background, manual. */
    suspend fun pushOut(): LibraryFolderSyncResult = withFolder { root ->
        copyOut(root)
        LibraryFolderSyncResult.PUSHED
    }

    /** Conflict resolution — keep the LOCAL copy (push it out, overwriting the folder). Safe any time. */
    suspend fun resolveUsingLocal(): LibraryFolderSyncResult = withFolder { root ->
        copyOut(root)
        LibraryFolderSyncResult.PUSHED
    }

    /** Conflict resolution — keep the FOLDER copy (pull it in, replacing local). Call only with the DB closed. */
    suspend fun resolveUsingFolder(): LibraryFolderSyncResult = withFolder { root ->
        copyIn(root)
        LibraryFolderSyncResult.PULLED
    }

    // ---- internals ----

    private suspend fun copyIn(root: PlatformFile) {
        val bundle = root.resolve(SALTY_LIBRARY_DIR)
        for (suffix in DB_SUFFIXES) {
            val src = bundle.resolve(SALTY_DB_FILE + suffix)
            val destPath = localLibraryDbPath()!! + suffix
            val dest = PlatformFile(destPath)
            val srcExists = src.exists()
            println("LibraryFolderLink copyIn: '$SALTY_DB_FILE$suffix' srcExists=$srcExists srcSize=${if (srcExists) src.size() else -1} -> $destPath")
            if (srcExists) copyFile(src, dest) else if (dest.exists()) dest.delete(mustExist = false)
        }
        val localImages = PlatformFile(localLibraryImagesDir()!!).also { it.createDirectories() }
        val folderImages = bundle.resolve(SALTY_IMAGES_DIR)
        if (folderImages.exists()) folderImages.list().forEach { copyFile(it, localImages.resolve(it.name)) }
        recordSyncedTokens(local = PlatformFile(localLibraryDbPath()!!), folder = bundle.resolve(SALTY_DB_FILE))
    }

    private suspend fun copyOut(root: PlatformFile) {
        checkpointWal() // flush WAL into the .sqlite (no-op at startup before the DB is open)
        val bundle = root.resolve(SALTY_LIBRARY_DIR).also { it.createDirectories() }
        for (suffix in DB_SUFFIXES) {
            val src = PlatformFile(localLibraryDbPath()!! + suffix)
            val dest = bundle.resolve(SALTY_DB_FILE + suffix)
            if (src.exists()) copyFile(src, dest) else if (dest.exists()) dest.delete(mustExist = false)
        }
        val localImages = PlatformFile(localLibraryImagesDir()!!)
        val folderImages = bundle.resolve(SALTY_IMAGES_DIR).also { it.createDirectories() }
        if (localImages.exists()) localImages.list().forEach { copyFile(it, folderImages.resolve(it.name)) }
        recordSyncedTokens(local = PlatformFile(localLibraryDbPath()!!), folder = bundle.resolve(SALTY_DB_FILE))
    }

    /**
     * Mark both copies as the synced baseline. Copying rewrites the destination's mtime, so each side gets
     * its OWN token — comparing them to a single shared token would spuriously CONFLICT on every launch.
     */
    private suspend fun recordSyncedTokens(local: PlatformFile, folder: PlatformFile) {
        store.putString(KEY_LAST_LOCAL_TOKEN, stateOf(local).token.orEmpty())
        store.putString(KEY_LAST_FOLDER_TOKEN, stateOf(folder).token.orEmpty())
    }

    private fun clearTokens() {
        store.putString(KEY_LAST_LOCAL_TOKEN, "")
        store.putString(KEY_LAST_FOLDER_TOKEN, "")
    }

    private suspend fun stateOf(f: PlatformFile): LibraryFileState =
        if (f.exists()) LibraryFileState(true, f.lastModified().toEpochMilliseconds(), f.size())
        else LibraryFileState(exists = false)

    private suspend fun copyFile(src: PlatformFile, dest: PlatformFile) {
        dest.write(src.readBytes())
    }

    /** Resolve the linked folder from its bookmark, hold security-scoped access for the operation, run [block]. */
    private suspend fun withFolder(
        block: suspend (PlatformFile) -> LibraryFolderSyncResult,
    ): LibraryFolderSyncResult {
        if (!isLinked()) return LibraryFolderSyncResult.NOT_LINKED
        lastError = null

        val decoded = runCatching { Base64.decode(store.getString(KEY_BOOKMARK, "")) }
        val bytes = decoded.getOrNull() ?: return fail("decode stored bookmark", decoded.exceptionOrNull())

        val resolved = runCatching { PlatformFile.fromBookmarkData(bytes) }
        val root = resolved.getOrNull() ?: return fail("fromBookmarkData (resolve bookmark)", resolved.exceptionOrNull())

        // On iOS this MUST return true for the file ops below to work; false here is the prime suspect.
        val accessed = runCatching { root.startAccessingSecurityScopedResource() }.getOrDefault(false)
        if (!accessed) println("LibraryFolderLink: startAccessingSecurityScopedResource() == false (access likely denied)")

        return try {
            runCatching { block(root) }.getOrElse { fail("folder I/O (securityScopeGranted=$accessed)", it) }
        } finally {
            if (accessed) runCatching { root.stopAccessingSecurityScopedResource() }
        }
    }

    /** Records the failing step + exception (for the UI via [lastError] and the console), returns ERROR. */
    private fun fail(step: String, cause: Throwable? = null): LibraryFolderSyncResult {
        val detail = "[$step] " + (cause?.message ?: cause?.toString() ?: "no exception thrown")
        lastError = detail
        println("LibraryFolderLink ERROR: $detail")
        cause?.printStackTrace()
        return LibraryFolderSyncResult.ERROR
    }

    companion object {
        private const val KEY_BOOKMARK = "linkedFolderBookmark"
        private const val KEY_LABEL = "linkedFolderLabel"
        // Separate last-synced tokens per side; a single shared token can't track two files whose mtimes
        // diverge on every copy (the cause of the spurious "Library changed in two places" prompt).
        private const val KEY_LAST_LOCAL_TOKEN = "linkedFolderLastLocalToken"
        private const val KEY_LAST_FOLDER_TOKEN = "linkedFolderLastFolderToken"
        // SQLite (WAL mode) keeps recent data in the -wal/-shm sidecars; copy the whole set so nothing is lost.
        private val DB_SUFFIXES = listOf("", "-wal", "-shm")
    }
}
