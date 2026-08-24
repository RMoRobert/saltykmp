package com.enuvro.saltykmp.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFolderSyncTest {

    private fun absent() = LibraryFileState(exists = false)

    /** Present file whose content fingerprint is [hash]; mtime/size are irrelevant to the decision. */
    private fun present(hash: String, mtime: Long = 0, size: Long = 0) =
        LibraryFileState(exists = true, lastModifiedMillis = mtime, size = size, contentHash = hash)

    private fun decide(
        local: LibraryFileState,
        folder: LibraryFileState,
        lastLocal: String?,
        lastFolder: String?,
    ) = LibraryFolderSync.decide(local, folder, lastLocal, lastFolder)

    @Test fun nothingAnywhere_isNoOp() {
        assertEquals(LibrarySyncAction.NONE, decide(absent(), absent(), null, null))
    }

    @Test fun localOnly_seedsFolder() {
        assertEquals(LibrarySyncAction.INITIAL_COPY_OUT, decide(present("a"), absent(), null, null))
    }

    @Test fun folderOnly_pullsToLocal() {
        assertEquals(LibrarySyncAction.COPY_IN, decide(absent(), present("a"), null, null))
    }

    @Test fun unchangedBothSides_isNoOp() {
        // Normal every-launch case: each side still carries the hash recorded at the last sync. The two
        // copies have DIFFERENT mtimes/sizes-of-record (copying rewrites the destination; cloud providers
        // rewrite timestamps after upload) — none of that may trigger a prompt.
        assertEquals(
            LibrarySyncAction.NONE,
            decide(present("a", 100, 50), present("a", 222_000, 50), lastLocal = "a", lastFolder = "a"),
        )
    }

    @Test fun identicalContent_isNoOp_evenWithStaleTokens() {
        // Same bytes on both sides but the recorded tokens are stale (or missing): nothing to copy.
        assertEquals(LibrarySyncAction.NONE, decide(present("a"), present("a"), lastLocal = "x", lastFolder = "y"))
        assertEquals(LibrarySyncAction.NONE, decide(present("a"), present("a"), lastLocal = null, lastFolder = null))
    }

    @Test fun onlyLocalChanged_copiesOut() {
        // Folder still matches its token; local differs from its own → push out.
        assertEquals(
            LibrarySyncAction.COPY_OUT,
            decide(present("b"), present("a"), lastLocal = "a", lastFolder = "a"),
        )
    }

    @Test fun onlyFolderChanged_copiesIn() {
        assertEquals(
            LibrarySyncAction.COPY_IN,
            decide(present("a"), present("b"), lastLocal = "a", lastFolder = "a"),
        )
    }

    @Test fun onlyFolderChanged_copiesIn_evenIfLocalFileIsNewer() {
        // The Mac edited the bundle while offline; Nextcloud preserved its (older) mtime, so the folder
        // copy looks older than the local file we wrote at the previous pull. Content says the folder
        // changed and local didn't — trust the content, don't prompt.
        assertEquals(
            LibrarySyncAction.COPY_IN,
            decide(present("a", mtime = 500), present("b", mtime = 100), lastLocal = "a", lastFolder = "a"),
        )
    }

    @Test fun bothChangedSinceLastSync_conflict() {
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(present("b"), present("c"), lastLocal = "a", lastFolder = "a"),
        )
    }

    @Test fun bothExistDifferButNeverReconciled_conflict() {
        // Different contents and no last-synced tokens: can't tell which side is authoritative → prompt.
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(present("a"), present("b"), lastLocal = null, lastFolder = null),
        )
    }

    @Test fun metadataKey_roundsToSeconds() {
        // 1234567 ms and 1234999 ms are the same second → same key; size participates.
        assertEquals(present("a", 1_234_567, 9).metadataKey, present("a", 1_234_999, 9).metadataKey)
        assertEquals("1234:9", present("a", 1_234_567, 9).metadataKey)
        assertEquals(null, absent().metadataKey)
    }
}
