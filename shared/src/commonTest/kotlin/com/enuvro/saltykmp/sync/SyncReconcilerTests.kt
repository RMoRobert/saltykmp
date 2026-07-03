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
}
