package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
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
 */
class SyncService(
    private val api: SaltyApiClient,
    private val local: LocalStore,
    private val deviceId: String,
    private val deviceName: String,
    private val imageSink: (suspend (recipeId: String, filename: String, bytes: ByteArray, imageDate: String?) -> Unit)? = null,
    private val imageSource: (suspend (recipeId: String, filename: String) -> ByteArray?)? = null,
) {
    suspend fun syncNow(): SyncResult {
        val device = api.registerDevice(deviceId, deviceName)
        val isFirstSync = device.isFirstSync
        val lastSync = LocalStore.parseOrNull(device.lastSyncDate)

        val library = syncCourses(isFirstSync, lastSync) +
            syncCategories(isFirstSync, lastSync) +
            syncTags(isFirstSync, lastSync)
        val recipes = syncRecipes(isFirstSync, lastSync, device.lastSyncDate)

        api.completeSync(deviceId)
        return SyncResult(
            recipesUp = recipes.up, recipesDown = recipes.down,
            recipesDeleted = recipes.deletedLocal + recipes.deletedServer,
            libraryUp = library.up, libraryDown = library.down,
            libraryDeleted = library.deletedLocal + library.deletedServer,
            imagesUp = recipes.imagesUp, imagesDown = recipes.imagesDown,
        )
    }

    /**
     * One-way overwrite: wipe the local library and replace it with the server's current contents.
     * Performs NO uploads and NO server deletions — a safe "force full re-sync" / recovery path.
     */
    suspend fun pullEverythingFromServer(): SyncResult {
        api.registerDevice(deviceId, deviceName) // ensure the device is registered
        val courses = api.fetchCourses()
        val categories = api.fetchCategories()
        val tags = api.fetchTags()
        val recipes = api.fetchRecipeDelta(modifiedSince = null) // all bodies

        local.clearAll()
        courses.forEach { local.upsertCourse(it) }
        categories.forEach { local.upsertCategory(it) }
        tags.forEach { local.upsertTag(it) }
        var imagesDown = 0
        for (recipe in recipes) {
            local.upsertRecipe(recipe)
            val filename = recipe.imageFilename
            if (filename != null && imageSink != null) {
                api.downloadImage(filename)?.let { bytes -> imageSink.invoke(recipe.id, filename, bytes, recipe.lastModifiedImageDate); imagesDown++ }
            }
        }
        api.completeSync(deviceId)
        return SyncResult(
            recipesDown = recipes.size,
            libraryDown = courses.size + categories.size + tags.size,
            imagesDown = imagesDown,
        )
    }

    /**
     * One-way overwrite in the OPPOSITE direction: wipe the server's contents and replace them with this
     * device's local library. Performs NO local deletions — a "force full re-sync" treating the CLIENT as
     * the source of truth (the inverse of [pullEverythingFromServer]).
     */
    suspend fun pushEverythingToServer(): SyncResult {
        api.registerDevice(deviceId, deviceName) // ensure the device is registered

        // 1. Delete everything currently on the server. Recipe deletions also drop their images server-side.
        val serverRecipeIds = api.fetchManifest().map { it.id }
        if (serverRecipeIds.isNotEmpty()) api.deleteRecipesOnServer(deviceId, serverRecipeIds)
        api.fetchCourses().forEach { api.deleteCourse(it.id) }
        api.fetchCategories().forEach { api.deleteCategory(it.id) }
        api.fetchTags().forEach { api.deleteTag(it.id) }

        // 2. Push the entire local library up. Organizers first so recipes can reference them.
        val courses = local.courses()
        val categories = local.categories()
        val tags = local.tags()
        courses.forEach { api.uploadCourse(it) }
        categories.forEach { api.uploadCategory(it) }
        tags.forEach { api.uploadTag(it) }

        var recipesUp = 0
        var imagesUp = 0
        for (entry in local.recipeEntries()) {
            val recipe = local.recipeForUpload(entry.id) ?: continue
            api.uploadRecipe(recipe)
            recipesUp++
            val filename = recipe.imageFilename
            if (filename != null && imageSource != null) {
                imageSource.invoke(recipe.id, filename)?.let { bytes ->
                    api.uploadImage(recipe.id, filename, bytes, recipe.lastModifiedImageDate)
                    imagesUp++
                }
            }
        }

        // The server now mirrors local exactly — any pending local deletions are moot.
        local.clearRecipeTombstones(local.tombstonedRecipeIds())

        api.completeSync(deviceId)
        return SyncResult(
            recipesUp = recipesUp,
            libraryUp = courses.size + categories.size + tags.size,
            imagesUp = imagesUp,
        )
    }

    private suspend fun syncRecipes(isFirstSync: Boolean, lastSync: Instant?, lastSyncWire: String?): Counts {
        // Locally-deleted recipes: push the deletions to the server and exclude them from the manifest
        // so the reconciler can never re-download them (delete-by-absence alone would resurrect a recipe
        // whose server copy changed since our last sync).
        val tombstones = local.tombstonedRecipeIds().toSet()
        if (tombstones.isNotEmpty()) {
            api.deleteRecipesOnServer(deviceId, tombstones.toList())
            local.clearRecipeTombstones(tombstones)
        }

        // Complete manifest drives reconciliation; delta carries only changed bodies.
        val manifest = api.fetchManifest().filter { it.id !in tombstones }
        val cutoff = if (isFirstSync) null else lastSyncWire
        val delta = api.fetchRecipeDelta(cutoff)
        val deltaById = delta.associateBy { it.id }

        val serverEntries = manifest.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) }
        val plan = SyncReconciler.plan(local.recipeEntries(), serverEntries, isFirstSync, lastSync)

        // Bodies only — image bytes are reconciled separately below, keyed on lastModifiedImageDate, so a
        // text-only change never moves an image and an image-only change never re-sends the body.
        for (id in plan.toUpload) {
            local.recipeForUpload(id)?.let { recipe -> api.uploadRecipe(recipe) }
        }
        for (id in plan.toDownload) {
            local.upsertRecipe(deltaById[id] ?: api.fetchRecipe(id))
        }

        // Server-driven deletions apply locally without a tombstone (the recipe is already gone server-side).
        for (id in plan.toDeleteLocally) local.deleteRecipeLocalOnly(id)
        if (plan.toDeleteOnServer.isNotEmpty()) api.deleteRecipesOnServer(deviceId, plan.toDeleteOnServer)

        val (imagesUp, imagesDown) = syncImages(manifest, tombstones)

        return Counts(
            up = plan.toUpload.size, down = plan.toDownload.size,
            deletedLocal = plan.toDeleteLocally.size, deletedServer = plan.toDeleteOnServer.size,
            imagesUp = imagesUp, imagesDown = imagesDown,
        )
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
            imageSource?.invoke(id, file)?.let { bytes -> api.uploadImage(id, file, bytes, date); up++ }
        }
        suspend fun pull(id: String, file: String, date: String?) {
            api.downloadImage(file)?.let { bytes -> imageSink?.invoke(id, file, bytes, date); down++ }
        }
        for (id in (serverById.keys + localById.keys) - tombstones) {
            val s = serverById[id]
            val l = localById[id]
            val serverDate = LocalStore.parseOrPast(s?.lastModifiedImageDate)
            val localDate = LocalStore.parseOrPast(l?.lastModifiedImageDate)
            val serverFile = s?.imageFilename
            val localFile = l?.imageFilename
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
        }
        return up to down
    }

    private suspend fun syncCourses(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchCourses()
        val serverById = server.associateBy { it.id }
        val localById = local.courses().associateBy { it.id }
        val plan = SyncReconciler.plan(
            local = localById.values.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadCourse(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertCourse(it) } }
        plan.toDeleteLocally.forEach { local.deleteCourse(it) }
        plan.toDeleteOnServer.forEach { api.deleteCourse(it) }
        return plan.counts()
    }

    private suspend fun syncCategories(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchCategories()
        val serverById = server.associateBy { it.id }
        val localById = local.categories().associateBy { it.id }
        val plan = SyncReconciler.plan(
            local = localById.values.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadCategory(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertCategory(it) } }
        plan.toDeleteLocally.forEach { local.deleteCategory(it) }
        plan.toDeleteOnServer.forEach { api.deleteCategory(it) }
        return plan.counts()
    }

    private suspend fun syncTags(isFirstSync: Boolean, lastSync: Instant?): Counts {
        val server = api.fetchTags()
        val serverById = server.associateBy { it.id }
        val localById = local.tags().associateBy { it.id }
        val plan = SyncReconciler.plan(
            local = localById.values.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            server = server.map { SyncReconciler.Entry(it.id, LocalStore.parseOrPast(it.lastModifiedDate)) },
            isFirstSync = isFirstSync, lastSyncDate = lastSync,
        )
        plan.toUpload.forEach { id -> localById[id]?.let { api.uploadTag(it) } }
        plan.toDownload.forEach { id -> serverById[id]?.let { local.upsertTag(it) } }
        plan.toDeleteLocally.forEach { local.deleteTag(it) }
        plan.toDeleteOnServer.forEach { api.deleteTag(it) }
        return plan.counts()
    }

    /** Internal per-step tally; library steps are summed via [plus]. */
    private data class Counts(
        val up: Int = 0, val down: Int = 0, val deletedLocal: Int = 0, val deletedServer: Int = 0,
        val imagesUp: Int = 0, val imagesDown: Int = 0,
    ) {
        operator fun plus(o: Counts) = Counts(
            up + o.up, down + o.down, deletedLocal + o.deletedLocal, deletedServer + o.deletedServer,
            imagesUp + o.imagesUp, imagesDown + o.imagesDown,
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
) {
    val isNoOp: Boolean
        get() = recipesUp + recipesDown + recipesDeleted + libraryUp + libraryDown + libraryDeleted +
            imagesUp + imagesDown == 0

    /** e.g. "recipes 3↑ 1↓ · images 2↑ · organizers 5↑", or "Already up to date." */
    fun summary(): String {
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
        group("organizers", libraryUp, libraryDown, libraryDeleted)
        return groups.joinToString(" · ")
    }
}
