package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.auth.JwtService
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.deleteAll
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SaltyServerTest {

    private val jwt = JwtService("test-secret", "salty", "salty-app", validityMs = 60_000)
    private val imageStore = ImageStore(Files.createTempDirectory("salty-test-img"))

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
                DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            UserRepository.create("tester", "pw")
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
    }

    private suspend fun login(client: io.ktor.client.HttpClient): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<AuthResponse>().token
    }

    private fun recipe(id: String, name: String, lastModified: String) = ServerRecipe(
        id = id, name = name, lastModifiedDate = lastModified,
    )

    // ---- Web UI ----

    @Test
    fun webLoginPageRenders() = testApplication {
        application { installSalty(jwt, imageStore) }
        val resp = createClient { }.get("/login")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(resp.bodyAsText().contains("Sign in"))
    }

    @Test
    fun webRootRedirectsWhenNotLoggedIn() = testApplication {
        application { installSalty(jwt, imageStore) }
        val resp = createClient { followRedirects = false }.get("/")
        assertEquals(HttpStatusCode.Found, resp.status)
        assertEquals("/login", resp.headers[HttpHeaders.Location])
    }

    @Test
    fun webLoginThenListsRecipes() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            RecipeRepository.upsert(uid, recipe("w1", "Web Waffles", "2026-06-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertTrue(web.get("/").bodyAsText().contains("Web Waffles"))
    }

    @Test
    fun webRecipesPaginate() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            // 30 zero-padded names so lexical sort == numeric: page 1 = 01..25, page 2 = 26..30.
            (1..30).forEach { i ->
                RecipeRepository.upsert(uid, recipe("r$i", "Recipe %02d".format(i), "2026-06-01T00:00:00.000Z"))
            }
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        val page1 = web.get("/").bodyAsText()
        assertTrue(page1.contains("Page 1 of 2"), "page 1 shows pagination")
        assertTrue(page1.contains("Recipe 01") && page1.contains("Recipe 25"), "page 1 holds the first 25")
        assertTrue(!page1.contains("Recipe 26"), "page 1 stops at 25")

        val page2 = web.get("/?page=2").bodyAsText()
        assertTrue(page2.contains("Recipe 26") && page2.contains("Recipe 30"), "page 2 holds the remainder")
        assertTrue(!page2.contains("Recipe 01"), "page 2 excludes page-1 recipes")
    }

    @Test
    fun webBrowseByCourseAndCategory() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking {
            val uid = UserRepository.findByUsername("tester")!!.id
            LibraryRepository.upsertCourse(uid, ServerCourse("c-dessert", "Desserts", "2026-06-01T00:00:00.000Z"))
            LibraryRepository.upsertCategory(uid, ServerCategory("cat-quick", "Quick", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.upsert(uid, recipe("r1", "Brownies", "2026-06-01T00:00:00.000Z")
                .copy(courseId = "c-dessert", categoryIds = listOf("cat-quick")))
            RecipeRepository.upsert(uid, recipe("r2", "Pot Roast", "2026-06-01T00:00:00.000Z"))
        }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })

        // Browse indexes list the vocabulary with a recipe count.
        val courses = web.get("/courses").bodyAsText()
        assertTrue(courses.contains("Desserts"), "course index lists the course")

        // Drill-down filters to just that course/category.
        val inCourse = web.get("/courses/c-dessert").bodyAsText()
        assertTrue(inCourse.contains("Brownies"), "course view includes its recipe")
        assertTrue(!inCourse.contains("Pot Roast"), "course view excludes other recipes")

        val inCategory = web.get("/categories/cat-quick").bodyAsText()
        assertTrue(inCategory.contains("Brownies") && !inCategory.contains("Pot Roast"), "category view filters correctly")

        // Unknown ids redirect back to the browse index rather than erroring.
        val missing = createClient { install(HttpCookies); followRedirects = false }
        missing.submitForm(url = "/login", formParameters = parameters { append("username", "tester"); append("password", "pw") })
        assertEquals("/tags", missing.get("/tags/nope").headers[HttpHeaders.Location])
    }

    @Test
    fun nonAdminCannotAccessUserManagement() = testApplication {
        application { installSalty(jwt, imageStore) }
        // "tester" is seeded as a non-admin in reset().
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertEquals(HttpStatusCode.Forbidden, web.get("/users").status)
    }

    @Test
    fun adminCanCreateUserWithIsolatedDataThenDelete() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "boss"); append("password", "pw") },
        )
        // Admin sees the management page.
        val usersHtml = web.get("/users").bodyAsText()
        assertTrue(usersHtml.contains("Users"))
        val csrf = extractCsrf(usersHtml)

        // Create a new user via the form (password must clear the 8-char minimum; CSRF token required).
        web.submitForm(
            url = "/users",
            formParameters = parameters { append("username", "alice"); append("password", "alicepw12"); append("csrf", csrf) },
        )
        val alice = runBlocking { UserRepository.findByUsername("alice") }
        assertNotNull(alice)
        assertTrue(!alice.isAdmin)

        // Alice's library is isolated: give boss a recipe, confirm alice's API view is empty.
        runBlocking {
            val bossId = UserRepository.findByUsername("boss")!!.id
            RecipeRepository.upsert(bossId, recipe("b1", "Boss Bread", "2026-06-01T00:00:00.000Z"))
        }
        val api = jsonClient()
        val aliceToken = runBlocking {
            api.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("alice", "alicepw12"))
            }.body<AuthResponse>().token
        }
        assertEquals(0, runBlocking { api.get("/api/recipes") { bearerAuth(aliceToken) }.body<List<ServerRecipe>>().size })

        // Delete alice; she can no longer authenticate.
        web.submitForm(url = "/users/${alice.id}/delete", formParameters = parameters { append("csrf", csrf) })
        assertEquals(null, runBlocking { UserRepository.findByUsername("alice") })
    }

    @Test
    fun adminCannotDeleteOwnAccount() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = createClient { install(HttpCookies) }
        web.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "boss"); append("password", "pw") },
        )
        val bossId = runBlocking { UserRepository.findByUsername("boss")!!.id }
        // Send a valid CSRF token so the self-delete guard (not the CSRF check) is what blocks this.
        val csrf = extractCsrf(web.get("/users").bodyAsText())
        web.submitForm(url = "/users/$bossId/delete", formParameters = parameters { append("csrf", csrf) })
        assertNotNull(runBlocking { UserRepository.findByUsername("boss") })
    }

    @Test
    fun loginSucceedsAndProtectsRoutes() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            // Bad creds → 401
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.post("/api/auth/login") {
                    contentType(ContentType.Application.Json); setBody(AuthRequest("tester", "wrong"))
                }.status,
            )
            // No token → 401
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes").status)
            // Good creds → token works
            val token = login(client)
            assertTrue(token.isNotBlank())
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun recipeCrudRoundTripPreservesShapeAndDate() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val date = "2026-06-14T10:00:00.000Z"
            val created = client.post("/api/recipes") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(recipe("r1", "Pancakes", date))
            }
            assertEquals(HttpStatusCode.Created, created.status)

            val fetched = client.get("/api/recipes/r1") { bearerAuth(token) }.body<ServerRecipe>()
            assertEquals("Pancakes", fetched.name)
            assertEquals(date, fetched.lastModifiedDate) // exact wire date round-trip
            assertNotNull(fetched.categoryIds)

            client.put("/api/recipes/r1") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(recipe("r1", "Pancakes v2", date))
            }
            assertEquals("Pancakes v2", client.get("/api/recipes/r1") { bearerAuth(token) }.body<ServerRecipe>().name)

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/recipes/r1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/r1") { bearerAuth(token) }.status)
        }
    }

    @Test
    fun listReportsTotalCountNoParams() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b")
            val resp = client.get("/api/recipes") { bearerAuth(token) }
            assertEquals("2", resp.header("X-Total-Count"))
            assertEquals(2, resp.body<List<ServerRecipe>>().size)
        }
    }

    @Test
    fun modifiedSinceReturnsOnlyTheDelta() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            post(client, token, recipe("old", "Old", "2026-01-01T00:00:00.000Z"))
            post(client, token, recipe("new", "New", "2026-06-01T00:00:00.000Z"))
            val resp = client.get("/api/recipes?modifiedSince=2026-03-01T00:00:00.000Z") { bearerAuth(token) }
            val items = resp.body<List<ServerRecipe>>()
            assertEquals(1, items.size)
            assertEquals("new", items.first().id)
            assertEquals("1", resp.header("X-Total-Count"))
        }
    }

    @Test
    fun paginationReturnsPageAndHeaders() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b", "c")
            val resp = client.get("/api/recipes?page=0&size=2") { bearerAuth(token) }
            assertEquals(2, resp.body<List<ServerRecipe>>().size)
            assertEquals("3", resp.header("X-Total-Count"))
            assertEquals("2", resp.header("X-Total-Pages"))
            assertEquals("0", resp.header("X-Page-Number"))
        }
    }

    @Test
    fun manifestListsAllIds() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seed(client, token, "a", "b")
            val resp = client.get("/api/recipes/sync/manifest") { bearerAuth(token) }
            assertEquals("2", resp.header("X-Total-Count"))
            assertEquals(setOf("a", "b"), resp.body<List<RecipeManifestEntry>>().map { it.id }.toSet())
        }
    }

    @Test
    fun badModifiedSinceReturns400() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            assertEquals(
                HttpStatusCode.BadRequest,
                client.get("/api/recipes?modifiedSince=not-a-date") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun deviceRegistrationTracksFirstSync() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            val first = client.post("/api/recipes/sync/device") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(DeviceRegisterRequest("device-1", "Test Phone"))
            }.body<DeviceSyncInfo>()
            assertTrue(first.isFirstSync)

            val second = client.post("/api/recipes/sync/device") {
                bearerAuth(token); contentType(ContentType.Application.Json)
                setBody(DeviceRegisterRequest("device-1", "Test Phone"))
            }.body<DeviceSyncInfo>()
            assertTrue(!second.isFirstSync)
        }
    }

    @Test
    fun thumbnailEndpointDownscalesImage() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            // Image endpoints are owner-scoped, so attach the stored image to a recipe owned by "tester".
            val uid = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("thumbtest", renderPng(1000, 800), "png")
            RecipeRepository.upsert(uid, recipe("thumbtest", "Thumb", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(uid, "thumbtest", filename, null)

            val thumb = client.get("/api/recipes/images/$filename/thumbnail") { bearerAuth(token) }
            assertEquals(HttpStatusCode.OK, thumb.status)
            val thumbBytes = thumb.body<ByteArray>()
            val decoded = ImageIO.read(ByteArrayInputStream(thumbBytes))
            assertNotNull(decoded)
            assertTrue(maxOf(decoded.width, decoded.height) <= ImageStore.THUMB_SIZE)
            // A 300px thumbnail must be far smaller than the ~1000px source on the wire.
            assertTrue(thumbBytes.size < imageStore.load(filename)!!.size)
        }
    }

    @Test
    fun headRequestsAnswerExistenceChecks() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            // Recipe existence (client uses HEAD to choose create-vs-update).
            post(client, token, recipe("r1", "Pancakes", "2026-06-14T10:00:00.000Z"))
            assertEquals(HttpStatusCode.OK, client.head("/api/recipes/r1") { bearerAuth(token) }.status)
            assertEquals(HttpStatusCode.NotFound, client.head("/api/recipes/missing") { bearerAuth(token) }.status)

            // Image existence (client uses HEAD to avoid re-uploading an image already on the server).
            // Owner-scoped, so bind the stored image to a recipe owned by "tester".
            val uid = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("headtest", renderPng(40, 40), "png")
            RecipeRepository.upsert(uid, recipe("headtest", "Head", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(uid, "headtest", filename, null)
            assertEquals(HttpStatusCode.OK, client.head("/api/recipes/images/$filename") { bearerAuth(token) }.status)
            assertEquals(
                HttpStatusCode.NotFound,
                client.head("/api/recipes/images/nope.jpg") { bearerAuth(token) }.status,
            )
        }
    }

    @Test
    fun cannotReadAnotherUsersImage() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            // "tester" owns a recipe with an image.
            val tester = UserRepository.findByUsername("tester")!!.id
            val filename = imageStore.store("victim", renderPng(60, 60), "png")
            RecipeRepository.upsert(tester, recipe("victim", "Secret", "2026-06-01T00:00:00.000Z"))
            RecipeRepository.setImageFilename(tester, "victim", filename, null)

            // A different user must not be able to read it by filename (GET/HEAD/thumbnail all 404).
            UserRepository.create("intruder", "pw")
            val intruderToken = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("intruder", "pw"))
            }.body<AuthResponse>().token

            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/images/$filename") { bearerAuth(intruderToken) }.status)
            assertEquals(HttpStatusCode.NotFound, client.head("/api/recipes/images/$filename") { bearerAuth(intruderToken) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/recipes/images/$filename/thumbnail") { bearerAuth(intruderToken) }.status)

            // The owner still can.
            val testerToken = login(client)
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes/images/$filename") { bearerAuth(testerToken) }.status)
        }
    }

    @Test
    fun tokenInvalidatedByPasswordChangeAndDeletion() = testApplication {
        application { installSalty(jwt, imageStore) }
        val client = jsonClient()
        runBlocking {
            UserRepository.create("carol", "carolpw12")
            val token = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("carol", "carolpw12"))
            }.body<AuthResponse>().token
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token) }.status)

            // Cross a whole second so the password-change timestamp is strictly after the token's iat
            // (iat has second granularity), then the old token must be rejected.
            kotlinx.coroutines.delay(1100)
            val carol = UserRepository.findByUsername("carol")!!
            UserRepository.changePassword(carol.id, "carolpw34")
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes") { bearerAuth(token) }.status)

            // A token for a since-deleted user is likewise rejected (existence check, no timing needed).
            val token2 = client.post("/api/auth/login") {
                contentType(ContentType.Application.Json); setBody(AuthRequest("carol", "carolpw34"))
            }.body<AuthResponse>().token
            assertEquals(HttpStatusCode.OK, client.get("/api/recipes") { bearerAuth(token2) }.status)
            UserRepository.deleteWithData(carol.id)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes") { bearerAuth(token2) }.status)
        }
    }

    @Test
    fun adminPostWithoutCsrfIsRejected() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = createClient { install(HttpCookies); followRedirects = false }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "boss"); append("password", "pw") })
        // No CSRF token → 403, and no user is created.
        val resp = web.submitForm(
            url = "/users",
            formParameters = parameters { append("username", "mallory"); append("password", "password123") },
        )
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(null, runBlocking { UserRepository.findByUsername("mallory") })
    }

    @Test
    fun createUserRejectsShortPassword() = testApplication {
        application { installSalty(jwt, imageStore) }
        runBlocking { UserRepository.create("boss", "pw", isAdmin = true) }
        val web = createClient { install(HttpCookies) }
        web.submitForm(url = "/login", formParameters = parameters { append("username", "boss"); append("password", "pw") })
        val csrf = extractCsrf(web.get("/users").bodyAsText())
        web.submitForm(
            url = "/users",
            formParameters = parameters { append("username", "shorty"); append("password", "abc"); append("csrf", csrf) },
        )
        assertEquals(null, runBlocking { UserRepository.findByUsername("shorty") })
    }

    /** Pulls the hidden CSRF token out of a rendered admin page. */
    private fun extractCsrf(html: String): String =
        Regex("name=\"csrf\" value=\"([0-9a-f]+)\"").find(html)?.groupValues?.get(1)
            ?: error("no CSRF token found in page")

    private fun renderPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    // helpers
    private suspend fun post(client: io.ktor.client.HttpClient, token: String, r: ServerRecipe) {
        client.post("/api/recipes") {
            bearerAuth(token); contentType(ContentType.Application.Json); setBody(r)
        }
    }

    private suspend fun seed(client: io.ktor.client.HttpClient, token: String, vararg ids: String) {
        ids.forEachIndexed { i, id ->
            post(client, token, recipe(id, "Recipe $id", "2026-06-0${i + 1}T00:00:00.000Z"))
        }
    }

    private fun HttpResponse.header(name: String): String? = headers[name]
}
