package com.enuvro.saltykmp.db

import at.favre.lib.crypto.bcrypt.BCrypt
import com.enuvro.saltykmp.db.DatabaseFactory.dbQuery
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

data class UserRow(
    val id: String,
    val username: String,
    val passwordHash: String,
    val isAdmin: Boolean,
    /** When the password was last set (UTC). A token issued before this is treated as stale. */
    val passwordChangedAt: LocalDateTime,
)

object UserRepository {

    private fun map(row: ResultRow) =
        UserRow(row[Users.id], row[Users.username], row[Users.passwordHash], row[Users.isAdmin], row[Users.passwordChangedAt])

    suspend fun findByUsername(username: String): UserRow? = dbQuery {
        Users.selectAll().where { Users.username eq username }.limit(1).map(::map).singleOrNull()
    }

    suspend fun findById(id: String): UserRow? = dbQuery {
        Users.selectAll().where { Users.id eq id }.limit(1).map(::map).singleOrNull()
    }

    suspend fun listAll(): List<UserRow> = dbQuery {
        Users.selectAll().orderBy(Users.username to SortOrder.ASC).map(::map)
    }

    suspend fun existsByUsername(username: String): Boolean = dbQuery {
        Users.selectAll().where { Users.username eq username }.limit(1).any()
    }

    suspend fun adminCount(): Long = dbQuery {
        Users.selectAll().where { Users.isAdmin eq true }.count()
    }

    suspend fun create(username: String, password: String, isAdmin: Boolean = false): UserRow = dbQuery {
        val id = UUID.randomUUID().toString()
        val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
        val now = LocalDateTime.now(ZoneOffset.UTC)
        Users.insert {
            it[Users.id] = id
            it[Users.username] = username
            it[Users.passwordHash] = hash
            it[Users.isAdmin] = isAdmin
            it[Users.passwordChangedAt] = now
        }
        UserRow(id, username, hash, isAdmin, now)
    }

    suspend fun changePassword(id: String, newPassword: String) = dbQuery {
        val hash = BCrypt.withDefaults().hashToString(12, newPassword.toCharArray())
        // Stamp the change so previously-issued JWTs for this user are rejected as stale.
        Users.update({ Users.id eq id }) {
            it[Users.passwordHash] = hash
            it[Users.passwordChangedAt] = LocalDateTime.now(ZoneOffset.UTC)
        }
        Unit
    }

    suspend fun setAdmin(id: String, isAdmin: Boolean) = dbQuery {
        Users.update({ Users.id eq id }) { it[Users.isAdmin] = isAdmin }
        Unit
    }

    fun verifyPassword(plain: String, hash: String): Boolean =
        BCrypt.verifyer().verify(plain.toCharArray(), hash).verified

    /**
     * A throwaway bcrypt hash (same cost as real ones) used only to spend equivalent CPU when the account
     * doesn't exist — so login timing doesn't reveal whether a username is valid (user enumeration). It can
     * never match a real password: it's the hash of a random, unguessable string.
     */
    private val dummyHash: String =
        BCrypt.withDefaults().hashToString(12, ("no-such-account-" + UUID.randomUUID()).toCharArray())

    /**
     * Verifies [plain] against [user]'s stored hash, or — when [user] is null — against [dummyHash] so the
     * call takes the same time whether or not the account exists. Returns true only for a real user whose
     * password matches. Callers should still re-check `user != null` for a non-null smart-cast.
     */
    fun verifyCredential(user: UserRow?, plain: String): Boolean {
        val matches = verifyPassword(plain, user?.passwordHash ?: dummyHash)
        return matches && user != null
    }

    /**
     * Deletes a user and ALL of their data (recipes, library, junctions, device-sync rows). Returns the
     * recipe image filenames that were attached so the caller can remove them from disk. There is no FK
     * cascade in the schema, so each user-scoped table is cleared explicitly.
     */
    suspend fun deleteWithData(id: String): List<String> = dbQuery {
        val recipeIds = Recipes.selectAll().where { Recipes.userId eq id }.map { it[Recipes.id] }
        val images = Recipes.selectAll().where { Recipes.userId eq id }
            .mapNotNull { it[Recipes.imageFilename] }
        recipeIds.forEach { rid ->
            RecipeCategories.deleteWhere { RecipeCategories.recipeId eq rid }
            RecipeTags.deleteWhere { RecipeTags.recipeId eq rid }
        }
        Recipes.deleteWhere { userId eq id }
        Courses.deleteWhere { userId eq id }
        Categories.deleteWhere { userId eq id }
        Tags.deleteWhere { userId eq id }
        DeviceSyncs.deleteWhere { userId eq id }
        Users.deleteWhere { Users.id eq id }
        images
    }

    /** Seed a default account if the table is empty (so there's something to log in with). It is an admin. */
    suspend fun seedIfEmpty(username: String, password: String) {
        val exists = dbQuery { Users.selectAll().limit(1).any() }
        if (!exists) create(username, password, isAdmin = true)
    }
}
