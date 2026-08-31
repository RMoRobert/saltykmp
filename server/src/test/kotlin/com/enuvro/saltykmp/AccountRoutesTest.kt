package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.auth.CSRF_HEADER
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
import com.enuvro.saltykmp.web.ChangePasswordRequest
import com.enuvro.saltykmp.web.CreateUserRequest
import com.enuvro.saltykmp.web.SetAdminRequest
import com.enuvro.saltykmp.web.SetPasswordRequest
import com.enuvro.saltykmp.web.UserSummary
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.request.delete
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
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
import org.jetbrains.exposed.v1.jdbc.deleteAll
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Account self-service and user administration — the screens that used to be Pico pages at
 * `/devices` and `/users`, and the password change that did not exist at all before.
 *
 * The interesting cases here are the ones a "change your password" feature gets wrong: leaving the
 * caller's own session invalid, leaving synced devices holding a credential minted against the old
 * password, or letting a borrowed cookie change the password without knowing it.
 */
class AccountRoutesTest {

    private val imageStore = ImageStore(Files.createTempDirectory("salty-account-img"))

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
            UserRepository.create("boss", "bosspassword", isAdmin = true)
            UserRepository.create("tester", "testpassword")
        }
    }

    private fun ApplicationTestBuilder.browser() = createClient {
        install(ContentNegotiation) { json(appJson) }
        install(HttpCookies)
    }

    /** Signs in and returns the CSRF token the app shell hands its own API calls. */
    private suspend fun signIn(client: HttpClient, username: String, password: String): String {
        client.submitForm(
            url = "/login",
            formParameters = parameters { append("username", username); append("password", password) },
        )
        return Regex("""data-csrf="([0-9a-f]+)"""").find(client.get("/app").bodyAsText())
            ?.groupValues?.get(1) ?: error("no CSRF token in the app shell")
    }

    private suspend fun apiLoginStatus(client: HttpClient, username: String, password: String) =
        client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username, password, deviceId = "$username-device"))
        }.status

    /* ------------------------------------------------------ changing your own password -- */

    @Test
    fun changingYourPasswordNeedsTheCurrentOne() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")

        val resp = web.post("/api/account/password") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(ChangePasswordRequest("not-my-password", "brandnewpassword"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        // And nothing changed: the original password still authenticates.
        assertEquals(HttpStatusCode.OK, apiLoginStatus(web, "tester", "testpassword"))
    }

    @Test
    fun changingYourPasswordWithoutCsrfIsRejected() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        signIn(web, "tester", "testpassword")

        val resp = web.post("/api/account/password") {
            contentType(ContentType.Application.Json)
            setBody(ChangePasswordRequest("testpassword", "brandnewpassword"))
        }
        assertEquals(HttpStatusCode.Forbidden, resp.status)
        assertEquals(HttpStatusCode.OK, apiLoginStatus(web, "tester", "testpassword"))
    }

    @Test
    fun aShortNewPasswordIsRejected() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")

        val resp = web.post("/api/account/password") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(ChangePasswordRequest("testpassword", "short"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(HttpStatusCode.OK, apiLoginStatus(web, "tester", "testpassword"))
    }

    /**
     * The one that is easy to get wrong. `revalidateSession` rejects any cookie issued before the
     * user's `passwordChangedAt`, which after this call includes the cookie that authenticated the
     * call itself — so without the re-mint, changing your password logs you out of the tab you
     * changed it in, and every subsequent request bounces to /login.
     */
    @Test
    fun changingYourPasswordKeepsYouSignedIn() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")

        assertEquals(
            HttpStatusCode.OK,
            web.post("/api/account/password") {
                contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
                setBody(ChangePasswordRequest("testpassword", "brandnewpassword"))
            }.status,
        )

        // Same cookie, next request: still signed in, and still able to write.
        assertEquals(HttpStatusCode.OK, web.get("/app").status)
        assertEquals(HttpStatusCode.OK, web.get("/api/recipes").status)
    }

    @Test
    fun changingYourPasswordReplacesItAndSignsOutEveryDevice() = testApplication {
        application { installSalty(imageStore) }
        val testerId = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking { DeviceRepository.issueToken(testerId, "phone-1", "Phone", "hash-1") }

        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")

        val resp = web.post("/api/account/password") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(ChangePasswordRequest("testpassword", "brandnewpassword"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(1, resp.body<Map<String, Int>>()["devicesSignedOut"])

        assertEquals(HttpStatusCode.Unauthorized, apiLoginStatus(web, "tester", "testpassword"))
        assertEquals(HttpStatusCode.OK, apiLoginStatus(web, "tester", "brandnewpassword"))

        // Scoped to the device enrolled BEFORE the change, not "no device holds a token": signing in
        // above enrols the client that did it, which is the point of mandatory enrolment.
        val phone = runBlocking { DeviceRepository.listForUser(testerId) }.single { it.deviceId == "phone-1" }
        assertEquals(false, phone.hasToken, "the enrolled device should have lost its token")
    }

    /* ---------------------------------------------------------- user administration -- */

    @Test
    fun nonAdminsCannotReachUserAdministration() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")
        val bossId = runBlocking { UserRepository.findByUsername("boss")!!.id }

        assertEquals(HttpStatusCode.Forbidden, web.get("/api/users").status)
        assertEquals(
            HttpStatusCode.Forbidden,
            web.post("/api/users") {
                contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
                setBody(CreateUserRequest("sneaky", "longenoughpw"))
            }.status,
        )
        assertEquals(
            HttpStatusCode.Forbidden,
            web.delete("/api/users/$bossId") { header(CSRF_HEADER, csrf) }.status,
        )
        assertNotNull(runBlocking { UserRepository.findByUsername("boss") })
    }

    @Test
    fun theListMarksWhichRowIsYou() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        signIn(web, "boss", "bosspassword")

        val users = web.get("/api/users").body<List<UserSummary>>()
        assertEquals(setOf("boss", "tester"), users.map { it.username }.toSet())
        assertTrue(users.single { it.username == "boss" }.isSelf)
        assertFalse(users.single { it.username == "tester" }.isSelf)
    }

    @Test
    fun theLastAdminCannotBeDemotedOrDeleted() = testApplication {
        application { installSalty(imageStore) }
        val web = browser()
        val csrf = signIn(web, "boss", "bosspassword")
        val bossId = runBlocking { UserRepository.findByUsername("boss")!!.id }

        val demote = web.patch("/api/users/$bossId") {
            contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
            setBody(SetAdminRequest(isAdmin = false))
        }
        assertEquals(HttpStatusCode.Conflict, demote.status)
        assertTrue(runBlocking { UserRepository.findById(bossId)!!.isAdmin })

        // Deleting yourself is refused before the last-admin rule even applies.
        assertEquals(
            HttpStatusCode.Conflict,
            web.delete("/api/users/$bossId") { header(CSRF_HEADER, csrf) }.status,
        )
    }

    /**
     * Admin comes off the user row, not the session cookie. Demotion has to bite on the very next
     * request, because in the app it is something you can do to yourself from a screen you are
     * still standing on.
     */
    @Test
    fun aDemotedAdminLosesAccessOnTheNextRequest() = testApplication {
        application { installSalty(imageStore) }
        runBlocking { UserRepository.setAdmin(UserRepository.findByUsername("tester")!!.id, true) }

        val web = browser()
        val csrf = signIn(web, "tester", "testpassword")
        assertEquals(HttpStatusCode.OK, web.get("/api/users").status)

        val testerId = runBlocking { UserRepository.findByUsername("tester")!!.id }
        assertEquals(
            HttpStatusCode.NoContent,
            web.patch("/api/users/$testerId") {
                contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
                setBody(SetAdminRequest(isAdmin = false))
            }.status,
        )

        // Same cookie, which still claims isAdmin=true; the row says otherwise and the row wins.
        assertEquals(HttpStatusCode.Forbidden, web.get("/api/users").status)
    }

    @Test
    fun anAdminResetReplacesThePasswordAndRevokesThatUsersDevices() = testApplication {
        application { installSalty(imageStore) }
        val testerId = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking { DeviceRepository.issueToken(testerId, "tablet-1", "Tablet", "hash-2") }

        val web = browser()
        val csrf = signIn(web, "boss", "bosspassword")

        assertEquals(
            HttpStatusCode.NoContent,
            web.post("/api/users/$testerId/password") {
                contentType(ContentType.Application.Json); header(CSRF_HEADER, csrf)
                setBody(SetPasswordRequest("resetpassword1"))
            }.status,
        )
        assertEquals(HttpStatusCode.Unauthorized, apiLoginStatus(web, "tester", "testpassword"))
        assertEquals(HttpStatusCode.OK, apiLoginStatus(web, "tester", "resetpassword1"))
        val tablet = runBlocking { DeviceRepository.listForUser(testerId) }.single { it.deviceId == "tablet-1" }
        assertEquals(false, tablet.hasToken, "the reset must have signed the tablet out")
    }

    @Test
    fun deletingAUserTakesTheirRecipesWithThem() = testApplication {
        application { installSalty(imageStore) }
        val testerId = runBlocking { UserRepository.findByUsername("tester")!!.id }
        runBlocking {
            RecipeRepository.upsert(testerId, com.enuvro.saltykmp.api.ServerRecipe(
                id = "r1", name = "Doomed Dumplings", lastModifiedDate = "2026-01-01T00:00:00.000Z",
            ))
        }

        val web = browser()
        val csrf = signIn(web, "boss", "bosspassword")
        assertEquals(
            HttpStatusCode.NoContent,
            web.delete("/api/users/$testerId") { header(CSRF_HEADER, csrf) }.status,
        )

        assertEquals(null, runBlocking { UserRepository.findByUsername("tester") })
        assertEquals(null, runBlocking { RecipeRepository.getById(testerId, "r1") })
        assertEquals(HttpStatusCode.Unauthorized, apiLoginStatus(web, "tester", "testpassword"))
    }
}
