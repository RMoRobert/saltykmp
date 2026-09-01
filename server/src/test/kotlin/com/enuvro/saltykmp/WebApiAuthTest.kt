package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.auth.CSRF_HEADER
import com.enuvro.saltykmp.auth.MAX_SESSION_AGE_SECONDS
import com.enuvro.saltykmp.auth.revalidateSession
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceRepository
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
import com.enuvro.saltykmp.util.appJson
import com.enuvro.saltykmp.web.UserSession
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.parameters
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JSON API accepts a Bearer device sync token (native clients) or the web session cookie (browser UI).
 *
 * These cover the three things that change when a second credential is allowed onto those routes: the
 * principal must still resolve to a user id, an unauthenticated call must fail as JSON rather than as a
 * login redirect, and a cookie-authenticated write must prove it wasn't cross-site.
 */
class WebApiAuthTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-webapi-img"))

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

    private fun ApplicationTestBuilder.jsonCookieClient() = createClient {
        install(ContentNegotiation) { json(appJson) }
        install(HttpCookies)
    }

    /** Logs the browser client in and returns the session's CSRF token, scraped from a rendered page. */
    private suspend fun webLogin(client: HttpClient): String {
        // A successful login answers with a redirect to the landing page; the cookie rides along.
        client.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        val html = client.get("/app").bodyAsText()
        val token = Regex("""data-csrf="([0-9a-f]+)"""").find(html)?.groupValues?.get(1)
        assertTrue(!token.isNullOrEmpty(), "expected a CSRF token in the rendered page")
        return token!!
    }

    private suspend fun seedRecipe(id: String, name: String) {
        RecipeRepository.upsert(
            userId = UserRepository.findByUsername("tester")!!.id,
            recipe = ServerRecipe(id = id, name = name, lastModifiedDate = "2026-01-01T00:00:00.000Z"),
        )
    }

    @Test
    fun sessionCookieCanReadTheJsonApi() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { seedRecipe("r1", "Skillet Cornbread") }

        val web = jsonCookieClient()
        webLogin(web)

        val resp = web.get("/api/recipes/r1")
        assertEquals(HttpStatusCode.OK, resp.status, "session cookie should authenticate an API GET")
        assertEquals("Skillet Cornbread", resp.body<ServerRecipe>().name)
    }

    @Test
    fun unauthenticatedApiCallGets401JsonNotALoginRedirect() = testApplication {
        application { installSalty(imageStore) }

        val anon = createClient { followRedirects = false }
        val resp = anon.get("/api/recipes/r1")

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertNotEquals(
            "/login", resp.headers["Location"],
            "an API call must not be redirected to the HTML login page",
        )
    }

    @Test
    fun sessionWriteWithoutCsrfHeaderIsRejectedAndChangesNothing() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { seedRecipe("r1", "Original Name") }

        val web = jsonCookieClient()
        webLogin(web)

        val resp = web.put("/api/recipes/r1") {
            contentType(ContentType.Application.Json)
            setBody(ServerRecipe(id = "r1", name = "Hijacked", lastModifiedDate = "2026-02-02T00:00:00.000Z"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status, "cookie-authenticated write needs a CSRF token")

        // The guard must short-circuit, not merely respond alongside the handler.
        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, "r1")
        }
        assertEquals("Original Name", stored?.name, "the rejected write must not have been applied")
    }

    @Test
    fun sessionWriteWithCsrfHeaderSucceeds() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { seedRecipe("r1", "Original Name") }

        val web = jsonCookieClient()
        val csrf = webLogin(web)

        val resp = web.put("/api/recipes/r1") {
            contentType(ContentType.Application.Json)
            header(CSRF_HEADER, csrf)
            setBody(ServerRecipe(id = "r1", name = "Edited On The Web", lastModifiedDate = "2026-02-02T00:00:00.000Z"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, "r1")
        }
        assertEquals("Edited On The Web", stored?.name)
    }

    @Test
    fun appPageRequiresAuth() = testApplication {
        application { installSalty(imageStore) }
        val anon = createClient { followRedirects = false }
        val resp = anon.get("/app")
        assertEquals(HttpStatusCode.Found, resp.status)
        assertEquals("/login", resp.headers["Location"])
    }

    /** `/editor` was this page's address while it was an experiment; bookmarks of it still exist. */
    @Test
    fun oldEditorUrlRedirectsToTheApp() = testApplication {
        application { installSalty(imageStore) }
        val anon = createClient { followRedirects = false }
        val resp = anon.get("/editor")
        assertEquals(HttpStatusCode.MovedPermanently, resp.status)
        assertEquals("/app", resp.headers["Location"])
    }

    @Test
    fun appPageCarriesTheSessionCsrfTokenForItsApiCalls() = testApplication {
        application { installSalty(imageStore) }
        val web = jsonCookieClient()
        val csrf = webLogin(web)

        val html = web.get("/app").bodyAsText()
        assertTrue(html.contains("/static/app/app.js"), "app page should load the app bundle")
        assertTrue(
            html.contains(csrf),
            "the page must hand the session CSRF token to the app, or every write would 403",
        )
    }

    @Test
    fun bearerTokenStillWorksAndNeedsNoCsrfHeader() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { seedRecipe("r1", "Original Name") }

        val api = createClient { install(ContentNegotiation) { json(appJson) } }
        val token = api.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw", deviceId = "test-device"))
        }.body<AuthResponse>().deviceToken!!

        val resp = api.put("/api/recipes/r1") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(ServerRecipe(id = "r1", name = "Edited By A Native Client", lastModifiedDate = "2026-02-02T00:00:00.000Z"))
        }
        assertEquals(HttpStatusCode.OK, resp.status, "Bearer-token callers must not be affected by the CSRF guard")

        val stored = runBlocking {
            RecipeRepository.getById(UserRepository.findByUsername("tester")!!.id, "r1")
        }
        assertEquals("Edited By A Native Client", stored?.name)
    }

    /**
     * A signed cookie proves we minted it, not that the account still exists. Deleting a user used
     * to lock their native clients out immediately while their open browser tab kept writing for
     * the cookie's full lifetime.
     */
    @Test
    fun aSessionStopsWorkingOnceTheUserIsDeleted() = testApplication {
        application { installSalty(imageStore) }
        val client = createClient { install(ContentNegotiation) { json(appJson) }; install(HttpCookies) }
        client.submitForm(
            url = "/login",
            formParameters = parameters { append("username", "tester"); append("password", "pw") },
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/recipes").status, "the session should work first")

        runBlocking {
            UserRepository.deleteWithData(UserRepository.findByUsername("tester")!!.id)
        }

        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/recipes").status,
            "the cookie must stop working the moment the account is gone")
    }

    /**
     * The device-management API is cookie-authenticated, so its writes need the same CSRF proof as
     * every other session-reachable JSON write. revoke-all is the case worth pinning: it takes no
     * body, which is exactly the shape a cross-site form POST can produce.
     */
    @Test
    fun deviceManagementWritesNeedTheCsrfHeaderToo() = testApplication {
        application { installSalty(imageStore) }
        val uid = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking { DeviceRepository.issueToken(uid, "phone", "Phone", "a".repeat(64)) }

        val web = jsonCookieClient()
        val csrf = webLogin(web)

        val blocked = web.post("/api/auth/devices/revoke-all")
        assertEquals(HttpStatusCode.Forbidden, blocked.status, "a cookie write without the header must be refused")
        assertTrue(runBlocking { DeviceRepository.listForUser(uid).single().hasToken },
            "the refused revoke-all must not have run")

        val allowed = web.post("/api/auth/devices/revoke-all") { header(CSRF_HEADER, csrf) }
        assertEquals(HttpStatusCode.OK, allowed.status, "the same write with the header must go through")
        assertEquals(false, runBlocking { DeviceRepository.listForUser(uid).single().hasToken })
    }

    /** A session cookie as the browser would present it, minted [ageSeconds] ago. */
    private fun sessionAged(userId: String, ageSeconds: Long) = UserSession(
        userId = userId,
        username = "tester",
        isAdmin = false,
        csrfToken = "csrf",
        issuedAt = Instant.now().epochSecond - ageSeconds,
    )

    /**
     * Backdates the password stamp out of the way, so the age check below is the only thing that can
     * reject the session. Without this every backdated cookie would fail the `issuedAt < changedSec`
     * comparison instead and the test would pass without exercising anything.
     */
    private suspend fun backdatePasswordChange(userId: String) = DatabaseFactory.dbQuery {
        Users.update({ Users.id eq userId }) {
            it[Users.passwordChangedAt] = LocalDateTime.now(ZoneOffset.UTC).minusDays(365)
        }
        Unit
    }

    /**
     * The cookie carries no `Max-Age`, and it could not be trusted if it did -- that is client-side
     * state, which a replayed cookie ignores. So the bound lives in [revalidateSession], where a
     * stolen cookie has to pass it too.
     */
    @Test
    fun aSessionPastTheMaxAgeIsRejected(): Unit = runBlocking {
        val userId = UserRepository.findByUsername("tester")!!.id
        backdatePasswordChange(userId)

        assertNull(
            revalidateSession(sessionAged(userId, MAX_SESSION_AGE_SECONDS + 60)),
            "a cookie older than the window must be rejected however valid its signature",
        )
    }

    @Test
    fun aSessionInsideTheMaxAgeIsAccepted(): Unit = runBlocking {
        val userId = UserRepository.findByUsername("tester")!!.id
        backdatePasswordChange(userId)

        assertNotNull(
            revalidateSession(sessionAged(userId, 0)),
            "a session minted just now must be valid",
        )
        assertNotNull(
            revalidateSession(sessionAged(userId, MAX_SESSION_AGE_SECONDS - 60)),
            "a session just inside the window must still be valid",
        )
    }
}
