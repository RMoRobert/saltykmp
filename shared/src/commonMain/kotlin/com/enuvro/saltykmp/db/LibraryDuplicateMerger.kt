package com.enuvro.saltykmp.db

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Which of the three classifier tables a duplicate group belongs to. */
enum class LibraryClassifier { CATEGORY, COURSE, TAG }

/** One course, category or tag row, with the number of recipes using it. */
data class LibraryClassifierItem(val id: String, val name: String, val recipeCount: Int)

/** A set of rows of one kind sharing a name: the one to keep, and the ones to fold into it. */
data class LibraryDuplicateGroup(
    val kind: LibraryClassifier,
    /** The row that survives. Its exact name — capitalization and spacing included — is what remains. */
    val survivor: LibraryClassifierItem,
    val duplicates: List<LibraryClassifierItem>,
) {
    val name: String get() = survivor.name

    /** How many rows this group removes. */
    val removedCount: Int get() = duplicates.size
}

/** What a merge run changed. */
data class LibraryMergeSummary(
    /** Names that were held by more than one row and got folded. */
    val mergedGroups: Int = 0,
    /** Course/category/tag rows deleted — the duplicates, never the survivors. */
    val removedItems: Int = 0,
    /** Recipes whose classification was re-pointed, and whose body clock was therefore bumped. */
    val touchedRecipes: Int = 0,
) {
    val isEmpty: Boolean get() = mergedGroups == 0
}

/** Which row of a same-named set is kept. */
enum class SurvivorRule {
    /**
     * The row the most recipes already use, ties to the oldest. Fewest junction rows to rewrite, so
     * this is what a user-facing "consolidate duplicates" command would use.
     */
    MOST_RECIPES,

    /**
     * The oldest row (smallest id), regardless of use. Recipe counts differ from device to device, so
     * only an id-based rule makes two devices choose the SAME winner — which is what the automatic
     * post-sync pass needs in order to converge instead of ping-ponging.
     */
    OLDEST_ID,
}

/**
 * The "which library rows share a name" half of the fold: pure grouping with no database access, so it
 * unit-tests directly. Port of Salty's `LibraryDuplicateFinder`.
 */
object LibraryDuplicateFinder {

    /**
     * Groups rows of one kind by name, returning only the names held by more than one row.
     *
     * Names are matched case-insensitively, trimmed, with any run of internal whitespace treated as a
     * single space — so "Main dish", "Main  Dish" and " main dish " are one group. Accents stay
     * significant ("Crème" and "Creme" are two categories), matching Salty. Rows whose name is empty or
     * whitespace only are skipped rather than merged together: they carry no evidence of being the
     * same thing.
     */
    fun groups(
        kind: LibraryClassifier,
        items: List<LibraryClassifierItem>,
        rule: SurvivorRule = SurvivorRule.MOST_RECIPES,
    ): List<LibraryDuplicateGroup> =
        items.groupBy { normalizedName(it.name) }
            .filterKeys { it.isNotEmpty() }
            .values
            .filter { it.size > 1 }
            .map { bucket ->
                val ordered = ranked(bucket, rule)
                LibraryDuplicateGroup(kind, ordered.first(), ordered.drop(1))
            }
            .sortedWith(compareBy({ it.name.lowercase() }, { it.survivor.id }))

    /**
     * Rows in survivor order: the one a merge should keep comes first. Ids are compared as plain
     * strings, the way every client compares them, and rows created by the newer clients carry UUIDv7
     * ids — so the smallest id is the earliest-created one.
     */
    fun ranked(
        items: List<LibraryClassifierItem>,
        rule: SurvivorRule = SurvivorRule.MOST_RECIPES,
    ): List<LibraryClassifierItem> = when (rule) {
        SurvivorRule.OLDEST_ID -> items.sortedBy { it.id }
        SurvivorRule.MOST_RECIPES -> items.sortedWith(compareByDescending<LibraryClassifierItem> { it.recipeCount }.thenBy { it.id })
    }

    /** The key two names are grouped by: trimmed, internal whitespace collapsed, lower-cased. */
    fun normalizedName(name: String?): String =
        name.orEmpty().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ").lowercase()

    private val WHITESPACE = Regex("\\s+")
}

/**
 * Folds same-named courses, categories and tags into a single row, re-pointing every recipe that
 * referenced a duplicate before deleting it, so no recipe loses a classification. Port of Salty's
 * `LibraryDuplicateMerger`.
 *
 * The three classifier tables are reconciled by **id**, never by name (see `SyncService.syncCategories`
 * and its siblings), so two devices that each create "Vegan" — or two libraries that each ran the
 * default seed and minted their own ids for "Breads", "Main", … — end up with two rows that sync then
 * replicates faithfully, forever. Nothing in the sync itself can notice: to the server they are simply
 * two different rows that happen to share a name.
 *
 * The automatic pass chooses the survivor by **id** rather than by recipe count: counts differ from
 * device to device, and only an id-based rule makes every device pick the same winner. Once they agree,
 * the loser's deletion propagates on the following sync — its server timestamp is by then older than
 * the watermark — and the library converges.
 */
class LibraryDuplicateMerger(private val db: AppDatabase) {

    private val q get() = db.queriesQueries

    /** Every duplicate-name group across all three tables: categories, then courses, then tags. */
    fun duplicateGroups(rule: SurvivorRule = SurvivorRule.MOST_RECIPES): List<LibraryDuplicateGroup> = scan(rule)

    /**
     * Scan and merge in ONE transaction — for the automatic post-sync pass, which isn't showing the
     * user a preview first. A partially applied merge would leave recipes pointing at rows that no
     * longer exist, so the two halves must not be separated.
     */
    fun consolidateDuplicates(
        rule: SurvivorRule = SurvivorRule.OLDEST_ID,
        now: String = nowDbDate(),
    ): LibraryMergeSummary {
        var summary = LibraryMergeSummary()
        q.transaction { summary = mergeInTransaction(scan(rule), now) }
        return summary
    }

    /**
     * Folds groups captured by an earlier [duplicateGroups] — for a caller that showed the user what
     * would happen first. Rows that have gone away since the scan are skipped.
     */
    fun merge(groups: List<LibraryDuplicateGroup>, now: String = nowDbDate()): LibraryMergeSummary {
        var summary = LibraryMergeSummary()
        q.transaction { summary = mergeInTransaction(groups, now) }
        return summary
    }

    // ---- Scanning ----

    private fun scan(rule: SurvivorRule): List<LibraryDuplicateGroup> =
        LibraryDuplicateFinder.groups(LibraryClassifier.CATEGORY, categoryItems(), rule) +
            LibraryDuplicateFinder.groups(LibraryClassifier.COURSE, courseItems(), rule) +
            LibraryDuplicateFinder.groups(LibraryClassifier.TAG, tagItems(), rule)

    private fun categoryItems(): List<LibraryClassifierItem> =
        q.selectCategoriesWithRecipeCounts { id, name, count -> item(id, name, count) }.executeAsList()

    private fun courseItems(): List<LibraryClassifierItem> =
        q.selectCoursesWithRecipeCounts { id, name, count -> item(id, name, count) }.executeAsList()

    private fun tagItems(): List<LibraryClassifierItem> =
        q.selectTagsWithRecipeCounts { id, name, count -> item(id, name, count) }.executeAsList()

    private fun item(id: String, name: String?, recipeCount: Long) =
        LibraryClassifierItem(id, name.orEmpty(), recipeCount.toInt())

    // ---- Merging ----

    /**
     * Every recipe that referenced a duplicate gets its `lastModifiedDate` bumped: category and tag
     * membership syncs as the `categoryIds`/`tagIds` arrays on the recipe payload, and a course as its
     * `courseId`, so a re-point that doesn't move the recipe's timestamp would never reach the server
     * or the other devices.
     */
    private fun mergeInTransaction(groups: List<LibraryDuplicateGroup>, now: String): LibraryMergeSummary {
        // Live ids per kind, read once and kept in step as rows are deleted: nothing else writes inside
        // this transaction. The check matters because [groups] may come from an earlier scan whose rows
        // another window, a sync or the Swift app has since removed — folding into a survivor that is
        // gone would orphan its recipes.
        val live = mutableMapOf<LibraryClassifier, MutableSet<String>>()
        fun liveIds(kind: LibraryClassifier): MutableSet<String> = live.getOrPut(kind) {
            when (kind) {
                LibraryClassifier.CATEGORY -> q.selectAllCategories().executeAsList().mapTo(mutableSetOf()) { it.id }
                LibraryClassifier.COURSE -> q.selectAllCourses().executeAsList().mapTo(mutableSetOf()) { it.id }
                LibraryClassifier.TAG -> q.selectAllTags().executeAsList().mapTo(mutableSetOf()) { it.id }
            }
        }

        val touched = mutableSetOf<String>()
        var mergedGroups = 0
        var removedItems = 0

        for (group in groups) {
            if (group.survivor.id !in liveIds(group.kind)) continue
            var mergedAny = false
            for (duplicate in group.duplicates) {
                if (duplicate.id == group.survivor.id || duplicate.id !in liveIds(group.kind)) continue
                touched += fold(group.kind, duplicate.id, group.survivor.id)
                liveIds(group.kind) -= duplicate.id
                removedItems++
                mergedAny = true
            }
            if (mergedAny) mergedGroups++
        }

        touched.forEach { q.touchRecipeLastModified(lastModifiedDate = now, id = it) }

        return LibraryMergeSummary(
            mergedGroups = mergedGroups,
            removedItems = removedItems,
            touchedRecipes = touched.size,
        )
    }

    /**
     * Moves everything referencing [duplicateId] onto [survivorId], then deletes the duplicate row.
     * Returns the ids of the recipes that referenced the duplicate.
     *
     * The junction cases are insert-then-delete rather than an `UPDATE … SET categoryId`, so the
     * surviving row always carries the canonical "<recipeId>|<otherId>" junction id, and so a recipe
     * already filed under BOTH rows ends up with one link rather than two — there is no unique index on
     * (recipeId, categoryId) to catch that.
     */
    private fun fold(kind: LibraryClassifier, duplicateId: String, survivorId: String): List<String> = when (kind) {
        LibraryClassifier.CATEGORY -> {
            val recipeIds = q.selectRecipeIdsForCategory(duplicateId).executeAsList()
            recipeIds.forEach {
                q.insertRecipeCategoryIfAbsent(id = junctionId(it, survivorId), recipeId = it, categoryId = survivorId)
            }
            q.deleteRecipeCategoriesByCategoryId(duplicateId)
            q.deleteCategoryById(duplicateId)
            recipeIds
        }

        LibraryClassifier.TAG -> {
            val recipeIds = q.selectRecipeIdsForTag(duplicateId).executeAsList()
            recipeIds.forEach {
                q.insertRecipeTagIfAbsent(id = junctionId(it, survivorId), recipeId = it, tagId = survivorId)
            }
            q.deleteRecipeTagsByTagId(duplicateId)
            q.deleteTagById(duplicateId)
            recipeIds
        }

        LibraryClassifier.COURSE -> {
            // A recipe has at most one course, so this is a straight re-point — and it must happen
            // BEFORE the delete, because courseId is ON DELETE SET NULL.
            val recipeIds = q.selectRecipeIdsForCourse(duplicateId).executeAsList()
            q.repointRecipeCourse(survivorId = survivorId, duplicateId = duplicateId)
            q.deleteCourseById(duplicateId)
            recipeIds
        }
    }

    private fun junctionId(recipeId: String, otherId: String) = "$recipeId|$otherId"

    companion object {
        // Same pair as LocalStore's private helpers: millisecond precision (the wire contract) written
        // in GRDB's "yyyy-MM-dd HH:mm:ss.SSS", so the Swift app reads what this writes.
        @OptIn(ExperimentalTime::class)
        fun nowDbDate(): String =
            Clock.System.now()
                .let { Instant.fromEpochMilliseconds(it.toEpochMilliseconds()) }
                .toString()
                .removeSuffix("Z")
                .replace('T', ' ')
    }
}
