package com.enuvro.saltykmp.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
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
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        val images = mutableMapOf<String, ByteArray>()
        var registered = false
        var completed = false

        private inline fun <reified T> bodyOf(content: Any?): T =
            apiJson.decodeFromString((content as TextContent).text)

        fun engine() = MockEngine { request ->
            val path = request.url.encodedPath
            val get = request.method == HttpMethod.Get
            val post = request.method == HttpMethod.Post
            when {
                path == "/api/recipes/sync/device" && post -> {
                    val first = !registered; registered = true
                    jsonOk(apiJson.encodeToString(DeviceSyncInfo(deviceId = "test-device", isFirstSync = first)))
                }
                path.endsWith("/complete") && post -> { completed = true; respond("", HttpStatusCode.OK) }

                path == "/api/recipes/sync/manifest" && get ->
                    jsonOk(apiJson.encodeToString(recipes.values.map {
                        RecipeManifestEntry(it.id, it.lastModifiedDate, it.imageFilename, it.lastModifiedImageDate)
                    }), recipes.size)

                path == "/api/recipes" && get -> {
                    val page = request.url.parameters["page"]?.toInt() ?: 0
                    val size = request.url.parameters["size"]?.toInt() ?: 100
                    val all = recipes.values.toList()
                    jsonOk(apiJson.encodeToString(all.drop(page * size).take(size)), all.size)
                }
                path == "/api/recipes" && post -> {
                    val r = bodyOf<ServerRecipe>(request.body); recipes[r.id] = r
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

                // Empty library — no-op sync.
                path == "/api/courses" && get -> jsonOk(apiJson.encodeToString(emptyList<ServerCourse>()), 0)
                path == "/api/categories" && get -> jsonOk(apiJson.encodeToString(emptyList<ServerCategory>()), 0)
                path == "/api/tags" && get -> jsonOk(apiJson.encodeToString(emptyList<ServerTag>()), 0)

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
}
