package com.enuvro.saltykmp.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

fun createAppDatabase(
    name: String = SALTY_DB_FILE,
): AppDatabase {
    val driver = JdbcSqliteDriver("jdbc:sqlite:$name")
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
