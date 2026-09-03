package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerRecipeSummary
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the server sends, and how much of it.
 *
 * Three changes are covered here, and they were made together because they answer the same
 * measurement: a cold load of the web app moved roughly a megabyte, nearly none of which had to
 * move. Nothing was compressed, nothing carried a cache validator so every reload re-fetched the
 * whole bundle and every visible thumbnail, and `/api/recipes` returned complete recipes -- around
 * 88% of a real library by bytes -- to draw a column of names.
 *
 * The sync clients are the constraint running through all of it: `/api/recipes` is what the Swift
 * and Compose apps sync against, so the lighter shape has to be opt-in and the default has to stay
 * exactly what it was.
 */
class TransferEfficiencyTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-transfer-img"))

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
            UserRepository.create("tester", "pw")
        }
    }

    private fun ApplicationTestBuilder.jsonClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
    }

    private suspend fun login(client: io.ktor.client.HttpClient): String {
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw", deviceId = "test-device"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        return resp.body<AuthResponse>().deviceToken!!
    }

    /** One recipe with a body worth omitting, and a second so a list is a list. */
    private suspend fun seedRecipes() {
        val uid = UserRepository.findByUsername("tester")!!.id
        RecipeRepository.upsert(
            uid,
            ServerRecipe(
                id = "r1",
                name = "Braised Beef",
                lastModifiedDate = "2026-06-01T00:00:00.000Z",
                introduction = "The row's second line.",
                rating = 4,
                isFavorite = true,
                ingredients = (1..40).map { Ingredient(id = "i$it", text = "Ingredient number $it") },
                directions = (1..20).map { Direction(id = "d$it", text = "Step number $it, at length.") },
            ),
        )
        RecipeRepository.upsert(
            uid,
            ServerRecipe(id = "r2", name = "Cornbread", lastModifiedDate = "2026-06-02T00:00:00.000Z"),
        )
    }

    /* ------------------------------------------------------- the summary projection -- */

    /**
     * The default shape is the sync clients' shape and has to stay byte-identical in what it
     * carries. This is the assertion that stops `fields=summary` from ever becoming the default by
     * accident: a sync that silently stopped receiving ingredients is data loss, not slowness.
     */
    @Test
    fun theDefaultListStillCarriesWholeRecipes() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seedRecipes()
            val full: List<ServerRecipe> = client.get("/api/recipes") { bearerAuth(token) }.body()
            val beef = full.single { it.id == "r1" }
            assertEquals(40, beef.ingredients?.size, "the sync shape carries ingredients")
            assertEquals(20, beef.directions?.size, "and directions")
        }
    }

    /** The lighter shape: what a list draws, and nothing that only a recipe page shows. */
    @Test
    fun theSummaryShapeDropsTheBodyAndKeepsWhatARowNeeds() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seedRecipes()
            val resp = client.get("/api/recipes?fields=summary") { bearerAuth(token) }
            val body = resp.bodyAsText()
            val rows: List<ServerRecipeSummary> = client
                .get("/api/recipes?fields=summary") { bearerAuth(token) }.body()

            val beef = rows.single { it.id == "r1" }
            assertEquals("Braised Beef", beef.name, "the name")
            assertEquals("The row's second line.", beef.introduction, "the second line")
            assertEquals(4, beef.rating, "the rating")
            assertEquals(true, beef.isFavorite, "the favourite mark")
            assertEquals("2026-06-01T00:00:00.000Z", beef.lastModifiedDate, "what the sorts order by")

            // Not "null ingredients" -- the key is not on the wire at all, which is the difference
            // between a summary and a recipe that happens to have nothing in it.
            for (key in listOf("ingredients", "directions", "notes", "variations", "nutrition")) {
                assertFalse(body.contains("\"$key\""), "the summary must not carry $key")
            }
            assertFalse(body.contains("Ingredient number"), "nor any of the body's text")
        }
    }

    /**
     * A summary that disagreed with the full list about which recipes exist would be worse than no
     * summary at all, so both go through the same filter and the same order.
     */
    @Test
    fun theSummaryAgreesWithTheFullListOnWhatExists() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seedRecipes()
            val full: List<ServerRecipe> = client.get("/api/recipes") { bearerAuth(token) }.body()
            val rows: List<ServerRecipeSummary> = client
                .get("/api/recipes?fields=summary") { bearerAuth(token) }.body()
            assertEquals(full.map { it.id }, rows.map { it.id }, "same recipes, same order")

            // And the delta filter behaves the same, since sync's other lever must keep working.
            val since = "?modifiedSince=2026-06-01T12:00:00.000Z"
            val fullDelta: List<ServerRecipe> = client.get("/api/recipes$since") { bearerAuth(token) }.body()
            val rowsDelta: List<ServerRecipeSummary> = client
                .get("/api/recipes$since&fields=summary") { bearerAuth(token) }.body()
            assertEquals(listOf("r2"), fullDelta.map { it.id })
            assertEquals(fullDelta.map { it.id }, rowsDelta.map { it.id }, "the delta filter too")
        }
    }

    /* -------------------------------------------------------------- compression -- */

    @Test
    fun jsonIsCompressedForAClientThatAsksAndNotForOneThatDoesNot() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            seedRecipes()
            val zipped = client.get("/api/recipes") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "gzip")
            }
            assertEquals("gzip", zipped.headers[HttpHeaders.ContentEncoding], "JSON compresses")

            val plain = client.get("/api/recipes") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "identity")
            }
            assertNull(plain.headers[HttpHeaders.ContentEncoding], "and only when asked for")
            assertTrue(
                zipped.bodyAsBytes().size < plain.bodyAsBytes().size,
                "and it is actually smaller: ${zipped.bodyAsBytes().size} vs ${plain.bodyAsBytes().size}",
            )
        }
    }

    /**
     * JPEG and PNG are already compressed; running them through gzip spends CPU on every request to
     * make the response no smaller, and occasionally larger.
     */
    @Test
    fun imagesAreNotCompressed() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            uploadImage(client, token)
            val resp = client.get("/api/recipes/images/img1.png") {
                bearerAuth(token); header(HttpHeaders.AcceptEncoding, "gzip")
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNull(resp.headers[HttpHeaders.ContentEncoding], "an image is already compressed")
        }
    }

    /**
     * The one response that pairs a secret -- the CSRF token -- with anything a page can influence.
     * SameSite=Strict already means a cross-site request arrives without a session, so this is
     * belt-and-braces; it is also free, because the shell is a couple of KB.
     */
    @Test
    fun theHtmlCarryingTheCsrfTokenIsNotCompressed() = testApplication {
        application { installSalty(imageStore) }
        val resp = createClient { }.get("/login") { header(HttpHeaders.AcceptEncoding, "gzip") }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertNull(resp.headers[HttpHeaders.ContentEncoding], "HTML is left alone deliberately")
    }

    /* ------------------------------------------------------------ cache headers -- */

    private suspend fun uploadImage(client: io.ktor.client.HttpClient, token: String) {
        val uid = UserRepository.findByUsername("tester")!!.id
        RecipeRepository.upsert(
            uid,
            ServerRecipe(id = "img1", name = "Img", lastModifiedDate = "2026-06-01T00:00:00.000Z"),
        )
        val resp = client.submitFormWithBinaryData(
            url = "/api/recipes/img1/image",
            formData = formData {
                append("file", renderPng(60, 60), Headers.build {
                    append(HttpHeaders.ContentType, "image/png")
                    append(HttpHeaders.ContentDisposition, "filename=\"img1.png\"")
                })
            },
        ) { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    /**
     * An image keeps its filename when it is replaced, so the URL alone cannot say which bytes it
     * means and nothing could safely be cached. The ETag is the recipe's `lastModifiedImageDate`,
     * which is bumped when and only when the bytes change.
     */
    @Test
    fun anImageCarriesAnEtagAndRevalidatesToNotModified() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            uploadImage(client, token)

            val first = client.get("/api/recipes/images/img1.png") { bearerAuth(token) }
            val etag = first.headers[HttpHeaders.ETag]
            assertNotNull(etag, "an image carries a validator")
            assertTrue(first.bodyAsBytes().isNotEmpty(), "and the bytes, the first time")

            val again = client.get("/api/recipes/images/img1.png") {
                bearerAuth(token); header(HttpHeaders.IfNoneMatch, etag)
            }
            assertEquals(HttpStatusCode.NotModified, again.status, "and then a 304 instead of them")
            assertEquals(0, again.bodyAsBytes().size, "with no body")
        }
    }

    /** The thumbnail is derived from the image, so it is valid for exactly as long. */
    @Test
    fun aThumbnailRevalidatesOnTheSameStamp() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            uploadImage(client, token)
            val first = client.get("/api/recipes/images/img1.png/thumbnail") { bearerAuth(token) }
            val etag = assertNotNull(first.headers[HttpHeaders.ETag])
            val again = client.get("/api/recipes/images/img1.png/thumbnail") {
                bearerAuth(token); header(HttpHeaders.IfNoneMatch, etag)
            }
            assertEquals(HttpStatusCode.NotModified, again.status)
        }
    }

    /**
     * A URL carrying the current stamp cannot come to mean different bytes, so it gets a year. A
     * stale one gets the cautious answer instead of being pinned for a year on a guess.
     */
    @Test
    fun aVersionedImageUrlIsImmutableAndAStaleOneIsNot() = testApplication {
        application { installSalty(imageStore) }
        val client = jsonClient()
        runBlocking {
            val token = login(client)
            uploadImage(client, token)
            val stamp = client.get("/api/recipes/images/img1.png") { bearerAuth(token) }
                .headers[HttpHeaders.ETag]!!.trim('"')

            val versioned = client.get("/api/recipes/images/img1.png?v=$stamp") { bearerAuth(token) }
            // getAll, not [..]: the app-wide CachingHeaders default APPENDS, so this response used to
            // carry `immutable` AND `no-cache`. Reading the first of the two is what let that ship.
            val directives = versioned.headers.getAll(HttpHeaders.CacheControl).orEmpty()
            assertEquals(1, directives.size, "one answer, not two: $directives")
            assertTrue(
                directives.single().contains("immutable"),
                "the current stamp buys a long cache: $directives",
            )

            val stale = client.get("/api/recipes/images/img1.png?v=2020-01-01T00:00:00.000Z") {
                bearerAuth(token)
            }
            assertFalse(
                stale.headers[HttpHeaders.CacheControl].orEmpty().contains("immutable"),
                "a stale one does not: ${stale.headers[HttpHeaders.CacheControl]}",
            )
        }
    }

    /**
     * The bundle's filename is fixed rather than content-hashed, on purpose, so the Mustache shell
     * can name it. That rules out a far-future cache -- an old `salty.js` would outlive its
     * deployment -- and leaves revalidation, which still turns a repeat load from a re-download
     * into a status line.
     */
    @Test
    fun staticAssetsRevalidateRatherThanBeingCachedForever() = testApplication {
        application { installSalty(imageStore) }
        val client = createClient { }
        val first = client.get("/static/salty.css")
        assertEquals(HttpStatusCode.OK, first.status)
        val cacheControl = first.headers[HttpHeaders.CacheControl].orEmpty()
        assertTrue(cacheControl.contains("no-cache"), "the bundle must not be pinned: $cacheControl")
        assertFalse(cacheControl.contains("max-age=3"), "and must not carry a long max-age")

        val validator = first.headers[HttpHeaders.ETag] ?: first.headers[HttpHeaders.LastModified]
        assertNotNull(validator, "static content carries a validator to revalidate against")
    }

    private fun renderPng(width: Int, height: Int): ByteArray {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }
}
