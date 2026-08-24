package com.enuvro.saltykmp.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.sync.LocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The merging half of the post-sync fold, against a real in-memory database with foreign keys ON —
 * the hazards it guards (courseId's ON DELETE SET NULL, the junctions' ON DELETE CASCADE) live in the
 * schema, so a test with foreign keys off would prove nothing.
 */
class LibraryDuplicateMergerTest {

    private val old = "2026-08-01T00:00:00.000Z"
    private val stamp = "2026-08-23 12:00:00.000"

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        AppDatabase.Schema.create(driver)
        return createAppDatabase(driver)
    }

    private fun seed(db: AppDatabase): LocalStore = LocalStore(db)

    private fun recipe(
        local: LocalStore,
        id: String,
        categoryIds: List<String>? = null,
        tagIds: List<String>? = null,
        courseId: String? = null,
    ): String {
        local.upsertRecipe(
            ServerRecipe(
                id = id,
                name = id,
                lastModifiedDate = old,
                categoryIds = categoryIds,
                tagIds = tagIds,
                courseId = courseId,
            ),
        )
        return id
    }

    private fun lastModified(db: AppDatabase, recipeId: String): String =
        db.queriesQueries.selectRecipeById(recipeId).executeAsOne().lastModifiedDate

    private fun categoryIds(db: AppDatabase, recipeId: String): List<String> =
        db.queriesQueries.selectCategoryIdsForRecipe(recipeId).executeAsList()

    @Test
    fun foldingACategoryRefilesItsRecipesAndBumpsTheirClocks() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "breads", old))
        val moved = recipe(local, "Sourdough", categoryIds = listOf("B-cat"))
        val untouched = recipe(local, "Unrelated")
        val before = lastModified(db, untouched)

        val summary = LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(LibraryMergeSummary(mergedGroups = 1, removedItems = 1, touchedRecipes = 1), summary)
        assertEquals(listOf("A-cat"), local.categories().map { it.id })
        assertEquals(listOf("A-cat"), categoryIds(db, moved))
        assertEquals(stamp, lastModified(db, moved), "membership travels on the recipe, so the clock must move")
        assertEquals(before, lastModified(db, untouched), "a recipe the fold didn't touch must not be re-uploaded")
    }

    @Test
    fun aRecipeFiledUnderBothRowsEndsUpWithOneLinkNotTwo() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "BREADS", old))
        val id = recipe(local, "Sourdough", categoryIds = listOf("A-cat", "B-cat"))

        LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(
            listOf("A-cat"),
            categoryIds(db, id),
            "there is no unique index on (recipeId, categoryId) to catch a doubled link",
        )
    }

    @Test
    fun aJunctionRowWrittenByAnotherClientIsNotDuplicatedByTheFold() {
        // Salty's sync writes "<recipeId>_<categoryId>" junction ids and its editor writes random
        // UUIDs; neither matches this client's "<recipeId>|<categoryId>", so an id-keyed insert alone
        // would leave the recipe listed under the surviving category twice.
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "breads", old))
        val id = recipe(local, "Sourdough", categoryIds = listOf("B-cat"))
        db.queriesQueries.upsertRecipeCategory("foreign-id", id, "A-cat")

        LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(listOf("A-cat"), categoryIds(db, id))
    }

    @Test
    fun foldingACourseRepointsRecipesInsteadOfClearingTheirCourse() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCourse(ServerCourse("A-course", "Main", old))
        local.upsertCourse(ServerCourse("B-course", "main ", old))
        val id = recipe(local, "Sourdough", courseId = "B-course")

        val summary = LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(1, summary.removedItems)
        assertEquals(
            "A-course",
            db.queriesQueries.selectRecipeById(id).executeAsOne().courseId,
            "courseId is ON DELETE SET NULL — deleting first would silently unfile it",
        )
        assertEquals(listOf("A-course"), local.courses().map { it.id })
    }

    @Test
    fun foldingATagRefilesItsRecipes() {
        val db = freshDb()
        val local = seed(db)
        local.upsertTag(ServerTag("A-tag", "Vegan", old))
        local.upsertTag(ServerTag("B-tag", "vegan", old))
        val id = recipe(local, "Dahl", tagIds = listOf("B-tag"))

        LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(listOf("A-tag"), db.queriesQueries.selectTagIdsForRecipe(id).executeAsList())
        assertEquals(listOf("A-tag"), local.tags().map { it.id })
    }

    @Test
    fun aLibraryWithNoDuplicatesIsLeftCompletelyAlone() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "Breakfast", old))
        val id = recipe(local, "Sourdough", categoryIds = listOf("A-cat"))
        val before = lastModified(db, id)

        val summary = LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertTrue(summary.isEmpty)
        assertEquals(0, summary.touchedRecipes)
        assertEquals(2, local.categories().size)
        assertEquals(before, lastModified(db, id), "an idle sync must not re-upload every recipe")
    }

    @Test
    fun runningTheFoldTwiceFindsNothingTheSecondTime() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "breads", old))
        recipe(local, "Sourdough", categoryIds = listOf("B-cat"))
        val merger = LibraryDuplicateMerger(db)

        merger.consolidateDuplicates(now = stamp)

        assertTrue(merger.consolidateDuplicates(now = stamp).isEmpty)
        assertTrue(merger.duplicateGroups().isEmpty())
    }

    @Test
    fun threeSameNamedRowsFoldIntoOne() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "breads", old))
        local.upsertCategory(ServerCategory("C-cat", " BREADS ", old))
        val one = recipe(local, "One", categoryIds = listOf("B-cat"))
        val two = recipe(local, "Two", categoryIds = listOf("C-cat"))

        val summary = LibraryDuplicateMerger(db).consolidateDuplicates(now = stamp)

        assertEquals(LibraryMergeSummary(mergedGroups = 1, removedItems = 2, touchedRecipes = 2), summary)
        assertEquals(listOf("A-cat"), categoryIds(db, one))
        assertEquals(listOf("A-cat"), categoryIds(db, two))
    }

    @Test
    fun aGroupWhoseSurvivorHasGoneSinceTheScanIsSkipped() {
        val db = freshDb()
        val local = seed(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCategory(ServerCategory("B-cat", "breads", old))
        val merger = LibraryDuplicateMerger(db)
        val groups = merger.duplicateGroups()
        local.deleteCategory("A-cat")

        val summary = merger.merge(groups, now = stamp)

        assertTrue(summary.isEmpty, "the survivor is gone; folding into it would orphan the recipes")
        assertEquals(listOf("B-cat"), local.categories().map { it.id })
    }
}
