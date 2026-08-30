package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.api.apiJson
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.LibraryDuplicateMerger
import com.enuvro.saltykmp.db.LibraryMergeSummary
import com.enuvro.saltykmp.db.model.Difficulty
import com.enuvro.saltykmp.db.model.Rating
import com.enuvro.saltykmp.util.newId
import com.enuvro.saltykmp.util.nowWireIso
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Local persistence bridge over the SQLDelight [AppDatabase] for sync. Maps between the wire DTOs and
 * the generated rows, and exposes the (id, lastModified) entries the reconciler needs. Calls are
 * synchronous SQLDelight operations; the suspend orchestration in [SyncService] invokes them.
 */
class LocalStore(private val db: AppDatabase) {

    private val q get() = db.queriesQueries

    // ---- Recipes ----

    /**
     * Every local recipe, with the SHARED-V0005 agreement stamp the reconciler needs. A null stamp means
     * the row has never been agreed with the server, which is what lets `SyncReconciler.plan` tell a new
     * recipe from one the server deleted without comparing any clock.
     */
    fun recipeEntries(): List<SyncReconciler.Entry> =
        q.selectAllRecipes().executeAsList().map {
            SyncReconciler.Entry(
                it.id,
                parseOrPast(dbToWireDate(it.lastModifiedDate)),
                parseOrNull(dbToWireDate(it.syncedModifiedDate)),
            )
        }

    /**
     * Records that these recipes now match the server. Call it only for recipes that actually made it:
     * stamping one that never reached the server would tell the next sync the server has a copy it does
     * not, and that row would be deleted locally instead of retried.
     */
    fun markRecipesAgreed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { ids.forEach { q.markRecipeAgreed(it) } }
    }

    /** Force-pull counterpart: the whole library came from the server, so all of it agrees. */
    fun markAllRecipesAgreed() = q.markAllRecipesAgreed()

    /** Per-recipe image state for the independent image-sync pass: id + filename + image timestamp (wire form). */
    data class ImageEntry(val id: String, val imageFilename: String?, val lastModifiedImageDate: String?)

    fun recipeImageEntries(): List<ImageEntry> =
        q.selectAllRecipes().executeAsList()
            .map { ImageEntry(it.id, it.imageFilename, dbToWireDate(it.lastModifiedImageDate)) }

    /** Per-recipe "last made on" state for the independent prepared-date pass (wire form). */
    data class PreparedEntry(val id: String, val lastPrepared: String?, val lastModifiedPreparedDate: String?)

    fun recipePreparedEntries(): List<PreparedEntry> =
        q.selectAllRecipes().executeAsList()
            .map { PreparedEntry(it.id, dbToWireDate(it.lastPrepared), dbToWireDate(it.lastModifiedPreparedDate)) }

    fun recipeForUpload(id: String): ServerRecipe? {
        val r = q.selectRecipeById(id).executeAsOneOrNull() ?: return null
        return ServerRecipe(
            id = r.id,
            name = r.name,
            createdDate = dbToWireDate(r.createdDate),
            lastModifiedDate = dbToWireDate(r.lastModifiedDate),
            lastPrepared = dbToWireDate(r.lastPrepared),
            lastModifiedPreparedDate = dbToWireDate(r.lastModifiedPreparedDate),
            source = r.source,
            sourceDetails = r.sourceDetails,
            introduction = r.introduction,
            difficulty = r.difficulty?.rawValue?.toInt(),
            rating = r.rating?.rawValue?.toInt(),
            imageFilename = r.imageFilename,
            lastModifiedImageDate = dbToWireDate(r.lastModifiedImageDate),
            isFavorite = r.isFavorite,
            wantToMake = r.wantToMake,
            yield = r.yield_,
            servings = r.servings?.toInt(),
            courseId = r.courseId,
            directions = r.directions,
            ingredients = r.ingredients,
            notes = r.notes,
            variations = r.variations,
            preparationTimes = r.preparationTimes,
            nutrition = r.nutrition,
            categoryIds = q.selectCategoryIdsForRecipe(id).executeAsList(),
            tagIds = q.selectTagIdsForRecipe(id).executeAsList(),
        )
    }

    fun upsertRecipe(s: ServerRecipe) {
        q.transaction {
            // The server has no FK on courseId, so it can serve a recipe whose course was deleted. With
            // foreign keys enforced locally, writing that dangling reference would fail — so drop it.
            val courseId = s.courseId?.takeIf { cid -> q.selectAllCourses().executeAsList().any { it.id == cid } }
            // Image state (filename, thumbnail blob, image timestamp) is owned ENTIRELY by the independent
            // image-sync pass / editor, NOT the body. Preserve whatever the row already holds (null for a new
            // row) so a text-only body update never disturbs the image, and a freshly-downloaded recipe keeps
            // a past image date — letting the image pass see the server's image as newer and fetch its bytes.
            val existing = q.selectRecipeById(s.id).executeAsOneOrNull()
            // The "last made on" pair rides along with the body, but the body's clock doesn't decide it:
            // keep whichever side's lastModifiedPreparedDate is newer. Without this, downloading a body
            // edit made elsewhere would silently undo a mark-as-made this device hasn't uploaded yet.
            // (The server applies the same merge in RecipeRepository.upsert, so both directions agree.)
            val incomingPreparedStamp = parseOrPast(s.lastModifiedPreparedDate)
            val existingPreparedStamp = parseOrPast(dbToWireDate(existing?.lastModifiedPreparedDate))
            val keepLocalPrepared = existing != null && existingPreparedStamp > incomingPreparedStamp
            // Dates are stored in GRDB's "yyyy-MM-dd HH:mm:ss.SSS" format and Swift's non-optional columns
            // get concrete defaults (""/0/false/[]) so the Salty app can decode rows from this same DB.
            q.upsertRecipe(
                id = s.id,
                name = s.name,
                createdDate = wireToDbDate(s.createdDate) ?: nowDbDate(),
                lastModifiedDate = wireToDbDate(s.lastModifiedDate) ?: nowDbDate(),
                lastPrepared = if (keepLocalPrepared) existing?.lastPrepared else wireToDbDate(s.lastPrepared),
                source = s.source ?: "",
                sourceDetails = s.sourceDetails ?: "",
                introduction = s.introduction ?: "",
                difficulty = s.difficulty?.let { Difficulty.fromRawValue(it.toLong()) } ?: Difficulty.NOT_SET,
                rating = s.rating?.let { Rating.fromRawValue(it.toLong()) } ?: Rating.NOT_SET,
                imageFilename = existing?.imageFilename,
                imageThumbnailData = existing?.imageThumbnailData,
                isFavorite = s.isFavorite ?: false,
                wantToMake = s.wantToMake ?: false,
                yield = s.yield ?: "",
                servings = s.servings?.toLong(),
                courseId = courseId,
                directions = s.directions ?: emptyList(),
                ingredients = s.ingredients ?: emptyList(),
                notes = s.notes ?: emptyList(),
                variations = s.variations ?: emptyList(),
                preparationTimes = s.preparationTimes ?: emptyList(),
                nutrition = s.nutrition,
                lastModifiedImageDate = existing?.lastModifiedImageDate ?: wireToDbDate(s.lastModifiedImageDate),
                lastModifiedPreparedDate =
                    if (keepLocalPrepared) existing?.lastModifiedPreparedDate
                    else wireToDbDate(s.lastModifiedPreparedDate),
            )
            // Replace junction associations when the server provided them.
            //
            // Ids this library doesn't have are SKIPPED, not written. The server never cleans its
            // junction rows when a category or tag is deleted, so it keeps serving those ids
            // indefinitely — and with foreign keys enforced here, writing one fails the whole
            // transaction, which (because the server serves the same recipe every time) breaks every
            // subsequent sync until someone re-saves that recipe elsewhere. The Swift app skips
            // unknown ids for exactly this reason.
            s.categoryIds?.let { ids ->
                q.deleteRecipeCategoriesByRecipeId(s.id)
                val known = q.selectAllCategories().executeAsList().mapTo(mutableSetOf()) { it.id }
                ids.distinct().filter { it in known }
                    .forEach { catId -> q.upsertRecipeCategory(newId(), s.id, catId) }
            }
            s.tagIds?.let { ids ->
                q.deleteRecipeTagsByRecipeId(s.id)
                val known = q.selectAllTags().executeAsList().mapTo(mutableSetOf()) { it.id }
                ids.distinct().filter { it in known }
                    .forEach { tagId -> q.upsertRecipeTag(newId(), s.id, tagId) }
            }
        }
    }

    /** Deletes locally AND records a tombstone so the next sync removes it server-side and never re-pulls it. */
    fun deleteRecipe(id: String) = q.transaction {
        q.deleteRecipeCategoriesByRecipeId(id)
        q.deleteRecipeTagsByRecipeId(id)
        q.deleteRecipeById(id)
        q.insertDeletedRecipe(id, nowIso())
    }

    /** Deletes locally without a tombstone — for the reconciler applying a server-driven deletion. */
    fun deleteRecipeLocalOnly(id: String) = q.transaction {
        q.deleteRecipeCategoriesByRecipeId(id)
        q.deleteRecipeTagsByRecipeId(id)
        q.deleteRecipeById(id)
    }

    fun tombstonedRecipeIds(): List<String> = q.selectDeletedRecipeIds().executeAsList()

    fun clearRecipeTombstones(ids: Collection<String>) = q.transaction {
        ids.forEach { q.deleteTombstone(it) }
    }

    /** Sets local image state (filename + thumbnail blob + image timestamp), independent of the body.
     * [imageDate] is the wire/ISO timestamp to record: `nowTimestamp()` for a user edit, the server's
     * image date for a downloaded image, or the row's existing value to leave it unchanged. */
    fun setRecipeImage(id: String, filename: String?, thumbnailData: ByteArray?, imageDate: String?) {
        q.updateRecipeImage(filename, thumbnailData, wireToDbDate(imageDate), id)
    }

    /** Sets the "last made on" date and its sync stamp WITHOUT touching lastModifiedDate — marking a
     * recipe made is deliberately not a body edit, so it never reorders a "Date Modified" sort. Pass
     * [lastPrepared] = null to clear the date. [preparedDate] is the wire/ISO stamp to record:
     * `nowTimestamp()` for a local edit, or the server's stamp when applying a downloaded change. */
    fun setRecipePrepared(id: String, lastPrepared: String?, preparedDate: String?) {
        q.updateRecipePrepared(wireToDbDate(lastPrepared), wireToDbDate(preparedDate), id)
    }

    // ---- Library ----

    fun courses(): List<ServerCourse> =
        q.selectAllCourses().executeAsList().map { ServerCourse(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }
    fun categories(): List<ServerCategory> =
        q.selectAllCategories().executeAsList().map { ServerCategory(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }
    fun tags(): List<ServerTag> =
        q.selectAllTags().executeAsList().map { ServerTag(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }

    // ---- Classifier agreement (SHARED-V0006): the recipe pattern, applied to the library tables ----

    /** Course entries with the agreement stamp the reconciler needs; see [recipeEntries]. */
    fun courseEntries(): List<SyncReconciler.Entry> =
        q.selectAllCourses().executeAsList().map {
            SyncReconciler.Entry(it.id, parseOrPast(dbToWireDate(it.lastModifiedDate)), parseOrNull(dbToWireDate(it.syncedModifiedDate)))
        }

    fun categoryEntries(): List<SyncReconciler.Entry> =
        q.selectAllCategories().executeAsList().map {
            SyncReconciler.Entry(it.id, parseOrPast(dbToWireDate(it.lastModifiedDate)), parseOrNull(dbToWireDate(it.syncedModifiedDate)))
        }

    fun tagEntries(): List<SyncReconciler.Entry> =
        q.selectAllTags().executeAsList().map {
            SyncReconciler.Entry(it.id, parseOrPast(dbToWireDate(it.lastModifiedDate)), parseOrNull(dbToWireDate(it.syncedModifiedDate)))
        }

    /** See [markRecipesAgreed] — same rule: only rows that actually made it. */
    fun markCoursesAgreed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { ids.forEach { q.markCourseAgreed(it) } }
    }

    fun markCategoriesAgreed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { ids.forEach { q.markCategoryAgreed(it) } }
    }

    fun markTagsAgreed(ids: Collection<String>) {
        if (ids.isEmpty()) return
        db.transaction { ids.forEach { q.markTagAgreed(it) } }
    }

    /** Force-path counterpart of [markAllRecipesAgreed] for the three library tables. */
    fun markAllClassifiersAgreed() {
        q.markAllCoursesAgreed()
        q.markAllCategoriesAgreed()
        q.markAllTagsAgreed()
    }

    // Named arguments throughout: the upserts are grouped statements (UPDATE + INSERT OR IGNORE), so
    // SQLDelight derives the parameter ORDER from first appearance in the SQL, not from the column list.
    fun upsertCourse(c: ServerCourse) =
        q.upsertCourse(name = c.name, lastModifiedDate = wireToDbDate(c.lastModifiedDate), id = c.id)
    fun upsertCategory(c: ServerCategory) =
        q.upsertCategory(name = c.name, lastModifiedDate = wireToDbDate(c.lastModifiedDate), id = c.id)
    fun upsertTag(t: ServerTag) =
        q.upsertTag(name = t.name, lastModifiedDate = wireToDbDate(t.lastModifiedDate), id = t.id)

    fun deleteCourse(id: String) = q.deleteCourseById(id)
    fun deleteCategory(id: String) = q.deleteCategoryById(id)
    fun deleteTag(id: String) = q.deleteTagById(id)

    /**
     * Folds same-named courses, categories and tags into one row each — the tidy-up a sync owes the
     * library after reconciling those three tables by id. See [LibraryDuplicateMerger].
     */
    fun consolidateDuplicateLibraryItems(): LibraryMergeSummary =
        LibraryDuplicateMerger(db).consolidateDuplicates()

    // Shopping lists. Synced as whole rows; items carry no server-side identity (their ids matter
    // only to client-side three-way merges). Each row carries its sync bookkeeping: the server
    // revision it was last agreed at plus a wire-JSON snapshot of that agreement (the merge BASE).

    /** A local row plus its sync state. [syncedSnapshot] is null for legacy/never-synced rows. */
    data class LocalShoppingList(
        val list: ServerShoppingList,
        val syncedRevision: Long?,
        val syncedSnapshot: ServerShoppingList?,
    ) {
        /** Edited since the last server agreement? Compares this device's own stamps only — no
         *  cross-machine clock comparison. Null snapshot (legacy row) is the caller's case to handle. */
        val isDirty: Boolean
            get() = syncedSnapshot != null && list.lastModifiedDate != syncedSnapshot.lastModifiedDate
    }

    fun shoppingLists(): List<ServerShoppingList> = shoppingListsWithSyncState().map { it.list }

    fun shoppingListsWithSyncState(): List<LocalShoppingList> =
        q.selectAllShoppingLists().executeAsList().map {
            LocalShoppingList(
                list = ServerShoppingList(
                    id = it.id,
                    name = it.name,
                    isFreeform = it.isFreeform,
                    contentsForList = it.contentsForList,
                    contentsForFreeform = it.contentsForFreeform,
                    lastModifiedDate = dbToWireDate(it.lastModifiedDate),
                ),
                syncedRevision = it.syncedRevision,
                // A snapshot that fails to decode (older build wrote junk?) degrades to "legacy row",
                // which just means one timestamp-based seed sync — never a crash, never data loss.
                syncedSnapshot = it.syncedSnapshot?.let { json ->
                    runCatching { apiJson.decodeFromString(ServerShoppingList.serializer(), json) }.getOrNull()
                },
            )
        }

    /**
     * Write a SERVER-agreed row (download, pull-everything): contents and sync bookkeeping move
     * together, so the row lands already-clean with the server row itself as the snapshot.
     */
    fun upsertShoppingList(l: ServerShoppingList) = q.upsertShoppingList(
        name = l.name, isFreeform = l.isFreeform, contentsForList = l.contentsForList ?: emptyList(),
        contentsForFreeform = l.contentsForFreeform, lastModifiedDate = wireToDbDate(l.lastModifiedDate),
        syncedRevision = l.revision, syncedSnapshot = snapshotJson(l), id = l.id,
    )

    /** Write a LOCAL row (conflict copy) that the server hasn't seen: no agreement to record yet. */
    fun insertLocalShoppingList(l: ServerShoppingList) = q.upsertShoppingList(
        name = l.name, isFreeform = l.isFreeform, contentsForList = l.contentsForList ?: emptyList(),
        contentsForFreeform = l.contentsForFreeform, lastModifiedDate = wireToDbDate(l.lastModifiedDate),
        syncedRevision = null, syncedSnapshot = null, id = l.id,
    )

    /**
     * Record the server agreement after a successful UPLOAD without touching the row's contents:
     * [accepted] is the server's response (our content + the revision it assigned).
     */
    fun markShoppingListSynced(accepted: ServerShoppingList) =
        q.markShoppingListSynced(accepted.revision, snapshotJson(accepted), accepted.id)

    private fun snapshotJson(l: ServerShoppingList): String? =
        l.revision?.let { apiJson.encodeToString(ServerShoppingList.serializer(), l.copy(baseRevision = null)) }

    /**
     * Write a LOCAL EDIT to an existing row: contents and [ServerShoppingList.lastModifiedDate] move while
     * the sync bookkeeping stays put, so the row reads as dirty at the next sync and uploads with the
     * `baseRevision` it was actually edited from. Use this for every user edit; [upsertShoppingList] is for
     * server-agreed rows only, and would mark the edit as already-synced.
     */
    fun updateLocalShoppingList(l: ServerShoppingList) = q.updateShoppingListContents(
        l.name, l.isFreeform, l.contentsForList ?: emptyList(), l.contentsForFreeform,
        wireToDbDate(l.lastModifiedDate), l.id,
    )

    fun deleteShoppingList(id: String) = q.deleteShoppingListById(id)

    /** Wipe the entire local library (used by Force Full Re-Sync before pulling from the server). */
    fun clearAll() = q.transaction {
        q.deleteAllRecipeCategories()
        q.deleteAllRecipeTags()
        q.deleteAllRecipes()
        q.deleteAllCourses()
        q.deleteAllCategories()
        q.deleteAllTags()
        q.deleteAllShoppingLists()
        q.deleteAllTombstones() // server-wins reset discards pending local deletions
    }

    companion object {
        fun parseOrPast(s: String?): Instant = parseOrNull(s) ?: Instant.DISTANT_PAST

        // Truncate to MILLISECONDS: the wire/server contract is `...SSS'Z'` (ms), but local timestamps
        // may carry nanoseconds (Instant.toString()). Comparing ms-vs-ns made the reconciler see local as
        // perpetually newer → re-upload every sync, forever. Normalizing on parse fixes both new writes
        // and nanosecond rows already in the DB without a re-upload migration.
        @OptIn(ExperimentalTime::class)
        fun parseOrNull(s: String?): Instant? =
            s?.takeIf { it.isNotBlank() }
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                ?.let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }

        /** Current time in the strict wire shape — ms precision, fraction always present. See [nowWireIso]. */
        internal fun nowIso(): String = nowWireIso()

        private fun nowDbDate(): String = wireToDbDate(nowIso())!!

        // The DB stores dates in GRDB's "yyyy-MM-dd HH:mm:ss.SSS" (UTC) so the Swift app reads them;
        // DTOs/wire use ISO-8601 ("...THH:mm:ss.SSS'Z'"). These convert at the storage boundary and
        // tolerate either form (alpha DBs may still hold ISO strings).
        fun wireToDbDate(s: String?): String? =
            s?.takeIf { it.isNotBlank() }?.removeSuffix("Z")?.replace('T', ' ')

        fun dbToWireDate(s: String?): String? {
            // Trim, and drop a trailing Z BEFORE choosing a branch. A database value carrying one
            // (alpha-era rows do -- Salty.NET's SaltyDates documents the same) has no 'T', so it used to
            // take the else branch and come back "...ZZ". Instant.parse rejects that, parseOrPast then
            // returned DISTANT_PAST, and the row lost every timestamp comparison in the reconciler --
            // so the server silently overwrote local edits to it. Pinned by corpus DATE-PARSE-003.
            val v = s?.trim()?.removeSuffix("Z")?.takeIf { it.isNotBlank() } ?: return null
            return if (v.contains('T')) "${v}Z" else "${v.replace(' ', 'T')}Z"
        }
    }
}
