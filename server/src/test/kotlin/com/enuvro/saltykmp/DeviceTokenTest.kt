package com.enuvro.saltykmp

import com.enuvro.saltykmp.auth.DeviceTokenService
import com.enuvro.saltykmp.auth.JwtService
import com.enuvro.saltykmp.db.Categories
import com.enuvro.saltykmp.db.Courses
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.DeviceSyncs
import com.enuvro.saltykmp.db.RecipeCategories
import com.enuvro.saltykmp.db.RecipeTags
import com.enuvro.saltykmp.db.Recipes
import com.enuvro.saltykmp.db.ShoppingLists
import com.enuvro.saltykmp.db.Tags
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.db.Users
import com.enuvro.saltykmp.image.ImageStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-device sync tokens.
 *
 * Weighted deliberately towards SCOPE and REVOCATION rather than the happy path. A token that syncs
 * is easy to notice working; a token that can also change a password, or that outlives the password
 * change meant to kill it, is the failure nobody sees until it matters.
 */
class DeviceTokenTest {

    private val jwt = JwtService("test-secret", "salty", "salty-app", validityMs = 60_000)
    private val imageStore = ImageStore(Files.createTempDirectory("salty-devtok-img"))
    private val tokens = DeviceTokenService("test-secret")

    companion object {
        @Volatile private var dbReady = false
        private fun ensureDb() {
            if (!dbReady) {
                DatabaseFactory.init(
                    "jdbc:h2:mem:saltydevtok;DB_CLOSE_DELAY=-1;MODE=PostgreSQL",
                    "org.h2.Driver", "sa", "",
                )
                dbReady = true
            }
        }
        const val DEVICE = "device-abc"
    }

    @BeforeTest
    fun setUp() {
        ensureDb()
        runBlocking {
            DatabaseFactory.dbQuery {
                RecipeTags.deleteAll(); RecipeCategories.deleteAll()
                Recipes.deleteAll(); Courses.deleteAll(); Categories.deleteAll(); Tags.deleteAll()
                ShoppingLists.deleteAll(); DeviceSyncs.deleteAll(); Users.deleteAll()
            }
            UserRepository.create("tester", "pw")
        }
    }

    private fun uid() = runBlocking { UserRepository.findByUsername("tester")!!.id }

    /** Mints a token for the test user and returns the plaintext, as enrolment will. */
    private fun issue(deviceId: String = DEVICE, name: String? = "Test Phone"): String {
        val token = tokens.generate()
        runBlocking { DeviceRepository.issueToken(uid(), deviceId, name, tokens.hash(token)) }
        return token
    }

    /* ------------------------------------------------------------------ the credential -- */

    @Test
    fun aTokenIsRecognisableAndNeverStoredInTheClear() {
        val token = tokens.generate()
        assertTrue(token.startsWith("salty_"), "leaked tokens must be greppable: $token")
        assertTrue(token.length > 40, "expected 32 random bytes, got ${token.length} chars")
        assertNotEquals(token, tokens.hash(token))
        assertEquals(64, tokens.hash(token).length, "hash should be hex SHA-256")
        assertEquals(tokens.hash(token), tokens.hash(token), "hashing must be deterministic")
        assertNotEquals(tokens.hash(token), tokens.hash(tokens.generate()))
    }

    @Test
    fun aDifferentSecretCannotVerifyTheSameToken() {
        val token = tokens.generate()
        assertNotEquals(
            tokens.hash(token), DeviceTokenService("a-different-secret").hash(token),
            "the HMAC key must actually participate, or a stolen database alone would be enough",
        )
    }

    /* ------------------------------------------------------------------------- syncing -- */

    @Test
    fun aTokenCanSync() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        val resp = client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun aTokenWorksOnEveryRouteGroupSyncNeeds() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        for (path in listOf("/api/recipes", "/api/courses", "/api/categories", "/api/tags", "/api/shoppingLists")) {
            val resp = client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }
            assertEquals(HttpStatusCode.OK, resp.status, "$path should accept a device token")
        }
    }

    /* --------------------------------------------------------------------------- scope -- */

    /**
     * The point of the whole design. A device token must not reach account administration, and it
     * cannot, because DEVICE_TOKEN_AUTH is not mounted on those routes.
     */
    @Test
    fun aTokenCannotReachAccountOrAdminRoutes() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        // followRedirects=false is essential: the session challenge answers with a redirect to
        // /login, and a client that follows it lands on a 200 login page -- which would make this
        // test pass while proving nothing at all.
        val strict = createClient { followRedirects = false }
        val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit =
            { header(HttpHeaders.Authorization, "Bearer $token") }

        val users = strict.get("/users", auth)
        assertEquals(HttpStatusCode.Found, users.status, "a sync credential must not list users")
        assertEquals("/login", users.headers[HttpHeaders.Location], "it should be bounced to sign in")
        assertEquals(HttpStatusCode.Found, strict.get("/users/new", auth).status)

        val changed = strict.post("/users/${uid()}/password") {
            auth()
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("password=hijacked-by-a-sync-token")
        }
        assertEquals(HttpStatusCode.Found, changed.status, "a sync credential must not change a password")

        // And the password genuinely still works, which is the assertion that actually matters.
        assertTrue(
            runBlocking { UserRepository.verifyCredential(UserRepository.findByUsername("tester"), "pw") },
            "the original password must still be valid",
        )
    }

    /* ---------------------------------------------------------------------- revocation -- */

    @Test
    fun revokingOneDeviceStopsThatTokenAndLeavesOthersAlone() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val phone = issue("phone", "Phone")
        val tablet = issue("tablet", "Tablet")

        runBlocking { DeviceRepository.revokeToken(uid(), "phone") }

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $phone") }.status,
            "the revoked device must be shut out")
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $tablet") }.status,
            "the other device must be unaffected")
    }

    /** Revoking keeps the row, so the device's history survives and it stays one device. */
    @Test
    fun revokingKeepsTheDeviceRowAndItsHistory() = runBlocking {
        issue("phone", "My Phone")
        DeviceRepository.revokeToken(uid(), "phone")
        val info = DeviceRepository.get(uid(), "phone")
        assertTrue(info != null, "revoking must not delete the device")
        assertNull(DeviceRepository.findByTokenHash(tokens.hash("salty_whatever")))
    }

    @Test
    fun revokeAllStopsEveryDevice() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val a = issue("a", "A")
        val b = issue("b", "B")
        runBlocking { DeviceRepository.revokeAllTokens(uid()) }
        for (t in listOf(a, b)) {
            assertEquals(HttpStatusCode.Unauthorized,
                client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $t") }.status)
        }
    }

    /**
     * Changing a password signs every device out. Two independent mechanisms are asserted here
     * because either alone would look fine in isolation: explicit revocation, and the issued-at
     * check that catches a token revocation somehow missed.
     */
    @Test
    fun changingThePasswordInvalidatesEveryToken() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status)

        runBlocking { UserRepository.changePassword(uid(), "a-brand-new-password") }

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "a token minted before the password change must stop working")
    }

    /**
     * The issued-at rule on its own, with the hash left in place — the case where explicit
     * revocation was skipped or missed.
     */
    @Test
    fun aTokenPredatingAPasswordChangeIsRejectedEvenIfItsHashSurvives() = runBlocking {
        val token = issue()
        assertTrue(DeviceRepository.findByTokenHash(tokens.hash(token)) != null)

        // Change the password WITHOUT revoking, then re-stamp the hash as if revocation was missed.
        val hash = tokens.hash(token)
        UserRepository.changePassword(uid(), "another-password")
        DatabaseFactory.dbQuery {
            DeviceSyncs.update({ DeviceSyncs.userId eq uid() }) { it[tokenHash] = hash }
        }

        assertNull(DeviceRepository.findByTokenHash(hash),
            "issued-at must reject it even with a live hash in the table")
    }

    /* ------------------------------------------------------------------- housekeeping -- */

    @Test
    fun aGarbageOrUnknownTokenIsRejected() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        for (bad in listOf("salty_nonsense", "not-even-close", "")) {
            assertEquals(HttpStatusCode.Unauthorized,
                client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $bad") }.status,
                "\"$bad\" must not authenticate")
        }
    }

    /** Re-enrolling a device replaces its token rather than leaving two live credentials. */
    @Test
    fun reEnrollingADeviceInvalidatesItsPreviousToken() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val first = issue()
        val second = issue()
        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $first") }.status,
            "the superseded token must stop working")
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $second") }.status)
    }

    /** last-used drives the devices page; without it "revoke the stale one" is guesswork. */
    @Test
    fun usingATokenRecordsWhenItWasLastUsed() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        assertNull(runBlocking { lastUsed() }, "a freshly issued token has never been used")

        client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertTrue(runBlocking { lastUsed() } != null, "syncing should stamp last-used")
    }

    private suspend fun lastUsed() = DatabaseFactory.dbQuery {
        DeviceSyncs.selectAll().where { DeviceSyncs.userId eq uid() }
            .limit(1).singleOrNull()?.get(DeviceSyncs.tokenLastUsed)
    }
}
