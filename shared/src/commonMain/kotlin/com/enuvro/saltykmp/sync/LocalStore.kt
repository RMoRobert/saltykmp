package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.model.Difficulty
import com.enuvro.saltykmp.db.model.Rating
import kotlin.time.Clock
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

    fun recipeEntries(): List<SyncReconciler.Entry> =
        q.selectAllRecipes().executeAsList().map { SyncReconciler.Entry(it.id, parseOrPast(dbToWireDate(it.lastModifiedDate))) }

    /** Per-recipe image state for the independent image-sync pass: id + filename + image timestamp (wire form). */
    data class ImageEntry(val id: String, val imageFilename: String?, val lastModifiedImageDate: String?)

    fun recipeImageEntries(): List<ImageEntry> =
        q.selectAllRecipes().executeAsList()
            .map { ImageEntry(it.id, it.imageFilename, dbToWireDate(it.lastModifiedImageDate)) }

    fun recipeForUpload(id: String): ServerRecipe? {
        val r = q.selectRecipeById(id).executeAsOneOrNull() ?: return null
        return ServerRecipe(
            id = r.id,
            name = r.name,
            createdDate = dbToWireDate(r.createdDate),
            lastModifiedDate = dbToWireDate(r.lastModifiedDate),
            lastPrepared = dbToWireDate(r.lastPrepared),
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
            // Dates are stored in GRDB's "yyyy-MM-dd HH:mm:ss.SSS" format and Swift's non-optional columns
            // get concrete defaults (""/0/false/[]) so the Salty app can decode rows from this same DB.
            q.upsertRecipe(
                id = s.id,
                name = s.name,
                createdDate = wireToDbDate(s.createdDate) ?: nowDbDate(),
                lastModifiedDate = wireToDbDate(s.lastModifiedDate) ?: nowDbDate(),
                lastPrepared = wireToDbDate(s.lastPrepared),
                source = s.source ?: "",
                sourceDetails = s.sourceDetails ?: "",
                introduction = s.introduction ?: "",
                difficulty = s.difficulty?.let { Difficulty.fromRawValue(it.toLong()) } ?: Difficulty.NOT_SET,
                rating = s.rating?.let { Rating.fromRawValue(it.toLong()) } ?: Rating.NOT_SET,
                imageFilename = existing?.imageFilename,
                imageThumbnailData = existing?.imageThumbnailData,
                isFavorite = s.isFavorite ?: false,
                wantToMake = s.wantToMake ?: false,
                yield_ = s.yield ?: "",
                servings = s.servings?.toLong(),
                courseId = courseId,
                directions = s.directions ?: emptyList(),
                ingredients = s.ingredients ?: emptyList(),
                notes = s.notes ?: emptyList(),
                variations = s.variations ?: emptyList(),
                preparationTimes = s.preparationTimes ?: emptyList(),
                nutrition = s.nutrition,
                lastModifiedImageDate = existing?.lastModifiedImageDate ?: wireToDbDate(s.lastModifiedImageDate),
            )
            // Replace junction associations when the server provided them.
            s.categoryIds?.let { ids ->
                q.deleteRecipeCategoriesByRecipeId(s.id)
                ids.distinct().forEach { catId -> q.upsertRecipeCategory(idFor(s.id, catId), s.id, catId) }
            }
            s.tagIds?.let { ids ->
                q.deleteRecipeTagsByRecipeId(s.id)
                ids.distinct().forEach { tagId -> q.upsertRecipeTag(idFor(s.id, tagId), s.id, tagId) }
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

    // ---- Library ----

    fun courses(): List<ServerCourse> =
        q.selectAllCourses().executeAsList().map { ServerCourse(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }
    fun categories(): List<ServerCategory> =
        q.selectAllCategories().executeAsList().map { ServerCategory(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }
    fun tags(): List<ServerTag> =
        q.selectAllTags().executeAsList().map { ServerTag(it.id, it.name, dbToWireDate(it.lastModifiedDate)) }

    fun upsertCourse(c: ServerCourse) = q.upsertCourse(c.id, c.name, wireToDbDate(c.lastModifiedDate))
    fun upsertCategory(c: ServerCategory) = q.upsertCategory(c.id, c.name, wireToDbDate(c.lastModifiedDate))
    fun upsertTag(t: ServerTag) = q.upsertTag(t.id, t.name, wireToDbDate(t.lastModifiedDate))

    fun deleteCourse(id: String) = q.deleteCourseById(id)
    fun deleteCategory(id: String) = q.deleteCategoryById(id)
    fun deleteTag(id: String) = q.deleteTagById(id)

    /** Wipe the entire local library (used by Force Full Re-Sync before pulling from the server). */
    fun clearAll() = q.transaction {
        q.deleteAllRecipeCategories()
        q.deleteAllRecipeTags()
        q.deleteAllRecipes()
        q.deleteAllCourses()
        q.deleteAllCategories()
        q.deleteAllTags()
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

        private fun idFor(recipeId: String, otherId: String) = "$recipeId|$otherId"

        /** Current time at millisecond precision, matching the wire contract (no nanoseconds). */
        @OptIn(ExperimentalTime::class)
        private fun nowIso(): String =
            Clock.System.now().let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }.toString()

        private fun nowDbDate(): String = wireToDbDate(nowIso())!!

        // The DB stores dates in GRDB's "yyyy-MM-dd HH:mm:ss.SSS" (UTC) so the Swift app reads them;
        // DTOs/wire use ISO-8601 ("...THH:mm:ss.SSS'Z'"). These convert at the storage boundary and
        // tolerate either form (alpha DBs may still hold ISO strings).
        fun wireToDbDate(s: String?): String? =
            s?.takeIf { it.isNotBlank() }?.removeSuffix("Z")?.replace('T', ' ')

        fun dbToWireDate(s: String?): String? {
            val v = s?.takeIf { it.isNotBlank() } ?: return null
            return when {
                v.contains('T') -> if (v.endsWith("Z")) v else "${v}Z"
                else -> "${v.replace(' ', 'T')}Z"
            }
        }
    }
}
