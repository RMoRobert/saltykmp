package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.serialization.kotlinx.json.json
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

    /* ------------------------------------------------------- enrolment and exchange -- */

    /**
     * Enrolment rides on the login the client already performs. The whole point of the design is
     * that this is the LAST time the password is needed.
     */
    @Test
    fun loggingInWithADeviceIdReturnsASyncToken() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }

        val resp: AuthResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw", deviceId = "phone-1", deviceName = "My Phone"))
        }.body()

        val token = resp.deviceToken
        assertTrue(token != null && token.startsWith("salty_"), "expected a device token, got $token")
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "the freshly enrolled token should sync immediately")
    }

    /** An old client sends no deviceId and must get exactly the response it always did. */
    @Test
    fun loggingInWithoutADeviceIdReturnsNoToken() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }

        val resp: AuthResponse = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw"))
        }.body()

        assertNull(resp.deviceToken, "an unmodified client must not be enrolled behind its back")
        assertTrue(resp.token.isNotBlank(), "but it still gets its JWT")
    }

    /** The call a client makes forever after: token in, fresh JWT out, no password anywhere. */
    @Test
    fun aDeviceTokenMintsAFreshJwt() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }
        val token = issue()

        val minted: AuthResponse =
            client.post("/api/auth/token") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

        assertTrue(minted.token.isNotBlank())
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer ${minted.token}") }.status,
            "the minted JWT must actually work")
    }

    /* --------------------------------------------------------------------- self-revoke -- */

    /**
     * "Forget this device" in a client: the device ends its own access, so signing out is not merely
     * a local gesture that leaves a live credential behind on the server.
     */
    @Test
    fun aDeviceCanRevokeItsOwnToken() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()

        val resp = client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, resp.status)

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "the token must be dead the moment its own device revokes it")
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "and it must not be able to mint its way back")
    }

    /**
     * The assertion the whole design rests on. Self-revoke is safe *because* it cannot aim anywhere
     * else — it takes no deviceId. If that ever changed, a stolen phone could lock out every other
     * device, which is exactly what [RequirePasswordAuth] exists to prevent.
     */
    @Test
    fun revokingItselfLeavesEveryOtherDeviceAlone() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val phone = issue("phone", "Phone")
        val tablet = issue("tablet", "Tablet")

        assertEquals(HttpStatusCode.NoContent,
            client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer $phone") }.status)

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $phone") }.status)
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $tablet") }.status,
            "revoking oneself must never reach another device")
    }

    /** Self-revoke keeps the row, exactly as an admin revoke does, so the history survives. */
    @Test
    fun revokingItselfKeepsTheDeviceRow() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue("phone", "My Phone")

        client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer $token") }

        val info = runBlocking { DeviceRepository.get(uid(), "phone") }
        assertTrue(info != null, "revoking must not delete the device")
        assertEquals(false, runBlocking { DeviceRepository.listForUser(uid()).single().hasToken })
    }

    /** An unauthenticated caller cannot revoke anything by guessing at the route. */
    @Test
    fun revokingNeedsALiveTokenOfItsOwn() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()

        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token/revoke").status,
            "no credential at all must not revoke")
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer salty_not-a-real-token") }.status)
        assertTrue(runBlocking { DeviceRepository.listForUser(uid()).single().hasToken },
            "the real device's token must have survived those attempts")
        assertTrue(token.isNotBlank())
    }

    @Test
    fun aRevokedTokenCannotMintAJwt() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val token = issue()
        runBlocking { DeviceRepository.revokeAllTokens(uid()) }
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /* ------------------------------------------------------------- managing devices -- */

    /**
     * The escalation this design has to prevent: a stolen phone holds a sync token, and revoking is
     * how you remove that phone. So neither the token nor a JWT minted from it may reach the device
     * routes — otherwise the thief simply revokes everyone else.
     */
    @Test
    fun neitherASyncTokenNorItsMintedJwtCanManageDevices() = testApplication {
        application { installSalty(jwt, imageStore, deviceTokens = tokens) }
        val client = createClient {
            install(ContentNegotiation) { json(appJson) }
            followRedirects = false
        }
        val token = issue()
        val minted: AuthResponse =
            client.post("/api/auth/token") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

        for ((label, credential) in listOf("sync token" to token, "JWT minted from it" to minted.token)) {
            val listed = client.get("/api/auth/devices") { header(HttpHeaders.Authorization, "Bearer $credential") }
            assertNotEquals(HttpStatusCode.OK, listed.status, "$label must not list devices")

            val revoked = client.delete("/api/auth/devices/$DEVICE") {
                header(HttpHeaders.Authorization, "Bearer $credential")
            }
            assertNotEquals(HttpStatusCode.NoContent, revoked.status, "$label must not revoke a device")
        }

        // And the token still syncs, proving the refusal was about scope and not a broken credential.
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /** The devices list is what the page renders; it must never carry the credential itself. */
    @Test
    fun theDeviceListNamesDevicesButNeverTheirTokens() = runBlocking {
        val token = issue("phone-1", "My Phone")
        val entries = DeviceRepository.listForUser(uid())
        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals("My Phone", entry.deviceName)
        assertTrue(entry.hasToken, "an enrolled device should read as able to sync")

        val serialised = appJson.encodeToString(entries)
        assertTrue(!serialised.contains(token), "the plaintext token must never be serialised")
        assertTrue(!serialised.contains(tokens.hash(token)), "nor its hash: $serialised")
    }

    @Test
    fun aRevokedDeviceStaysInTheListMarkedUnableToSync() = runBlocking {
        issue("phone-1", "My Phone")
        DeviceRepository.revokeToken(uid(), "phone-1")
        val entry = DeviceRepository.listForUser(uid()).single()
        assertEquals("My Phone", entry.deviceName, "history is worth keeping visible")
        assertEquals(false, entry.hasToken)
    }

    @Test
    fun aDeviceCanBeRenamed() = runBlocking {
        issue("phone-1", "iPhone")
        assertTrue(DeviceRepository.renameDevice(uid(), "phone-1", "Kitchen iPad"))
        assertEquals("Kitchen iPad", DeviceRepository.listForUser(uid()).single().deviceName)
        assertEquals(false, DeviceRepository.renameDevice(uid(), "no-such-device", "Nope"))
    }
}
