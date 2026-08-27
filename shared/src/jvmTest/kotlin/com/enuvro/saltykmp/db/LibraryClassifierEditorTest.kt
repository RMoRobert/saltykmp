package com.enuvro.saltykmp.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.sync.LocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The classifier editor's bulk delete, against a real in-memory database with foreign keys ON — the
 * course case leans on `recipe.courseId`'s ON DELETE SET NULL, so a test with them off would prove
 * nothing. See [LibraryDuplicateMergerTest], whose harness this mirrors.
 */
class LibraryClassifierEditorTest {

    private val old = "2026-08-01T00:00:00.000Z"
    private val stamp = "2026-08-24 12:00:00.000"

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        AppDatabase.Schema.create(driver)
        return createAppDatabase(driver)
    }

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

    @Test
    fun deletingACategoryUnfilesItsRecipesAndBumpsTheirClocks() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertCategory(ServerCategory("cat-1", "Breads", old))
        val filed = recipe(local, "Sourdough", categoryIds = listOf("cat-1"))
        val untouched = recipe(local, "Unrelated")
        val before = lastModified(db, untouched)

        val touched = LibraryClassifierEditor(db).delete(LibraryClassifier.CATEGORY, listOf("cat-1"), now = stamp)

        assertEquals(1, touched)
        assertEquals(emptyList(), local.categories().map { it.id })
        assertEquals(emptyList(), db.queriesQueries.selectCategoryIdsForRecipe(filed).executeAsList())
        assertEquals(stamp, lastModified(db, filed), "membership travels on the recipe, so the clock must move")
        assertEquals(before, lastModified(db, untouched), "a recipe the delete didn't touch must not be re-uploaded")
    }

    @Test
    fun deletingACourseClearsTheRecipesThatUsedIt() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertCourse(ServerCourse("course-1", "Dessert", old))
        val filed = recipe(local, "Trifle", courseId = "course-1")

        val touched = LibraryClassifierEditor(db).delete(LibraryClassifier.COURSE, listOf("course-1"), now = stamp)

        assertEquals(1, touched)
        assertEquals(emptyList(), local.courses().map { it.id })
        assertNull(db.queriesQueries.selectRecipeById(filed).executeAsOne().courseId, "ON DELETE SET NULL clears it")
        assertEquals(stamp, lastModified(db, filed))
    }

    @Test
    fun aRecipeHoldingTwoOfTheDeletedTagsIsCountedAndTouchedOnce() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertTag(ServerTag("tag-1", "quick", old))
        local.upsertTag(ServerTag("tag-2", "fast", old))
        val both = recipe(local, "Weeknight Pasta", tagIds = listOf("tag-1", "tag-2"))

        val touched = LibraryClassifierEditor(db).delete(LibraryClassifier.TAG, listOf("tag-1", "tag-2"), now = stamp)

        assertEquals(1, touched, "the count is recipes affected, not links removed")
        assertEquals(emptyList(), local.tags().map { it.id })
        assertEquals(emptyList(), db.queriesQueries.selectTagIdsForRecipe(both).executeAsList())
        assertEquals(stamp, lastModified(db, both))
    }

    @Test
    fun deletingAnUnusedRowTouchesNoRecipes() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertTag(ServerTag("tag-1", "orphan", old))
        val untouched = recipe(local, "Unrelated")
        val before = lastModified(db, untouched)

        val touched = LibraryClassifierEditor(db).delete(LibraryClassifier.TAG, listOf("tag-1"), now = stamp)

        assertEquals(0, touched)
        assertEquals(emptyList(), local.tags().map { it.id })
        assertEquals(before, lastModified(db, untouched))
    }

    @Test
    fun deletingNothingIsANoOp() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertTag(ServerTag("tag-1", "keep", old))

        assertEquals(0, LibraryClassifierEditor(db).delete(LibraryClassifier.TAG, emptyList(), now = stamp))
        assertEquals(listOf("tag-1"), local.tags().map { it.id })
    }
}
