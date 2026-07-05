package com.enuvro.saltykmp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountLockoutTest {

    private class Clock(var t: Long = 1_000L) {
        fun now(): Long = t
    }

    @Test
    fun locksAfterMaxFailuresThenAutoExpires() {
        val clock = Clock()
        val lock = AccountLockout(maxFailures = 3, lockMs = 1000L, now = clock::now)

        repeat(2) { lock.recordFailure("alice") }
        assertNull(lock.retryAfterSeconds("alice"), "not locked before the threshold")

        lock.recordFailure("alice") // 3rd failure locks
        assertEquals(1L, lock.retryAfterSeconds("alice"))

        clock.t += 1000L
        assertNull(lock.retryAfterSeconds("alice"), "lock auto-expires once the window passes")
    }

    @Test
    fun successClearsTheCounter() {
        val clock = Clock()
        val lock = AccountLockout(maxFailures = 3, lockMs = 1000L, now = clock::now)

        repeat(2) { lock.recordFailure("alice") }
        lock.recordSuccess("alice")
        lock.recordFailure("alice") // counter restarts at 1
        assertNull(lock.retryAfterSeconds("alice"))
    }

    @Test
    fun countsAcrossUsernameCase() {
        val clock = Clock()
        val lock = AccountLockout(maxFailures = 2, lockMs = 1000L, now = clock::now)
        // Different-cased attempts target the same account, so they share one counter.
        lock.recordFailure("Alice")
        lock.recordFailure("alice")
        assertTrue(lock.retryAfterSeconds("ALICE") != null)
    }

    @Test
    fun distinctUsernameFloodStaysBounded() {
        val clock = Clock()
        val lock = AccountLockout(
            maxFailures = 5, lockMs = 1000L, maxEntries = 50, pruneThreshold = 10, now = clock::now,
        )
        repeat(5000) { i -> lock.recordFailure("user$i") }
        assertTrue(lock.size() <= 50, "hard cap keeps the map bounded, was ${lock.size()}")
    }

    @Test
    fun activeLockSurvivesHardCapEviction() {
        val clock = Clock()
        val lock = AccountLockout(
            maxFailures = 2, lockMs = 60_000L, maxEntries = 50, pruneThreshold = 10, now = clock::now,
        )
        repeat(2) { lock.recordFailure("victim") } // lock the victim
        assertTrue(lock.retryAfterSeconds("victim") != null)

        repeat(5000) { i -> lock.recordFailure("user$i") } // flood to trip the cap
        assertTrue(lock.retryAfterSeconds("victim") != null, "active lockouts are preserved through eviction")
    }
}
