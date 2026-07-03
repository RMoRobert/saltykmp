package com.enuvro.saltykmp.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression: local timestamps may carry nanoseconds (Instant.toString()) while the server round-trips
 * at millisecond precision (`...SSS'Z'`). Without normalization the reconciler saw local as perpetually
 * newer and re-uploaded every recipe/vocab item on every sync. [LocalStore.parseOrPast] truncates to ms.
 */
class SyncDatePrecisionTests {

    @Test
    fun parseTruncatesSubMillisecondPrecision() {
        val nanos = LocalStore.parseOrPast("2026-06-20T10:00:00.123456789Z")
        val millis = LocalStore.parseOrPast("2026-06-20T10:00:00.123Z")
        assertEquals(millis, nanos, "sub-millisecond digits must not affect the compared instant")
    }

    @Test
    fun nanosecondLocalDoesNotReuploadAgainstMillisecondServer() {
        // Local kept nanoseconds; server echoed the millisecond-truncated value of the SAME instant.
        val local = listOf(SyncReconciler.Entry("r1", LocalStore.parseOrPast("2026-06-20T10:00:00.123456789Z")))
        val server = listOf(SyncReconciler.Entry("r1", LocalStore.parseOrPast("2026-06-20T10:00:00.123Z")))
        val plan = SyncReconciler.plan(local, server, isFirstSync = false, lastSyncDate = null)
        assertTrue(plan.toUpload.isEmpty(), "in-sync item must not re-upload")
        assertTrue(plan.toDownload.isEmpty(), "in-sync item must not re-download")
    }
}
