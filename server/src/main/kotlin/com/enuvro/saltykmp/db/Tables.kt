package com.enuvro.saltykmp.db

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime

// All recipe/library rows are user-scoped (userId FK). Recipe list/object fields are stored as JSON text
// (serialized with appJson) to mirror the Spring CLOB converters. Timestamps are UTC LocalDateTime.

object Users : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    // Admins can manage other users (create/delete/reset passwords) via the web UI. The first/seeded
    // account is promoted to admin (see UserRepository.ensureAdminExists). Added after initial release,
    // so DatabaseFactory.init runs an idempotent ALTER for pre-existing DBs.
    val isAdmin = bool("is_admin").default(false)
    // Bumped whenever the password changes (and set on creation). A JWT issued before this instant is
    // treated as stale and rejected (see Auth.configureAuth), so a password reset invalidates old tokens.
    // Added after initial release → idempotent ALTER in DatabaseFactory.init; null on legacy rows (no epoch
    // check, so pre-existing tokens keep working until they expire).
    val passwordChangedAt = datetime("password_changed_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Recipes : Table("recipe") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val name = text("name")
    val createdDate = datetime("created_date").nullable()
    val lastModifiedDate = datetime("last_modified_date").nullable().index()
    val lastPrepared = datetime("last_prepared").nullable()
    val sourceDetails = text("source_details").nullable()
    val introduction = text("introduction").nullable()
    val sourceText = text("source").nullable()
    val difficulty = integer("difficulty").nullable()
    val rating = integer("rating").nullable()
    val imageFilename = varchar("image_filename", 512).nullable()
    // Bumped only on image change (set/replace/remove), independent of lastModifiedDate. Lets sync move
    // image bytes only when the image actually changed. Added after initial release → ALTER in DatabaseFactory.
    val lastModifiedImageDate = datetime("last_modified_image_date").nullable()
    val isFavorite = bool("is_favorite").nullable()
    val wantToMake = bool("want_to_make").nullable()
    val yield = text("yield").nullable()
    val servings = integer("servings").nullable()
    val courseId = varchar("course_id", 64).nullable()
    val directions = text("directions").nullable()
    val ingredients = text("ingredients").nullable()
    val notes = text("notes").nullable()
    val variations = text("variations").nullable()
    val preparationTimes = text("preparation_times").nullable()
    val nutrition = text("nutrition").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Courses : Table("course") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val name = text("name").nullable()
    val lastModifiedDate = datetime("last_modified_date").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Categories : Table("category") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val name = text("name").nullable()
    val lastModifiedDate = datetime("last_modified_date").nullable()
    override val primaryKey = PrimaryKey(id)
}

object Tags : Table("tag") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val name = text("name").nullable()
    val lastModifiedDate = datetime("last_modified_date").nullable()
    override val primaryKey = PrimaryKey(id)
}

object RecipeCategories : Table("recipe_category") {
    val id = varchar("id", 64)
    val recipeId = varchar("recipe_id", 64).index()
    val categoryId = varchar("category_id", 64)
    override val primaryKey = PrimaryKey(id)
}

object RecipeTags : Table("recipe_tag") {
    val id = varchar("id", 64)
    val recipeId = varchar("recipe_id", 64).index()
    val tagId = varchar("tag_id", 64)
    override val primaryKey = PrimaryKey(id)
}

object DeviceSyncs : Table("device_sync") {
    val deviceId = varchar("device_id", 128)
    val userId = varchar("user_id", 64).index()
    val deviceName = varchar("device_name", 255).nullable()
    val firstSyncDate = datetime("first_sync_date").nullable()
    val lastSyncDate = datetime("last_sync_date").nullable()
    override val primaryKey = PrimaryKey(deviceId)
}
