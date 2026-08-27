package com.enuvro.saltykmp.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.util.Properties

fun createAppDatabase(
    name: String = SALTY_DB_FILE,
): AppDatabase {
    val driver = JdbcSqliteDriver(
        "jdbc:sqlite:$name",
        Properties().apply {
            // WAL from day one — the mode persists in the file, and the Swift app's DatabasePool
            // requires WAL and would flip it anyway — plus a busy timeout so an accidental overlap
            // with the Swift app on a shared library folder waits briefly instead of failing with
            // SQLITE_BUSY. The Swift side sets the matching timeout (AppDatabase.swift busyMode).
            setProperty("journal_mode", "WAL")
            setProperty("busy_timeout", "5000")
        },
    )
    // Enforce the schema's foreign keys (SQLite leaves them OFF per connection by default), so the
    // courseId→course FK is honored and cascades fire — matching the Swift/GRDB app.
    driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    val version = driver.executeQuery(null, "PRAGMA user_version;", { cursor ->
        val result = if (cursor.next().value) cursor.getLong(0) else 0L
        QueryResult.Value(result)
    }, 0).value ?: 0L

    // SaltyCompatSchema makes create() a no-op when a GRDB-made DB already has the tables, and seeds the
    // cross-platform bookkeeping, so the same saltyRecipeDB.sqlite opens here and in the Swift app.
    val schemaVersion = SaltyCompatSchema.version
    if (version == 0L) {
        SaltyCompatSchema.create(driver)
        driver.execute(null, "PRAGMA user_version = $schemaVersion;", 0)
    } else if (version < schemaVersion) {
        SaltyCompatSchema.migrate(driver, version, schemaVersion)
        driver.execute(null, "PRAGMA user_version = $schemaVersion;", 0)
    }
    return createAppDatabase(driver)
}
