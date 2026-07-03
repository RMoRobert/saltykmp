package com.enuvro.saltykmp.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

fun createAppDatabase(
    context: Context,
    name: String = SALTY_DB_FILE,
): AppDatabase = createAppDatabase(
    // SaltyCompatSchema lets a GRDB-created DB open without re-creating tables and seeds the
    // cross-platform bookkeeping (see Database.kt).
    driver = AndroidSqliteDriver(
        schema = SaltyCompatSchema,
        context = context,
        name = name,
        // Enforce the schema's foreign keys (SQLite leaves them OFF by default), so the courseId→course
        // FK is honored and ON DELETE SET NULL/CASCADE fire — matching the Swift/GRDB app and preventing
        // dangling references. Must be enabled in onConfigure, outside any transaction.
        callback = object : AndroidSqliteDriver.Callback(SaltyCompatSchema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
            }
        },
    ),
)
