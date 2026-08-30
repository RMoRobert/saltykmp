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

    /**
     * A missing timestamp should be mapped to [Instant.DISTANT_PAST] by the caller.
     *
     * [syncedModified] is `recipe.syncedModifiedDate` (SHARED-V0005): the `lastModified` this row
     * carried when the two sides last agreed about it, or null if they never have. Only meaningful on
     * LOCAL entries, and only when [plan] is called with `tracksAgreement = true`.
     */
    data class Entry(val id: String, val lastModified: Instant, val syncedModified: Instant? = null)

    data class Plan(
        val toUpload: List<String> = emptyList(),       // local is new or newer → push to server
        val toDownload: List<String> = emptyList(),     // server is new or newer → pull to local
        val toDeleteLocally: List<String> = emptyList(),  // existed before lastSync, gone on server
        val toDeleteOnServer: List<String> = emptyList(), // existed before lastSync, gone locally
    )

    /**
     * [tracksAgreement] replaces one decision — what to do with a row that exists here and not on the
     * server — with a recorded fact instead of a guess about clocks. Pass it when the local entries
     * carry [Entry.syncedModified] (i.e. the library has SHARED-V0005's `recipe.syncedModifiedDate`).
     *
     * - null stamp → never been to the server → it is NEW HERE → upload.
     * - stamp set, row unchanged since → the server had exactly this row and deleted it → delete here.
     * - stamp set, row CHANGED since → a real delete-vs-edit conflict, resolved the way Salty resolves
     *   it everywhere: the edit wins → upload.
     *
     * No clock is consulted, which matters because the timestamp rule is wrong in two real cases: when
     * this device's clock and the server's disagree, and for any row whose `lastModified` is genuinely
     * old but has never been anywhere — a recipe imported from a years-old export read as "existed
     * before the last sync, gone on the server" and was destroyed.
     *
     * Mirror: Swift's `RecipeSyncReconciler.plan(tracksAgreement:)` and Salty.NET's
     * `SyncReconciler.CreatePlan(tracksAgreement:)`.
     */
    /**
     * SYNC-016. Whether deletions inferred from a side's absence may be applied, given how many rows
     * that side actually returned.
     *
     * The plan says what the timestamps and stamps *imply*; this says whether the evidence behind an
     * inferred deletion is worth acting on. It is not when the side the absence was observed on came
     * back completely empty — a server list that is `[]` because a proxy answered, or a library that is
     * empty because it was restored from an older backup or opened mid-download. "Everything is
     * missing" is far more often a bad fetch than a real mass deletion.
     *
     * Lives here, on the pure type, so both directions and all three clients consult one predicate: the
     * local half existed for a long time while the server half did not, which is the kind of drift a
     * shared function prevents and six separate call sites do not. Applying it stays the caller's job —
     * see SYNC-016 — and the caller is what must also report the refusal.
     *
     * Emptiness, not proportion: one row left and inference proceeds normally.
     *
     * Mirror: Swift's `RecipeSyncReconciler.allowsDeletions` and Salty.NET's
     * `SyncReconciler.AllowsDeletions`. Pinned by corpus GUARD-001..GUARD-006.
     */
    fun allowsDeletions(sideCount: Int, pendingDeletions: Int): Boolean =
        !(sideCount == 0 && pendingDeletions > 0)

    fun plan(
        local: List<Entry>,
        server: List<Entry>,
        isFirstSync: Boolean,
        lastSyncDate: Instant?,
        tracksAgreement: Boolean = false,
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
            } else if (tracksAgreement) {
                // Still clock-free: both values were written by THIS device (the stamp is a copy of
                // lastModified taken at agreement time; LocalStore normalizes both to epoch millis).
                //
                //   null stamp          → never been to the server → new here → upload.
                //   row changed since   → edited here after the server last had it, and the server has
                //                         since deleted it. Delete-vs-edit is a genuine conflict, and
                //                         everywhere in Salty an edit beats a delete → upload,
                //                         resurrecting it WITH the edit.
                //   row unchanged since → the server had exactly this row and deleted it → delete here.
                //
                // `!=` rather than `>` on purpose: any difference means "changed since agreement", so a
                // clock that ran backwards errs toward upload — the non-destructive direction.
                if (l.syncedModified == null || l.lastModified != l.syncedModified) {
                    toUpload += l.id
                } else {
                    toDeleteLocally += l.id
                }
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
