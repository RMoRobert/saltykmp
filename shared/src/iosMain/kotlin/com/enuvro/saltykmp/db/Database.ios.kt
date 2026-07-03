package com.enuvro.saltykmp.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver

fun createAppDatabase(
    name: String = SALTY_DB_FILE,
): AppDatabase = createAppDatabase(
    // SaltyCompatSchema lets a GRDB-created DB open without re-creating tables and seeds the
    // cross-platform bookkeeping (see Database.kt).
    driver = NativeSqliteDriver(
        schema = SaltyCompatSchema,
        name = name,
        // Enforce the schema's foreign keys (off by default), so the courseId→course FK is honored and
        // ON DELETE SET NULL/CASCADE fire — matching the Swift/GRDB app and preventing dangling refs.
        onConfiguration = { config ->
            config.copy(extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true))
        },
    ),
)
