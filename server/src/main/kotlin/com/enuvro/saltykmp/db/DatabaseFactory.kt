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
        }
    }

    // suspendTransaction (Exposed 1.x) dropped the CoroutineContext parameter its deprecated predecessor
    // newSuspendedTransaction took, so the dispatcher is chosen here instead. It still has to be IO: the
    // driver is JDBC, so every statement blocks its thread and must stay off the request dispatcher.
    suspend fun <T> dbQuery(block: suspend () -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction { block() } }
}
