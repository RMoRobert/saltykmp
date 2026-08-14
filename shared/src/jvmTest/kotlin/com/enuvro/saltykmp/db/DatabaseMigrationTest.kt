package com.enuvro.saltykmp.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationTest {

    /** A shared migration runs exactly once per DB and is recorded in the cross-platform ledger. */
    @Test
    fun sharedMigrationRunsOnceAndIsLedgered() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)

        var runs = 0
        val migrations = listOf(
            SharedMigration("test-add-col") { d ->
                runs++
                d.execute(null, "ALTER TABLE recipe ADD COLUMN testCol TEXT", 0) // would throw if run twice
            }
        )

        applySharedMigrations(driver, migrations)
        applySharedMigrations(driver, migrations) // second open must skip via the ledger

        assertEquals(1, runs, "migration must run exactly once")

        val ledgered = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM saltyMigration WHERE identifier = 'test-add-col'",
            { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) },
            0,
        ).value
        assertEquals(1L, ledgered, "migration must be recorded in saltyMigration")
    }

    /** A migration already recorded by the other platform is skipped entirely. */
    @Test
    fun migrationRecordedByOtherPlatformIsSkipped() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        // Simulate the Swift app having already applied & recorded it.
        driver.execute(null, """CREATE TABLE IF NOT EXISTS "saltyMigration" ("identifier" TEXT NOT NULL PRIMARY KEY, "platform" TEXT, "appliedDate" TEXT NOT NULL)""", 0)
        driver.execute(null, """INSERT INTO "saltyMigration" ("identifier","platform","appliedDate") VALUES ('shared-x','swift','2026-06-01T00:00:00.000Z')""", 0)

        var runs = 0
        applySharedMigrations(driver, listOf(SharedMigration("shared-x") { runs++ }))

        assertEquals(0, runs, "a migration the other platform already ran must not run again")
    }

    /**
     * The real [SHARED_MIGRATIONS] must be safe on a fresh KMP database, where Schema.sq already
     * declares the columns those migrations add. Each one guards its ALTER on column existence, so
     * this would throw "duplicate column name" if a guard were missing or wrong.
     */
    @Test
    fun realSharedMigrationsAreSafeOnAFreshSchema() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)

        applySharedMigrations(driver)
        applySharedMigrations(driver) // and re-opening must stay a no-op

        assertEquals(true, columnPresent(driver, "recipe", "lastModifiedImageDate"))
        assertEquals(true, columnPresent(driver, "shoppingList", "lastModifiedDate"))
        assertEquals(true, columnPresent(driver, "shoppingList", "syncedRevision"))
        assertEquals(true, columnPresent(driver, "shoppingList", "syncedSnapshot"))
    }

    /**
     * Mirrors Salty's `saltySharedMigrations`. The ids are the contract between the two apps — a
     * mismatch means one platform re-runs a migration the other already recorded — so pin them.
     * Update this list ONLY together with the Swift side, and never rename a shipped id.
     */
    @Test
    fun sharedMigrationIdsMatchTheSwiftApp() {
        assertEquals(
            listOf("2026-06-recipe-add-lastModifiedImageDate", "SHARED-V0002", "SHARED-V0003"),
            SHARED_MIGRATIONS.map { it.id },
        )
    }

    /** SHARED-V0002 must add the column on a DB that predates it (the Swift-created / older-KMP case). */
    @Test
    fun shoppingListLastModifiedDateIsAddedToAnOlderDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // A pre-SHARED-V0002 shoppingList: no lastModifiedDate column.
        driver.execute(
            null,
            """CREATE TABLE "shoppingList" ("id" TEXT PRIMARY KEY NOT NULL, "name" TEXT, "isFreeform" INTEGER,
               "contentsForList" TEXT, "contentsForFreeform" TEXT)""",
            0,
        )
        assertEquals(false, columnPresent(driver, "shoppingList", "lastModifiedDate"))

        applySharedMigrations(driver, SHARED_MIGRATIONS.filter { it.id == "SHARED-V0002" })

        assertEquals(true, columnPresent(driver, "shoppingList", "lastModifiedDate"))
    }

    /** SHARED-V0003 must add both sync-state columns on a DB that predates it. */
    @Test
    fun shoppingListSyncStateColumnsAreAddedToAnOlderDatabase() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // A pre-SHARED-V0003 shoppingList: SHARED-V0002 shape, no sync-state columns.
        driver.execute(
            null,
            """CREATE TABLE "shoppingList" ("id" TEXT PRIMARY KEY NOT NULL, "name" TEXT, "isFreeform" INTEGER,
               "contentsForList" TEXT, "contentsForFreeform" TEXT, "lastModifiedDate" TEXT)""",
            0,
        )
        assertEquals(false, columnPresent(driver, "shoppingList", "syncedRevision"))

        applySharedMigrations(driver, SHARED_MIGRATIONS.filter { it.id == "SHARED-V0003" })

        assertEquals(true, columnPresent(driver, "shoppingList", "syncedRevision"))
        assertEquals(true, columnPresent(driver, "shoppingList", "syncedSnapshot"))
    }

    private fun columnPresent(driver: SqlDriver, table: String, column: String): Boolean =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM pragma_table_info('$table') WHERE name = ?",
            { c -> QueryResult.Value(c.next().value && (c.getLong(0) ?: 0L) > 0L) },
            1,
        ) { bindString(0, column) }.value
}
