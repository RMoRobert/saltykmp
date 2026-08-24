package com.enuvro.saltykmp.sync

/**
 * State of the recipe database on one side of a linked-folder sync — either the app's local copy or the
 * copy in the user's linked external folder (Nextcloud/OneDrive/etc., via SAF on Android / the document
 * picker on iOS).
 *
 * [contentHash] is the fingerprint of the DB bytes (`.sqlite` followed by a non-empty `-wal`, see
 * `ContentHash`) and is what [token] compares against the last-synced marker. It is deliberately NOT the
 * mtime: cloud providers rewrite timestamps when an upload completes (Nextcloud stores whole seconds, and
 * the provider's `lastModified` can move between the write and the finished upload), which made an
 * mtime-based token read as "the folder changed" on the next launch and spuriously prompt for a conflict.
 *
 * [lastModifiedMillis]/[size] are kept only as a cheap pre-check ([metadataKey]): if they match what was
 * recorded alongside the last-synced hash, the folder-side hash can be reused without re-downloading the
 * file. The local side is always hashed (it's cheap, and an edit within the same second at the same size is
 * realistic for SQLite, whose file size often doesn't move on small writes).
 */
data class LibraryFileState(
    val exists: Boolean,
    val lastModifiedMillis: Long = 0L,
    val size: Long = 0L,
    val contentHash: String? = null,
) {
    /** Stable per-version marker; null when the file is absent. Compared against the stored last-synced token. */
    val token: String? get() = if (exists) contentHash else null

    /** Second-granularity mtime + size: a metadata fingerprint used to skip re-hashing an unchanged remote file. */
    val metadataKey: String? get() = if (exists) "${lastModifiedMillis / 1000}:$size" else null
}

/** What a linked-folder reconcile decided to do. [CONFLICT] means "don't touch anything; ask the user". */
enum class LibrarySyncAction { NONE, COPY_OUT, COPY_IN, INITIAL_COPY_OUT, CONFLICT }

/**
 * Pure, I/O-free decision for copy-based library sync to a user-linked folder (the only model Android can
 * support — SQLite can't run live on a SAF/cloud document; see TODO.md). Given the local DB state, the
 * linked-folder DB state, and the tokens recorded at the last successful sync, it decides which way to copy
 * — or to stop and prompt. Kept pure so every branch is unit-tested (this overwrites whole DB files, so a
 * wrong call loses recipes).
 *
 * Each side is compared to ITS OWN last-synced token (a copy rewrites the destination, so the two copies
 * can legitimately differ in metadata). Because tokens are content hashes, two sides with the SAME token
 * are byte-identical and need no action even when the stored tokens are stale or missing.
 *
 * Conflict (prompt) cases, per the user's "warn if something seems off":
 *  - both sides changed since the last sync (independent edits), OR
 *  - the two sides differ but were never reconciled (no last-synced tokens — can't know which is authoritative).
 */
object LibraryFolderSync {
    fun decide(
        local: LibraryFileState,
        folder: LibraryFileState,
        lastLocalToken: String?,
        lastFolderToken: String?,
    ): LibrarySyncAction {
        if (!local.exists && !folder.exists) return LibrarySyncAction.NONE
        if (local.exists && !folder.exists) return LibrarySyncAction.INITIAL_COPY_OUT
        if (!local.exists && folder.exists) return LibrarySyncAction.COPY_IN

        // Identical bytes on both sides: nothing to do regardless of what (or whether) we recorded last time.
        if (local.token != null && local.token == folder.token) return LibrarySyncAction.NONE

        // Never-reconciled (tokens null) reads as changed on both sides → CONFLICT, which is what we want
        // (the contents differ and we can't know which copy is authoritative).
        val localChanged = local.token != lastLocalToken
        val folderChanged = folder.token != lastFolderToken
        return when {
            !localChanged && !folderChanged -> LibrarySyncAction.NONE
            localChanged && folderChanged -> LibrarySyncAction.CONFLICT
            localChanged -> LibrarySyncAction.COPY_OUT
            else -> LibrarySyncAction.COPY_IN
        }
    }
}
