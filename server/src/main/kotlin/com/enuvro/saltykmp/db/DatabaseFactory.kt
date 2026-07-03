package com.enuvro.saltykmp.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.ZoneOffset

object DatabaseFactory {

    fun init(
        jdbcUrl: String,
        driverClassName: String,
        username: String,
        password: String,
    ) {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.driverClassName = driverClassName
            this.username = username
            this.password = password
            maximumPoolSize = 8
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            validate()
        }
        Database.connect(HikariDataSource(config))
        transaction {
            SchemaUtils.create(
                Users, Recipes, Courses, Categories, Tags,
                RecipeCategories, RecipeTags, DeviceSyncs,
            )
            // Lightweight migrations: SchemaUtils.create makes missing TABLES but not missing COLUMNS,
            // so add columns introduced after the first release here. IF NOT EXISTS makes it idempotent
            // and the syntax is accepted by both PostgreSQL (prod) and H2 (tests).
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS is_admin BOOLEAN DEFAULT FALSE NOT NULL")
            // Nullable (no default): legacy rows stay null so their existing tokens aren't retroactively
            // invalidated; new/changed passwords stamp it going forward.
            exec("ALTER TABLE users ADD COLUMN IF NOT EXISTS password_changed_at TIMESTAMP")
            exec("ALTER TABLE recipe ADD COLUMN IF NOT EXISTS last_modified_image_date TIMESTAMP")

            // Backfill rows authored before non-optional recipe columns were guaranteed non-null, so every
            // recipe decodes with concrete values on the client (mirrors the Swift app's NULL-coalescing
            // pass and the KMP write path). Idempotent — each UPDATE only touches NULL rows — and cheap, so
            // it's safe to run on every startup. Uses the Exposed DSL (not raw SQL) so identifier quoting
            // and casing stay correct on both H2 and Postgres.
            val now = LocalDateTime.now(ZoneOffset.UTC)
            Recipes.update({ Recipes.directions.isNull() }) { it[directions] = "[]" }
            Recipes.update({ Recipes.ingredients.isNull() }) { it[ingredients] = "[]" }
            Recipes.update({ Recipes.notes.isNull() }) { it[notes] = "[]" }
            Recipes.update({ Recipes.variations.isNull() }) { it[variations] = "[]" }
            Recipes.update({ Recipes.preparationTimes.isNull() }) { it[preparationTimes] = "[]" }
            Recipes.update({ Recipes.sourceText.isNull() }) { it[sourceText] = "" }
            Recipes.update({ Recipes.sourceDetails.isNull() }) { it[sourceDetails] = "" }
            Recipes.update({ Recipes.introduction.isNull() }) { it[introduction] = "" }
            Recipes.update({ Recipes.yield.isNull() }) { it[yield] = "" }
            Recipes.update({ Recipes.difficulty.isNull() }) { it[difficulty] = 0 }
            Recipes.update({ Recipes.rating.isNull() }) { it[rating] = 0 }
            Recipes.update({ Recipes.isFavorite.isNull() }) { it[isFavorite] = false }
            Recipes.update({ Recipes.wantToMake.isNull() }) { it[wantToMake] = false }
            Recipes.update({ Recipes.createdDate.isNull() }) { it[createdDate] = now }
            Recipes.update({ Recipes.lastModifiedDate.isNull() }) { it[lastModifiedDate] = now }
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
