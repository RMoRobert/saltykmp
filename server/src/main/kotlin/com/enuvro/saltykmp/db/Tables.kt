package com.enuvro.saltykmp.db

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime

// All recipe/library rows are user-scoped (userId FK). Recipe list/object fields are stored as JSON text
// (serialized with appJson) to mirror the Spring CLOB converters. Timestamps are UTC LocalDateTime.

object Users : Table("users") {
    val id = varchar("id", 64)
    val username = varchar("username", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    // Admins can manage other users (create/delete/reset passwords) via the web UI. The first/seeded
    // account is created as an admin (see UserRepository.seedIfEmpty).
    val isAdmin = bool("is_admin").default(false)
    // Bumped whenever the password changes (and set on creation). A JWT issued before this instant is
    // treated as stale and rejected (see Auth.configureAuth), so a password reset invalidates old tokens.
    val passwordChangedAt = datetime("password_changed_at")
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
    // Bumped only when lastPrepared changes ("marked as prepared"), independent of lastModifiedDate — so
    // marking a recipe prepared never looks like a body edit to clients sorting by Date Modified. Sync
    // reconciles lastPrepared against this, and upsert merges the field by it (newer wins) so a stale
    // body upload can't clobber a newer prepared date. Added after initial release → ALTER in
    // DatabaseFactory.
    val lastModifiedPreparedDate = datetime("last_modified_prepared_date").nullable()
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

// A shopping list is either a checklist (`contents_for_list`, a JSON array of items) or a freeform
// Markdown document (`contents_for_freeform`), fixed at creation. Sync granularity is the whole row —
// most-recently-modified wins — so items deliberately have no server-side identity of their own.
object ShoppingLists : Table("shopping_list") {
    val id = varchar("id", 64)
    val userId = varchar("user_id", 64).index()
    val name = text("name").nullable()
    val isFreeform = bool("is_freeform").nullable()
    val contentsForList = text("contents_for_list").nullable()
    val contentsForFreeform = text("contents_for_freeform").nullable()
    val lastModifiedDate = datetime("last_modified_date").nullable().index()
    // Optimistic-concurrency counter, bumped on every accepted write (API sync and web edits alike).
    // Clients compare it against the revision they last synced — unlike lastModifiedDate (stamped by
    // the editing device), it reliably answers "has this row changed hands since I last looked".
    val revision = long("revision").default(1)
    override val primaryKey = PrimaryKey(id)
}

object DeviceSyncs : Table("device_sync") {
    val deviceId = varchar("device_id", 128)
    val userId = varchar("user_id", 64).index()
    val deviceName = varchar("device_name", 255).nullable()
    val firstSyncDate = datetime("first_sync_date").nullable()
    val lastSyncDate = datetime("last_sync_date").nullable()

    // --- device sync tokens -------------------------------------------------------------------
    // A client authenticates once with a password and is handed a token that can ONLY sync, so it
    // never has to store the password -- the one credential that could also change the password.
    //
    // HMAC-SHA256 of the token, hex. Indexed because a presented token is looked up by hash alone,
    // with no user in hand yet. NULL means revoked or never enrolled, which is what makes revoking
    // a single device a one-column write that keeps the row's sync history intact.
    val tokenHash = varchar("token_hash", 64).nullable().index()
    // Compared against the user's passwordChangedAt, exactly as the JWT validator compares `iat`,
    // so a token that predates a password change cannot be used even if revocation missed it.
    val tokenIssuedAt = datetime("token_issued_at").nullable()
    // What makes the devices page worth reading: "last synced 11 months ago" is a revoke decision
    // you can make without thinking. Tokens never expire, so this is the only staleness signal.
    val tokenLastUsed = datetime("token_last_used").nullable()
    // Composite (user_id, device_id): device ids are client-supplied, so scoping the key by user lets two
    // users present the same device id without a primary-key collision (which used to 500 the second user's
    // sync registration). user_id leads so it also covers user-scoped lookups (e.g. delete-all-for-user).
    override val primaryKey = PrimaryKey(userId, deviceId)
}
