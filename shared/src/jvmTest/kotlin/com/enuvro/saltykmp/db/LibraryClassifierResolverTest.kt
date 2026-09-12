package com.enuvro.saltykmp.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.sync.LocalStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/** Name-to-row resolution for importers, against a real in-memory database. */
class LibraryClassifierResolverTest {

    private val old = "2026-08-01T00:00:00.000Z"
    private val stamp = "2026-09-11 10:00:00.000"

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
        AppDatabase.Schema.create(driver)
        return createAppDatabase(driver)
    }

    @Test
    fun anExistingRowMatchesIgnoringCaseAndSpacing() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertCategory(ServerCategory("cat-1", "Main  Dish", old))

        val id = LibraryClassifierResolver(db).resolveId(LibraryClassifier.CATEGORY, "  main dish ", stamp)

        assertEquals("cat-1", id)
        assertEquals(listOf("cat-1"), local.categories().map { it.id }, "nothing new was created")
    }

    @Test
    fun accentsStaySignificant() {
        val db = freshDb()
        LocalStore(db).upsertTag(ServerTag("tag-1", "Crème", old))

        val id = LibraryClassifierResolver(db).resolveId(LibraryClassifier.TAG, "Creme", stamp)

        assertNotEquals("tag-1", id)
    }

    @Test
    fun anUnknownNameIsCreatedTrimmedWithItsCapitalizationAndNoAgreementStamp() {
        val db = freshDb()
        val local = LocalStore(db)

        val id = LibraryClassifierResolver(db).resolveId(LibraryClassifier.COURSE, "  Side Dish ", stamp)

        val row = db.queriesQueries.selectAllCourses().executeAsList().single()
        assertEquals(id, row.id)
        assertEquals("Side Dish", row.name)
        assertEquals(stamp, row.lastModifiedDate)
        assertNull(row.syncedModifiedDate, "never agreed with the server, so the next sync uploads it")
        assertEquals(listOf(id), local.courseEntries().filter { it.syncedModified == null }.map { it.id })
    }

    @Test
    fun aBlankNameResolvesToNothingAndCreatesNothing() {
        val db = freshDb()
        assertNull(LibraryClassifierResolver(db).resolveId(LibraryClassifier.TAG, "   ", stamp))
        assertEquals(emptyList(), LocalStore(db).tags())
    }

    @Test
    fun amongDuplicatesTheOldestIdWins() {
        val db = freshDb()
        val local = LocalStore(db)
        // Inserted out of order, so a first-row-wins scan would pick the wrong one.
        local.upsertCategory(ServerCategory("0198B-newer", "Vegan", old))
        local.upsertCategory(ServerCategory("0198A-older", "vegan", old))

        assertEquals("0198A-older", LibraryClassifierResolver(db).resolveId(LibraryClassifier.CATEGORY, "VEGAN", stamp))
    }

    @Test
    fun theThreeKindsAreSeparate() {
        val db = freshDb()
        LocalStore(db).upsertCourse(ServerCourse("course-1", "Dessert", old))

        val categoryId = LibraryClassifierResolver(db).resolveId(LibraryClassifier.CATEGORY, "Dessert", stamp)

        assertNotEquals("course-1", categoryId)
        assertEquals(listOf("Dessert"), LocalStore(db).categories().map { it.name })
    }

    @Test
    fun aRowCreatedOnceIsFoundTheSecondTime() {
        val db = freshDb()
        val resolver = LibraryClassifierResolver(db)

        val first = resolver.resolveId(LibraryClassifier.TAG, "Weeknight", stamp)
        val second = resolver.resolveId(LibraryClassifier.TAG, "weeknight", stamp)

        assertEquals(first, second)
        assertEquals(1, LocalStore(db).tags().size)
    }
}
