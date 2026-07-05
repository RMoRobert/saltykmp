package com.enuvro.saltykmp.auth

import java.util.concurrent.ConcurrentHashMap

/**
 * Global per-username lockout that backstops the per-IP [LoginThrottle] against DISTRIBUTED brute force.
 * An attacker spreading guesses for one account across thousands of IPs never trips the per-(IP, username)
 * throttle — each IP only accrues a failure or two — but every failure from any source counts here. After
 * [maxFailures] total failures a username is locked for [lockMs] regardless of source IP; a successful login
 * clears it. The lock check runs before the (expensive) bcrypt verify, so a locked account's flood costs
 * almost nothing to reject — bounding CPU as well as memory under attack.
 *
 * Deliberately username-only (no IP): catching attempts from arbitrary IPs is the whole point. A locked-out
 * legitimate user therefore waits out the [lockMs] window — set [maxFailures] high enough that ordinary
 * typos never reach it. In-memory, so the state is also cleared by restarting the server (an operator with
 * host access can recover a locked account that way, without any remote endpoint to abuse).
 *
 * Memory is bounded exactly like [LoginThrottle]: the key is an attacker-controlled username, so a flood of
 * DISTINCT usernames is capped at [maxEntries] (excess unlocked entries are shed while active lockouts are
 * kept), idle entries are pruned once past the retention window, and the username is length-capped.
 */
class AccountLockout(
    private val maxFailures: Int = MAX_FAILURES,
    private val lockMs: Long = LOCK_MS,
    private val maxEntries: Int = MAX_ENTRIES,
    private val pruneThreshold: Int = PRUNE_THRESHOLD,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private data class Entry(val failures: Int, val lockedUntil: Long, val lastSeen: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    private fun key(username: String) = username.lowercase().take(MAX_USERNAME_KEY_LEN)

    /** Seconds until the account may be tried again, or null if it isn't currently locked. */
    fun retryAfterSeconds(username: String): Long? {
        val e = entries[key(username)] ?: return null
        val remaining = e.lockedUntil - now()
        return if (remaining > 0) (remaining + 999) / 1000 else null
    }

    fun recordFailure(username: String) {
        val ts = now()
        entries.compute(key(username)) { _, existing ->
            val failures = (existing?.failures ?: 0) + 1
            val lockedUntil = if (failures >= maxFailures) ts + lockMs else 0L
            Entry(failures, lockedUntil, ts)
        }
        pruneIfNeeded()
    }

    fun recordSuccess(username: String) {
        entries.remove(key(username))
    }

    /** Number of tracked accounts. Visible for tests asserting memory stays bounded under abuse. */
    internal fun size(): Int = entries.size

    private fun pruneIfNeeded() {
        if (entries.size < pruneThreshold) return
        val cutoff = now()
        // Drop accounts that are neither currently locked nor recently active.
        entries.entries.removeIf { it.value.lockedUntil <= cutoff && cutoff - it.value.lastSeen > lockMs }
        // Absolute ceiling: a flood of distinct usernames sheds everything not actively locked (cheap for an
        // attacker to rebuild, so no security loss), preserving the lockouts that matter. O(n), no sort.
        if (entries.size > maxEntries) {
            val stillNow = now()
            entries.entries.removeIf { it.value.lockedUntil <= stillNow }
        }
    }

    companion object {
        /** Total failures (across all IPs) before a username locks. High so a real user's typos never reach it. */
        const val MAX_FAILURES = 50
        /** Lockout duration once tripped. Auto-expires; also cleared by a server restart. */
        const val LOCK_MS = 60 * 60 * 1000L // 1 hour
        private const val PRUNE_THRESHOLD = 1024
        /** Hard cap on tracked accounts, so a distinct-username flood can't exhaust memory. */
        private const val MAX_ENTRIES = 10_000
        /** Upper bound on the username key (DB usernames are ≤255). */
        private const val MAX_USERNAME_KEY_LEN = 256
    }
}
