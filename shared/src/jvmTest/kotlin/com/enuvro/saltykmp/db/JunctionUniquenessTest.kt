package com.enuvro.saltykmp.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.util.newId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Forward-compatibility for the `UNIQUE (recipeId, categoryId)` index the junction tables do not have
 * yet (ID-006 in salty-contract/SPEC.md), and the pin for ID-007 on this client: every junction insert
 * must tolerate a conflict it does not target.
 *
 * KMP already satisfies that — `upsertRecipeCategory` / `upsertRecipeTag` are `INSERT OR IGNORE`, which
 * is untargeted — but nothing else would go red if someone converted them to an `ON CONFLICT(id)`
 * upsert, and a targeted clause absorbs only primary-key collisions: a violation of the pair index
 * would then throw mid-sync. So this test creates the future index up front and drives the real
 * generated queries against it. Mirrors `JunctionUniquenessTests` in Salty.Core.Tests and SaltyTests.
 */
class JunctionUniquenessTest {

    private fun openWithPairIndex(): Pair<SqlDriver, AppDatabase> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        driver.execute(
            null,
            """CREATE UNIQUE INDEX "recipeCategory_pair" ON "recipeCategory" ("recipeId", "categoryId")""",
            0,
        )
        return driver to createAppDatabase(driver)
    }

    private fun linkCount(driver: SqlDriver): Long =
        driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM recipeCategory",
            { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) },
            0,
        ).value ?: 0L

    /** The real generated query, applied twice for the same pair: a no-op, never an exception. */
    @Test
    fun upsertToleratesADuplicatePairOnceTheIndexExists() {
        val (driver, db) = openWithPairIndex()
        val q = db.queriesQueries

        repeat(2) { q.upsertRecipeCategory(newId(), "R1", "C1") }

        assertEquals(1L, linkCount(driver), "the second upsert must be ignored, not doubled or thrown")
    }

    /**
     * The contrast, and what the OR IGNORE is protecting against: a plain INSERT of the same pair —
     * fresh id, so the primary key never collides — is rejected by the pair index itself.
     */
    @Test
    fun aPlainInsertIsWhatTheIndexWouldHaveBroken() {
        val (driver, db) = openWithPairIndex()
        db.queriesQueries.upsertRecipeCategory(newId(), "R1", "C1")

        assertFailsWith<Exception>("the pair index must reject an unguarded duplicate") {
            driver.execute(
                null,
                """INSERT INTO "recipeCategory" ("id", "recipeId", "categoryId") VALUES ('${newId()}', 'R1', 'C1')""",
                0,
            )
        }
        assertEquals(1L, linkCount(driver))
    }
}
