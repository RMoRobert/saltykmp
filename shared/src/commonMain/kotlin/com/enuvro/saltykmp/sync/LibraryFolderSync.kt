package com.enuvro.saltykmp.sync

/**
 * State of the recipe database file on one side of a linked-folder sync — either the app's local copy or
 * the copy in the user's linked external folder (OneDrive/Nextcloud/etc., via SAF on Android). [token]
 * is what we compare to the last-synced marker to tell whether that side changed.
 */
data class LibraryFileState(val exists: Boolean, val lastModifiedMillis: Long = 0L, val size: Long = 0L) {
    /** Stable per-version marker; null when the file is absent. Compared against the stored last-synced token. */
    val token: String? get() = if (exists) "$lastModifiedMillis:$size" else null
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
 * The two sides are tracked with SEPARATE last-synced tokens. Copying a file rewrites the destination, so
 * the two copies never share an mtime — a single shared token would always read as "one side changed" and
 * spuriously CONFLICT on every launch.
 *
 * Conflict (prompt) cases, per the user's "warn if something seems off":
 *  - both sides changed since the last sync (independent edits), OR
 *  - the two sides exist but were never reconciled (no last-synced token — can't know which is authoritative), OR
 *  - the change-direction contradicts the timestamps (e.g. local changed so we'd copy OUT, yet the folder's
 *    file is actually NEWER — something synced underneath us; overwriting it could clobber newer data).
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

        // Each side is compared to ITS OWN last-synced token. Never-reconciled (tokens null) reads as
        // changed on both sides → CONFLICT, which is what we want (can't know which copy is authoritative).
        val localChanged = local.token != lastLocalToken
        val folderChanged = folder.token != lastFolderToken
        return when {
            !localChanged && !folderChanged -> LibrarySyncAction.NONE
            localChanged && folderChanged -> LibrarySyncAction.CONFLICT
            localChanged ->
                // We'd copy local → folder, but bail if the folder copy is somehow newer than ours.
                if (folder.lastModifiedMillis > local.lastModifiedMillis) LibrarySyncAction.CONFLICT
                else LibrarySyncAction.COPY_OUT
            else ->
                // folderChanged: copy folder → local, but bail if our copy is somehow newer than the folder's.
                if (local.lastModifiedMillis > folder.lastModifiedMillis) LibrarySyncAction.CONFLICT
                else LibrarySyncAction.COPY_IN
        }
    }
}
