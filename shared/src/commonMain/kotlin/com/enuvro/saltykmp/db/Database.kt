package com.enuvro.saltykmp.db

import app.cash.sqldelight.ColumnAdapter
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.enuvro.saltykmp.db.model.Difficulty
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Rating
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.db.model.Variation
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private val appJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

// Names that mirror the Swift app's saltyRecipeLibrary bundle so storage is layout-compatible.
const val SALTY_LIBRARY_DIR = "SaltyRecipeLibrary.saltyRecipeLibrary"
const val SALTY_DB_FILE = "saltyRecipeDB.sqlite"
const val SALTY_IMAGES_DIR = "recipeImages"

private fun <T : Any> listAdapter(serializer: KSerializer<T>): ColumnAdapter<List<T>, String> =
    object : ColumnAdapter<List<T>, String> {
        private val listSerializer = ListSerializer(serializer)
        override fun decode(databaseValue: String): List<T> {
            if (databaseValue.isBlank()) return emptyList()
            return appJson.decodeFromString(listSerializer, databaseValue)
        }

        override fun encode(value: List<T>): String =
            appJson.encodeToString(listSerializer, value)
    }

private fun <T : Any> jsonAdapter(serializer: KSerializer<T>): ColumnAdapter<T, String> =
    object : ColumnAdapter<T, String> {
        override fun decode(databaseValue: String): T =
            appJson.decodeFromString(serializer, databaseValue)

        override fun encode(value: T): String =
            appJson.encodeToString(serializer, value)
    }

private val difficultyAdapter = object : ColumnAdapter<Difficulty, Long> {
    override fun decode(databaseValue: Long): Difficulty = Difficulty.fromRawValue(databaseValue)
    override fun encode(value: Difficulty): Long = value.rawValue
}

private val ratingAdapter = object : ColumnAdapter<Rating, Long> {
    override fun decode(databaseValue: Long): Rating = Rating.fromRawValue(databaseValue)
    override fun encode(value: Rating): Long = value.rawValue
}

/**
 * Swift (GRDB) migration identifiers. Seeding these into `grdb_migrations` lets the Salty app treat a
 * KMP-created `saltyRecipeDB.sqlite` as fully migrated, so the same file opens on both platforms.
 * Keep in sync with the Swift app's DatabaseMigrator (Salty/Models/Schema.swift).
 */
private val GRDB_MIGRATIONS = listOf(
    "0001: Create initial tables",
    "0002: Populate default categories, courses, and shopping lists",
    "0003: Add 'variations' column to 'recipe' table",
    "0004: Add 'lastModifiedDate' column to 'category', 'course', and 'tag' tables",
)

private fun tableExists(driver: SqlDriver, name: String): Boolean =
    driver.executeQuery(
        null,
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?",
        { cursor -> QueryResult.Value(cursor.next().value) },
        1,
    ) { bindString(0, name) }.value

/** Reconcile cross-platform bookkeeping: the KMP-only tombstone table + GRDB's + the shared ledger. */
private fun ensureSaltyCompat(driver: SqlDriver) {
    driver.execute(null, """CREATE TABLE IF NOT EXISTS "deletedRecipe" ("id" TEXT NOT NULL PRIMARY KEY, "deletedDate" TEXT NOT NULL)""", 0)
    driver.execute(null, """CREATE TABLE IF NOT EXISTS "grdb_migrations" ("identifier" TEXT NOT NULL PRIMARY KEY)""", 0)
    GRDB_MIGRATIONS.forEach { id ->
        driver.execute(null, """INSERT OR IGNORE INTO "grdb_migrations" ("identifier") VALUES (?)""", 1) { bindString(0, id) }
    }
    ensureSaltyMigrationLedger(driver)
}

// ---- Cross-platform "shared" migration ledger -------------------------------------------------
//
// GRDB (Swift) and SQLDelight (KMP) each have their own per-platform migrator (grdb_migrations /
// PRAGMA user_version), which only coordinate the BASE table creation. For any schema/data change
// that BOTH platforms need on a shared saltyRecipeDB.sqlite, neither native tracker knows what the
// other did. The `saltyMigration` table is the single source of truth that both apps consult: a
// shared migration runs exactly once per DB, whoever opens it first.
//
// The Swift app mirrors this exactly: `runSaltySharedMigrations` + `saltySharedMigrations` in
// Salty/Models/Schema.swift, called from `appDatabase()` right after the GRDB migrator. To add a shared
// change, append a `SharedMigration` HERE and a `SaltySharedMigration` THERE with the SAME identifier.
// Namespace platform-only steps ("kmp:…" / "swift:…") so the other side never references them. (Do NOT
// coordinate shared-table changes via a new GRDB-only migration — only base tables 0001–0004 use that
// path; see GRDB_MIGRATIONS above and TODO.md "Shared migration ledger".)

private fun ensureSaltyMigrationLedger(driver: SqlDriver) {
    driver.execute(
        null,
        """CREATE TABLE IF NOT EXISTS "saltyMigration" ("identifier" TEXT NOT NULL PRIMARY KEY, "platform" TEXT, "appliedDate" TEXT NOT NULL)""",
        0,
    )
}

private fun migrationApplied(driver: SqlDriver, id: String): Boolean =
    driver.executeQuery(
        null,
        "SELECT 1 FROM saltyMigration WHERE identifier = ?",
        { cursor -> QueryResult.Value(cursor.next().value) },
        1,
    ) { bindString(0, id) }.value

@OptIn(ExperimentalTime::class)
private fun recordMigration(driver: SqlDriver, id: String) {
    driver.execute(
        null,
        """INSERT OR IGNORE INTO "saltyMigration" ("identifier", "platform", "appliedDate") VALUES (?, 'kmp', ?)""",
        2,
    ) {
        bindString(0, id)
        bindString(1, Clock.System.now().toString())
    }
}

/** A schema/data change that must run exactly once per DB, coordinated across platforms via [id]. */
class SharedMigration(val id: String, val apply: (SqlDriver) -> Unit)

/**
 * Cross-platform migrations applied in order, each gated on the shared ledger. Add future shared schema
 * changes here AND to the Swift app's `saltySharedMigrations` (Salty/Models/Schema.swift) using the SAME
 * [SharedMigration.id]. Example:
 *
 *   SharedMigration("2026-07-recipe-add-prepNotes") {
 *       it.execute(null, "ALTER TABLE recipe ADD COLUMN prepNotes TEXT", 0)
 *   }
 *
 * Use `ADD COLUMN` (idempotency is provided by the ledger, not the SQL) and append columns at the end
 * so the other platform's `SELECT *` keeps working.
 */
internal val SHARED_MIGRATIONS: List<SharedMigration> = listOf(
    // Decouples image transfer from recipe-body sync. The column is also in Schema.sq (fresh DBs already
    // have it), so guard the ALTER on column existence to stay safe when this runs on a fresh KMP DB or a
    // DB the Swift app already migrated. Mirror: Salty's `saltySharedMigrations` with the SAME id.
    SharedMigration("2026-06-recipe-add-lastModifiedImageDate") { driver ->
        if (!recipeColumnExists(driver, "lastModifiedImageDate")) {
            driver.execute(null, """ALTER TABLE "recipe" ADD COLUMN "lastModifiedImageDate" TEXT""", 0)
        }
    },
)

private fun recipeColumnExists(driver: SqlDriver, column: String): Boolean =
    driver.executeQuery(
        null,
        "SELECT COUNT(*) FROM pragma_table_info('recipe') WHERE name = ?",
        { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0L) },
        1,
    ) { bindString(0, column) }.value

/**
 * Applies any shared migrations not yet recorded in `saltyMigration`. Idempotent and cheap, so it runs
 * on every open — this catches migrations added after a DB was already created (the native trackers
 * wouldn't re-fire) and migrations the OTHER platform hasn't run yet.
 */
internal fun applySharedMigrations(driver: SqlDriver, migrations: List<SharedMigration> = SHARED_MIGRATIONS) {
    ensureSaltyMigrationLedger(driver)
    for (m in migrations) {
        if (!migrationApplied(driver, m.id)) {
            m.apply(driver)
            recordMigration(driver, m.id)
        }
    }
}

/**
 * Wraps the generated schema so one on-disk DB works whether created by this app or the Swift/GRDB app:
 * `create()` skips table creation when a Salty DB already exists (a GRDB-made file), and both paths
 * reconcile the tombstone table + GRDB migration ledger. Pass this to the platform SQLDelight drivers.
 */
/**
 * Advertised schema version. Deliberately high so SQLiter (which refuses to open a DB whose
 * `PRAGMA user_version` is *newer* than the driver's version) accepts a Salty DB stamped with any version
 * — e.g. a v3 file created by another build meeting a v2 binary. This is safe because `user_version` is NOT
 * a shared marker between the two apps: the Swift/GRDB app records its migrations in a `grdb_migrations`
 * table and never writes `user_version`, so only the SQLDelight/SQLiter side ever sets it. Structural
 * migrations are still applied only within the range we actually have `.sqm` files for (see migrate()).
 */
private const val COMPAT_SCHEMA_VERSION = 1000L

val SaltyCompatSchema: SqlSchema<QueryResult.Value<Unit>> = object : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long get() = COMPAT_SCHEMA_VERSION

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        if (!tableExists(driver, "recipe")) AppDatabase.Schema.create(driver)
        ensureSaltyCompat(driver)
        return QueryResult.Value(Unit)
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        // Only run real SQLDelight migrations within the range we have `.sqm` files for. A DB already at or
        // beyond the generated schema version (incl. one stamped by another Salty build) needs no structural
        // change here — SaltyCompatSchema just reconciles the compat bits.
        val generated = AppDatabase.Schema.version
        if (oldVersion < generated) {
            AppDatabase.Schema.migrate(driver, oldVersion, minOf(newVersion, generated), *callbacks)
        }
        ensureSaltyCompat(driver)
        return QueryResult.Value(Unit)
    }
}

/**
 * The driver of the most recently opened [AppDatabase]. Held so [checkpointWal] can flush the WAL into the
 * main `.sqlite` before the linked-folder sync copies the file out. Single live DB per process, so one slot.
 */
private var activeDriver: SqlDriver? = null

/**
 * Checkpoints (TRUNCATE) the write-ahead log so the main `saltyRecipeDB.sqlite` contains all committed data
 * — required before copying the DB file to a linked folder, since recent edits otherwise live only in `-wal`.
 * No-op when no DB is open (e.g. a startup copy-out before the driver is created).
 */
fun checkpointWal() {
    // `PRAGMA wal_checkpoint` RETURNS a row (busy, log, checkpointed), so it must run through the query
    // path. On Kotlin/Native, driver.execute() uses SQLiter's non-query path and throws
    // "Queries can be performed using ... query or rawQuery methods only." Use executeQuery and step the
    // cursor once so the checkpoint actually runs (we ignore the returned values).
    activeDriver?.executeQuery(
        identifier = null,
        sql = "PRAGMA wal_checkpoint(TRUNCATE)",
        mapper = { cursor ->
            cursor.next()
            QueryResult.Value(Unit)
        },
        parameters = 0,
    )
}

/**
 * Backfills NULLs in `recipe` columns the apps treat as non-optional, so a row authored by an older Swift
 * app, an older build, or raw SQL still decodes — the generated row types are now non-null (Schema.sq
 * declares these `NOT NULL DEFAULT`), so a stored NULL would crash on read. Idempotent (each UPDATE only
 * touches NULL rows) and cheap, so it runs on every open. Mirrors the Swift app's `coalesceNullRecipeColumns`
 * and the server's startup backfill. Genuinely-optional columns (lastPrepared, image*, servings, courseId,
 * nutrition) stay nullable.
 */
private fun coalesceNullRecipeColumns(driver: SqlDriver) {
    val statements = listOf(
        """UPDATE "recipe" SET "directions" = '[]' WHERE "directions" IS NULL""",
        """UPDATE "recipe" SET "ingredients" = '[]' WHERE "ingredients" IS NULL""",
        """UPDATE "recipe" SET "notes" = '[]' WHERE "notes" IS NULL""",
        """UPDATE "recipe" SET "variations" = '[]' WHERE "variations" IS NULL""",
        """UPDATE "recipe" SET "preparationTimes" = '[]' WHERE "preparationTimes" IS NULL""",
        """UPDATE "recipe" SET "source" = '' WHERE "source" IS NULL""",
        """UPDATE "recipe" SET "sourceDetails" = '' WHERE "sourceDetails" IS NULL""",
        """UPDATE "recipe" SET "introduction" = '' WHERE "introduction" IS NULL""",
        """UPDATE "recipe" SET "yield" = '' WHERE "yield" IS NULL""",
        """UPDATE "recipe" SET "difficulty" = 0 WHERE "difficulty" IS NULL""",
        """UPDATE "recipe" SET "rating" = 0 WHERE "rating" IS NULL""",
        """UPDATE "recipe" SET "isFavorite" = 0 WHERE "isFavorite" IS NULL""",
        """UPDATE "recipe" SET "wantToMake" = 0 WHERE "wantToMake" IS NULL""",
        """UPDATE "recipe" SET "createdDate" = CURRENT_TIMESTAMP WHERE "createdDate" IS NULL""",
        """UPDATE "recipe" SET "lastModifiedDate" = CURRENT_TIMESTAMP WHERE "lastModifiedDate" IS NULL""",
    )
    statements.forEach { driver.execute(null, it, 0) }
}

fun createAppDatabase(driver: SqlDriver): AppDatabase {
    activeDriver = driver
    // Runs on every open (after the native schema setup), so shared migrations land regardless of which
    // platform created the DB or whether they were added after the DB already existed.
    applySharedMigrations(driver)
    coalesceNullRecipeColumns(driver)
    return AppDatabase(
        driver = driver,
        recipeAdapter = Recipe.Adapter(
            difficultyAdapter = difficultyAdapter,
            ratingAdapter = ratingAdapter,
            directionsAdapter = listAdapter(Direction.serializer()),
            ingredientsAdapter = listAdapter(Ingredient.serializer()),
            notesAdapter = listAdapter(Note.serializer()),
            variationsAdapter = listAdapter(Variation.serializer()),
            preparationTimesAdapter = listAdapter(PreparationTime.serializer()),
            nutritionAdapter = jsonAdapter(NutritionInformation.serializer()),
        ),
        shoppingListAdapter = ShoppingList.Adapter(
            contentsForListAdapter = listAdapter(ShoppingListListContents.serializer()),
        ),
    )
}
