package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.apiJson
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SaltyApiClientTest {

    private val jsonAnd = { extra: Pair<String, String> ->
        headersOf(
            HttpHeaders.ContentType to listOf("application/json"),
            extra.first to listOf(extra.second),
        )
    }

    @Test
    fun loginStoresTokenAndSubsequentRequestsCarryBearer() = runTest {
        var manifestAuth: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/login" -> respond(
                    apiJson.encodeToString(AuthResponse("tester", deviceToken = "salty_tok-123")),
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
                "/api/recipes/sync/manifest" -> {
                    manifestAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        apiJson.encodeToString(listOf(RecipeManifestEntry("a"), RecipeManifestEntry("b"))),
                        HttpStatusCode.OK,
                        jsonAnd("X-Total-Count" to "2"),
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val store = InMemoryTokenStore()
        val api = SaltyApiClient("http://test", store, engine)

        val auth = api.login("tester", "pw", deviceId = "dev-1")
        assertEquals("salty_tok-123", auth.deviceToken)
        assertEquals("salty_tok-123", store.token, "the device token IS the credential later calls carry")

        val manifest = api.fetchManifest()
        assertEquals(listOf("a", "b"), manifest.map { it.id })
        assertEquals("Bearer salty_tok-123", manifestAuth)
    }

    @Test
    fun manifestGuardThrowsOnTruncatedResponse() = runTest {
        val engine = MockEngine {
            respond(
                apiJson.encodeToString(listOf(RecipeManifestEntry("a"))),
                HttpStatusCode.OK,
                jsonAnd("X-Total-Count" to "5"), // claims 5 but body has 1
            )
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)
        assertFailsWith<SyncException> { api.fetchManifest() }
    }

    @Test
    fun proxyHtmlErrorPageBecomesFriendlyMessage() = runTest {
        // A reverse proxy / firewall (e.g. IP allowlist) returns an HTML 403 — NOT our JSON API.
        val engine = MockEngine {
            respond(
                "<!DOCTYPE html><html><body><h1>403 Forbidden</h1></body></html>",
                HttpStatusCode.Forbidden,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)
        val message = assertFailsWith<SyncException> { api.fetchManifest() }.message ?: ""
        assertTrue(!message.contains("<"), "raw HTML leaked into the message: $message")
        assertTrue(!message.contains("password"), "proxy block must not blame credentials: $message")
        assertTrue(message.contains("web page"), "expected proxy/network hint: $message")
    }

    @Test
    fun downloadImageEncodesHostileFilenameAsSinglePathSegment() = runTest {
        // The filename comes from the server's manifest — a hostile value must not be able to steer the
        // authenticated GET to another endpoint ("../sync/delete") or truncate the URL ("?", "#").
        var requested: io.ktor.http.Url? = null
        val engine = MockEngine { request ->
            requested = request.url
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)
        api.downloadImage("../sync/delete?all=true#frag")

        val url = requested ?: error("no request was made")
        val path = url.encodedPath
        assertTrue(path.startsWith("/api/recipes/images/"), "request escaped the images endpoint: $path")
        val segment = path.removePrefix("/api/recipes/images/")
        assertTrue(!segment.contains("/"), "'/' survived encoding, allowing traversal: $path")
        assertTrue(url.parameters.isEmpty(), "'?' in the filename injected query parameters")
    }

    @Test
    fun downloadImageLeavesLegitimateFilenameUntouched() = runTest {
        var path: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            respond(byteArrayOf(1), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/jpeg"))
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)
        api.downloadImage("0E8E4E43-B37A-4B47-A2B7-8A11D09A1D57.jpg")
        assertEquals("/api/recipes/images/0E8E4E43-B37A-4B47-A2B7-8A11D09A1D57.jpg", path)
    }

    @Test
    fun libraryDeleteCarriesIfMatchAndDownloadsCurrentRowOnConflict() = runTest {
        // The If-Match value is the timestamp the delete decision was based on; a 409 carries the
        // current server row (a web rename raced the delete) for the caller to download instead.
        var ifMatch: String? = null
        val engine = MockEngine { request ->
            ifMatch = request.headers[HttpHeaders.IfMatch]
            respond(
                """{"id": "cat-1", "name": "Renamed on web", "lastModifiedDate": "2026-08-16T12:00:00.000Z"}""",
                HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)

        val outcome = api.deleteCategory("cat-1", expectedLastModified = "2026-08-16T10:00:00.000Z")
        assertEquals("2026-08-16T10:00:00.000Z", ifMatch)
        val conflict = outcome as SaltyApiClient.LibraryDeleteOutcome.Conflict
        assertEquals("Renamed on web", conflict.current.name)
    }

    @Test
    fun libraryDeleteOmitsIfMatchWhenNoExpectedStamp() = runTest {
        var ifMatch: String? = "sentinel"
        val engine = MockEngine { request ->
            ifMatch = request.headers[HttpHeaders.IfMatch]
            respond("", HttpStatusCode.NoContent)
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)

        val outcome = api.deleteCourse("course-1")
        assertEquals(null, ifMatch)
        assertEquals(SaltyApiClient.LibraryDeleteOutcome.Deleted, outcome)
    }

    @Test
    fun forcedUploadsCarryTheForceHeaderAndRegularOnesDoNot() = runTest {
        val headerPerRequest = mutableListOf<String?>()
        val engine = MockEngine { request ->
            headerPerRequest += request.headers[SaltyApiClient.FORCE_WRITE_HEADER]
            respond(
                """{"id": "c1", "name": "Breads"}""",
                HttpStatusCode.Created,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine)

        api.uploadCategory(com.enuvro.saltykmp.api.ServerCategory("c1", "Breads"), force = true)
        api.uploadCategory(com.enuvro.saltykmp.api.ServerCategory("c1", "Breads"))
        assertEquals(listOf("1", null), headerPerRequest)
    }

    @Test
    fun deltaPagingAccumulatesAllPages() = runTest {
        val engine = MockEngine { request ->
            val page = request.url.parameters["page"]?.toInt() ?: 0
            val items = if (page == 0) listOf(ServerRecipe("a"), ServerRecipe("b")) else listOf(ServerRecipe("c"))
            respond(
                apiJson.encodeToString(items),
                HttpStatusCode.OK,
                jsonAnd("X-Total-Count" to "3"),
            )
        }
        val api = SaltyApiClient("http://test", InMemoryTokenStore("t"), engine, pageSize = 2)
        val all = api.fetchRecipeDelta(modifiedSince = "2026-01-01T00:00:00.000Z")
        assertEquals(listOf("a", "b", "c"), all.map { it.id })
    }

    /* ------------------------------------------------------------ device sync tokens -- */

    /** Enrolment: sending a deviceId asks for a token, and the server's reply carries one. */
    @Test
    fun loginWithADeviceIdSendsItAndReturnsTheToken() = runTest {
        var sentBody: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                apiJson.encodeToString(AuthResponse("tester", deviceToken = "salty_abc")),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        val auth = api.login("tester", "pw", deviceId = "dev-1", deviceName = "Test Device")

        assertEquals("salty_abc", auth.deviceToken)
        assertTrue(sentBody!!.contains("dev-1"), "the deviceId must reach the server: $sentBody")
        assertTrue(sentBody!!.contains("Test Device"))
    }

    /**
     * Every login enrols, so the deviceId always goes out — there is no longer a way to ask for a
     * password-only session, which is what let a client sync unenrolled and invisible.
     */
    @Test
    fun everyLoginSendsADeviceId() = runTest {
        var sentBody: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as io.ktor.http.content.TextContent).text
            respond(
                apiJson.encodeToString(AuthResponse("tester", deviceToken = "salty_abc")),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        api.login("tester", "pw", deviceId = "dev-9")
        assertTrue(sentBody!!.contains("dev-9"), "the deviceId must always be sent: $sentBody")
    }

    /**
     * A server old enough to answer without a token is recognised rather than papered over: the
     * caller decides, and [AppModule] treats it as a hard failure instead of silently keeping the
     * password — the exact behaviour that produced unenrolled, password-syncing clients.
     */
    @Test
    fun aServerThatCannotEnrolReturnsNoDeviceToken() = runTest {
        val engine = MockEngine {
            respond(
                apiJson.encodeToString(AuthResponse("tester")),
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        assertEquals(null, api.login("tester", "pw", deviceId = "dev-1").deviceToken)
    }

    /**
     * The ordinary path once enrolled: the stored token verifies itself, then carries every later
     * call. It used to be traded for a JWT at this point; now nothing is exchanged, so the credential
     * on the manifest request is the same one that started the session.
     */
    @Test
    fun aVerifiedDeviceTokenIsWhatLaterCallsCarry() = runTest {
        var verifyAuth: String? = null
        var manifestAuth: String? = null
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/auth/token/verify" -> {
                    verifyAuth = request.headers[HttpHeaders.Authorization]
                    respond(
                        apiJson.encodeToString(AuthResponse("tester")),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> {
                    manifestAuth = request.headers[HttpHeaders.Authorization]
                    respond(apiJson.encodeToString(emptyList<RecipeManifestEntry>()),
                        HttpStatusCode.OK, jsonAnd("X-Total-Count" to "0"))
                }
            }
        }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        val auth = api.loginWithDeviceToken("salty_abc")

        assertEquals("tester", auth?.username)
        assertEquals("Bearer salty_abc", verifyAuth, "the token authenticates its own check")
        api.fetchManifest()
        assertEquals("Bearer salty_abc", manifestAuth, "and keeps authenticating everything after")
    }

    /**
     * A revoked token is a normal state, reported as null so the caller can ask for the password.
     * Distinguishing it from a transport failure is the point of the next test.
     */
    @Test
    fun aRejectedDeviceTokenReturnsNullRatherThanThrowing() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized) }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        assertEquals(null, api.loginWithDeviceToken("salty_revoked"))
    }

    /**
     * A server error must NOT look like revocation: the caller discards the token when it is told
     * the token is dead, so a 500 or an outage mistaken for rejection would sign a working device
     * out and demand a password for no reason.
     */
    @Test
    fun aServerFailureIsNotMistakenForRevocation() = runTest {
        val engine = MockEngine { respond("upstream exploded", HttpStatusCode.InternalServerError) }
        val api = SaltyApiClient("http://fake", InMemoryTokenStore(), engine)
        assertFailsWith<SyncException> { api.loginWithDeviceToken("salty_still_good") }
    }
}
