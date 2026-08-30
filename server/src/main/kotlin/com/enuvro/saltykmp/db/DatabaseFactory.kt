package com.enuvro.saltykmp.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

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
                RecipeCategories, RecipeTags, DeviceSyncs, ShoppingLists,
            )
            // SchemaUtils.create only creates missing TABLES; deployments that predate shopping-list
            // revisions need the column added. Idempotent on Postgres and H2 alike.
            exec("ALTER TABLE shopping_list ADD COLUMN IF NOT EXISTS revision BIGINT NOT NULL DEFAULT 1")
            // Likewise for the prepared-date sync channel (client-side counterpart: SHARED-V0004).
            // Nullable with no default: NULL means "no prepared-date agreement recorded yet", which the
            // merge in RecipeRepository.upsert treats as "always lose to an incoming stamp".
            exec("ALTER TABLE recipe ADD COLUMN IF NOT EXISTS last_modified_prepared_date TIMESTAMP")
            // Device sync tokens. Nullable with no default: NULL means "this device has no token",
            // which is both the pre-migration state and the revoked state -- deliberately the same
            // thing, so an existing deployment starts with every device simply un-enrolled.
            exec("ALTER TABLE device_sync ADD COLUMN IF NOT EXISTS token_hash VARCHAR(64)")
            exec("ALTER TABLE device_sync ADD COLUMN IF NOT EXISTS token_issued_at TIMESTAMP")
            exec("ALTER TABLE device_sync ADD COLUMN IF NOT EXISTS token_last_used TIMESTAMP")
            exec("CREATE INDEX IF NOT EXISTS idx_device_sync_token_hash ON device_sync (token_hash)")
        }
    }

    // suspendTransaction (Exposed 1.x) dropped the CoroutineContext parameter its deprecated predecessor
    // newSuspendedTransaction took, so the dispatcher is chosen here instead. It still has to be IO: the
    // driver is JDBC, so every statement blocks its thread and must stay off the request dispatcher.
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction { block() } }
}
