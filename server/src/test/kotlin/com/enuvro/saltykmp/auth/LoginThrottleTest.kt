package com.enuvro.saltykmp.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginThrottleTest {

    /** Manually-advanced clock so tests don't depend on wall time. */
    private class Clock(var t: Long = 1_000L) {
        fun now(): Long = t
    }

    @Test
    fun locksOutAfterMaxFailuresThenExpires() {
        val clock = Clock()
        val throttle = LoginThrottle(maxFailures = 3, lockMs = 1000L, now = clock::now)

        repeat(2) { throttle.recordFailure("1.2.3.4", "alice") }
        assertNull(throttle.retryAfterSeconds("1.2.3.4", "alice"), "not locked before the threshold")

        throttle.recordFailure("1.2.3.4", "alice") // 3rd failure trips the lock
        assertEquals(1L, throttle.retryAfterSeconds("1.2.3.4", "alice"), "1000ms lock rounds up to 1s")

        clock.t += 1000L
        assertNull(throttle.retryAfterSeconds("1.2.3.4", "alice"), "lock expires once the window passes")
    }

    @Test
    fun successClearsTheCounter() {
        val clock = Clock()
        val throttle = LoginThrottle(maxFailures = 3, lockMs = 1000L, now = clock::now)

        repeat(2) { throttle.recordFailure("1.2.3.4", "alice") }
        throttle.recordSuccess("1.2.3.4", "alice")
        throttle.recordFailure("1.2.3.4", "alice") // counter restarts at 1
        assertNull(throttle.retryAfterSeconds("1.2.3.4", "alice"))
    }

    @Test
    fun lockoutIsScopedToIpAndUsername() {
        val clock = Clock()
        val throttle = LoginThrottle(maxFailures = 2, lockMs = 1000L, now = clock::now)

        repeat(2) { throttle.recordFailure("1.1.1.1", "alice") }
        assertTrue(throttle.retryAfterSeconds("1.1.1.1", "alice") != null)
        // Same username from a different IP is unaffected (no cross-IP lockout DoS)...
        assertNull(throttle.retryAfterSeconds("2.2.2.2", "alice"))
        // ...and a different username from the same IP is unaffected too.
        assertNull(throttle.retryAfterSeconds("1.1.1.1", "bob"))
    }

    @Test
    fun distinctUsernameFloodStaysBounded() {
        val clock = Clock()
        val throttle = LoginThrottle(
            maxFailures = 5, lockMs = 1000L, maxEntries = 50, pruneThreshold = 10, now = clock::now,
        )
        // Attacker-controlled usernames, each a single failure (never locked). Without the hard cap these
        // would accumulate forever; the ceiling must keep the map bounded.
        repeat(5000) { i -> throttle.recordFailure("9.9.9.9", "user$i") }
        assertTrue(throttle.size() <= 50, "hard cap keeps the map bounded, was ${throttle.size()}")
    }

    @Test
    fun activeLockoutSurvivesHardCapEviction() {
        val clock = Clock()
        val throttle = LoginThrottle(
            maxFailures = 2, lockMs = 60_000L, maxEntries = 50, pruneThreshold = 10, now = clock::now,
        )
        repeat(2) { throttle.recordFailure("1.1.1.1", "victim") } // lock the victim
        assertTrue(throttle.retryAfterSeconds("1.1.1.1", "victim") != null)

        repeat(5000) { i -> throttle.recordFailure("9.9.9.9", "user$i") } // flood to trip the cap
        assertTrue(
            throttle.retryAfterSeconds("1.1.1.1", "victim") != null,
            "eviction sheds unlocked counters but preserves active lockouts",
        )
    }

    @Test
    fun staleIdleEntriesArePrunedWhenTimeAdvances() {
        val clock = Clock()
        val throttle = LoginThrottle(
            maxFailures = 5, lockMs = 1000L, maxEntries = 100_000, pruneThreshold = 10, now = clock::now,
        )
        repeat(20) { i -> throttle.recordFailure("9.9.9.9", "user$i") } // 20 idle (unlocked) entries
        clock.t += 5000L // advance well past the retention window
        throttle.recordFailure("9.9.9.9", "trigger") // triggers a prune pass
        assertTrue(throttle.size() <= 2, "stale idle entries are swept, was ${throttle.size()}")
    }
}
