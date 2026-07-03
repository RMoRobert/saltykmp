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
                    apiJson.encodeToString(AuthResponse("tok-123", "tester", 1000)),
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

        val auth = api.login("tester", "pw")
        assertEquals("tok-123", auth.token)
        assertEquals("tok-123", store.token)

        val manifest = api.fetchManifest()
        assertEquals(listOf("a", "b"), manifest.map { it.id })
        assertEquals("Bearer tok-123", manifestAuth)
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
}
