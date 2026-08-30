package com.enuvro.saltykmp.sync

import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Port of the Swift RecipeSyncReconcilerTests — pins every branch of the data-loss-critical diff. */
class SyncReconcilerTests {

    private fun t(seconds: Long): Instant = Instant.fromEpochSeconds(seconds)
    private fun e(id: String, seconds: Long) = SyncReconciler.Entry(id, t(seconds))

    @Test
    fun firstSyncUploadsLocalOnlyAndDownloadsServerOnly() {
        val plan = SyncReconciler.plan(listOf(e("a", 100)), listOf(e("b", 100)), isFirstSync = true, lastSyncDate = null)
        assertEquals(listOf("a"), plan.toUpload)
        assertEquals(listOf("b"), plan.toDownload)
        assertTrue(plan.toDeleteLocally.isEmpty() && plan.toDeleteOnServer.isEmpty())
    }

    @Test
    fun bothSidesLocalNewerUploads() {
        val plan = SyncReconciler.plan(listOf(e("a", 200)), listOf(e("a", 100)), false, t(150))
        assertEquals(listOf("a"), plan.toUpload)
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test
    fun bothSidesServerNewerDownloads() {
        val plan = SyncReconciler.plan(listOf(e("a", 100)), listOf(e("a", 200)), false, t(150))
        assertEquals(listOf("a"), plan.toDownload)
        assertTrue(plan.toUpload.isEmpty())
    }

    @Test
    fun bothSidesEqualIsNoOp() {
        val plan = SyncReconciler.plan(listOf(e("a", 100)), listOf(e("a", 100)), false, t(150))
        assertEquals(SyncReconciler.Plan(), plan)
    }

    @Test
    fun onlyLocalNewerThanLastSyncUploads() {
        val plan = SyncReconciler.plan(listOf(e("a", 200)), emptyList(), false, t(150))
        assertEquals(listOf("a"), plan.toUpload)
        assertTrue(plan.toDeleteLocally.isEmpty())
    }

    @Test
    fun onlyLocalOlderThanLastSyncIsDeletedOnServer() {
        val plan = SyncReconciler.plan(listOf(e("a", 100)), emptyList(), false, t(150))
        assertEquals(listOf("a"), plan.toDeleteLocally)
        assertTrue(plan.toUpload.isEmpty())
    }

    @Test
    fun onlyServerNewerThanLastSyncDownloads() {
        val plan = SyncReconciler.plan(emptyList(), listOf(e("b", 200)), false, t(150))
        assertEquals(listOf("b"), plan.toDownload)
        assertTrue(plan.toDeleteOnServer.isEmpty())
    }

    @Test
    fun onlyServerOlderThanLastSyncIsDeletedLocally_soDeleteOnServer() {
        val plan = SyncReconciler.plan(emptyList(), listOf(e("b", 100)), false, t(150))
        assertEquals(listOf("b"), plan.toDeleteOnServer)
        assertTrue(plan.toDownload.isEmpty())
    }

    @Test
    fun serverMissingTimestampMappedToDistantPastIsTreatedAsOld() {
        val plan = SyncReconciler.plan(
            emptyList(), listOf(SyncReconciler.Entry("b", Instant.DISTANT_PAST)), false, t(150),
        )
        assertEquals(listOf("b"), plan.toDeleteOnServer)
    }

    @Test
    fun nilLastSyncNotFirstSyncTreatsOrphansAsNewNeverDeletes() {
        val plan = SyncReconciler.plan(listOf(e("a", 100)), listOf(e("b", 100)), false, null)
        assertEquals(listOf("a"), plan.toUpload)
        assertEquals(listOf("b"), plan.toDownload)
        assertTrue(plan.toDeleteLocally.isEmpty() && plan.toDeleteOnServer.isEmpty())
    }

    @Test
    fun mixedBatchRoutesEachItemCorrectly() {
        val plan = SyncReconciler.plan(
            local = listOf(e("same", 100), e("localNewer", 200), e("newLocal", 300), e("goneOnServer", 50)),
            server = listOf(e("same", 100), e("localNewer", 100), e("serverNewer", 300), e("goneLocally", 40)),
            isFirstSync = false,
            lastSyncDate = t(150),
        )
        assertEquals(setOf("localNewer", "newLocal"), plan.toUpload.toSet())
        assertEquals(setOf("serverNewer"), plan.toDownload.toSet())
        assertEquals(setOf("goneOnServer"), plan.toDeleteLocally.toSet())
        assertEquals(setOf("goneLocally"), plan.toDeleteOnServer.toSet())
    }

    // ---- SHARED-V0005: agreement bookkeeping replaces the local-only guess ----

    /** A local entry that HAS been agreed with the server. */
    private fun agreed(id: String, seconds: Long, synced: Long? = null) =
        SyncReconciler.Entry(id, t(seconds), t(synced ?: seconds))

    private fun tracked(
        local: List<SyncReconciler.Entry>,
        server: List<SyncReconciler.Entry>,
        lastSyncDate: Instant?,
    ) = SyncReconciler.plan(local, server, isFirstSync = false, lastSyncDate = lastSyncDate, tracksAgreement = true)

    /**
     * The bug the column exists for. A recipe imported from a years-old export carries an ancient
     * lastModified, so the watermark rule reads it as "existed before the last sync, gone on the
     * server" and DELETES it. It has simply never been to the server.
     */
    @Test
    fun anOldRowThatWasNeverAgreedIsUploadedNotDeleted() {
        val plan = tracked(listOf(e("imported", 1)), emptyList(), t(1_000_000))
        assertEquals(listOf("imported"), plan.toUpload)
        assertTrue(plan.toDeleteLocally.isEmpty())
    }

    /** The other half: a row that WAS agreed and is now absent really was deleted there. */
    @Test
    fun aRowThatWasAgreedAndIsNowAbsentIsDeletedLocally() {
        val plan = tracked(listOf(agreed("gone", 100)), emptyList(), t(1_000_000))
        assertEquals(listOf("gone"), plan.toDeleteLocally)
        assertTrue(plan.toUpload.isEmpty())
    }

    /**
     * Delete-vs-edit is a genuine conflict, resolved the way Salty resolves it everywhere: the edit
     * wins. A row agreed at 100 and edited to 200 while another device deleted it comes back WITH the
     * edit rather than being destroyed.
     */
    @Test
    fun aRowEditedSinceTheAgreementIsUploadedEvenThoughTheServerDeletedIt() {
        val plan = tracked(listOf(agreed("edited", 200, synced = 100)), emptyList(), t(1_000_000))
        assertEquals(listOf("edited"), plan.toUpload)
        assertTrue(plan.toDeleteLocally.isEmpty())
    }

    /** Any difference counts as an edit — a clock that ran backwards errs toward upload. */
    @Test
    fun aStampThatDisagreesInEitherDirectionReadsAsEdited() {
        assertEquals(listOf("back"), tracked(listOf(agreed("back", 50, synced = 100)), emptyList(), null).toUpload)
    }

    /** No clock is consulted, so the answer must not move when the watermark does. */
    @Test
    fun theOutcomeDoesNotDependOnTheWatermark() {
        for (watermark in listOf(null, t(0), t(50), t(100), t(999_999_999))) {
            assertEquals(listOf("new"), tracked(listOf(e("new", 100)), emptyList(), watermark).toUpload)
            assertEquals(listOf("old"), tracked(listOf(agreed("old", 100)), emptyList(), watermark).toDeleteLocally)
        }
    }

    /**
     * Rows on both sides are unaffected: newest-wins is genuine conflict resolution, not an inference,
     * and this change deliberately leaves it alone.
     */
    @Test
    fun rowsOnBothSidesStillResolveByNewestWins() {
        val plan = tracked(
            listOf(agreed("local-newer", 200, synced = 100), agreed("server-newer", 100)),
            listOf(e("local-newer", 100), e("server-newer", 200)),
            t(150),
        )
        assertEquals(listOf("local-newer"), plan.toUpload)
        assertEquals(listOf("server-newer"), plan.toDownload)
        assertTrue(plan.toDeleteLocally.isEmpty())
    }

    /** A first sync still uploads everything: deletion inference is off entirely. */
    @Test
    fun aFirstSyncIsUnaffected() {
        val plan = SyncReconciler.plan(
            listOf(agreed("a", 100), e("b", 100)), emptyList(),
            isFirstSync = true, lastSyncDate = null, tracksAgreement = true,
        )
        assertEquals(setOf("a", "b"), plan.toUpload.toSet())
        assertTrue(plan.toDeleteLocally.isEmpty())
    }

    /** Collections without the bookkeeping — courses, categories, tags — keep the old rule exactly. */
    @Test
    fun collectionsWithoutBookkeepingKeepTheOldRule() {
        assertEquals(listOf("old"), SyncReconciler.plan(listOf(e("old", 50)), emptyList(), false, t(150)).toDeleteLocally)
        assertEquals(listOf("new"), SyncReconciler.plan(listOf(e("new", 200)), emptyList(), false, t(150)).toUpload)
    }
}
