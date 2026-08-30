package com.enuvro.saltykmp.contract

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.db.GRDB_MIGRATIONS
import com.enuvro.saltykmp.db.SHARED_MIGRATIONS
import com.enuvro.saltykmp.export.toSwiftIso8601
import com.enuvro.saltykmp.sync.LocalStore
import com.enuvro.saltykmp.sync.SyncReconciler
import com.enuvro.saltykmp.util.newId
import com.enuvro.saltykmp.util.normalizeId
import com.enuvro.saltykmp.util.wireIso
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Runs the shared conformance corpus (`salty-contract/corpus`) against this client's implementation.
 * The Swift and .NET cores run the same cases through their own runners.
 *
 * This suite deliberately holds no expected values of its own — they all live in the corpus, so adding
 * a case there adds it to three clients at once. What lives here is only the mapping from a corpus
 * `op` to the function in `shared` that implements it.
 *
 * An unmapped `op` FAILS rather than skipping. Silently ignoring a case nobody wired up turns a new
 * rule into no coverage at all, which is the failure mode the corpus exists to prevent.
 *
 * Lives in `jvmTest` rather than `commonTest` because the corpus is read from disk and resource
 * loading is not uniform across KMP targets. Everything under test is `commonMain` with no
 * expect/actual on any path here, so running it on the JVM exercises the same code every target gets.
 */
class ContractCorpusTest {

    private val platform = "kmp"

    @Test fun dates() = runSuite("dates")
    @Test fun ids() = runSuite("ids")
    @Test fun migrations() = runSuite("migrations")
    @Test fun reconciler() = runSuite("reconciler")

    private fun runSuite(suite: String) {
        val cases = allCases.filter { it.suite == suite }
        assertTrue(cases.isNotEmpty(), "no cases loaded for suite '$suite'")

        val failures = mutableListOf<String>()
        val waived = mutableListOf<String>()

        for (case in cases) {
            val why = case.knownDivergence?.get(platform)
            val outcome = runCatching { execute(case) }

            when {
                // A waiver is a claim that this client fails the case. Verify the claim: if it now
                // passes, the divergence is fixed and the entry is stale, which is a failure of its own
                // -- otherwise waivers accumulate and quietly become permanent.
                why != null && outcome.isFailure -> waived += "${case.because}\n    reason: $why"
                why != null -> failures += "${case.id} is marked as a known divergence for '$platform' " +
                    "but PASSED.\n    recorded reason: $why\n    Delete the known_divergence entry and " +
                    "its note in SPEC.md §7."
                outcome.isFailure -> failures += "${case.because}\n    ${outcome.exceptionOrNull()?.message}"
            }
        }

        waived.forEach { println("WAIVED $it") }
        println("$suite: ${cases.size - failures.size - waived.size} passed, ${waived.size} waived, ${failures.size} failed")

        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size} case(s) failed in '$suite':\n\n" + failures.joinToString("\n\n"))
        }
    }

    @OptIn(ExperimentalTime::class)
    private fun execute(c: CorpusCase) {
        when (c.op) {
            // ---- dates ---------------------------------------------------------------------------

            // KMP has no single "parse a DATETIME column" entry point: LocalStore converts to the wire
            // form first and parses that, which is exactly how every read path calls it (see the
            // parseOrPast(dbToWireDate(...)) pairs in LocalStore). The composition IS the implementation.
            "parse_database" -> assertEquals(
                c.expect.epochMs(),
                LocalStore.parseOrNull(LocalStore.dbToWireDate(c.input.stringOrNull()))?.toEpochMilliseconds(),
                c.because,
            )

            "format_database" -> assertEquals(
                c.expect.stringOrNull(),
                LocalStore.wireToDbDate(wireIso(c.inputInstant())),
                c.because,
            )

            "format_wire" -> assertEquals(c.expect.stringOrNull(), wireIso(c.inputInstant()), c.because)

            "format_file" -> assertEquals(
                c.expect.stringOrNull(),
                toSwiftIso8601(c.inputInstant().toString()),
                c.because,
            )

            "wire_to_database" -> assertEquals(
                c.expect.stringOrNull(), LocalStore.wireToDbDate(c.input.stringOrNull()), c.because,
            )

            "database_to_wire" -> assertEquals(
                c.expect.stringOrNull(), LocalStore.dbToWireDate(c.input.stringOrNull()), c.because,
            )

            "now_precision" -> {
                val allowed = c.expect.jsonObject["max_sub_millisecond_ticks"]!!.jsonPrimitive.content.toLong()
                val now = Instant.parse(LocalStore.nowIso())
                assertTrue(
                    (now.nanosecondsOfSecond % 1_000_000L) <= allowed,
                    "${c.because}\n    ${LocalStore.nowIso()} carries sub-millisecond precision",
                )
            }

            // ---- ids -----------------------------------------------------------------------------

            "new_id" -> {
                val pattern = Regex(c.expect.jsonObject["matches"]!!.jsonPrimitive.content)
                repeat(ID_SAMPLE_SIZE) {
                    val id = newId()
                    assertTrue(pattern.matches(id), "${c.because}\n    offending id: $id")
                }
            }

            "new_id_uniqueness" -> {
                val size = c.expect.jsonObject["sample_size"]!!.jsonPrimitive.content.toInt()
                val ids = List(size) { newId() }
                assertEquals(size, ids.toSet().size, c.because)
            }

            "normalize_id" -> assertEquals(
                c.expect.stringOrNull(), normalizeId(c.input.stringOrNull()!!), c.because,
            )

            // Minted, not derived (ID-005). The corpus asks the two things that remain true of an
            // opaque id: two calls for the same pair differ, and the result is an ordinary identifier.
            "junction_id" -> {
                val first = newId()
                c.expect.jsonObject["distinct"]?.let {
                    assertTrue(newId() != first, c.because)
                }
                c.expect.jsonObject["matches"]?.let {
                    val pattern = Regex(it.jsonPrimitive.content)
                    assertTrue(pattern.matches(first), "${c.because}\n    offending id: $first")
                }
            }

            // ---- migrations ----------------------------------------------------------------------

            "grdb_migration_identifiers" -> assertEquals(c.expect.strings(), GRDB_MIGRATIONS, c.because)

            "shared_migration_identifiers" ->
                assertEquals(c.expect.strings(), SHARED_MIGRATIONS.map { it.id }, c.because)

            "shared_migration_effects" -> assertMigrationEffects(c)

            // ---- reconciler ----------------------------------------------------------------------

            "reconcile" -> assertReconcile(c)

            "deletion_guard" -> assertDeletionGuard(c)

            else -> fail(
                "Unmapped corpus op '${c.op}' in ${c.id}.\n" +
                    "    Map it to a function in `shared` here, or remove the case from the corpus."
            )
        }
    }

    /**
     * Applies each shared migration to a database that predates it and checks the column it claims to
     * add really arrives, with the declared type.
     *
     * The tables are created bare rather than through `AppDatabase.Schema`, because a KMP-created
     * database already declares every column up front — running the migrations against one would only
     * exercise their guards and would pass even if a migration did nothing at all.
     */
    private fun assertMigrationEffects(c: CorpusCase) {
        val mismatches = mutableListOf<String>()
        for (effect in c.expect.jsonArray) {
            val id = effect.jsonObject["id"]!!.jsonPrimitive.content
            val migration = SHARED_MIGRATIONS.singleOrNull { it.id == id }
                ?: fail("${c.because}\n    no shared migration is declared with id '$id'")
            val columns = effect.jsonObject["adds_columns"]!!.jsonArray

            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            try {
                columns.map { it.jsonObject["table"]!!.jsonPrimitive.content }.distinct().forEach { table ->
                    driver.execute(null, """CREATE TABLE "$table" ("id" TEXT PRIMARY KEY NOT NULL)""", 0)
                }

                migration.apply(driver)

                // Collected rather than asserted one at a time: a case covering five migrations would
                // otherwise report only the first mismatch and hide the rest behind it.
                for (column in columns) {
                    val table = column.jsonObject["table"]!!.jsonPrimitive.content
                    val name = column.jsonObject["column"]!!.jsonPrimitive.content
                    val type = column.jsonObject["type"]!!.jsonPrimitive.content
                    val actual = declaredType(driver, table, name)
                    if (actual != type) {
                        mismatches += "$id must add $table.$name as $type, but it is ${actual ?: "absent"}"
                    }
                }
            } finally {
                driver.close()
            }
        }

        if (mismatches.isNotEmpty()) {
            fail("${c.because}\n    " + mismatches.joinToString("\n    "))
        }
    }

    private fun declaredType(driver: SqlDriver, table: String, column: String): String? =
        driver.executeQuery(
            null,
            "SELECT type FROM pragma_table_info('$table') WHERE name = ?",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            1,
        ) { bindString(0, column) }.value

    @OptIn(ExperimentalTime::class)
    private fun assertReconcile(c: CorpusCase) {
        val input = json.decodeFromJsonElement(ReconcileInput.serializer(), c.input)

        val plan = SyncReconciler.plan(
            local = input.local.map {
                SyncReconciler.Entry(
                    it.id,
                    Instant.fromEpochMilliseconds(it.lastModifiedMs),
                    it.syncedModifiedMs?.let(Instant::fromEpochMilliseconds),
                )
            },
            server = input.server.map {
                SyncReconciler.Entry(it.id, Instant.fromEpochMilliseconds(it.lastModifiedMs))
            },
            isFirstSync = input.isFirstSync,
            lastSyncDate = input.lastSyncMs?.let(Instant::fromEpochMilliseconds),
            tracksAgreement = input.tracksAgreement,
        )

        // Sets, not sequences: no rule constrains the order ids come back in, and pinning an incidental
        // order would make a harmless refactor look like a regression.
        assertEquals(c.ids("to_upload"), plan.toUpload.toSet(), "${c.because}\n    [toUpload]")
        assertEquals(c.ids("to_download"), plan.toDownload.toSet(), "${c.because}\n    [toDownload]")
        assertEquals(c.ids("to_delete_locally"), plan.toDeleteLocally.toSet(), "${c.because}\n    [toDeleteLocally]")
        assertEquals(c.ids("to_delete_on_server"), plan.toDeleteOnServer.toSet(), "${c.because}\n    [toDeleteOnServer]")
    }

    /**
     * SYNC-016: whether deletions inferred from a side's absence may be applied at all.
     *
     * The predicate only. That a client consults it on BOTH directions of EVERY collection is wiring
     * rather than a value, so it cannot be seen from here and each client pins it with its own tests.
     * What this stops is the three drifting on the rule itself — which is what happened when the local
     * half existed and the server half did not.
     */
    private fun assertDeletionGuard(c: CorpusCase) {
        val input = json.decodeFromJsonElement(DeletionGuardInput.serializer(), c.input)

        assertEquals(
            c.expect.jsonObject["allows"]!!.jsonPrimitive.boolean,
            SyncReconciler.allowsDeletions(input.sideCount, input.pendingDeletions),
            c.because,
        )
    }

    // ---- corpus value helpers ------------------------------------------------------------------

    private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonElement.strings(): List<String> = jsonArray.map { it.jsonPrimitive.content }

    private fun JsonElement.epochMs(): Long? =
        jsonObject["epoch_ms"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLong()

    @OptIn(ExperimentalTime::class)
    private fun CorpusCase.inputInstant(): Instant =
        Instant.fromEpochMilliseconds(input.jsonObject["epoch_ms"]!!.jsonPrimitive.content.toLong())

    private fun CorpusCase.ids(key: String): Set<String> =
        expect.jsonObject[key]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    @Serializable
    private data class ReconcileEntry(
        val id: String,
        @SerialName("last_modified_ms") val lastModifiedMs: Long,
        @SerialName("synced_modified_ms") val syncedModifiedMs: Long? = null,
    )

    @Serializable
    private data class ReconcileInput(
        val local: List<ReconcileEntry> = emptyList(),
        val server: List<ReconcileEntry> = emptyList(),
        @SerialName("is_first_sync") val isFirstSync: Boolean = false,
        @SerialName("last_sync_ms") val lastSyncMs: Long? = null,
        @SerialName("tracks_agreement") val tracksAgreement: Boolean = false,
    )

    @Serializable
    private data class DeletionGuardInput(
        @SerialName("side_count") val sideCount: Int,
        @SerialName("pending_deletions") val pendingDeletions: Int,
    )

    private companion object {
        /** How many ids the generator cases draw before asserting shape. */
        const val ID_SAMPLE_SIZE = 1000

        val json = Json { ignoreUnknownKeys = true }
        val allCases: List<CorpusCase> by lazy { CorpusLoader.load() }
    }
}
