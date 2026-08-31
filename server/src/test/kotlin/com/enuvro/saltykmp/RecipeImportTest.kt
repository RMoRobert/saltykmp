package com.enuvro.saltykmp

import com.enuvro.saltykmp.auth.CSRF_HEADER
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
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.recipe.ImportResponse
import com.enuvro.saltykmp.recipe.addressRefusal
import com.enuvro.saltykmp.util.appJson
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web import: `POST /api/recipes/import`.
 *
 * The half of this that matters most is not the happy path — it is that a server which fetches a URL
 * a user typed does not become a way to reach the network it is sitting in. Those tests are the ones
 * that would still be worth keeping if the feature were rewritten.
 */
class RecipeImportTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-import-img"))

    /** A stand-in recipe site. It runs on loopback, which is exactly what the real policy refuses. */
    private lateinit var site: HttpServer
    private var sitePort = 0
    private var lastPathServed: String? = null

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

        private val RECIPE_PAGE = """
            <!doctype html><html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe",
             "name":"Skillet Cornbread",
             "description":"A cast-iron staple.",
             "recipeYield":"8 wedges",
             "recipeIngredient":["1 cup cornmeal","1 cup buttermilk","2 eggs"],
             "recipeInstructions":[{"@type":"HowToStep","text":"Heat the skillet."},
                                   {"@type":"HowToStep","text":"Bake 25 minutes."}]}
            </script></head><body>Cornbread</body></html>
        """.trimIndent()
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
            UserRepository.create("tester", "testpassword")
        }

        site = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        sitePort = site.address.port
        site.createContext("/") { exchange ->
            lastPathServed = exchange.requestURI.path
            val (status, body, type) = when (exchange.requestURI.path) {
                "/recipe" -> Triple(200, RECIPE_PAGE, "text/html; charset=utf-8")
                "/plain" -> Triple(200, "<html><body>Just a blog post.</body></html>", "text/html")
                "/gone" -> Triple(404, "no", "text/plain")
                else -> Triple(404, "no", "text/plain")
            }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", type)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        // A public-looking page that bounces to somewhere it should not be able to reach.
        site.createContext("/redirect-inward") { exchange ->
            exchange.responseHeaders.add("Location", "http://169.254.169.254/latest/meta-data/")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        site.start()
    }

    @AfterTest
    fun stopSite() {
        site.stop(0)
    }

    private fun ApplicationTestBuilder.browser() = createClient {
        install(ContentNegotiation) { json(appJson) }
        install(HttpCookies)
    }

    private suspend fun signIn(client: HttpClient): String {
        client.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "testpassword") },
        )
        return Regex("""data-csrf="([0-9a-f]+)"""").find(client.get("/app").bodyAsText())
            ?.groupValues?.get(1) ?: error("no CSRF token in the app shell")
    }

    private suspend fun importUrl(client: HttpClient, csrf: String?, url: String): HttpResponse =
        client.post("/api/recipes/import") {
            contentType(ContentType.Application.Json)
            if (csrf != null) header(CSRF_HEADER, csrf)
            setBody("""{"url":${appJson.encodeToString(kotlinx.serialization.serializer(), url)}}""")
        }

    /* ------------------------------------------------------------- the guard -- */

    /**
     * The address policy on its own. These are the addresses that make an importer on a server a
     * different thing from an importer on a phone — metadata services, loopback, the private ranges.
     */
    @Test
    fun theAddressPolicyRefusesEverythingInsideTheNetwork() {
        val refused = listOf(
            "127.0.0.1",        // loopback
            "localhost",        // ...by name
            "169.254.169.254",  // cloud metadata, the classic SSRF target
            "10.0.0.1",         // RFC 1918
            "192.168.1.1",
            "172.16.0.1",
            "100.64.0.1",       // carrier-grade NAT
            "0.0.0.0",
            "[::1]".trim('[', ']'),
        )
        for (host in refused) {
            assertNotNull(addressRefusal(host), "$host should have been refused")
        }
        assertNotNull(addressRefusal(""), "an empty host should be refused")
        assertNotNull(addressRefusal(null), "a missing host should be refused")
    }

    /** With the real policy in place, a loopback URL is refused before any request is made. */
    @Test
    fun anInternalAddressIsRefusedAndNeverFetched() = testApplication {
        application { installSalty(imageStore) }   // real policy, deliberately
        val web = browser()
        val csrf = signIn(web)
        lastPathServed = null

        val resp = importUrl(web, csrf, "http://127.0.0.1:$sitePort/recipe")

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertNull(lastPathServed, "the server must not have made the request at all")
    }

    /**
     * The reason redirects are followed by hand. A page that passes the address check is free to
     * answer with a Location pointing somewhere that would not have — so the check has to run again
     * on every hop, not just on what the user typed.
     */
    @Test
    fun aRedirectTowardsAnInternalAddressIsRefused() = testApplication {
        application { installSalty(imageStore, importAddressPolicy = allowLoopback()) }
        val web = browser()
        val csrf = signIn(web)

        val resp = importUrl(web, csrf, "http://127.0.0.1:$sitePort/redirect-inward")

        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertTrue(
            resp.bodyAsText().contains("won't follow") || resp.bodyAsText().contains("didn't fetch"),
            "should say it refused the redirect: ${resp.bodyAsText()}",
        )
    }

    @Test
    fun onlyHttpAndHttpsAreAccepted() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web)

        for (url in listOf("file:///etc/passwd", "ftp://example.com/x", "not a url at all")) {
            assertEquals(
                HttpStatusCode.BadRequest, importUrl(web, csrf, url).status,
                "$url should not have been accepted",
            )
        }
    }

    @Test
    fun importingNeedsASessionAndACsrfToken() = testApplication {
        application { installSalty(imageStore, importAddressPolicy = allowLoopback()) }

        val anon = createClient { install(ContentNegotiation) { json(appJson) } }
        assertEquals(
            HttpStatusCode.Unauthorized,
            importUrl(anon, null, "http://127.0.0.1:$sitePort/recipe").status,
        )

        val web = browser()
        signIn(web)
        assertEquals(
            HttpStatusCode.Forbidden,
            importUrl(web, null, "http://127.0.0.1:$sitePort/recipe").status,
            "a cookie-authenticated write needs the CSRF header",
        )
    }

    /* -------------------------------------------------------- the happy path -- */

    @Test
    fun aPageWithSchemaOrgDataImportsAsAnUnsavedDraft() = testApplication {
        application { installSalty(imageStore, importAddressPolicy = allowLoopback()) }
        val web = browser()
        val csrf = signIn(web)

        val resp = importUrl(web, csrf, "http://127.0.0.1:$sitePort/recipe")
        assertEquals(HttpStatusCode.OK, resp.status)

        val imported = resp.body<ImportResponse>()
        assertEquals("Skillet Cornbread", imported.recipe.name)
        assertEquals("8 wedges", imported.recipe.yield)
        assertEquals(3, imported.recipe.ingredients?.size)
        assertEquals(2, imported.recipe.directions?.size)

        // Where it came from, since the page's JSON-LD didn't say.
        assertTrue(imported.recipe.sourceDetails.orEmpty().contains("/recipe"))

        // A draft has no id: the browser mints one, so ids stay UUIDv7.
        assertEquals("", imported.recipe.id)

        // And nothing was written. Import is a proposal, not a save.
        val stored = runBlocking {
            RecipeRepository.listForSync(UserRepository.findByUsername("tester")!!.id, null, null, 100).recipes
        }
        assertTrue(stored.isEmpty(), "import must not save anything: $stored")
    }

    @Test
    fun aPageWithoutRecipeDataSaysSoRatherThanFailing() = testApplication {
        application { installSalty(imageStore, importAddressPolicy = allowLoopback()) }
        val web = browser()
        val csrf = signIn(web)

        val resp = importUrl(web, csrf, "http://127.0.0.1:$sitePort/plain")

        assertEquals(HttpStatusCode.UnprocessableEntity, resp.status)
        assertTrue(resp.bodyAsText().contains("doesn't publish recipe data"))
    }

    @Test
    fun theFarSidesErrorIsReportedAsTheirs() = testApplication {
        application { installSalty(imageStore, importAddressPolicy = allowLoopback()) }
        val web = browser()
        val csrf = signIn(web)

        val resp = importUrl(web, csrf, "http://127.0.0.1:$sitePort/gone")

        assertEquals(HttpStatusCode.BadGateway, resp.status)
        assertTrue(resp.bodyAsText().contains("404"))
    }

    /**
     * Lets the test reach its own loopback stand-in site. Everything else still goes through the
     * real policy, so a test can't accidentally pass because the guard was disabled wholesale.
     */
    private fun allowLoopback(): (String?) -> String? = { host ->
        if (host == "127.0.0.1" || host == "localhost") null else addressRefusal(host)
    }
}
