package com.enuvro.saltykmp.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction

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
        }
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
