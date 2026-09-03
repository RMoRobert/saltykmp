package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingListRepository
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.image.InvalidImageNameException
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.net.Socket
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What an id is allowed to be, and whose row it may touch.
 *
 * Two holes are covered here, and they share a cause: an id arrives from a client, and nothing
 * downstream treated it as untrusted. It named a FILE — the recipe's photo is stored as
 * `<id>.<ext>` — and it keyed a ROW whose primary key carries no user, while every existence check
 * around it was user-scoped.
 */
class IdSafetyTest {

    // The store's own directory, inside a base that must stay empty: a traversal writes into the
    // parent, so the parent is where the evidence would be.
    private val base: Path = Files.createTempDirectory("salty-idsafety")
    private val imageDir: Path = base.resolve("images")
    private val imageStore = ImageStore(imageDir)

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:salty;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }
    }

    @BeforeTest
    fun reset() {
        ensureDb()
        runBlocking {
            DatabaseFactory.dbQuery {
                RecipeTags.deleteAll(); RecipeCategories.deleteAll()
                Recipes.deleteAll(); Courses.deleteAll(); Categories.deleteAll(); Tags.deleteAll()
                ShoppingLists.deleteAll()
                DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            UserRepository.create("alice", "alicepassword")
            UserRepository.create("mallory", "mallorypassword")
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
    }

    private suspend fun token(client: HttpClient, username: String, password: String): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username, password, deviceId = "$username-device"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<AuthResponse>().deviceToken!!
    }

    private fun gifBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "gif", out)
        return out.toByteArray()
    }

    private fun pngBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private suspend fun userId(username: String) = UserRepository.findByUsername(username)!!.id

    /* ------------------------------------------------------------------ ids as paths -- */

    /**
     * The whole chain, as an attacker would walk it: create a recipe whose id is a path, then upload
     * an image to it. Ktor splits the path on the raw `/` and decodes each segment afterwards, so
     * `..%2F..%2Fx` reaches the handler as a real relative path.
     */
    @Test
    fun anIdThatNamesAPathIsRefusedAndWritesNothing() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val t = token(client, "mallory", "mallorypassword")

            val created = client.post("/api/recipes") {
                bearerAuth(t); contentType(ContentType.Application.Json)
                setBody(ServerRecipe(id = "../../pwned", name = "Traversal"))
            }
            assertEquals(HttpStatusCode.BadRequest, created.status, "the id never reaches the database")

            // And the upload route refuses it too, whether or not a row was ever made.
            val upload = client.submitFormWithBinaryData(
                url = "/api/recipes/..%2F..%2Fpwned/image",
                formData = formData {
                    append("file", gifBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "image/gif")
                        append(HttpHeaders.ContentDisposition, "filename=\"x.gif\"")
                    })
                },
            ) { bearerAuth(t) }
            assertEquals(HttpStatusCode.BadRequest, upload.status)

            // Nothing outside the image directory, which is the only thing that actually matters.
            assertEquals(
                listOf(imageDir),
                base.listDirectoryEntries().sorted(),
                "a traversal wrote into the store's parent",
            )
        }
    }

    /** The storage layer refuses on its own, so it is not relying on a route having checked first. */
    @Test
    fun theImageStoreRefusesAnIdItCannotNameAFileWith() {
        for (id in listOf("../escape", "./sneak", "a/b", "", ".", "..")) {
            assertFailsWith<InvalidImageNameException>("stored under \"$id\"") {
                imageStore.store(id, gifBytes())
            }
        }
        assertEquals(emptyList(), base.listDirectoryEntries().filter { it != imageDir })
    }

    /**
     * A stored `imageFilename` of `""` used to resolve to the image DIRECTORY: `Paths.get("").fileName`
     * is `""`, which compared equal to what was passed in. A later delete then removed the directory
     * itself and every upload after it failed.
     */
    @Test
    fun anEmptyOrDottedImageNameTouchesNothing() {
        val stored = imageStore.store("real-recipe", pngBytes())
        for (name in listOf("", ".", "..", ".thumbs")) {
            assertFalse(imageStore.exists(name), "\"$name\" is not one of this store's files")
            imageStore.delete(name)
        }
        assertTrue(imageDir.exists(), "the image directory survived")
        assertNotNull(imageStore.load(stored), "and so did the image in it")
    }

    /* --------------------------------------------------------- ids across accounts -- */

    /**
     * The id is the whole primary key, so a save from another account used to UPDATE the row —
     * user_id and all — and the recipe simply left its owner's library.
     */
    @Test
    fun savingARecipeIdAnotherAccountOwnsIsRefused() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val alice = userId("alice")
            RecipeRepository.upsert(alice, ServerRecipe(id = "SHARED-ID", name = "Alice's Bread"))

            val t = token(client, "mallory", "mallorypassword")
            val resp = client.put("/api/recipes/SHARED-ID") {
                bearerAuth(t); contentType(ContentType.Application.Json)
                setBody(ServerRecipe(id = "SHARED-ID", name = "Mallory's Bread"))
            }
            assertEquals(HttpStatusCode.Conflict, resp.status)

            val stillAlices = RecipeRepository.getById(alice, "SHARED-ID")
            assertEquals("Alice's Bread", stillAlices?.name, "the row stayed with its owner")
            assertNull(RecipeRepository.getById(userId("mallory"), "SHARED-ID"))
        }
    }

    @Test
    fun theSameHoldsForShoppingListsAndClassifiers() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val alice = userId("alice")
            ShoppingListRepository.save(alice, ServerShoppingList(id = "LIST-ID", name = "Alice's list"))

            val t = token(client, "mallory", "mallorypassword")
            assertEquals(
                HttpStatusCode.Conflict,
                client.put("/api/shoppingLists/LIST-ID") {
                    bearerAuth(t); contentType(ContentType.Application.Json)
                    setBody(ServerShoppingList(id = "LIST-ID", name = "Mallory's list"))
                }.status,
            )
            assertEquals("Alice's list", ShoppingListRepository.getById(alice, "LIST-ID")?.name)

            com.enuvro.saltykmp.db.LibraryRepository.upsertCategory(
                alice, ServerCategory(id = "CAT-ID", name = "Baking"),
            )
            assertEquals(
                HttpStatusCode.Conflict,
                client.put("/api/categories/CAT-ID") {
                    bearerAuth(t); contentType(ContentType.Application.Json)
                    setBody(ServerCategory(id = "CAT-ID", name = "Stolen"))
                }.status,
            )
            assertEquals(
                "Baking",
                com.enuvro.saltykmp.db.LibraryRepository.listCategories(alice).single().name,
            )
        }
    }

    /* --------------------------------------------------------- one token, one device -- */

    /**
     * A sync token may only name the device it was issued for (SYNC-019).
     *
     * These rows are all the caller's own devices, so this was never a route between accounts. What it
     * was is a way to stamp `lastSyncDate` on a SIBLING device's row — and `isFirstSync` is that column
     * being null, which is the flag that suppresses deletion inference. A device whose untouched row
     * was marked synced treats its first real sync as a returning one and deletes local rows it has no
     * agreement about.
     */
    @Test
    fun aSyncTokenCannotActOnAnotherDevice() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            // Two devices on ONE account: alice's phone enrols, alice's laptop has never synced.
            val phone = token(client, "alice", "alicepassword") // enrols "alice-device"
            DeviceRepository.getOrCreate(userId("alice"), "alice-laptop", "Laptop")

            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/recipes/sync/device/alice-laptop/complete") { bearerAuth(phone) }.status,
                "the phone's token cannot advance the laptop's watermark",
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.get("/api/recipes/sync/device/alice-laptop") { bearerAuth(phone) }.status,
            )
            assertEquals(
                HttpStatusCode.Forbidden,
                client.post("/api/recipes/sync/device") {
                    bearerAuth(phone); contentType(ContentType.Application.Json)
                    setBody(DeviceRegisterRequest("alice-laptop", "Laptop"))
                }.status,
            )

            // The laptop is still untouched, so its own first sync is still a first sync.
            val laptop = DeviceRepository.get(userId("alice"), "alice-laptop")
            assertEquals(true, laptop?.isFirstSync, "the flag that guards against deletion inference held")

            // And the phone's own device is unaffected: this is a scope check, not a lockout.
            assertEquals(
                HttpStatusCode.OK,
                client.post("/api/recipes/sync/device/alice-device/complete") { bearerAuth(phone) }.status,
            )
        }
    }

    /* ------------------------------------------------------------------- the rest -- */

    /** An id longer than the column used to reach the insert and come back as a 500. */
    @Test
    fun anOverlongIdIsARefusalRatherThanAnInternalError() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val t = token(client, "alice", "alicepassword")
            val resp = client.put("/api/recipes/${"x".repeat(200)}") {
                bearerAuth(t); contentType(ContentType.Application.Json)
                setBody(ServerRecipe(id = "ignored", name = "Too long"))
            }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
        }
    }

    /**
     * A chunked body declares no length, so the size cap never saw it — on the unauthenticated login
     * endpoint too, where a handler then read the whole thing into memory.
     *
     * Over a raw socket against a real Netty, because the in-memory test engine cannot express this:
     * it sends a channel body with neither `Content-Length` nor `Transfer-Encoding`, and the client
     * refuses to let a test set the latter by hand. This is the one case where the engine under the
     * test has to be the engine that ships.
     */
    @Test
    fun aChunkedBodyIsRefusedBecauseItsSizeCannotBeKnown() {
        val store = imageStore
        val server = embeddedServer(Netty, port = 0) { installSalty(store) }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val body = """{"username":"alice","password":"alicepassword","deviceId":"d"}"""
            val request = buildString {
                append("POST /api/auth/login HTTP/1.1\r\n")
                append("Host: localhost\r\n")
                append("Content-Type: application/json\r\n")
                append("Transfer-Encoding: chunked\r\n")
                append("Connection: close\r\n\r\n")
                append(Integer.toHexString(body.length)).append("\r\n").append(body).append("\r\n")
                append("0\r\n\r\n")
            }
            Socket("127.0.0.1", port).use { socket ->
                socket.getOutputStream().write(request.toByteArray())
                socket.getOutputStream().flush()
                val status = socket.getInputStream().bufferedReader().readLine()
                assertTrue(
                    status.contains("411"),
                    "a body of undeclared length is refused, not read: $status",
                )
            }
        } finally {
            server.stop(0, 0)
        }
    }

    /** And the ordinary shape — a JSON body with a length — still goes through untouched. */
    @Test
    fun abodyThatDeclaresItsLengthIsUnaffected() = testApplication {
        application { installSalty(imageStore) }
        val resp = jsonClient().post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("alice", "alicepassword", deviceId = "alice-device"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    /**
     * `CachingHeaders` APPENDS, so the route's own `immutable` used to arrive alongside the app-wide
     * `no-cache` and a browser unioned the two. Reading only the first header hid it.
     */
    @Test
    fun aVersionedImageCarriesOneCacheControlAndItIsTheLongOne() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val t = token(client, "alice", "alicepassword")
            RecipeRepository.upsert(userId("alice"), ServerRecipe(id = "img1", name = "Img"))
            client.submitFormWithBinaryData(
                url = "/api/recipes/img1/image",
                formData = formData {
                    append("file", pngBytes(), Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"img1.png\"")
                    })
                },
            ) { bearerAuth(t) }

            val stamp = client.get("/api/recipes/images/img1.png") { bearerAuth(t) }
                .headers[HttpHeaders.ETag]!!.trim('"')
            val versioned = client.get("/api/recipes/images/img1.png?v=$stamp") { bearerAuth(t) }
            val values = versioned.headers.getAll(HttpHeaders.CacheControl).orEmpty()
            assertEquals(1, values.size, "one answer to the question, not two: $values")
            assertTrue(values.single().contains("immutable"), "and it is the long one: $values")
            assertFalse(values.single().contains("no-cache"))

            // The unversioned URL still revalidates.
            val bare = client.get("/api/recipes/images/img1.png") { bearerAuth(t) }
            assertTrue(bare.headers.getAll(HttpHeaders.CacheControl).orEmpty().any { it.contains("no-cache") })
        }
    }

    @Test
    fun deletingAUserTakesTheirShoppingListsToo() = testApplication {
        application { installSalty(imageStore) }
        runBlocking {
            val alice = userId("alice")
            ShoppingListRepository.save(alice, ServerShoppingList(id = "GONE", name = "Weekly shop"))
            UserRepository.deleteWithData(alice)
            assertEquals(emptyList(), ShoppingListRepository.list(alice))
            assertEquals(0L, DatabaseFactory.dbQuery { ShoppingLists.selectAll().count() })
        }
    }
}
