package com.enuvro.saltykmp

import com.enuvro.saltykmp.db.SALTY_DB_FILE
import com.enuvro.saltykmp.db.SALTY_IMAGES_DIR
import com.enuvro.saltykmp.db.SALTY_LIBRARY_DIR
import com.enuvro.saltykmp.db.checkpointWal
import com.enuvro.saltykmp.di.KeyValueStore
import com.enuvro.saltykmp.di.childFile
import com.enuvro.saltykmp.di.ensureSubdirectory
import com.enuvro.saltykmp.di.findChild
import com.enuvro.saltykmp.di.linkedFolderSyncSupported
import com.enuvro.saltykmp.di.localLibraryDbPath
import com.enuvro.saltykmp.di.localLibraryImagesDir
import com.enuvro.saltykmp.sync.LibraryFileState
import com.enuvro.saltykmp.sync.LibraryFolderSync
import com.enuvro.saltykmp.sync.LibrarySyncAction
import com.enuvro.saltykmp.util.ContentHash
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
import io.github.vinceglb.filekit.size
import io.github.vinceglb.filekit.startAccessingSecurityScopedResource
import io.github.vinceglb.filekit.stopAccessingSecurityScopedResource
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime

/** Outcome of a linked-folder reconcile/push, surfaced to Settings so the user can see what happened. */
enum class LibraryFolderSyncResult {
    NOT_LINKED,    // no folder linked (or unsupported platform)
    NO_CHANGE,     // already in sync
    PUSHED,        // local → folder
    PULLED,        // folder → local (applied at startup; UI should suggest the data is now current)
    SEEDED,        // folder was empty; local copied out to initialize it
    FOLDER_NEWER,  // folder has a newer library but the DB is open, so it will be pulled on the next launch
    CONFLICT,      // both sides changed — nothing copied, ask the user
    ERROR,         // bookmark unresolved / I/O failure
}

/**
 * Copy-based library sync to a user-linked folder (Nextcloud/OneDrive/… via SAF on Android, the document
 * picker on iOS). SQLite can't run live on a cloud document, so the whole DB (+ images) is copied in/out
 * and reconciled by [LibraryFolderSync]. The chosen folder is persisted as a FileKit bookmark (a SAF
 * persistable URI on Android / a security-scoped bookmark on iOS) so access survives restarts.
 *
 * The folder receives the same `SaltyRecipeLibrary.saltyRecipeLibrary/{saltyRecipeDB.sqlite, recipeImages/}`
 * bundle the Swift app and the desktop build open directly, so a Mac can share the library through the
 * same cloud folder. This is meant for backup / one-device-at-a-time use; Salty Server remains the
 * recommended way to keep several devices in sync.
 *
 * Safety model:
 *  - COPY-IN (folder → local) is performed ONLY by [reconcileAtStartup] (and the conflict resolver), before
 *    the DB is opened, so the SQLite file is never swapped under a live connection. A folder that turns out
 *    to be newer mid-session is reported as [LibraryFolderSyncResult.FOLDER_NEWER] and pulled next launch.
 *  - COPY-OUT (local → folder) is safe for local data any time; it checkpoints the WAL first so the `.sqlite`
 *    is complete. It refuses ([LibraryFolderSyncResult.CONFLICT]) when the folder changed independently.
 *  - Change detection is by CONTENT HASH, not mtime (cloud providers rewrite timestamps after upload; see
 *    [LibraryFileState]). Pushes are cheap no-ops when nothing changed, so they can run on every background.
 *  - All operations are serialized by a mutex (background push, debounced push and the Settings button can
 *    otherwise overlap).
 *
 * [isLocalLibraryEmpty] lets [link] treat the folder as authoritative for a fresh install (no recipes yet)
 * instead of prompting for a conflict it can't explain.
 *
 * NOTE: the SAF/bookmark round-trips are not yet device-tested; validate on a real device (Nextcloud,
 * OneDrive) before relying on it.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalTime::class)
class LibraryFolderLink(
    private val store: KeyValueStore,
    private val isLocalLibraryEmpty: () -> Boolean = { false },
) {
    private val mutex = Mutex()

    /**
     * Detail of the most recent failure — the step that died plus the underlying exception — so the
     * Settings UI (and logs) can show *why* a link/sync failed instead of the generic ERROR. Null when OK.
     */
    var lastError: String? = null
        private set

    /** Platform offers copy-based linked folders AND we know where the local DB lives. */
    val isSupported: Boolean get() = linkedFolderSyncSupported && localLibraryDbPath() != null

    fun isLinked(): Boolean = isSupported && store.getString(KEY_BOOKMARK, "").isNotEmpty()

    /** Human-readable name of the linked folder (for Settings), or "" if none. */
    fun linkedLabel(): String = store.getString(KEY_LABEL, "")

    /**
     * Persist a newly-picked folder, then reconcile with the DB assumed OPEN (this runs from Settings): seeds an
     * empty folder, pushes if only we have data, and otherwise defers to the next launch — taking the folder's
     * copy automatically when this install has no recipes yet, prompting when both sides have data.
     */
    suspend fun link(folder: PlatformFile): LibraryFolderSyncResult {
        lastError = null
        val result = runCatching { folder.bookmarkData().bytes }
        val bytes = result.getOrNull() ?: return fail("bookmarkData (create)", result.exceptionOrNull())
        store.putString(KEY_BOOKMARK, Base64.encode(bytes))
        store.putString(KEY_LABEL, folder.name)
        clearTokens() // unknown history → reconcile decides
        return withFolder { root ->
            val (local, remote) = states(root)
            when (LibraryFolderSync.decide(local, remote, null, null)) {
                LibrarySyncAction.CONFLICT, LibrarySyncAction.COPY_IN -> {
                    if (local.exists && !isLocalLibraryEmpty()) {
                        LibraryFolderSyncResult.CONFLICT
                    } else {
                        // Nothing worth keeping here: make only the folder read as "changed" so the next launch
                        // pulls it in (local edits made before then still surface as a CONFLICT prompt).
                        recordTokens(local = local, folder = null)
                        LibraryFolderSyncResult.FOLDER_NEWER
                    }
                }
                else -> reconcileWithDbOpen(root, local, remote)
            }
        }
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
        val (local, remote) = states(root)
        val action = decide(local, remote)
        when (action) {
            LibrarySyncAction.NONE -> { recordTokens(local, remote); LibraryFolderSyncResult.NO_CHANGE }
            LibrarySyncAction.CONFLICT -> LibraryFolderSyncResult.CONFLICT
            LibrarySyncAction.COPY_IN -> { copyIn(root); LibraryFolderSyncResult.PULLED }
            LibrarySyncAction.COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.PUSHED }
            LibrarySyncAction.INITIAL_COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.SEEDED }
        }
    }

    /**
     * Push local → folder if local changed since the last sync. Never writes local, never overwrites a folder
     * that changed independently (→ CONFLICT), and reports FOLDER_NEWER when the folder alone moved on. Cheap
     * when nothing changed, so it runs on app background, shortly after edits, after server sync, and on demand.
     */
    suspend fun pushOut(): LibraryFolderSyncResult = withFolder { root ->
        val (local, remote) = states(root)
        reconcileWithDbOpen(root, local, remote)
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

    /** Reconcile when the DB may be open: every action except COPY_IN (deferred to the next launch). */
    private suspend fun reconcileWithDbOpen(
        root: PlatformFile,
        local: LibraryFileState,
        remote: LibraryFileState,
    ): LibraryFolderSyncResult = when (decide(local, remote)) {
        LibrarySyncAction.NONE -> { recordTokens(local, remote); LibraryFolderSyncResult.NO_CHANGE }
        LibrarySyncAction.CONFLICT -> LibraryFolderSyncResult.CONFLICT
        LibrarySyncAction.COPY_IN -> LibraryFolderSyncResult.FOLDER_NEWER
        LibrarySyncAction.COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.PUSHED }
        LibrarySyncAction.INITIAL_COPY_OUT -> { copyOut(root); LibraryFolderSyncResult.SEEDED }
    }

    private fun decide(local: LibraryFileState, remote: LibraryFileState): LibrarySyncAction {
        val lastLocal = store.getString(KEY_LAST_LOCAL_TOKEN, "").ifEmpty { null }
        val lastFolder = store.getString(KEY_LAST_FOLDER_TOKEN, "").ifEmpty { null }
        val action = LibraryFolderSync.decide(local, remote, lastLocal, lastFolder)
        println(
            "LibraryFolderLink reconcile: action=$action lastLocal=$lastLocal lastFolder=$lastFolder\n" +
                "  local : exists=${local.exists} size=${local.size} hash=${local.contentHash}\n" +
                "  folder: exists=${remote.exists} size=${remote.size} hash=${remote.contentHash}",
        )
        return action
    }

    /** Current state of both DB copies. The folder bundle may not exist yet. */
    private suspend fun states(root: PlatformFile): Pair<LibraryFileState, LibraryFileState> {
        val bundle = root.findChild(SALTY_LIBRARY_DIR)
        val local = hashedState(PlatformFile(localLibraryDbPath()!!), PlatformFile(localLibraryDbPath()!! + WAL))
        val remote = folderState(bundle?.findChild(SALTY_DB_FILE), bundle?.findChild(SALTY_DB_FILE + WAL))
        return local to remote
    }

    /** Full content fingerprint: the `.sqlite` bytes followed by a non-empty `-wal` (un-checkpointed writes). */
    private suspend fun hashedState(db: PlatformFile?, wal: PlatformFile?): LibraryFileState {
        if (db == null || !db.exists()) return LibraryFileState(exists = false)
        val hash = ContentHash().update(db.readBytes())
        if (wal != null && wal.exists() && wal.size() > 0) hash.update(wal.readBytes())
        return LibraryFileState(true, db.lastModified().toEpochMilliseconds(), db.size(), hash.hex)
    }

    /**
     * Folder-side state with a cheap pre-check: if the file's second-granularity mtime + size match what we
     * recorded with the last-synced hash (and there's no stray `-wal` to account for), reuse that hash instead
     * of downloading the whole DB from the cloud provider just to compare.
     */
    private suspend fun folderState(db: PlatformFile?, wal: PlatformFile?): LibraryFileState {
        if (db == null || !db.exists()) return LibraryFileState(exists = false)
        val meta = LibraryFileState(true, db.lastModified().toEpochMilliseconds(), db.size())
        val storedHash = store.getString(KEY_LAST_FOLDER_TOKEN, "")
        val storedMeta = store.getString(KEY_LAST_FOLDER_META, "")
        val walPresent = wal != null && wal.exists() && wal.size() > 0
        if (!walPresent && storedHash.isNotEmpty() && meta.metadataKey == storedMeta) {
            return meta.copy(contentHash = storedHash)
        }
        return hashedState(db, wal)
    }

    private suspend fun copyIn(root: PlatformFile) {
        val bundle = root.findChild(SALTY_LIBRARY_DIR) ?: error("'$SALTY_LIBRARY_DIR' is missing from the linked folder")
        val src = bundle.findChild(SALTY_DB_FILE) ?: error("'$SALTY_DB_FILE' is missing from the linked folder")
        val localPath = localLibraryDbPath()!!
        println("LibraryFolderLink copyIn: '$SALTY_DB_FILE' size=${src.size()} -> $localPath")
        copyFile(src, PlatformFile(localPath))
        // A `-wal` left by the other app carries committed-but-not-checkpointed pages: bring it along. The
        // `-shm` is a per-process index SQLite rebuilds; a stale one must not survive the swap.
        val srcWal = bundle.findChild(SALTY_DB_FILE + WAL)
        val localWal = PlatformFile(localPath + WAL)
        if (srcWal != null && srcWal.size() > 0) copyFile(srcWal, localWal) else deleteIfExists(localWal)
        deleteIfExists(PlatformFile(localPath + SHM))

        val localImages = PlatformFile(localLibraryImagesDir()!!).also { it.createDirectories() }
        bundle.findChild(SALTY_IMAGES_DIR)?.let { copyImages(from = it, to = localImages) }
        recordTokens(local = hashedState(PlatformFile(localPath), localWal), folder = folderState(src, srcWal))
    }

    private suspend fun copyOut(root: PlatformFile) {
        checkpointWal() // flush WAL into the .sqlite (no-op at startup before the DB is open)
        val bundle = root.ensureSubdirectory(SALTY_LIBRARY_DIR)
        val localPath = localLibraryDbPath()!!
        val localDb = PlatformFile(localPath)
        val localWal = PlatformFile(localPath + WAL)
        copyFile(localDb, bundle.findChild(SALTY_DB_FILE) ?: bundle.childFile(SALTY_DB_FILE))
        // After a checkpoint the WAL is empty; only ship it when the DB wasn't open to checkpoint (e.g. the
        // app was killed last session). Never ship `-shm`, and clear stale sidecars from an older copy.
        val remoteWal = bundle.findChild(SALTY_DB_FILE + WAL)
        if (localWal.exists() && localWal.size() > 0) {
            copyFile(localWal, remoteWal ?: bundle.childFile(SALTY_DB_FILE + WAL))
        } else {
            remoteWal?.let { deleteIfExists(it) }
        }
        bundle.findChild(SALTY_DB_FILE + SHM)?.let { deleteIfExists(it) }

        val localImages = PlatformFile(localLibraryImagesDir()!!)
        val folderImages = bundle.ensureSubdirectory(SALTY_IMAGES_DIR)
        if (localImages.exists()) copyImages(from = localImages, to = folderImages)
        val remoteDb = bundle.findChild(SALTY_DB_FILE)!!
        recordTokens(local = hashedState(localDb, localWal), folder = folderState(remoteDb, bundle.findChild(SALTY_DB_FILE + WAL)))
    }

    /**
     * Copy every image in [from] into [to], skipping ones already present with the same size and a destination
     * no older than the source (images are immutable per filename in practice; this keeps a routine push from
     * re-uploading the whole image set to the cloud each time).
     */
    private suspend fun copyImages(from: PlatformFile, to: PlatformFile) {
        val existing = to.list().associateBy { it.name }
        for (src in from.list()) {
            val dest = existing[src.name]
            if (dest != null && dest.size() == src.size() &&
                dest.lastModified().toEpochMilliseconds() >= src.lastModified().toEpochMilliseconds()
            ) continue
            copyFile(src, dest ?: to.childFile(src.name))
        }
    }

    /** Record both sides' hashes (plus the folder's metadata key for the pre-check). `null` clears that side. */
    private fun recordTokens(local: LibraryFileState?, folder: LibraryFileState?) {
        store.putString(KEY_LAST_LOCAL_TOKEN, local?.token.orEmpty())
        store.putString(KEY_LAST_FOLDER_TOKEN, folder?.token.orEmpty())
        store.putString(KEY_LAST_FOLDER_META, folder?.metadataKey.orEmpty())
    }

    private fun clearTokens() = recordTokens(null, null)

    private suspend fun copyFile(src: PlatformFile, dest: PlatformFile) {
        dest.write(src.readBytes())
    }

    private suspend fun deleteIfExists(f: PlatformFile) {
        if (f.exists()) f.delete(mustExist = false)
    }

    /** Resolve the linked folder from its bookmark, hold security-scoped access for the operation, run [block]. */
    private suspend fun withFolder(
        block: suspend (PlatformFile) -> LibraryFolderSyncResult,
    ): LibraryFolderSyncResult = mutex.withLock {
        if (!isLinked()) return@withLock LibraryFolderSyncResult.NOT_LINKED
        lastError = null

        val decoded = runCatching { Base64.decode(store.getString(KEY_BOOKMARK, "")) }
        val bytes = decoded.getOrNull() ?: return@withLock fail("decode stored bookmark", decoded.exceptionOrNull())

        val resolved = runCatching { PlatformFile.fromBookmarkData(bytes) }
        val root = resolved.getOrNull() ?: return@withLock fail("fromBookmarkData (resolve bookmark)", resolved.exceptionOrNull())

        // On iOS this MUST return true for the file ops below to work; false here is the prime suspect.
        val accessed = runCatching { root.startAccessingSecurityScopedResource() }.getOrDefault(false)
        if (!accessed) println("LibraryFolderLink: startAccessingSecurityScopedResource() == false (access likely denied)")

        try {
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
        // Per-side last-synced CONTENT hashes (a copy rewrites the destination's metadata, so the two sides
        // never share one token), plus the folder file's mtime/size so an unchanged file needn't be re-read.
        private const val KEY_LAST_LOCAL_TOKEN = "linkedFolderLastLocalHash"
        private const val KEY_LAST_FOLDER_TOKEN = "linkedFolderLastFolderHash"
        private const val KEY_LAST_FOLDER_META = "linkedFolderLastFolderMeta"
        private const val WAL = "-wal"
        private const val SHM = "-shm"
    }
}
