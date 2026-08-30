package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.LibraryDuplicateMerger
import com.enuvro.saltykmp.util.newId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Orchestrates a full bidirectional sync — the Kotlin counterpart of the Swift `SaltySyncService`.
 * Library (courses/categories/tags) syncs via full lists; recipes sync via the lightweight manifest plus
 * a paged `modifiedSince` delta, reconciled by [SyncReconciler]. Deletions are detected from the
 * COMPLETE manifest/list, never the delta.
 *
 * @param imageSink optional hook to persist a downloaded recipe image per platform (filesystem etc.).
 *   [imageDate] is the server's lastModifiedImageDate (wire form) to record alongside the saved file.
 * @param imageSource optional hook to provide a recipe image for upload from the platform.
 * @param imageConverter optional platform codec used to re-encode an image the server can't serve as-is
 *   (HEIC from a Swift-written bundle, WebP). Without one such an image is skipped rather than uploaded
 *   under a content type that lies about it — see [SyncImagePreparer].
 */
class SyncService(
    private val api: SaltyApiClient,
    private val local: LocalStore,
    private val deviceId: String,
    private val deviceName: String,
    private val imageSink: (suspend (recipeId: String, filename: String, bytes: ByteArray, imageDate: String?) -> Unit)? = null,
    private val imageSource: (suspend (recipeId: String, filename: String) -> ByteArray?)? = null,
    private val imageConverter: ImageToJpegConverter? = null,
    private val onProgress: ((SyncProgress) -> Unit)? = null,
) {
    /**
     * Publish what we are about to do, and — because a progress point is also a point where stopping is
     * safe — check for cancellation while we are here.
     *
     * The cancellation check is not redundant with the suspending network calls around it: a delta that
     * carried every recipe body downloads nothing, so [syncRecipes]'s download loop would run to the end
     * without ever yielding, leaving "Stop" dead for its duration.
     *
     * Pass [stoppable] = false for a stretch that must NOT be interrupted — see [pullEverythingFromServer],
     * which has already wiped the local library by the time it reports anything.
     */
    private suspend fun report(phase: SyncPhase, done: Int = 0, total: Int = 0, stoppable: Boolean = true) {
        if (stoppable) currentCoroutineContext().ensureActive()
        onProgress?.invoke(SyncProgress(phase, done, total))
    }

    /**
     * Uploads one image, sniffing its real format first. Returns true when bytes actually went up;
     * false when there was nothing to send or the format couldn't be made servable here.
     */
    private suspend fun pushImage(recipeId: String, filename: String, imageDate: String?): Boolean {
        val bytes = imageSource?.invoke(recipeId, filename) ?: return false
        val prepared = SyncImagePreparer.prepare(bytes, imageConverter) ?: return false
        api.uploadImage(recipeId, "$recipeId.${prepared.extension}", prepared.bytes, prepared.contentType, imageDate)
        return true
    }

    /**
     * Stopping this partway through is safe by construction: [SaltyApiClient.completeSync] is the LAST call,
     * so the server's lastSyncDate does not advance and the next run re-plans from the same cutoff and
     * redoes whatever is still outstanding. Work already applied stays applied — stopping halts the sync,
     * it does not roll it back.
     */
    suspend fun syncNow(): SyncResult {
        report(SyncPhase.CONNECTING)
        val device = api.registerDevice(deviceId, deviceName)
        val isFirstSync = device.isFirstSync
        val lastSync = LocalStore.parseOrNull(device.lastSyncDate)

        report(SyncPhase.LIBRARY)
        val classifiers = syncCourses(isFirstSync, lastSync) +
            syncCategories(isFirstSync, lastSync) +
            syncTags(isFirstSync, lastSync)
        report(SyncPhase.SHOPPING_LISTS)
        val library = classifiers + syncShoppingLists(isFirstSync, lastSync)
        val recipes = syncRecipes(isFirstSync, lastSync, device.lastSyncDate)

        // Deliberately AFTER the downloads, as Salty's step 6b is: a recipe arriving in this same sync
        // still sees both ids and keeps its membership, and the fold then re-points it.
        report(SyncPhase.FINISHING)
        val (duplicatesMerged, foldWarning) = consolidateDuplicates()

        api.completeSync(deviceId)
        return SyncResult(
            recipesUp = recipes.up, recipesDown = recipes.down,
            recipesDeleted = recipes.deletedLocal + recipes.deletedServer,
            libraryUp = library.up, libraryDown = library.down,
            libraryDeleted = library.deletedLocal + library.deletedServer,
            imagesUp = recipes.imagesUp, imagesDown = recipes.imagesDown,
            conflictsMerged = library.conflictsMerged, conflictCopies = library.conflictCopies,
            duplicatesMerged = duplicatesMerged,
            warnings = library.warnings + recipes.warnings + listOfNotNull(foldWarning),
        )
    }

    /**
     * Folds same-named courses/categories/tags into one row each, at the end of a sync.
     *
     * Those three tables are reconciled by id, never by name, so two devices that each created "Vegan"
     * — or two libraries that each ran the default seed and minted their own ids for "Breads", "Main",
     * … — would otherwise replicate both rows to each other forever. Salty runs the same pass as its
     * step 6b; see [LibraryDuplicateMerger] for how a survivor is chosen and why the deletion converges
     * on the following sync.
     *
     * Best-effort: a failure here must not fail an otherwise-good sync, so it is reported as a warning.
     * The recipes it re-points are stamped now and upload on the next sync.
     *
     * @return the number of rows folded away, and the warning to report if the fold itself failed.
     */
    private fun consolidateDuplicates(): Pair<Int, String?> =
        try {
            local.consolidateDuplicateLibraryItems().removedItems to null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            0 to "Could not merge same-named courses, categories and tags: ${e.message}"
        }

    /**
     * Port of Salty's `serverResponseAllowsLocalDeletions`: refuses to apply local deletions inferred
     * from a server list that came back EMPTY. Returns null when the deletions may proceed, or the
     * warning to report when they may not.
     *
     * "Present here, absent there, unchanged since the last sync" is how deletions are detected, and
     * that inference is only as trustworthy as the list it runs against. A server restored from an
     * older backup, a proxy answering `[]`, another device's force-push caught halfway — each produces
     * an empty list that would otherwise read as "delete everything". Refusing costs nothing when the
     * server really is empty (the user reaches for the force pull); it saves the library when it isn't.
     * The X-Total-Count check in [SaltyApiClient] covers the partial-response case.
     */
    private fun allowsLocalDeletions(serverItemCount: Int, pendingLocalDeletions: Int, entity: String): String? =
        if (serverItemCount == 0 && pendingLocalDeletions > 0) {
            "Kept $pendingLocalDeletions local $entity${if (pendingLocalDeletions == 1) "" else "s"} the server no longer " +
                "lists, because it returned an empty list; if the server really is empty, use " +
                "\"Replace this library with the server's\"."
        } else {
            null
        }

    /**
     * One-way overwrite: wipe the local library and replace it with the server's current contents.
     * Performs NO uploads and NO server deletions — a safe "force full re-sync" / recovery path.
     */
    suspend fun pullEverythingFromServer(): SyncResult {
        report(SyncPhase.CONNECTING)
        api.registerDevice(deviceId, deviceName) // ensure the device is registered
        report(SyncPhase.PLANNING)
        val courses = api.fetchCourses()
        val categories = api.fetchCategories()
        val tags = api.fetchTags()
        val shoppingLists = api.fetchShoppingLists()
        val recipes = api.fetchRecipeDelta(modifiedSince = null) // all bodies

        // Everything from here to completeSync reports with stoppable = false. The local library has just
        // been wiped, and unlike an ordinary sync there is no cutoff to resume from — stopping mid-restore
        // would strand a truncated library with nothing to reconcile it against. The UI does not offer
        // "Stop" for this path either; the flag keeps that promise if some other caller cancels us.
        local.clearAll()
        report(SyncPhase.LIBRARY, stoppable = false)
        courses.forEach { local.upsertCourse(it) }
        categories.forEach { local.upsertCategory(it) }
        tags.forEach { local.upsertTag(it) }
        shoppingLists.forEach { local.upsertShoppingList(it) }
        var imagesDown = 0
        for ((i, recipe) in recipes.withIndex()) {
            report(SyncPhase.DOWNLOADING_RECIPES, i + 1, recipes.size, stoppable = false)
            local.upsertRecipe(recipe)
            val filename = recipe.imageFilename
            if (filename != null && imageSink != null) {
                // One bad image must not abort the restore — the local library was already wiped above, so
                // throwing here would strand it half-restored. Skipped images retry on the next sync (the
                // image dates still differ because imageSink never recorded the server's date).
                try {
                    api.downloadImage(filename)?.let { bytes -> imageSink.invoke(recipe.id, filename, bytes, recipe.lastModifiedImageDate); imagesDown++ }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
        // Every recipe here came from the server moments ago, so the two sides agree by construction.
        // Recording that stops the next ordinary sync from reading the whole restored library as
        // never-agreed and uploading all of it straight back (SHARED-V0005).
        local.markAllRecipesAgreed()
        local.markAllClassifiersAgreed()

        // The server can hold same-named rows of its own, so the restored library gets the same fold an
        // ordinary sync ends with. Salty tidies after its force restore for the same reason.
        report(SyncPhase.FINISHING, stoppable = false)
        val (duplicatesMerged, foldWarning) = consolidateDuplicates()

        api.completeSync(deviceId)
        return SyncResult(
            recipesDown = recipes.size,
            libraryDown = courses.size + categories.size + tags.size + shoppingLists.size,
            imagesDown = imagesDown,
            duplicatesMerged = duplicatesMerged,
            warnings = listOfNotNull(foldWarning),
        )
    }

    /**
     * One-way overwrite in the OPPOSITE direction: wipe the server's contents and replace them with this
     * device's local library. Performs NO local deletions — a "force full re-sync" treating the CLIENT as
     * the source of truth (the inverse of [pullEverythingFromServer]).
     */
    suspend fun pushEverythingToServer(): SyncResult {
        report(SyncPhase.CONNECTING)
        api.registerDevice(deviceId, deviceName) // ensure the device is registered

        // Upload FIRST, delete orphans LAST. The old order — wipe, then upload — left the server empty
        // for the whole upload, and a connection dropped in that window left it empty for good: every
        // OTHER device's next sync then read its own library as "deleted on the server". Uploading over
        // the existing rows is safe because the server's POST is an unconditional upsert, so nothing is
        // ever missing from the server mid-push. The Swift app does it in this order too.
        report(SyncPhase.PLANNING)
        val serverRecipeIds = api.fetchManifest().map { it.id }
        val serverCourses = api.fetchCourses()
        val serverCategories = api.fetchCategories()
        val serverTags = api.fetchTags()
        val serverLists = api.fetchShoppingLists()

        // 1. Push the entire local library up. Classifiers first so recipes can reference them.
        // `force = true` marks these as deliberate mirror-this-device overwrites (see
        // SaltyApiClient.FORCE_WRITE_HEADER) — inserts today, but it keeps a future server-side
        // stale-write guard from vetoing a racing re-creation.
        report(SyncPhase.LIBRARY)
        val courses = local.courses()
        val categories = local.categories()
        val tags = local.tags()
        courses.forEach { api.uploadCourse(it, force = true) }
        categories.forEach { api.uploadCategory(it, force = true) }
        tags.forEach { api.uploadTag(it, force = true) }
        report(SyncPhase.SHOPPING_LISTS)
        val serverListsById = serverLists.associateBy { it.id }
        val shoppingLists = local.shoppingLists()
        shoppingLists.forEach { l ->
            // Base the write on whatever revision the server holds right now, so it is accepted over a
            // newer server row (this device is the truth for a force push) and a genuine race — the row
            // moving between our GET and this POST — 409s and is retried once against what it reports.
            val base = serverListsById[l.id]?.revision ?: 0
            var out = api.uploadShoppingList(l.copy(revision = null, baseRevision = base))
            if (out is SaltyApiClient.ShoppingListSaveOutcome.Conflict) {
                out = api.uploadShoppingList(l.copy(revision = null, baseRevision = out.current.revision))
            }
            if (out is SaltyApiClient.ShoppingListSaveOutcome.Saved) local.markShoppingListSynced(out.list)
        }

        var recipesUp = 0
        var imagesUp = 0
        val localRecipeIds = mutableSetOf<String>()
        val outgoing = local.recipeEntries()
        for ((i, entry) in outgoing.withIndex()) {
            report(SyncPhase.UPLOADING_RECIPES, i + 1, outgoing.size)
            localRecipeIds += entry.id
            val recipe = local.recipeForUpload(entry.id) ?: continue
            api.uploadRecipe(recipe, force = true)
            recipesUp++
            val filename = recipe.imageFilename
            if (filename != null && pushImage(recipe.id, filename, recipe.lastModifiedImageDate)) imagesUp++
        }

        // 2. The server now holds everything this device has, so remove what it has EXTRA. Recipe
        // deletions also drop their images server-side.
        report(SyncPhase.APPLYING_DELETIONS)
        val orphanRecipes = serverRecipeIds.filter { it !in localRecipeIds }
        if (orphanRecipes.isNotEmpty()) api.deleteRecipesOnServer(deviceId, orphanRecipes)
        val localCourseIds = courses.mapTo(mutableSetOf()) { it.id }
        serverCourses.filter { it.id !in localCourseIds }.forEach { api.deleteCourse(it.id) }
        val localCategoryIds = categories.mapTo(mutableSetOf()) { it.id }
        serverCategories.filter { it.id !in localCategoryIds }.forEach { api.deleteCategory(it.id) }
        val localTagIds = tags.mapTo(mutableSetOf()) { it.id }
        serverTags.filter { it.id !in localTagIds }.forEach { api.deleteTag(it.id) }
        val localListIds = shoppingLists.mapTo(mutableSetOf()) { it.id }
        serverLists.filter { it.id !in localListIds }.forEach { api.deleteShoppingList(it.id) }

        // The server now mirrors local exactly — any pending local deletions are moot, and every row
        // is agreed by construction (recipeForUpload returns null only for a row deleted mid-loop,
        // whose stamp UPDATE then matches nothing). Stamping stops the next ordinary sync from
        // re-uploading every never-agreed row — and from resurrecting a recipe deleted elsewhere
        // between this push and that sync (SHARED-V0005).
        local.clearRecipeTombstones(local.tombstonedRecipeIds())
        local.markAllRecipesAgreed()
        local.markAllClassifiersAgreed()

        api.completeSync(deviceId)
        return SyncResult(
            recipesUp = recipesUp,
            libraryUp = courses.size + categories.size + tags.size + shoppingLists.size,
            imagesUp = imagesUp,
        )
    }

    private suspend fun syncRecipes(isFirstSync: Boolean, lastSync: Instant?, lastSyncWire: String?): Counts {
        // Locally-deleted recipes: push the deletions to the server and exclude them from the manifest
        // so the reconciler can never re-download them (delete-by-absence alone would resurrect a recipe
        // whose server copy changed since our last sync).
        val tombstones = local.tombstonedRecipeIds().toSet()
        if (tombstones.isNotEmpty()) {
            report(SyncPhase.APPLYING_DELETIONS)
            api.deleteRecipesOnServer(deviceId, tombstones.toList())
            local.clearRecipeTombstones(tombstones)
        }

        report(SyncPhase.PLANNING)
        // Complete manifest drives reconciliation; delta carries only changed bodies.
        val fullManifest = api.fetchManifest()
        val manifest = fullManifest.filter { it.id !in tombstones }
        val cutoff = if (isFirstSync) null else lastSyncWire
        val delta = api.fetchRecipeDelta(cutoff)
        val deltaById = delta.associateBy { it.id }

        val serverEntries = manifest.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) }
        val localEntries = local.recipeEntries()
        // The column is guaranteed present: applySharedMigrations runs on every open, and SQLDelight's
        // generated row for `SELECT * FROM recipe` requires it. See SHARED-V0005 in Database.kt.
        val plan = SyncReconciler.plan(localEntries, serverEntries, isFirstSync, lastSync, tracksAgreement = true)

        // Bodies only — image bytes are reconciled separately below, keyed on lastModifiedImageDate, so a
        // text-only change never moves an image and an image-only change never re-sends the body.
        for ((i, id) in plan.toUpload.withIndex()) {
            report(SyncPhase.UPLOADING_RECIPES, i + 1, plan.toUpload.size)
            local.recipeForUpload(id)?.let { recipe -> api.uploadRecipe(recipe) }
        }
        for ((i, id) in plan.toDownload.withIndex()) {
            report(SyncPhase.DOWNLOADING_RECIPES, i + 1, plan.toDownload.size)
            local.upsertRecipe(deltaById[id] ?: api.fetchRecipe(id))
        }

        // Server-driven deletions apply locally without a tombstone (the recipe is already gone
        // server-side) — but only when the manifest wasn't empty; see allowsLocalDeletions.
        val warnings = mutableListOf<String>()
        var deletedLocally = 0
        val refusal = allowsLocalDeletions(fullManifest.size, plan.toDeleteLocally.size, "recipe")
        if (refusal != null) {
            warnings += refusal
        } else {
            for ((i, id) in plan.toDeleteLocally.withIndex()) {
                report(SyncPhase.APPLYING_DELETIONS, i + 1, plan.toDeleteLocally.size)
                local.deleteRecipeLocalOnly(id)
                deletedLocally++
            }
        }
        if (plan.toDeleteOnServer.isNotEmpty()) api.deleteRecipesOnServer(deviceId, plan.toDeleteOnServer)

        // Record what this pass agreed on, so the next one needn't guess (SHARED-V0005). Three groups
        // mean the same thing afterwards — the server's copy matches this row as it stands: what went
        // up, what came down, and what was already identical on both sides. Anything just deleted is
        // excluded, including deletions the guard refused, since those rows are still local-only.
        val manifestIds = manifest.mapTo(mutableSetOf()) { it.id }
        val agreed = localEntries.mapTo(mutableSetOf()) { it.id }
            .apply { retainAll(manifestIds) }
            .apply {
                addAll(plan.toUpload)
                addAll(plan.toDownload)
                removeAll(plan.toDeleteLocally.toSet())
            }
        local.markRecipesAgreed(agreed)

        val (imagesUp, imagesDown) = syncImages(manifest, tombstones)
        val (preparedUp, preparedDown) = syncPreparedDates(manifest, plan, tombstones)

        return Counts(
            // Prepared-date transfers fold into the recipe counts: they move real recipe data, just not a
            // body edit. Keeping them out entirely would report "0 recipes" for a sync that changed rows.
            up = plan.toUpload.size + preparedUp, down = plan.toDownload.size + preparedDown,
            deletedLocal = deletedLocally, deletedServer = plan.toDeleteOnServer.size,
            imagesUp = imagesUp, imagesDown = imagesDown,
            warnings = warnings,
        )
    }

    /**
     * Independent "last made on" reconciliation, keyed on lastModifiedPreparedDate — the same decoupling
     * as [syncImages], for the opposite reason. Images get their own channel because re-sending bytes on a
     * body edit is EXPENSIVE; prepared dates get one because marking a recipe made deliberately does NOT
     * bump lastModifiedDate (that would reorder every client's "Date Modified" sort), so the body plan is
     * blind to the change and would never move it.
     *
     * Newer stamp wins; a null stamp means "never marked made through a prepared-date-aware client" and
     * always loses. A whole-row upload is what moves the value — safe because the bodies agree by the time
     * a push happens here, so it re-sends matching content and moves only the prepared pair, needing no
     * partial-update endpoint.
     *
     * Recipes whose bodies moved this cycle need only ONE direction, because the body transfer already
     * carried the prepared pair through a merge at the far end (RecipeRepository.upsert server-side,
     * LocalStore.upsertRecipe locally), both keyed on this same stamp:
     *   - body UPLOADED — the server kept its own pair exactly when its stamp was newer, so only a pull
     *     can still be owed. Pushing again would be a no-op.
     *   - body DOWNLOADED — the local merge kept its own pair exactly when the local stamp was newer, so
     *     only a push can still be owed.
     * Skipping such ids entirely (the obvious simplification) leaves the losing side stale until the NEXT
     * sync — see the clobber tests in SyncIntegrationTest, which converge in one pass because of this.
     *
     * Returns (uploaded, downloaded) counts.
     */
    private suspend fun syncPreparedDates(
        manifest: List<RecipeManifestEntry>,
        plan: SyncReconciler.Plan,
        tombstones: Set<String>,
    ): Pair<Int, Int> {
        var up = 0
        var down = 0
        val uploadedBodies = plan.toUpload.toSet()
        val downloadedBodies = plan.toDownload.toSet()
        val deleted = plan.toDeleteLocally.toSet() + plan.toDeleteOnServer.toSet()
        val serverById = manifest.associateBy { it.id }
        // Read AFTER the body plan ran, so downloaded rows show their post-merge prepared pair.
        val localById = local.recipePreparedEntries().associateBy { it.id }
        // Intersection only: a recipe missing from either side has no body agreement to piggyback on.
        for (id in (serverById.keys intersect localById.keys) - tombstones - deleted) {
            val s = serverById.getValue(id)
            val l = localById.getValue(id)
            val serverStamp = LocalStore.parseOrPast(s.lastModifiedPreparedDate)
            val localStamp = LocalStore.parseOrPast(l.lastModifiedPreparedDate)
            when {
                localStamp > serverStamp && id !in uploadedBodies ->
                    local.recipeForUpload(id)?.let { api.uploadRecipe(it); up++ }
                serverStamp > localStamp && id !in downloadedBodies -> {
                    local.setRecipePrepared(id, s.lastPrepared, s.lastModifiedPreparedDate)
                    down++
                }
            }
        }
        return up to down
    }

    /**
     * Independent image reconciliation, decoupled from the body plan and keyed on lastModifiedImageDate.
     * For each recipe the newer image side wins: push the local image (or its removal) when local is newer,
     * pull the server image (or apply its removal) when the server is newer. With EQUAL dates (incl. the
     * legacy null==null state and a body downloaded before its bytes), an image is still propagated to
     * whichever side never received it — uploaded if only local has it, downloaded if only the server does.
     * Returns (uploaded, downloaded) counts.
     */
    private suspend fun syncImages(manifest: List<RecipeManifestEntry>, tombstones: Set<String>): Pair<Int, Int> {
        var up = 0
        var down = 0
        val serverById = manifest.associateBy { it.id }
        val localById = local.recipeImageEntries().associateBy { it.id }
        suspend fun push(id: String, file: String, date: String?) {
            if (pushImage(id, file, date)) up++
        }
        suspend fun pull(id: String, file: String, date: String?) {
            api.downloadImage(file)?.let { bytes -> imageSink?.invoke(id, file, bytes, date); down++ }
        }
        val failed = mutableListOf<String>()
        val work = (serverById.keys + localById.keys) - tombstones
        for ((i, id) in work.withIndex()) {
            report(SyncPhase.IMAGES, i + 1, work.size)
            val s = serverById[id]
            val l = localById[id]
            val serverDate = LocalStore.parseOrPast(s?.lastModifiedImageDate)
            val localDate = LocalStore.parseOrPast(l?.lastModifiedImageDate)
            val serverFile = s?.imageFilename
            val localFile = l?.imageFilename
            // Per-recipe failure containment (mirrors the Swift app's syncImages): one bad image is skipped
            // and retried on the next sync — the image dates still differ because nothing was recorded.
            try {
                when {
                    localDate > serverDate -> when {
                        localFile != null -> push(id, localFile, l.lastModifiedImageDate)
                        serverFile != null -> { api.deleteImage(id, l?.lastModifiedImageDate); up++ } // local removed it
                    }
                    serverDate > localDate -> when {
                        serverFile != null -> pull(id, serverFile, s.lastModifiedImageDate)
                        localFile != null -> { local.setRecipeImage(id, null, null, s?.lastModifiedImageDate); down++ } // server removed it
                    }
                    // Equal dates → propagate an image the other side never received (no removal involved, since
                    // a removal stamps a fresh, unequal date).
                    serverFile != null && localFile == null -> pull(id, serverFile, s.lastModifiedImageDate)
                    localFile != null && serverFile == null -> push(id, localFile, l.lastModifiedImageDate)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += id
            }
        }
        // If EVERY image operation failed, something systemic is wrong (auth, network, server) — surface it
        // instead of silently reporting a "successful" sync with no images moved.
        if (failed.isNotEmpty() && up == 0 && down == 0) {
            throw SyncException("Image sync failed for: ${failed.joinToString(", ")}")
        }
        return up to down
    }

    /**
     * Shopping lists sync on per-row REVISIONS, not timestamps (see SHOPPING_LIST_REVISIONS_PLAN.md).
     * For every row on both sides, two clock-free questions classify it:
     *   dirty         — does the local row differ from its `syncedSnapshot` (last server agreement)?
     *   serverChanged — does the server's `revision` differ from our `syncedRevision`?
     * neither → in sync; dirty → upload (with baseRevision, so a race 409s instead of clobbering);
     * serverChanged → download; BOTH → real conflict, resolved by [ShoppingListMerge] (three-way
     * against the snapshot; freeform conflicts keep the local text as a new "conflicted copy" list).
     *
     * Legacy rows (no snapshot yet — pre-revision builds wrote them) get ONE timestamp-based decision
     * to pick a direction, then the bookkeeping is seeded and every later sync is revision-based.
     * Rows on only one side keep the watermark absence logic (no tombstones for lists, a deliberate
     * FEATURE_PLANS.md decision), except server-side deletes now carry If-Match so a list that
     * changed under us is downloaded instead of deleted.
     */
    private suspend fun syncShoppingLists(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchShoppingLists()
        val serverById = server.associateBy { it.id }
        val locals = local.shoppingListsWithSyncState()
        val localIds = locals.mapTo(mutableSetOf()) { it.list.id }

        var counts = Counts()
        val toDeleteLocally = mutableListOf<String>()

        for (l in locals) {
            val s = serverById[l.list.id]
            counts += when {
                s == null -> shoppingListAbsentOnServer(l, isFirstSync, lastSync, toDeleteLocally)
                l.syncedRevision == null || l.syncedSnapshot == null -> shoppingListLegacySeed(l, s)
                else -> {
                    val dirty = l.isDirty
                    val serverChanged = s.revision != l.syncedRevision
                    when {
                        !dirty && !serverChanged -> Counts()
                        dirty && !serverChanged -> uploadShoppingList(l.list, baseRevision = l.syncedRevision, snapshot = l.syncedSnapshot)
                        !dirty -> { local.upsertShoppingList(s); Counts(down = 1) }
                        else -> resolveShoppingListConflict(l, s)
                    }
                }
            }
        }

        // Local deletions are applied together, after the guard — the same protection recipes and
        // classifiers get against an empty list reading as "everything was deleted".
        val deletionRefusal = allowsLocalDeletions(server.size, toDeleteLocally.size, "shopping list")
        if (deletionRefusal != null) {
            counts += Counts(warnings = listOf(deletionRefusal))
        } else {
            for (id in toDeleteLocally) {
                local.deleteShoppingList(id)
                counts += Counts(deletedLocal = 1)
            }
        }

        for (s in server) {
            if (s.id in localIds) continue
            // Server-only row: new to us, or deleted here. No tombstones for lists, so the watermark
            // decides — except a failed If-Match delete proves the row changed, and change wins.
            counts += if (isFirstSync || lastSync == null || LocalStore.parseOrPast(s.lastModifiedDate) > lastSync) {
                local.upsertShoppingList(s)
                Counts(down = 1)
            } else when (val out = api.deleteShoppingList(s.id, expectedRevision = s.revision)) {
                is SaltyApiClient.ShoppingListDeleteOutcome.Deleted -> Counts(deletedServer = 1)
                is SaltyApiClient.ShoppingListDeleteOutcome.Conflict -> {
                    local.upsertShoppingList(out.current)
                    Counts(down = 1)
                }
            }
        }
        return counts
    }

    /**
     * Local row the server doesn't have: never-uploaded (push it) or server-deleted (respect it —
     * unless we edited since). Deletions are COLLECTED into [toDeleteLocally] rather than applied, so
     * the empty-response guard in [syncShoppingLists] can veto them as a batch.
     */
    private suspend fun shoppingListAbsentOnServer(
        l: LocalStore.LocalShoppingList,
        isFirstSync: Boolean,
        lastSync: Instant?,
        toDeleteLocally: MutableList<String>,
    ): Counts {
        // baseRevision 0 = "I expect NO server row": an insert sails through (the server accepts any
        // save of a row it doesn't have), but if another writer re-created the id between our GET and
        // this POST, the mismatch 409s into a proper merge instead of silently last-writer-winning.
        val everSynced = l.syncedRevision != null
        return if (everSynced) {
            if (l.isDirty) {
                // Deleted on the server but edited here since our last agreement: edit beats delete.
                uploadShoppingList(l.list, baseRevision = 0, snapshot = null)
            } else {
                toDeleteLocally += l.list.id
                Counts()
            }
        } else {
            // Legacy/never-synced row: the old watermark logic, then the upload seeds the bookkeeping.
            if (isFirstSync || lastSync == null || LocalStore.parseOrPast(l.list.lastModifiedDate) > lastSync) {
                uploadShoppingList(l.list, baseRevision = 0, snapshot = null)
            } else {
                toDeleteLocally += l.list.id
                Counts()
            }
        }
    }

    /**
     * Row exists on both sides but predates revision bookkeeping locally: ONE timestamp-based
     * last-writer-wins decision (exactly what every sync did before revisions), whose outcome seeds
     * `syncedRevision`/`syncedSnapshot` so this row never takes this path again.
     */
    private suspend fun shoppingListLegacySeed(l: LocalStore.LocalShoppingList, s: ServerShoppingList): Counts {
        val localDate = LocalStore.parseOrPast(l.list.lastModifiedDate)
        val serverDate = LocalStore.parseOrPast(s.lastModifiedDate)
        return when {
            localDate > serverDate -> uploadShoppingList(l.list, baseRevision = s.revision, snapshot = null)
            serverDate > localDate -> { local.upsertShoppingList(s); Counts(down = 1) }
            else -> { local.markShoppingListSynced(s); Counts() } // equal → agree; just record it
        }
    }

    /** Upload one list; a 409 means it changed since we fetched → resolve as a conflict instead. */
    private suspend fun uploadShoppingList(list: ServerShoppingList, baseRevision: Long?, snapshot: ServerShoppingList?): Counts =
        when (val out = api.uploadShoppingList(list.copy(revision = null, baseRevision = baseRevision))) {
            is SaltyApiClient.ShoppingListSaveOutcome.Saved -> {
                local.markShoppingListSynced(out.list)
                Counts(up = 1)
            }
            is SaltyApiClient.ShoppingListSaveOutcome.Conflict ->
                resolveShoppingListConflict(
                    LocalStore.LocalShoppingList(list, syncedRevision = baseRevision, syncedSnapshot = snapshot),
                    out.current,
                )
        }

    /**
     * Both sides changed since the last agreement. Merge (three-way when a snapshot exists), push the
     * result with the server's CURRENT revision as base, and store what the server accepted. A 409 on
     * that push means yet another writer landed in between — retry once against the newest row; a
     * second 409 leaves the row dirty for the next sync (never a wrong overwrite, by construction).
     */
    private suspend fun resolveShoppingListConflict(
        l: LocalStore.LocalShoppingList,
        s: ServerShoppingList,
        retriesLeft: Int = 1,
    ): Counts {
        val resolution = ShoppingListMerge.resolve(
            base = l.syncedSnapshot,
            local = l.list,
            server = s,
            conflictCopyId = newId(),
            conflictCopyLabel = "conflicted copy from $deviceName ${nowDayStamp()}",
        )
        var counts = Counts(conflictsMerged = 1)

        // The conflict copy is a brand-new list: keep it locally and push it up like any other row.
        resolution.conflictCopy?.let { copy ->
            local.insertLocalShoppingList(copy)
            when (val out = api.uploadShoppingList(copy)) {
                is SaltyApiClient.ShoppingListSaveOutcome.Saved -> local.markShoppingListSynced(out.list)
                is SaltyApiClient.ShoppingListSaveOutcome.Conflict -> {} // fresh id — can't happen; next sync retries
            }
            counts += Counts(conflictCopies = 1)
        }

        when (val out = api.uploadShoppingList(resolution.merged.copy(baseRevision = s.revision))) {
            is SaltyApiClient.ShoppingListSaveOutcome.Saved -> {
                local.upsertShoppingList(out.list) // contents + bookkeeping land together, row is clean
                counts += Counts(up = 1)
            }
            is SaltyApiClient.ShoppingListSaveOutcome.Conflict -> {
                counts += if (retriesLeft > 0) {
                    resolveShoppingListConflict(
                        LocalStore.LocalShoppingList(resolution.merged, l.syncedRevision, l.syncedSnapshot),
                        out.current,
                        retriesLeft - 1,
                    )
                } else {
                    Counts() // give up this round; the row stays dirty and next sync re-merges
                }
            }
        }
        return counts
    }

    @OptIn(ExperimentalTime::class)
    private fun nowDayStamp(): String = Clock.System.now().toString().take(10)

    private suspend fun syncCourses(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchCourses()
        val serverById = server.associateBy { it.id }
        val localById = local.courses().associateBy { it.id }
        // Agreement-tracked (SHARED-V0006), exactly like recipes: a row that exists here and not on
        // the server is classified by its recorded stamp, never by comparing clocks to the watermark.
        val localEntries = local.courseEntries()
        val plan = SyncReconciler.plan(
            local = localEntries,
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
            tracksAgreement = true,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadCourse(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertCourse(it) } }
        // Local deletes only when the server actually listed something; see allowsLocalDeletions.
        val warnings = mutableListOf<String>()
        var deletedLocally = 0
        val refusal = allowsLocalDeletions(server.size, plan.toDeleteLocally.size, "course")
        if (refusal != null) {
            warnings += refusal
        } else {
            plan.toDeleteLocally.forEach { local.deleteCourse(it); deletedLocally++ }
        }
        // Server deletes are conditional on the timestamp the decision was based on: a row that
        // changed after our fetch (e.g. a web rename racing this sync) is downloaded, not deleted.
        var deletedOnServer = 0
        var conflictDownloads = 0
        plan.toDeleteOnServer.forEach { id ->
            when (val out = api.deleteCourse(id, expectedLastModified = serverById[id]?.lastModifiedDate)) {
                SaltyApiClient.LibraryDeleteOutcome.Deleted -> deletedOnServer++
                is SaltyApiClient.LibraryDeleteOutcome.Conflict -> {
                    local.upsertCourse(out.current)
                    conflictDownloads++
                }
            }
        }
        // Record what this pass agreed on (SHARED-V0006); see the recipe pass for why these three
        // groups are one statement, and why anything just deleted — including deletions the guard
        // refused — must stay unstamped.
        val agreed = localEntries.mapTo(mutableSetOf()) { it.id }
            .apply {
                retainAll(serverById.keys)
                addAll(plan.toUpload)
                addAll(plan.toDownload)
                removeAll(plan.toDeleteLocally.toSet())
            }
        local.markCoursesAgreed(agreed)

        return plan.counts().copy(
            down = plan.toDownload.size + conflictDownloads,
            deletedLocal = deletedLocally,
            deletedServer = deletedOnServer,
            warnings = warnings,
        )
    }

    private suspend fun syncCategories(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchCategories()
        val serverById = server.associateBy { it.id }
        val localById = local.categories().associateBy { it.id }
        // Agreement-tracked (SHARED-V0006), exactly like recipes: a row that exists here and not on
        // the server is classified by its recorded stamp, never by comparing clocks to the watermark.
        val localEntries = local.categoryEntries()
        val plan = SyncReconciler.plan(
            local = localEntries,
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
            tracksAgreement = true,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadCategory(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertCategory(it) } }
        // Guarded local deletes — see syncCourses.
        val warnings = mutableListOf<String>()
        var deletedLocally = 0
        val refusal = allowsLocalDeletions(server.size, plan.toDeleteLocally.size, "category")
        if (refusal != null) {
            warnings += refusal
        } else {
            plan.toDeleteLocally.forEach { local.deleteCategory(it); deletedLocally++ }
        }
        // Conditional server deletes — see syncCourses.
        var deletedOnServer = 0
        var conflictDownloads = 0
        plan.toDeleteOnServer.forEach { id ->
            when (val out = api.deleteCategory(id, expectedLastModified = serverById[id]?.lastModifiedDate)) {
                SaltyApiClient.LibraryDeleteOutcome.Deleted -> deletedOnServer++
                is SaltyApiClient.LibraryDeleteOutcome.Conflict -> {
                    local.upsertCategory(out.current)
                    conflictDownloads++
                }
            }
        }
        // Record what this pass agreed on (SHARED-V0006); see the recipe pass for why these three
        // groups are one statement, and why anything just deleted — including deletions the guard
        // refused — must stay unstamped.
        val agreed = localEntries.mapTo(mutableSetOf()) { it.id }
            .apply {
                retainAll(serverById.keys)
                addAll(plan.toUpload)
                addAll(plan.toDownload)
                removeAll(plan.toDeleteLocally.toSet())
            }
        local.markCategoriesAgreed(agreed)

        return plan.counts().copy(
            down = plan.toDownload.size + conflictDownloads,
            deletedLocal = deletedLocally,
            deletedServer = deletedOnServer,
            warnings = warnings,
        )
    }

    private suspend fun syncTags(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchTags()
        val serverById = server.associateBy { it.id }
        val localById = local.tags().associateBy { it.id }
        // Agreement-tracked (SHARED-V0006), exactly like recipes: a row that exists here and not on
        // the server is classified by its recorded stamp, never by comparing clocks to the watermark.
        val localEntries = local.tagEntries()
        val plan = SyncReconciler.plan(
            local = localEntries,
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
            tracksAgreement = true,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadTag(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertTag(it) } }
        // Guarded local deletes — see syncCourses.
        val warnings = mutableListOf<String>()
        var deletedLocally = 0
        val refusal = allowsLocalDeletions(server.size, plan.toDeleteLocally.size, "tag")
        if (refusal != null) {
            warnings += refusal
        } else {
            plan.toDeleteLocally.forEach { local.deleteTag(it); deletedLocally++ }
        }
        // Conditional server deletes — see syncCourses.
        var deletedOnServer = 0
        var conflictDownloads = 0
        plan.toDeleteOnServer.forEach { id ->
            when (val out = api.deleteTag(id, expectedLastModified = serverById[id]?.lastModifiedDate)) {
                SaltyApiClient.LibraryDeleteOutcome.Deleted -> deletedOnServer++
                is SaltyApiClient.LibraryDeleteOutcome.Conflict -> {
                    local.upsertTag(out.current)
                    conflictDownloads++
                }
            }
        }
        // Record what this pass agreed on (SHARED-V0006); see the recipe pass for why these three
        // groups are one statement, and why anything just deleted — including deletions the guard
        // refused — must stay unstamped.
        val agreed = localEntries.mapTo(mutableSetOf()) { it.id }
            .apply {
                retainAll(serverById.keys)
                addAll(plan.toUpload)
                addAll(plan.toDownload)
                removeAll(plan.toDeleteLocally.toSet())
            }
        local.markTagsAgreed(agreed)

        return plan.counts().copy(
            down = plan.toDownload.size + conflictDownloads,
            deletedLocal = deletedLocally,
            deletedServer = deletedOnServer,
            warnings = warnings,
        )
    }

    /** Internal per-step tally; library steps are summed via [plus]. */
    private data class Counts(
        val up: Int = 0, val down: Int = 0, val deletedLocal: Int = 0, val deletedServer: Int = 0,
        val imagesUp: Int = 0, val imagesDown: Int = 0,
        val conflictsMerged: Int = 0, val conflictCopies: Int = 0,
        /** Things this step declined to do — see [allowsLocalDeletions]. */
        val warnings: List<String> = emptyList(),
    ) {
        operator fun plus(o: Counts) = Counts(
            up + o.up, down + o.down, deletedLocal + o.deletedLocal, deletedServer + o.deletedServer,
            imagesUp + o.imagesUp, imagesDown + o.imagesDown,
            conflictsMerged + o.conflictsMerged, conflictCopies + o.conflictCopies,
            warnings + o.warnings,
        )
    }

    private fun SyncReconciler.Plan.counts() = Counts(
        up = toUpload.size, down = toDownload.size,
        deletedLocal = toDeleteLocally.size, deletedServer = toDeleteOnServer.size,
    )
}

/** A human-facing summary of what a sync changed, surfaced by the app after [SyncService.syncNow]. */
data class SyncResult(
    val recipesUp: Int = 0, val recipesDown: Int = 0, val recipesDeleted: Int = 0,
    val libraryUp: Int = 0, val libraryDown: Int = 0, val libraryDeleted: Int = 0,
    val imagesUp: Int = 0, val imagesDown: Int = 0,
    /** Shopping lists that changed on both sides and were auto-merged (see ShoppingListMerge). */
    val conflictsMerged: Int = 0,
    /** New "(conflicted copy …)" lists created to preserve an unmergeable side — worth surfacing
     *  prominently: the user should know a duplicate now exists and why. */
    val conflictCopies: Int = 0,
    /** Same-named course/category/tag rows folded into their survivor after the sync. Counted because
     *  the fold really did change the library — rows went away and recipes were re-filed — and because
     *  a sync that silently deletes classifier rows would be alarming to notice later. */
    val duplicatesMerged: Int = 0,
    /** Things the sync declined to do without failing outright — most importantly local deletions it
     *  skipped because the server answered with an empty list. A sync with warnings still succeeded. */
    val warnings: List<String> = emptyList(),
) {
    val isNoOp: Boolean
        get() = recipesUp + recipesDown + recipesDeleted + libraryUp + libraryDown + libraryDeleted +
            imagesUp + imagesDown + conflictsMerged + conflictCopies + duplicatesMerged == 0

    /** e.g. "recipes 3↑ 1↓ · images 2↑ · classifiers 5↑", or "Already up to date." */
    fun summary(): String = listOf(countsSummary(), warnings.joinToString(" "))
        .filter { it.isNotEmpty() }
        .joinToString(" ")

    private fun countsSummary(): String {
        if (isNoOp) return "No changes to sync."
        val groups = mutableListOf<String>()
        fun group(label: String, up: Int, down: Int, removed: Int) {
            val bits = buildList {
                if (up > 0) add("$up ↑")
                if (down > 0) add("$down ↓")
                if (removed > 0) add("$removed removed")
            }
            if (bits.isNotEmpty()) groups += "$label ${bits.joinToString(" ")}"
        }
        group("recipes", recipesUp, recipesDown, recipesDeleted)
        group("images", imagesUp, imagesDown, 0)
        group("classifiers", libraryUp, libraryDown, libraryDeleted)
        if (conflictsMerged > 0) groups += "$conflictsMerged list conflict${if (conflictsMerged == 1) "" else "s"} merged"
        if (conflictCopies > 0) groups += "$conflictCopies conflicted cop${if (conflictCopies == 1) "y" else "ies"} kept"
        if (duplicatesMerged > 0) groups += "$duplicatesMerged duplicate classifier${if (duplicatesMerged == 1) "" else "s"} merged"
        return groups.joinToString(" · ")
    }
}
