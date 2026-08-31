package com.enuvro.saltykmp

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.util.appJson
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.serialization.kotlinx.json.json
import com.enuvro.saltykmp.auth.DeviceTokenService
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
import kotlin.test.assertNotNull
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
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue()
        val resp = client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun aTokenWorksOnEveryRouteGroupSyncNeeds() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
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
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue()
        // followRedirects=false is essential for the HTML routes: the session challenge answers with
        // a redirect to /login, and a client that follows it lands on a 200 login page -- which would
        // make this test pass while proving nothing at all.
        val strict = createClient { followRedirects = false }
        val auth: io.ktor.client.request.HttpRequestBuilder.() -> Unit =
            { header(HttpHeaders.Authorization, "Bearer $token") }

        // The app shell, which is where account administration is reached from.
        assertEquals(HttpStatusCode.Found, strict.get("/app", auth).status)
        assertEquals("/login", strict.get("/app", auth).headers[HttpHeaders.Location])

        // User administration is JSON, so it answers 401 rather than redirecting.
        val users = strict.get("/api/users", auth)
        assertEquals(HttpStatusCode.Unauthorized, users.status, "a sync credential must not list users")

        val changed = strict.post("/api/users/${uid()}/password") {
            auth()
            contentType(ContentType.Application.Json)
            setBody("""{"password":"hijacked-by-a-sync-token"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, changed.status,
                     "a sync credential must not change a password")

        // Nor the self-service route, which is the one a stolen device would actually want: it holds
        // no password to present as `currentPassword`, and RequirePasswordAuth stops it regardless.
        val selfChange = strict.post("/api/account/password") {
            auth()
            contentType(ContentType.Application.Json)
            setBody("""{"currentPassword":"pw","newPassword":"hijacked-by-a-sync-token"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, selfChange.status,
                     "a sync credential must not change its own account's password")

        // And the password genuinely still works, which is the assertion that actually matters.
        assertTrue(
            runBlocking { UserRepository.verifyCredential(UserRepository.findByUsername("tester"), "pw") },
            "the original password must still be valid",
        )
    }

    /* ---------------------------------------------------------------------- revocation -- */

    @Test
    fun removingOneDeviceStopsThatTokenAndLeavesOthersAlone() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val phone = issue("phone", "Phone")
        val tablet = issue("tablet", "Tablet")

        runBlocking { DeviceRepository.removeDevice(uid(), "phone") }

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $phone") }.status,
            "the revoked device must be shut out")
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $tablet") }.status,
            "the other device must be unaffected")
    }

    /**
     * Removing deletes the row: revoking and forgetting are one act.
     *
     * The old behaviour kept the row with a null hash so a device that re-enrolled stayed the same
     * device. Nobody wanted that continuity, and it cost the list any way to tell a revoked device
     * from one that had never enrolled — which is precisely the confusion this change removes.
     */
    @Test
    fun removingDeletesTheDeviceRow() = runBlocking {
        issue("phone", "My Phone")
        assertTrue(DeviceRepository.removeDevice(uid(), "phone"))
        assertNull(DeviceRepository.get(uid(), "phone"), "removing must delete the device")
        assertNull(DeviceRepository.findByTokenHash(tokens.hash("salty_whatever")))
    }

    /** Nothing to remove is a false, so the route can answer 404 instead of a hollow success. */
    @Test
    fun removingAnUnknownDeviceReportsNothingRemoved() = runBlocking {
        issue("phone", "My Phone")
        assertEquals(false, DeviceRepository.removeDevice(uid(), "never-existed"))
        assertEquals(1, DeviceRepository.listForUser(uid()).size)
    }

    @Test
    fun revokeAllStopsEveryDevice() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
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
        application { installSalty(imageStore, deviceTokens = tokens) }
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
        application { installSalty(imageStore, deviceTokens = tokens) }
        for (bad in listOf("salty_nonsense", "not-even-close", "")) {
            assertEquals(HttpStatusCode.Unauthorized,
                client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $bad") }.status,
                "\"$bad\" must not authenticate")
        }
    }

    /** Re-enrolling a device replaces its token rather than leaving two live credentials. */
    @Test
    fun reEnrollingADeviceInvalidatesItsPreviousToken() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
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
        application { installSalty(imageStore, deviceTokens = tokens) }
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
        application { installSalty(imageStore, deviceTokens = tokens) }
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

    /**
     * A client that sends no deviceId is refused outright — the flag day this replaced the old
     * "carry on unenrolled" behaviour with.
     *
     * That behaviour was the bug. Such a client got no token, so it kept the password and synced with
     * it indefinitely, while the `device_sync` row `registerDevice` created for it sat there with a
     * null hash — indistinguishable, on the account's app list, from a device that had been revoked.
     * Refusing is what makes "every syncing client is an enrolled client" an invariant rather than an
     * aspiration, and it is what leaves a null `token_hash` with exactly one meaning.
     */
    @Test
    fun loggingInWithoutADeviceIdIsRejected() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw"))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status,
            "a client too old to enrol must be told so, not handed a password-sync session")
    }

    /** Blank is the same as absent: it was the shape the old optional check let through. */
    @Test
    fun loggingInWithABlankDeviceIdIsRejected() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "pw", deviceId = "   "))
        }

        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    /** Credentials are still checked first: a bad password must not learn the client is outdated. */
    @Test
    fun badCredentialsBeatTheMissingDeviceIdCheck() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest("tester", "wrong"))
        }

        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    /**
     * The startup call: the token proves itself and names its owner. Nothing is minted.
     *
     * It used to hand back a JWT that the client then presented instead. That indirection is gone —
     * the token authenticates sync directly — but the round trip survives because it is how a client
     * tells "revoked" apart from "offline" before deciding to ask for the password again.
     */
    @Test
    fun aDeviceTokenCanVerifyItself() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val client = createClient { install(ContentNegotiation) { json(appJson) } }
        val token = issue()

        val verified: AuthResponse =
            client.post("/api/auth/token/verify") { header(HttpHeaders.Authorization, "Bearer $token") }.body()

        assertEquals("tester", verified.username)
        assertNull(verified.deviceToken, "verifying must not re-issue a credential the caller already holds")
        assertEquals(HttpStatusCode.OK,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "and the token itself is what syncs")
    }

    /** A revoked token cannot even verify — the 401 is what tells a client to ask for the password. */
    @Test
    fun aRemovedDeviceCannotVerify() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue()
        runBlocking { DeviceRepository.removeDevice(uid(), DEVICE) }

        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token/verify") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /* --------------------------------------------------------------------- self-revoke -- */

    /**
     * "Forget this device" in a client: the device ends its own access, so signing out is not merely
     * a local gesture that leaves a live credential behind on the server.
     */
    @Test
    fun aDeviceCanRevokeItsOwnToken() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue()

        val resp = client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertEquals(HttpStatusCode.NoContent, resp.status)

        assertEquals(HttpStatusCode.Unauthorized,
            client.get("/api/recipes") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "the token must be dead the moment its own device revokes it")
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token/verify") { header(HttpHeaders.Authorization, "Bearer $token") }.status,
            "and it must not be able to verify its way back")
    }

    /**
     * The assertion the whole design rests on. Self-revoke is safe *because* it cannot aim anywhere
     * else — it takes no deviceId. If that ever changed, a stolen phone could lock out every other
     * device, which is exactly what [RequirePasswordAuth] exists to prevent.
     */
    @Test
    fun revokingItselfLeavesEveryOtherDeviceAlone() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
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

    /** Self-revoke removes the row, exactly as revoking from the web app does. */
    @Test
    fun revokingItselfRemovesTheDeviceRow() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue("phone", "My Phone")

        client.post("/api/auth/token/revoke") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertNull(runBlocking { DeviceRepository.get(uid(), "phone") }, "forgetting must delete the row")
        assertTrue(runBlocking { DeviceRepository.listForUser(uid()) }.isEmpty())
    }

    /** An unauthenticated caller cannot revoke anything by guessing at the route. */
    @Test
    fun revokingNeedsALiveTokenOfItsOwn() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
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
    fun aSignedOutTokenCannotVerify() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val token = issue()
        runBlocking { DeviceRepository.revokeAllTokens(uid()) }
        assertEquals(HttpStatusCode.Unauthorized,
            client.post("/api/auth/token/verify") { header(HttpHeaders.Authorization, "Bearer $token") }.status)
    }

    /* ------------------------------------------------------------- managing devices -- */

    /**
     * The escalation this design has to prevent: a stolen phone holds a sync token, and revoking is
     * how you remove that phone. So the sync token must not reach the device routes — otherwise the
     * thief simply revokes everyone else.
     */
    @Test
    fun aSyncTokenCannotManageDevices() = testApplication {
        application { installSalty(imageStore, deviceTokens = tokens) }
        val client = createClient {
            install(ContentNegotiation) { json(appJson) }
            followRedirects = false
        }
        val token = issue()

        // Once there were two credentials to check here: the sync token, and the JWT any device could
        // mint from it by trading at /api/auth/token. The trade is gone, so a device holds exactly one
        // credential and there is only one thing that must not reach device management.
        for ((label, credential) in listOf("sync token" to token)) {
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

    /** Removal takes the row out of the list entirely — no tombstone to explain to anyone. */
    @Test
    fun aRemovedDeviceLeavesTheList() = runBlocking {
        issue("phone-1", "My Phone")
        issue("phone-2", "Old Phone")
        DeviceRepository.removeDevice(uid(), "phone-2")
        val entry = DeviceRepository.listForUser(uid()).single()
        assertEquals("My Phone", entry.deviceName)
    }

    /**
     * Signing out is the one thing that still leaves a token-less row, and it keeps the watermark on
     * purpose: a password change must not make every device re-merge its whole library.
     */
    @Test
    fun aSignedOutDeviceStaysInTheListWithoutItsToken() = runBlocking {
        issue("phone-1", "My Phone")
        DeviceRepository.revokeAllTokens(uid())
        val entry = DeviceRepository.listForUser(uid()).single()
        assertEquals("My Phone", entry.deviceName, "it can sign back in; it is the same device")
        assertEquals(false, entry.hasToken)
        assertNotNull(DeviceRepository.get(uid(), "phone-1")?.firstSyncDate, "the watermark must survive")
        Unit
    }

    /**
     * The count is devices actually signed out, not rows touched.
     *
     * Exposed's `update` returns rows MATCHED, so filtering only on the user counted every row the
     * account had — reporting "2 apps signed out" when the second had no token to lose. That number
     * is shown to the user after a password change, so it has to be true.
     */
    @Test
    fun signingAllOutCountsOnlyDevicesThatHeldAToken() = runBlocking {
        issue("phone-1", "My Phone")
        issue("phone-2", "Tablet")
        assertEquals(2, DeviceRepository.revokeAllTokens(uid()))
        assertEquals(0, DeviceRepository.revokeAllTokens(uid()), "nothing left to sign out")
    }

    @Test
    fun aDeviceCanBeRenamed() = runBlocking {
        issue("phone-1", "iPhone")
        assertTrue(DeviceRepository.renameDevice(uid(), "phone-1", "Kitchen iPad"))
        assertEquals("Kitchen iPad", DeviceRepository.listForUser(uid()).single().deviceName)
        assertEquals(false, DeviceRepository.renameDevice(uid(), "no-such-device", "Nope"))
    }
}
