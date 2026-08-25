package com.enuvro.saltykmp.sync

/**
 * What a running sync is doing right now, published by [SyncService] for the progress line in Settings.
 *
 * Counts are real work items taken from the reconciler's plan, never an estimate: once the manifest is in,
 * the exact number of recipes to move and images to reconcile is known. Deliberately NOT a time estimate —
 * image sizes and link speed vary far too much for an ETA to be anything but a number that walks backwards.
 *
 * Phases whose work is a single request carry [total] = 0 and render as a bare label.
 */
data class SyncProgress(val phase: SyncPhase, val done: Int = 0, val total: Int = 0) {
    /** e.g. "Uploading recipes (3 of 17)", or just "Checking what changed" when there is nothing to count. */
    fun describe(): String = if (total > 0) "${phase.label} ($done of $total)" else phase.label

    /** 0f..1f for a determinate bar, or null when this phase has no countable work. */
    fun fraction(): Float? = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
}

/**
 * Roughly the order [SyncService.syncNow] works through, though nothing should assume it: a sync with
 * nothing to upload never reports [UPLOADING_RECIPES] at all, and the one-way overwrites reuse the subset
 * that fits them. The UI shows one phase at a time and simply renders whatever arrives last.
 */
enum class SyncPhase(val label: String) {
    CONNECTING("Connecting"),
    LIBRARY("Syncing courses, categories and tags"),
    SHOPPING_LISTS("Syncing shopping lists"),
    PLANNING("Checking what changed"),
    UPLOADING_RECIPES("Uploading recipes"),
    DOWNLOADING_RECIPES("Downloading recipes"),
    APPLYING_DELETIONS("Applying deletions"),
    IMAGES("Syncing images"),
    FINISHING("Finishing up"),
}
