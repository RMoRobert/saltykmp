package com.enuvro.saltykmp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Debounced automatic background sync — the Kotlin counterpart of the Swift app's auto-sync.
 *
 * After any local change [notifyChange] is called; the manager waits a quiet period ([DEBOUNCE]) so a burst
 * of edits collapses into a single sync, then runs one. Transient server failures are tolerated silently —
 * only after [FAILURES_BEFORE_BANNER] consecutive failures does [failing] flip true, which the UI surfaces as
 * a dismissible banner offering to close or pause auto-sync for a day. Off by default (gated entirely on
 * [SettingsState.autoSyncEnabled]); while disabled or paused, change notifications are ignored.
 */
@OptIn(FlowPreview::class, ExperimentalTime::class)
class AutoSyncManager(
    private val settings: SettingsState,
    scope: CoroutineScope,
    private val sync: suspend () -> Unit,
) {
    private val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)

    private val _failing = MutableStateFlow(false)
    /** True once several syncs in a row have failed; the UI shows a dismissible banner while set. */
    val failing: StateFlow<Boolean> = _failing.asStateFlow()

    private var consecutiveFailures = 0

    init {
        scope.launch {
            changes.debounce(DEBOUNCE).collect { runSync() }
        }
    }

    /** Signal that the local library changed (recipe/organizer add, edit, or delete). Safe to call anytime. */
    fun notifyChange() {
        if (settings.autoSyncEnabled) changes.tryEmit(Unit)
    }

    private suspend fun runSync() {
        if (!settings.autoSyncEnabled || isPaused()) return
        // Nothing to sync against until the server connection has been configured.
        if (settings.serverUrl.isBlank() || settings.username.isBlank()) return
        try {
            sync()
            consecutiveFailures = 0
            _failing.value = false
        } catch (e: CancellationException) {
            throw e // never swallow coroutine cancellation
        } catch (e: Throwable) {
            consecutiveFailures++
            if (consecutiveFailures >= FAILURES_BEFORE_BANNER) _failing.value = true
        }
    }

    /** User dismissed the failure banner; reset the streak so it reappears only after fresh failures. */
    fun dismissBanner() {
        _failing.value = false
        consecutiveFailures = 0
    }

    /** Pause auto-sync for a day (persisted) and hide the banner. */
    fun pauseForOneDay() {
        settings.autoSyncPausedUntil = now() + PAUSE.inWholeMilliseconds
        dismissBanner()
    }

    private fun isPaused(): Boolean = now() < settings.autoSyncPausedUntil

    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    companion object {
        /** Quiet period after the last change before syncing ("a minute or two"). */
        private val DEBOUNCE: Duration = 90.seconds
        /** Consecutive failures tolerated silently before the banner appears (the first 1–2 are ignored). */
        private const val FAILURES_BEFORE_BANNER = 3
        private val PAUSE: Duration = 24.hours
    }
}
