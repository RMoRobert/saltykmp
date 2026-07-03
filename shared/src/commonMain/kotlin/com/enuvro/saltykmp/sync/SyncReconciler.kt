package com.enuvro.saltykmp.sync

import kotlin.time.Instant

/**
 * Pure, I/O-free sync diff — a 1:1 port of the Swift `RecipeSyncReconciler`. Given the local items and
 * the server's COMPLETE manifest (id + lastModified for every server item), it decides what to upload,
 * download, and delete on each side. This is the data-loss-critical logic, so it stays pure and is
 * exhaustively unit-tested (see SyncReconcilerTests). Reusable for recipes and the library tables alike.
 *
 * IMPORTANT: `server` must be the COMPLETE manifest, not a `modifiedSince` delta — deletions are
 * detected by absence; running this against a partial list would treat unchanged items as deleted.
 */
object SyncReconciler {

    /** A missing timestamp should be mapped to [Instant.DISTANT_PAST] by the caller. */
    data class Entry(val id: String, val lastModified: Instant)

    data class Plan(
        val toUpload: List<String> = emptyList(),       // local is new or newer → push to server
        val toDownload: List<String> = emptyList(),     // server is new or newer → pull to local
        val toDeleteLocally: List<String> = emptyList(),  // existed before lastSync, gone on server
        val toDeleteOnServer: List<String> = emptyList(), // existed before lastSync, gone locally
    )

    fun plan(
        local: List<Entry>,
        server: List<Entry>,
        isFirstSync: Boolean,
        lastSyncDate: Instant?,
    ): Plan {
        val serverById = server.associateBy { it.id }
        val localIds = local.mapTo(mutableSetOf()) { it.id }

        val toUpload = mutableListOf<String>()
        val toDownload = mutableListOf<String>()
        val toDeleteLocally = mutableListOf<String>()
        val toDeleteOnServer = mutableListOf<String>()

        for (l in local) {
            val s = serverById[l.id]
            if (s != null) {
                if (l.lastModified > s.lastModified) {
                    toUpload += l.id
                } else if (s.lastModified > l.lastModified) {
                    toDownload += l.id
                }
                // equal → already in sync
            } else if (isFirstSync) {
                toUpload += l.id
            } else if (lastSyncDate != null) {
                if (l.lastModified > lastSyncDate) toUpload += l.id else toDeleteLocally += l.id
            } else {
                toUpload += l.id
            }
        }

        for (s in server) {
            if (s.id in localIds) continue
            if (isFirstSync) {
                toDownload += s.id
            } else if (lastSyncDate != null) {
                if (s.lastModified > lastSyncDate) toDownload += s.id else toDeleteOnServer += s.id
            } else {
                toDownload += s.id
            }
        }

        return Plan(toUpload, toDownload, toDeleteLocally, toDeleteOnServer)
    }
}
