package com.enuvro.saltykmp.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryFolderSyncTest {

    private fun state(exists: Boolean, mtime: Long = 0, size: Long = 0) =
        LibraryFileState(exists, mtime, size)

    private fun decide(
        local: LibraryFileState,
        folder: LibraryFileState,
        lastLocal: String?,
        lastFolder: String?,
    ) = LibraryFolderSync.decide(local, folder, lastLocal, lastFolder)

    @Test fun nothingAnywhere_isNoOp() {
        assertEquals(LibrarySyncAction.NONE, decide(state(false), state(false), null, null))
    }

    @Test fun localOnly_seedsFolder() {
        assertEquals(LibrarySyncAction.INITIAL_COPY_OUT, decide(state(true, 10, 5), state(false), null, null))
    }

    @Test fun folderOnly_pullsToLocal() {
        assertEquals(LibrarySyncAction.COPY_IN, decide(state(false), state(true, 10, 5), null, null))
    }

    @Test fun unchangedBothSides_isNoOp() {
        // The two files have DIFFERENT mtimes (copying rewrites the destination) but each matches its own
        // recorded token → nothing changed. This is the normal every-launch case that must NOT prompt.
        assertEquals(
            LibrarySyncAction.NONE,
            decide(state(true, 100, 50), state(true, 222, 50), lastLocal = "100:50", lastFolder = "222:50"),
        )
    }

    @Test fun onlyLocalChanged_copiesOut() {
        // Folder still matches its token; local differs from its own → push out.
        assertEquals(
            LibrarySyncAction.COPY_OUT,
            decide(state(true, 200, 60), state(true, 100, 50), lastLocal = "90:50", lastFolder = "100:50"),
        )
    }

    @Test fun onlyFolderChanged_copiesIn() {
        assertEquals(
            LibrarySyncAction.COPY_IN,
            decide(state(true, 100, 50), state(true, 200, 60), lastLocal = "100:50", lastFolder = "90:50"),
        )
    }

    @Test fun bothChangedSinceLastSync_conflict() {
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(state(true, 200, 60), state(true, 300, 70), lastLocal = "100:50", lastFolder = "110:50"),
        )
    }

    @Test fun bothExistButNeverReconciled_conflict() {
        // No last-synced tokens: can't tell which side is authoritative → prompt.
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(state(true, 100, 50), state(true, 100, 50), lastLocal = null, lastFolder = null),
        )
    }

    @Test fun wouldCopyOutButFolderIsNewer_conflict() {
        // Only LOCAL changed (folder still matches its token) → naive call is COPY_OUT. But the folder
        // file's timestamp is actually newer than ours — something updated it underneath us. Don't clobber
        // it; ask. ("thinks it should copy, but its copy is older than the folder's.")
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(state(true, 80, 70), state(true, 100, 50), lastLocal = "70:70", lastFolder = "100:50"),
        )
    }

    @Test fun wouldCopyInButLocalIsNewer_conflict() {
        // Only the FOLDER changed (local still matches its token) → naive call COPY_IN. But our local file
        // is newer than the folder's → contradiction → ask.
        assertEquals(
            LibrarySyncAction.CONFLICT,
            decide(state(true, 100, 50), state(true, 80, 70), lastLocal = "100:50", lastFolder = "70:70"),
        )
    }
}
