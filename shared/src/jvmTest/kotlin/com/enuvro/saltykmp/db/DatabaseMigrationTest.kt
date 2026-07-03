package com.enuvro.saltykmp.db

import app.cash.sqldelight.db.QueryResult
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
}
