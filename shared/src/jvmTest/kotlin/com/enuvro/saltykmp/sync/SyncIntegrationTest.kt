package com.enuvro.saltykmp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.api.SyncDeleteRequest
import com.enuvro.saltykmp.api.SyncDeleteResponse
import com.enuvro.saltykmp.api.apiJson
import com.enuvro.saltykmp.db.AppDatabase
import com.enuvro.saltykmp.db.createAppDatabase
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Full client-pipeline test: a real in-memory SQLDelight DB + a MockEngine standing in for the server,
 * driving [SyncService.syncNow]. Proves reconciler + api client + local store + orchestration converge
 * both sides on a first sync (local-only uploads; server-only downloads).
 */
class SyncIntegrationTest {

    /** Minimal in-memory server keyed on the endpoints syncNow touches. */
    private class FakeServer {
        val recipes = linkedMapOf<String, ServerRecipe>()
        val courses = linkedMapOf<String, ServerCourse>()
        val categories = linkedMapOf<String, ServerCategory>()
        val tags = linkedMapOf<String, ServerTag>()
        val shoppingLists = linkedMapOf<String, ServerShoppingList>()
        val images = mutableMapOf<String, ByteArray>()
        var registered = false
        var completed = false
        /** Returned as the device's lastSyncDate on registration — set it to exercise watermark paths. */
        var lastSyncDate: String? = null
        /** Makes the next shopping-list DELETE 409 with the current row (simulates an edit racing a delete). */
        var deleteConflictsOnce = false

        /** Mirror of the real server's revision handling in ShoppingListRepository.save. */
        fun saveShoppingList(l: ServerShoppingList): Pair<HttpStatusCode, ServerShoppingList> {
            val current = shoppingLists[l.id]
            val accepted = current == null || l.baseRevision == null || l.baseRevision == current.revision
            if (!accepted) return HttpStatusCode.Conflict to current!!
            val saved = l.copy(revision = (current?.revision ?: 0L) + 1L, baseRevision = null)
            shoppingLists[l.id] = saved
            return HttpStatusCode.Created to saved
        }

        /**
         * Mirror of the real server's independent-field merge in RecipeRepository.upsert: the "last made
         * on" pair is resolved by lastModifiedPreparedDate (newer wins), NOT by the body clock — so a
         * body upload carrying a stale prepared date can't clobber a newer mark-as-made.
         */
        fun saveRecipe(incoming: ServerRecipe): ServerRecipe {
            val existing = recipes[incoming.id]
            val incomingStamp = LocalStore.parseOrPast(incoming.lastModifiedPreparedDate)
            val existingStamp = LocalStore.parseOrPast(existing?.lastModifiedPreparedDate)
            val merged = if (existing == null || incomingStamp >= existingStamp) {
                incoming
            } else {
                incoming.copy(
                    lastPrepared = existing.lastPrepared,
                    lastModifiedPreparedDate = existing.lastModifiedPreparedDate,
                )
            }
            recipes[incoming.id] = merged
            return merged
        }

        private inline fun <reified T> bodyOf(content: Any?): T =
            apiJson.decodeFromString((content as TextContent).text)

        fun engine() = MockEngine { request ->
            val path = request.url.encodedPath
            val get = request.method == HttpMethod.Get
            val post = request.method == HttpMethod.Post
            when {
                path == "/api/recipes/sync/device" && post -> {
                    val first = !registered; registered = true
                    jsonOk(apiJson.encodeToString(DeviceSyncInfo(deviceId = "test-device", isFirstSync = first, lastSyncDate = lastSyncDate)))
                }
                path.endsWith("/complete") && post -> { completed = true; respond("", HttpStatusCode.OK) }

                path == "/api/recipes/sync/manifest" && get ->
                    jsonOk(apiJson.encodeToString(recipes.values.map {
                        RecipeManifestEntry(
                            it.id, it.lastModifiedDate, it.imageFilename, it.lastModifiedImageDate,
                            it.lastPrepared, it.lastModifiedPreparedDate,
                        )
                    }), recipes.size)

                path == "/api/recipes" && get -> {
                    val page = request.url.parameters["page"]?.toInt() ?: 0
                    val size = request.url.parameters["size"]?.toInt() ?: 100
                    val all = recipes.values.toList()
                    jsonOk(apiJson.encodeToString(all.drop(page * size).take(size)), all.size)
                }
                path == "/api/recipes" && post -> {
                    val r = saveRecipe(bodyOf(request.body))
                    jsonOk(apiJson.encodeToString(r), status = HttpStatusCode.Created)
                }
                path == "/api/recipes/sync/delete" && post -> {
                    val req = bodyOf<SyncDeleteRequest>(request.body)
                    req.recipeIds.forEach { recipes.remove(it) }
                    jsonOk(apiJson.encodeToString(SyncDeleteResponse(req.recipeIds.size)))
                }

                path.startsWith("/api/recipes/images/") && get -> {
                    val fn = path.substringAfterLast("/")
                    val bytes = images[fn]
                    if (bytes != null) respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
                    else respond("", HttpStatusCode.NotFound)
                }
                path.contains("/image") && post -> {
                    // Very crude multipart byte extraction for the mock.
                    images[path.substringAfter("/api/recipes/").substringBefore("/image") + ".jpg"] = "fake-bytes".encodeToByteArray()
                    jsonOk("""{"filename":"uploaded.jpg"}""")
                }

                // Courses / categories / tags: full lists, upsert by id, unconditional delete. Empty
                // unless a test seeds them, so the "no-op sync" cases behave exactly as before.
                path == "/api/courses" && get -> jsonOk(apiJson.encodeToString(courses.values.toList()), courses.size)
                path == "/api/courses" && post -> {
                    val c = bodyOf<ServerCourse>(request.body).also { courses[it.id] = it }
                    jsonOk(apiJson.encodeToString(c), status = HttpStatusCode.Created)
                }
                path.startsWith("/api/courses/") && request.method == HttpMethod.Delete -> {
                    courses.remove(path.substringAfterLast("/"))
                    respond("", HttpStatusCode.NoContent)
                }
                path == "/api/categories" && get -> jsonOk(apiJson.encodeToString(categories.values.toList()), categories.size)
                path == "/api/categories" && post -> {
                    val c = bodyOf<ServerCategory>(request.body).also { categories[it.id] = it }
                    jsonOk(apiJson.encodeToString(c), status = HttpStatusCode.Created)
                }
                path.startsWith("/api/categories/") && request.method == HttpMethod.Delete -> {
                    categories.remove(path.substringAfterLast("/"))
                    respond("", HttpStatusCode.NoContent)
                }
                path == "/api/tags" && get -> jsonOk(apiJson.encodeToString(tags.values.toList()), tags.size)
                path == "/api/tags" && post -> {
                    val t = bodyOf<ServerTag>(request.body).also { tags[it.id] = it }
                    jsonOk(apiJson.encodeToString(t), status = HttpStatusCode.Created)
                }
                path.startsWith("/api/tags/") && request.method == HttpMethod.Delete -> {
                    tags.remove(path.substringAfterLast("/"))
                    respond("", HttpStatusCode.NoContent)
                }
                path == "/api/shoppingLists" && get ->
                    // The real DB defaults revision to 1, so seeded rows never serve a null revision.
                    jsonOk(
                        apiJson.encodeToString(shoppingLists.values.map { it.copy(revision = it.revision ?: 1L) }),
                        shoppingLists.size,
                    )
                path == "/api/shoppingLists" && post -> {
                    val (status, body) = saveShoppingList(bodyOf<ServerShoppingList>(request.body))
                    jsonOk(apiJson.encodeToString(body), status = status)
                }
                path.startsWith("/api/shoppingLists/") && request.method == HttpMethod.Delete -> {
                    val id = path.substringAfterLast("/")
                    val current = shoppingLists[id]
                    if (deleteConflictsOnce && current != null) {
                        deleteConflictsOnce = false
                        jsonOk(apiJson.encodeToString(current.copy(revision = current.revision ?: 1L)), status = HttpStatusCode.Conflict)
                    } else {
                        shoppingLists.remove(id)
                        respond("", HttpStatusCode.NoContent)
                    }
                }

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        private fun io.ktor.client.engine.mock.MockRequestHandleScope.jsonOk(
            body: String,
            count: Int? = null,
            status: HttpStatusCode = HttpStatusCode.OK,
        ) = respond(
            body, status,
            if (count != null) {
                headersOf(HttpHeaders.ContentType to listOf("application/json"), "X-Total-Count" to listOf("$count"))
            } else {
                headersOf(HttpHeaders.ContentType, "application/json")
            },
        )
    }

    /**
     * Shopping lists converge both directions on a first sync, and the checklist items survive the
     * round trip through the JSON column — including the heading/completed flags, which are the parts
     * the Swift and KMP models most recently diverged on.
     */
    @Test
    fun firstSyncConvergesShoppingListsBothDirections() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertShoppingList(
            ServerShoppingList(
                id = "localList", name = "Local Groceries", isFreeform = false,
                contentsForList = listOf(
                    com.enuvro.saltykmp.db.model.ShoppingListListContents(id = "h1", isHeading = true, text = "Produce"),
                    com.enuvro.saltykmp.db.model.ShoppingListListContents(id = "i1", isCompleted = true, text = "Apples"),
                ),
                lastModifiedDate = "2026-07-20T00:00:00.000Z",
            )
        )

        val server = FakeServer()
        server.shoppingLists["serverList"] = ServerShoppingList(
            id = "serverList", name = "Server List", isFreeform = true,
            contentsForFreeform = "# From the server",
            lastModifiedDate = "2026-07-21T00:00:00.000Z",
        )

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        // Local-only list was pushed up, preserving its items.
        val uploaded = server.shoppingLists["localList"]
        assertEquals("Local Groceries", uploaded?.name)
        assertEquals(2, uploaded?.contentsForList?.size)
        assertEquals(true, uploaded?.contentsForList?.first()?.isHeading)
        assertEquals(true, uploaded?.contentsForList?.get(1)?.isCompleted)

        // Server-only list was pulled down, keeping its freeform text.
        val pulled = local.shoppingLists().associateBy { it.id }["serverList"]
        assertEquals("Server List", pulled?.name)
        assertEquals(true, pulled?.isFreeform)
        assertEquals("# From the server", pulled?.contentsForFreeform)

        // Both sides now agree.
        assertEquals(setOf("localList", "serverList"), local.shoppingLists().map { it.id }.toSet())
        assertEquals(setOf("localList", "serverList"), server.shoppingLists.keys.toSet())
    }

    // ---- same-named classifiers are folded, not replicated forever ----

    /**
     * The classifier tables reconcile by id, so a library that minted its own ids for the default
     * seed ("Breads", "Main", …) and a server whose copies came from another install each download
     * the other's row. The post-sync fold is what stops that pair from living forever.
     */
    @Test
    fun firstSyncFoldsSameNamedClassifiersAndTheLibrariesConverge() = runTest {
        val old = "2026-08-01T00:00:00.000Z"
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertCategory(ServerCategory("A-cat", "Breads", old))
        local.upsertCourse(ServerCourse("A-course", "Main", old))
        local.upsertRecipe(
            ServerRecipe(
                id = "Local loaf", name = "Local loaf", lastModifiedDate = old,
                categoryIds = listOf("A-cat"), courseId = "A-course",
            )
        )

        // The same two classifiers, created independently on the other device: same names, other ids.
        val server = FakeServer()
        server.categories["Z-cat"] = ServerCategory("Z-cat", "Breads", old)
        server.courses["Z-course"] = ServerCourse("Z-course", "Main", old)
        server.recipes["Sourdough"] = ServerRecipe(
            id = "Sourdough", name = "Sourdough", lastModifiedDate = old,
            categoryIds = listOf("Z-cat"), courseId = "Z-course",
        )

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        fun sync() = SyncService(api, local, deviceId = "test-device", deviceName = "Test")

        val first = sync().syncNow()

        assertEquals(2, first.duplicatesMerged)
        assertEquals(listOf("Breads"), local.categories().map { it.name })
        assertEquals(listOf("Main"), local.courses().map { it.name })
        // The downloaded recipe is filed under the surviving rows, not left unfiled.
        assertEquals(listOf("A-cat"), db.queriesQueries.selectCategoryIdsForRecipe("Sourdough").executeAsList())
        assertEquals("A-course", db.queriesQueries.selectRecipeById("Sourdough").executeAsOne().courseId)

        // The fold is local; the NEXT sync carries it to the server — the duplicate rows go (their
        // server timestamps now predate the watermark) and the re-pointed recipes upload.
        server.lastSyncDate = "2026-08-10T00:00:00.000Z"
        val second = sync().syncNow()

        assertEquals(listOf("A-cat"), server.categories.keys.toList())
        assertEquals(listOf("A-course"), server.courses.keys.toList())
        assertEquals(listOf("A-cat"), server.recipes["Sourdough"]?.categoryIds)
        assertEquals("A-course", server.recipes["Sourdough"]?.courseId)
        assertEquals(0, second.duplicatesMerged)

        // And it settles: no ping-pong between the two devices' choices.
        assertTrue(sync().syncNow().isNoOp, "the fold must converge, not repeat every sync")
    }

    /** The survivor is the older id, never "whichever side it came from". */
    @Test
    fun theOlderIdSurvivesEvenWhenItIsTheServersRow() = runTest {
        val old = "2026-08-01T00:00:00.000Z"
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertCategory(ServerCategory("Z-cat", "Breads", old))
        val server = FakeServer()
        server.categories["A-cat"] = ServerCategory("A-cat", "breads", old)

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        assertEquals(listOf("A-cat"), local.categories().map { it.id })
    }

    @Test
    fun storesGrdbCompatibleRowFormat() {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(
            ServerRecipe(
                id = "r1", name = "Test",
                createdDate = "2026-06-01T00:00:00.000Z",
                lastModifiedDate = "2026-06-02T03:04:05.678Z",
            )
        )
        val row = db.queriesQueries.selectRecipeById("r1").executeAsOne()
        // Dates stored in GRDB's "yyyy-MM-dd HH:mm:ss.SSS" form (space separator, no T/Z).
        assertEquals("2026-06-02 03:04:05.678", row.lastModifiedDate)
        // Swift's non-optional columns get concrete defaults, never NULL (GRDB can't decode NULL into them).
        assertEquals("", row.source)
        assertEquals("", row.yield_)
        assertEquals(com.enuvro.saltykmp.db.model.Difficulty.NOT_SET, row.difficulty)
        assertEquals(false, row.isFavorite)
        // ...and the conversion is reversible: the upload DTO is back in ISO wire form.
        assertEquals("2026-06-02T03:04:05.678Z", local.recipeForUpload("r1")?.lastModifiedDate)
    }

    private fun freshDb(): AppDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        AppDatabase.Schema.create(driver)
        return createAppDatabase(driver)
    }

    // ---- Shopping-list revision sync ----

    private fun item(id: String, text: String, done: Boolean = false) =
        com.enuvro.saltykmp.db.model.ShoppingListListContents(id = id, text = text, isCompleted = done)

    /** Simulates a UI edit: rewrites the row's contents + date while PRESERVING the sync bookkeeping,
     *  exactly what the apps' edit paths do (they never touch syncedRevision/syncedSnapshot). */
    private fun editLocally(db: AppDatabase, local: LocalStore, edited: ServerShoppingList) {
        val state = local.shoppingListsWithSyncState().first { it.list.id == edited.id }
        // Named arguments: upsertShoppingList is a grouped statement (UPDATE + INSERT OR IGNORE), so
        // SQLDelight orders its parameters by first appearance in the SQL rather than by column list.
        db.queriesQueries.upsertShoppingList(
            name = edited.name,
            isFreeform = edited.isFreeform,
            contentsForList = edited.contentsForList ?: emptyList(),
            contentsForFreeform = edited.contentsForFreeform,
            lastModifiedDate = LocalStore.wireToDbDate(edited.lastModifiedDate),
            syncedRevision = state.syncedRevision,
            syncedSnapshot = state.syncedSnapshot?.let { apiJson.encodeToString(ServerShoppingList.serializer(), it) },
            id = edited.id,
        )
    }

    /** Both sides in agreement at the server's revision (the state after any clean sync). */
    private fun agree(local: LocalStore, server: FakeServer, list: ServerShoppingList): ServerShoppingList {
        val (_, saved) = server.saveShoppingList(list)
        local.upsertShoppingList(saved)
        return saved
    }

    @Test
    fun dirtyLocalRowUploadsAndComesBackClean() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        val agreed = agree(local, server, ServerShoppingList(
            id = "L", name = "Groceries", isFreeform = false,
            contentsForList = listOf(item("a", "Milk")),
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))

        editLocally(db, local, agreed.copy(
            contentsForList = listOf(item("a", "Milk"), item("b", "Eggs")),
            lastModifiedDate = "2026-08-02T00:00:00.000Z",
        ))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        assertEquals(1, result.libraryUp)
        assertEquals(0, result.conflictsMerged)
        assertEquals(2, server.shoppingLists["L"]?.contentsForList?.size)
        assertEquals(2L, server.shoppingLists["L"]?.revision, "accepted upload bumps the revision")
        val state = local.shoppingListsWithSyncState().single()
        assertEquals(2L, state.syncedRevision, "upload records the new agreement")
        assertEquals(false, state.isDirty, "row is clean after upload")
    }

    @Test
    fun serverChangedOnlyDownloadsWithoutUploading() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        val agreed = agree(local, server, ServerShoppingList(
            id = "L", name = "Groceries", isFreeform = false,
            contentsForList = listOf(item("a", "Milk")),
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))

        // Another device (or the web UI) edits the server copy → revision moves past our agreement.
        server.saveShoppingList(agreed.copy(
            contentsForList = listOf(item("a", "Milk", done = true)),
            lastModifiedDate = "2026-08-03T00:00:00.000Z",
            baseRevision = agreed.revision,
        ))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        assertEquals(1, result.libraryDown)
        assertEquals(0, result.libraryUp)
        val state = local.shoppingListsWithSyncState().single()
        assertEquals(true, state.list.contentsForList?.single()?.isCompleted)
        assertEquals(2L, state.syncedRevision)
        assertEquals(false, state.isDirty)
    }

    /** The headline scenario: both sides edited since the last agreement → three-way merge, no loss. */
    @Test
    fun concurrentEditsMergeItemLevelAndConverge() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        val agreed = agree(local, server, ServerShoppingList(
            id = "L", name = "Groceries", isFreeform = false,
            contentsForList = listOf(item("a", "Milk"), item("b", "Eggs")),
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))

        // Web checks off Milk…
        server.saveShoppingList(agreed.copy(
            contentsForList = listOf(item("a", "Milk", done = true), item("b", "Eggs")),
            lastModifiedDate = "2026-08-02T00:00:00.000Z",
            baseRevision = agreed.revision,
        ))
        // …while this device adds Bread.
        editLocally(db, local, agreed.copy(
            contentsForList = listOf(item("a", "Milk"), item("b", "Eggs"), item("c", "Bread")),
            lastModifiedDate = "2026-08-03T00:00:00.000Z",
        ))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        assertEquals(1, result.conflictsMerged)
        assertEquals(0, result.conflictCopies)
        val merged = server.shoppingLists["L"]!!
        assertEquals(listOf("Milk", "Eggs", "Bread"), merged.contentsForList?.map { it.text })
        assertEquals(true, merged.contentsForList?.first()?.isCompleted, "web's check-off survived")
        assertEquals(3L, merged.revision)
        val state = local.shoppingListsWithSyncState().single()
        assertEquals(merged.contentsForList, state.list.contentsForList, "local converged to the merge")
        assertEquals(3L, state.syncedRevision)
        assertEquals(false, state.isDirty)
    }

    @Test
    fun freeformConflictKeepsLocalTextAsANewCopyOnBothSides() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        val agreed = agree(local, server, ServerShoppingList(
            id = "F", name = "Notes", isFreeform = true,
            contentsForFreeform = "v0",
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))

        server.saveShoppingList(agreed.copy(
            contentsForFreeform = "server words",
            lastModifiedDate = "2026-08-02T00:00:00.000Z",
            baseRevision = agreed.revision,
        ))
        editLocally(db, local, agreed.copy(
            contentsForFreeform = "local words",
            lastModifiedDate = "2026-08-03T00:00:00.000Z",
        ))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "My iPhone").syncNow()

        assertEquals(1, result.conflictsMerged)
        assertEquals(1, result.conflictCopies)
        assertEquals("server words", server.shoppingLists["F"]?.contentsForFreeform, "shared list keeps the server text")

        val copies = server.shoppingLists.values.filter { it.id != "F" }
        assertEquals(1, copies.size, "the local text became one new list on the server")
        assertEquals("local words", copies.single().contentsForFreeform)
        assertTrue(copies.single().name!!.contains("conflicted copy from My iPhone"), "was: ${copies.single().name}")

        // Local has both, clean, in agreement.
        val states = local.shoppingListsWithSyncState()
        assertEquals(2, states.size)
        assertTrue(states.none { it.isDirty })
    }

    /** Pre-revision rows (no snapshot) get one timestamp-based decision, then bookkeeping is seeded. */
    @Test
    fun legacyRowIsSeededAndThenSyncsByRevision() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        // Server row at revision 1; local row identical but written WITHOUT bookkeeping (legacy build).
        val (_, onServer) = server.saveShoppingList(ServerShoppingList(
            id = "L", name = "Groceries", isFreeform = false,
            contentsForList = listOf(item("a", "Milk")),
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))
        local.insertLocalShoppingList(onServer.copy(revision = null))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        val state = local.shoppingListsWithSyncState().single()
        assertEquals(1L, state.syncedRevision, "equal-date legacy row seeds the agreement without transferring")
        assertEquals(false, state.isDirty)
    }

    /** A server-only row that 409s its delete (edited under us) is downloaded, not lost. */
    @Test
    fun deleteRefusedByIfMatchDownloadsTheRowInstead() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        server.registered = true // not a first sync
        server.lastSyncDate = "2026-08-10T00:00:00.000Z"
        // Old server row (before the watermark) that we don't have locally → delete-by-absence fires…
        server.saveShoppingList(ServerShoppingList(
            id = "L", name = "Edited Meanwhile", isFreeform = true,
            contentsForFreeform = "still wanted",
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))
        // …but the delete is refused because the row just changed (If-Match mismatch on the server).
        server.deleteConflictsOnce = true

        // A list on both sides, already agreed, so nothing about it moves. It is here only to keep the
        // local collection non-empty: SYNC-016 withholds server deletions entirely when it is empty,
        // and this case is about the If-Match conflict, not about that guard.
        val agreed = ServerShoppingList(
            id = "K", name = "Untouched", isFreeform = true, contentsForFreeform = "kept",
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        )
        server.saveShoppingList(agreed)
        local.insertLocalShoppingList(agreed.copy(revision = null))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        assertEquals(
            "still wanted",
            local.shoppingLists().single { it.id == "L" }.contentsForFreeform,
            "refused delete → download",
        )
        assertEquals(1, result.libraryDown)
        assertTrue(server.shoppingLists.containsKey("L"), "row survives on the server too")
    }

    /**
     * SYNC-016, the direction that was missing: an empty local collection must not be read as "every
     * one of these was deleted here" and pushed at the server.
     *
     * A library is empty for the same kinds of reason a server list is — restored from an older
     * backup, recreated at the old path, or opened before a file sync finished bringing it down. This
     * direction loses the shared copy rather than one device's.
     */
    @Test
    fun anEmptyLibraryDoesNotAskTheServerToDeleteEverything() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        server.registered = true // not a first sync
        server.lastSyncDate = "2026-08-10T00:00:00.000Z"
        server.saveShoppingList(ServerShoppingList(
            id = "L", name = "Only on the server", isFreeform = true, contentsForFreeform = "kept",
            lastModifiedDate = "2026-08-01T00:00:00.000Z",
        ))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val result = SyncService(api, local, deviceId = "d", deviceName = "Test").syncNow()

        assertTrue(server.shoppingLists.containsKey("L"), "the server keeps what an empty library cannot vouch for")
        assertTrue(
            result.warnings.any { "Delete Server, Push from Local" in it },
            "withholding silently looks identical to having synced: ${result.warnings}",
        )
    }

    @Test
    fun firstSyncUploadsLocalAndDownloadsServer() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(
            ServerRecipe(
                id = "local1", name = "Local Pancakes",
                lastModifiedDate = "2026-06-01T00:00:00.000Z",
                imageFilename = "local-img.jpg"
            )
        )

        val server = FakeServer()
        server.recipes["server1"] = ServerRecipe(
            id = "server1", name = "Server Waffles",
            lastModifiedDate = "2026-06-02T00:00:00.000Z",
            imageFilename = "server-img.jpg"
        )
        server.images["server-img.jpg"] = "fake-bytes".encodeToByteArray()

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val downloadedImages = mutableMapOf<String, ByteArray>()
        SyncService(
            api, local, deviceId = "test-device", deviceName = "Test",
            imageSink = { _, filename, bytes, _ -> downloadedImages[filename] = bytes },
            imageSource = { _, _ -> "local-bytes".encodeToByteArray() }
        ).syncNow()

        // Server received the local recipe; local received the server recipe.
        assertEquals(setOf("local1", "server1"), server.recipes.keys.toSet())
        assertEquals(setOf("local1", "server1"), local.recipeEntries().map { it.id }.toSet())
        assertEquals("Server Waffles", local.recipeForUpload("server1")?.name)
        assertTrue(server.completed)

        // Image sync verified.
        assertEquals("fake-bytes".encodeToByteArray().toList(), downloadedImages["server-img.jpg"]?.toList())
        assertTrue(server.images.isNotEmpty())
    }

    @Test
    fun textOnlyEditUploadsBodyButNotImage() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        // A recipe already in sync with the server, image included (same image timestamp on both sides).
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Cake", lastModifiedDate = "2026-06-01T00:00:00.000Z"))
        local.setRecipeImage("r1", "r1.jpg", null, "2026-06-01T00:00:00.000Z")

        val server = FakeServer()
        server.recipes["r1"] = ServerRecipe(
            id = "r1", name = "Cake",
            lastModifiedDate = "2026-06-01T00:00:00.000Z",
            imageFilename = "r1.jpg", lastModifiedImageDate = "2026-06-01T00:00:00.000Z",
        )
        server.images["r1.jpg"] = "img".encodeToByteArray()

        // User edits only text → body timestamp advances, image timestamp unchanged.
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Carrot Cake", lastModifiedDate = "2026-06-02T00:00:00.000Z"))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        var imageSourceCalls = 0
        SyncService(
            api, local, deviceId = "test-device", deviceName = "Test",
            imageSink = { _, _, _, _ -> },
            imageSource = { _, _ -> imageSourceCalls++; "img".encodeToByteArray() },
        ).syncNow()

        // Body propagated; image bytes were never read for upload (image date matched on both sides).
        assertEquals("Carrot Cake", server.recipes["r1"]?.name)
        assertEquals("2026-06-02T00:00:00.000Z", server.recipes["r1"]?.lastModifiedDate)
        assertEquals(0, imageSourceCalls, "a text-only edit must not re-transfer the image")
    }

    // ---- "Last made on" (prepared dates) --------------------------------------------------------
    //
    // The whole point of the separate channel: marking a recipe made does NOT bump lastModifiedDate, so
    // the body reconciler is blind to it and these transfers have to come from the prepared-date pass.

    @Test
    fun markingMadeLocallyUploadsWithoutTouchingTheBodyClock() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        // A recipe already in sync with the server, bodies identical.
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z"))
        val server = FakeServer()
        server.recipes["r1"] = ServerRecipe(id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z")

        // "Made today" — sets the date and its stamp, deliberately leaving lastModifiedDate alone.
        local.setRecipePrepared("r1", "2026-08-14T12:00:00.000Z", "2026-08-14T18:30:00.000Z")

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        assertEquals("2026-08-14T12:00:00.000Z", server.recipes["r1"]?.lastPrepared)
        assertEquals("2026-08-14T18:30:00.000Z", server.recipes["r1"]?.lastModifiedPreparedDate)
        // The body clock must NOT have moved — that's what keeps the "Date Modified" sort stable.
        assertEquals("2026-06-01T00:00:00.000Z", server.recipes["r1"]?.lastModifiedDate)
    }

    @Test
    fun markingMadeOnAnotherDeviceDownloadsLocally() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z"))
        val server = FakeServer()
        // Same body, but the server already knows about a mark-as-made from elsewhere.
        server.recipes["r1"] = ServerRecipe(
            id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z",
            lastPrepared = "2026-08-10T12:00:00.000Z", lastModifiedPreparedDate = "2026-08-10T20:00:00.000Z",
        )

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        val r = local.recipeForUpload("r1")
        assertEquals("2026-08-10T12:00:00.000Z", r?.lastPrepared)
        assertEquals("2026-08-10T20:00:00.000Z", r?.lastModifiedPreparedDate)
        assertEquals("2026-06-01T00:00:00.000Z", r?.lastModifiedDate)
    }

    /**
     * The race the independent stamp exists to survive: this device edits the BODY while another device
     * marks the recipe made. The body upload carries a stale prepared date, so without the field-level
     * merge the mark would be silently erased.
     */
    @Test
    fun aBodyEditDoesNotClobberANewerMarkAsMade() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z"))

        val server = FakeServer()
        server.recipes["r1"] = ServerRecipe(
            id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z",
            lastPrepared = "2026-08-10T12:00:00.000Z", lastModifiedPreparedDate = "2026-08-10T20:00:00.000Z",
        )

        // Local body edit, made without ever seeing the other device's mark-as-made.
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Chili Verde", lastModifiedDate = "2026-08-12T00:00:00.000Z"))

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        // The body edit won (it's newer) AND the prepared date survived on both sides.
        assertEquals("Chili Verde", server.recipes["r1"]?.name)
        assertEquals("2026-08-10T12:00:00.000Z", server.recipes["r1"]?.lastPrepared)
        assertEquals("2026-08-10T12:00:00.000Z", local.recipeForUpload("r1")?.lastPrepared)
    }

    /**
     * The mirror-image race: this device marks the recipe made while the SERVER body moves ahead. The
     * body download carries a stale prepared date, so LocalStore.upsertRecipe has to keep the local one.
     */
    @Test
    fun aBodyDownloadDoesNotClobberANewerLocalMarkAsMade() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(ServerRecipe(id = "r1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z"))
        local.setRecipePrepared("r1", "2026-08-14T12:00:00.000Z", "2026-08-14T18:30:00.000Z")

        val server = FakeServer()
        // Server body is newer, but its prepared date predates the local mark (and here is absent).
        server.recipes["r1"] = ServerRecipe(
            id = "r1", name = "Server Chili", lastModifiedDate = "2026-08-20T00:00:00.000Z",
        )

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(api, local, deviceId = "test-device", deviceName = "Test").syncNow()

        val r = local.recipeForUpload("r1")
        assertEquals("Server Chili", r?.name, "the newer server body still wins")
        assertEquals("2026-08-14T12:00:00.000Z", r?.lastPrepared, "the local mark-as-made survives it")
    }

    @Test
    fun oneFailedImageDoesNotAbortTheSync() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        server.recipes["bad"] = ServerRecipe(
            id = "bad", name = "Broken Image", lastModifiedDate = "2026-06-01T00:00:00.000Z",
            imageFilename = "bad.jpg", lastModifiedImageDate = "2026-06-01T00:00:00.000Z",
        )
        server.recipes["good"] = ServerRecipe(
            id = "good", name = "Good Image", lastModifiedDate = "2026-06-01T00:00:00.000Z",
            imageFilename = "good.jpg", lastModifiedImageDate = "2026-06-01T00:00:00.000Z",
        )
        server.images["bad.jpg"] = "img".encodeToByteArray()
        server.images["good.jpg"] = "img".encodeToByteArray()

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val saved = mutableListOf<String>()
        val result = SyncService(
            api, local, deviceId = "test-device", deviceName = "Test",
            // Simulate a per-image persistence failure (disk full, bad bytes, …) for one recipe only.
            imageSink = { _, filename, _, _ ->
                if (filename == "bad.jpg") error("disk full")
                saved += filename
            },
        ).syncNow()

        // The failed image is skipped; everything else — including the other image — still syncs.
        assertEquals(listOf("good.jpg"), saved)
        assertEquals(1, result.imagesDown)
        assertEquals(setOf("bad", "good"), local.recipeEntries().map { it.id }.toSet())
        assertTrue(server.completed, "one bad image must not abort the sync")
    }

    @Test
    fun allImagesFailingSurfacesAnError() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        server.recipes["r1"] = ServerRecipe(
            id = "r1", name = "Only Recipe", lastModifiedDate = "2026-06-01T00:00:00.000Z",
            imageFilename = "r1.jpg", lastModifiedImageDate = "2026-06-01T00:00:00.000Z",
        )
        server.images["r1.jpg"] = "img".encodeToByteArray()

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        val service = SyncService(
            api, local, deviceId = "test-device", deviceName = "Test",
            imageSink = { _, _, _, _ -> error("disk full") },
        )

        // EVERY image operation failing points at something systemic — that must not report success.
        val message = kotlin.test.assertFailsWith<SyncException> { service.syncNow() }.message ?: ""
        assertTrue("r1" in message, "failed recipe ids should be listed: $message")
    }

    @Test
    fun localDeletionTombstonesPropagateAndDoNotResurrect() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        server.recipes["r1"] = ServerRecipe(id = "r1", name = "Doomed", lastModifiedDate = "2026-06-02T00:00:00.000Z")

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        fun service() = SyncService(api, local, deviceId = "test-device", deviceName = "Test")

        // First sync pulls r1 into the local store.
        service().syncNow()
        assertTrue(local.recipeEntries().any { it.id == "r1" })

        // User deletes it locally → a tombstone is recorded.
        local.deleteRecipe("r1")
        assertEquals(listOf("r1"), local.tombstonedRecipeIds())

        // Second sync: the server still has r1 and lastSyncDate is null, so plain delete-by-absence would
        // re-download it. Tombstones must instead delete it server-side and keep it gone locally.
        service().syncNow()

        assertTrue(local.recipeEntries().none { it.id == "r1" }, "deleted recipe must not be resurrected")
        assertTrue(!server.recipes.containsKey("r1"), "deletion must propagate to the server")
        assertTrue(local.tombstonedRecipeIds().isEmpty(), "tombstone cleared after a successful sync")
    }

    // ---- progress reporting and stopping ----

    /**
     * Progress carries the reconciler's real counts, not a guess: two local-only recipes to push and three
     * server-only ones to pull are reported as exactly that, and each phase counts up to its own total.
     */
    @Test
    fun progressReportsTheRealPlanCounts() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        local.upsertRecipe(ServerRecipe(id = "up1", name = "Chili", lastModifiedDate = "2026-06-01T00:00:00.000Z"))
        local.upsertRecipe(ServerRecipe(id = "up2", name = "Stew", lastModifiedDate = "2026-06-01T00:00:00.000Z"))

        val server = FakeServer()
        for (id in listOf("down1", "down2", "down3")) {
            server.recipes[id] = ServerRecipe(id = id, name = id, lastModifiedDate = "2026-06-02T00:00:00.000Z")
        }

        val seen = mutableListOf<SyncProgress>()
        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        SyncService(
            api, local, deviceId = "d", deviceName = "Test",
            onProgress = { seen += it },
        ).syncNow()

        fun countsFor(phase: SyncPhase) = seen.filter { it.phase == phase }
        assertEquals(listOf(1 to 2, 2 to 2), countsFor(SyncPhase.UPLOADING_RECIPES).map { it.done to it.total })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), countsFor(SyncPhase.DOWNLOADING_RECIPES).map { it.done to it.total })

        // The bookend phases always report, so the UI is never left on a stale label.
        val phases = seen.map { it.phase }
        assertEquals(SyncPhase.CONNECTING, phases.first())
        assertTrue(SyncPhase.PLANNING in phases)
        assertTrue(SyncPhase.FINISHING in phases)

        // A single-request phase has nothing to count and must render as a bare label, not "0 of 0".
        val library = seen.first { it.phase == SyncPhase.LIBRARY }
        assertEquals(0, library.total)
        assertEquals(null, library.fraction())
        assertEquals("Syncing courses, categories and tags", library.describe())
        assertEquals("Downloading recipes (2 of 3)", SyncProgress(SyncPhase.DOWNLOADING_RECIPES, 2, 3).describe())
    }

    /**
     * Stopping mid-sync is safe because completeSync is the LAST call: the server's cutoff never moves, so
     * the next run re-plans the same work. Also proves the cancellation check in `report` actually bites —
     * a first sync's delta carries every body, so the download loop makes no network calls at all and would
     * otherwise run to completion with nothing to interrupt it.
     */
    @Test
    fun stoppingMidSyncLeavesTheServerCutoffUnmoved() = runTest {
        val db = freshDb()
        val local = LocalStore(db)
        val server = FakeServer()
        for (id in listOf("r1", "r2", "r3", "r4")) {
            server.recipes[id] = ServerRecipe(id = id, name = id, lastModifiedDate = "2026-06-02T00:00:00.000Z")
        }

        val api = SaltyApiClient("http://fake", InMemoryTokenStore("t"), server.engine())
        var job: Job? = null
        val service = SyncService(
            api, local, deviceId = "d", deviceName = "Test",
            // Stop the way the Settings button does, once two recipes have been applied.
            onProgress = { if (it.phase == SyncPhase.DOWNLOADING_RECIPES && it.done == 2) job?.cancel() },
        )
        job = launch { service.syncNow() }
        job.join()

        assertTrue(job.isCancelled, "the sync job must actually cancel")
        assertFalse(server.completed, "completeSync must NOT run, so the next sync re-plans from the same cutoff")
        // Stopping halts the sync, it does not roll it back: what already landed stays landed.
        assertEquals(2, local.recipeEntries().size, "recipes applied before the stop are kept")
    }
}
