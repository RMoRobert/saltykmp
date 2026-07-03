package com.enuvro.saltykmp.sync

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.RecipeManifestEntry
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.api.SyncDeleteRequest
import com.enuvro.saltykmp.api.SyncDeleteResponse
import com.enuvro.saltykmp.api.apiJson
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException

/** Holds the JWT so it can be persisted per-platform later; defaults to in-memory. */
interface TokenStore {
    var token: String?
}

class InMemoryTokenStore(override var token: String? = null) : TokenStore

class SyncException(message: String) : Exception(message)

/**
 * User-facing, HTML-free message for a non-2xx HTTP status. [bodySnippet] is the leading, trimmed text of
 * the response body — used only to recognise an HTML page (proxy/firewall/portal), never displayed raw.
 */
internal fun friendlyHttpMessage(code: Int, bodySnippet: String = ""): String {
    // Our API always returns JSON ({ or [). A body starting with '<' is an HTML/XML page from something
    // ELSE in the path — reverse proxy, firewall / IP allowlist, gateway, or sign-in portal. Status alone
    // is misleading there (a proxy's 403 is an access block, not bad credentials), so explain the network.
    if (bodySnippet.startsWith("<")) {
        return "The server address returned a web page instead of app data (HTTP $code). A reverse proxy, " +
            "firewall, or sign-in portal is likely blocking access — common when you're away from your home " +
            "network or off VPN. Check the server address and your network."
    }
    return when (code) {
        401 -> "The server rejected your saved username or password. Check them in Settings."
        403 -> "Access to the server was blocked (HTTP 403). A firewall or IP restriction may be blocking you — " +
            "common when you're away from your home network or off VPN. If you're on the right network, " +
            "double-check your username and password."
        404 -> "The server didn't recognize that request (HTTP 404). Check the server address in Settings."
        408, 429 -> "The server is busy right now. Please try again in a moment."
        in 500..599 -> "The server had a problem (HTTP $code). Please try again later."
        else -> "The server returned an unexpected response (HTTP $code)."
    }
}

/**
 * Maps a non-HTTP-status failure (no response, or an unreadable one) to a friendly message. HTTP-status
 * errors already arrive as [SyncException] via `ensureOk`; this covers the transport/decoding layer.
 */
fun friendlyNetworkMessage(e: Throwable): String = when (e) {
    is SyncException -> e.message ?: "Sync failed."
    is ContentConvertException, is SerializationException ->
        "The server returned a response the app couldn't read. Check that the address points to your Salty server."
    else ->
        "Couldn't reach the server. Check your connection and the server address — it may only be reachable on your home network."
}

/**
 * Ktor client for the Salty sync API (same contract as the Swift app). The HTTP engine is injected so
 * tests can pass a MockEngine and each platform can supply its own (Darwin/Android/CIO).
 */
class SaltyApiClient(
    private val baseUrl: String,
    private val tokenStore: TokenStore,
    engine: HttpClientEngine,
    private val pageSize: Int = 100,
) {
    private val client = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) { json(apiJson) }
    }

    fun close() = client.close()

    private fun HttpRequestBuilder.auth() {
        tokenStore.token?.let { bearerAuth(it) }
    }

    private suspend fun HttpResponse.ensureOk(): HttpResponse {
        if (status.isSuccess()) return this
        // Read the leading body only to CLASSIFY it (HTML proxy page vs our JSON) — never to display it raw.
        val snippet = runCatching { bodyAsText() }.getOrDefault("").take(512).trimStart()
        throw SyncException(friendlyHttpMessage(status.value, snippet))
    }

    /**
     * Percent-encodes a value as a SINGLE URL path segment: unlike `encodeURLPath`, "/" is also encoded,
     * so a hostile value like "../sync/delete" can't traverse to another endpoint, and "?"/"#" can't
     * truncate the path. Ids and image filenames round-trip through the server (manifest, delta), so
     * every one of them gets this treatment before being interpolated into a URL. Legitimate values
     * (UUIDs, "<recipeId>.<ext>") pass through unchanged. Mirrors the Swift app's
     * `encodedImagePathComponent`.
     */
    private fun seg(value: String): String = value.encodeURLPathPart()

    // ---- Auth ----

    suspend fun login(username: String, password: String): AuthResponse {
        val resp = client.post("$baseUrl/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(AuthRequest(username, password))
        }.ensureOk()
        val auth: AuthResponse = resp.body()
        tokenStore.token = auth.token
        return auth
    }

    // ---- Recipes ----

    /** Full manifest (id + lastModified for every recipe); verified complete via X-Total-Count. */
    suspend fun fetchManifest(): List<RecipeManifestEntry> {
        val resp = client.get("$baseUrl/api/recipes/sync/manifest") { auth() }.ensureOk()
        val items: List<RecipeManifestEntry> = resp.body()
        val total = resp.headers["X-Total-Count"]?.toIntOrNull()
        if (total != null && total != items.size) {
            throw SyncException("Incomplete manifest (${items.size}/$total)")
        }
        return items
    }

    /** Paged `modifiedSince` delta of full recipe bodies; accumulates all pages, verifies the total. */
    suspend fun fetchRecipeDelta(modifiedSince: String?): List<ServerRecipe> {
        val all = mutableListOf<ServerRecipe>()
        var page = 0
        var expectedTotal: Int? = null
        while (true) {
            val resp = client.get("$baseUrl/api/recipes") {
                auth()
                if (modifiedSince != null) parameter("modifiedSince", modifiedSince)
                parameter("page", page)
                parameter("size", pageSize)
            }.ensureOk()
            val items: List<ServerRecipe> = resp.body()
            if (expectedTotal == null) expectedTotal = resp.headers["X-Total-Count"]?.toIntOrNull()
            all += items
            val total = expectedTotal
            if (total != null && all.size >= total) break
            if (items.size < pageSize) break
            page++
        }
        val total = expectedTotal
        if (total != null && all.size != total) {
            throw SyncException("Incomplete recipe delta (${all.size}/$total)")
        }
        return all
    }

    suspend fun fetchRecipe(id: String): ServerRecipe =
        client.get("$baseUrl/api/recipes/${seg(id)}") { auth() }.ensureOk().body()

    suspend fun uploadRecipe(recipe: ServerRecipe): ServerRecipe =
        client.post("$baseUrl/api/recipes") {
            auth(); contentType(ContentType.Application.Json); setBody(recipe)
        }.ensureOk().body()

    /** Uploads image bytes. [imageDate] (client-authoritative lastModifiedImageDate, wire form) is stored
     * verbatim server-side so this device won't later see the server image as "newer" and re-download it. */
    suspend fun uploadImage(recipeId: String, filename: String, bytes: ByteArray, imageDate: String? = null): String {
        val extension = filename.substringAfterLast('.', "jpg")
        val contentType = when (extension.lowercase()) {
            "png" -> ContentType.Image.PNG
            "gif" -> ContentType.Image.GIF
            else -> ContentType.Image.JPEG
        }
        val resp = client.post("$baseUrl/api/recipes/${seg(recipeId)}/image") {
            auth()
            setBody(io.ktor.client.request.forms.MultiPartFormDataContent(
                io.ktor.client.request.forms.formData {
                    append("file", bytes, io.ktor.http.Headers.build {
                        append(io.ktor.http.HttpHeaders.ContentType, contentType.toString())
                        append(io.ktor.http.HttpHeaders.ContentDisposition, "filename=\"$filename\"")
                    })
                    if (imageDate != null) append("lastModifiedImageDate", imageDate)
                }
            ))
        }.ensureOk()
        val body: Map<String, String> = resp.body()
        return body["filename"] ?: filename
    }

    /** Removes the recipe's image server-side, stamping the (client-authoritative) removal timestamp. */
    suspend fun deleteImage(recipeId: String, imageDate: String? = null) {
        client.delete("$baseUrl/api/recipes/${seg(recipeId)}/image") {
            auth()
            if (imageDate != null) parameter("lastModifiedImageDate", imageDate)
        }.ensureOk()
    }

    suspend fun deleteRecipesOnServer(deviceId: String, ids: List<String>): Int {
        if (ids.isEmpty()) return 0
        val resp = client.post("$baseUrl/api/recipes/sync/delete") {
            auth(); contentType(ContentType.Application.Json); setBody(SyncDeleteRequest(deviceId, ids))
        }.ensureOk()
        return resp.body<SyncDeleteResponse>().deleted
    }

    suspend fun downloadImage(filename: String): ByteArray? {
        // `filename` comes from the server's manifest/delta — encode it so a hostile name can't steer
        // this authenticated GET to a different endpoint (same hardening as the Swift app's downloadImage).
        val resp = client.get("$baseUrl/api/recipes/images/${seg(filename)}") { auth() }
        return if (resp.status.isSuccess()) resp.body() else null
    }

    // ---- Device sync ----

    suspend fun registerDevice(deviceId: String, deviceName: String?): DeviceSyncInfo =
        client.post("$baseUrl/api/recipes/sync/device") {
            auth(); contentType(ContentType.Application.Json); setBody(DeviceRegisterRequest(deviceId, deviceName))
        }.ensureOk().body()

    suspend fun completeSync(deviceId: String) {
        client.post("$baseUrl/api/recipes/sync/device/${seg(deviceId)}/complete") { auth() }.ensureOk()
    }

    // ---- Library (small full-list tables) ----

    suspend fun fetchCourses(): List<ServerCourse> = client.get("$baseUrl/api/courses") { auth() }.ensureOk().body()
    suspend fun fetchCategories(): List<ServerCategory> = client.get("$baseUrl/api/categories") { auth() }.ensureOk().body()
    suspend fun fetchTags(): List<ServerTag> = client.get("$baseUrl/api/tags") { auth() }.ensureOk().body()

    suspend fun uploadCourse(c: ServerCourse): ServerCourse =
        client.post("$baseUrl/api/courses") { auth(); contentType(ContentType.Application.Json); setBody(c) }.ensureOk().body()
    suspend fun uploadCategory(c: ServerCategory): ServerCategory =
        client.post("$baseUrl/api/categories") { auth(); contentType(ContentType.Application.Json); setBody(c) }.ensureOk().body()
    suspend fun uploadTag(t: ServerTag): ServerTag =
        client.post("$baseUrl/api/tags") { auth(); contentType(ContentType.Application.Json); setBody(t) }.ensureOk().body()

    suspend fun deleteCourse(id: String) { client.delete("$baseUrl/api/courses/${seg(id)}") { auth() } }
    suspend fun deleteCategory(id: String) { client.delete("$baseUrl/api/categories/${seg(id)}") { auth() } }
    suspend fun deleteTag(id: String) { client.delete("$baseUrl/api/tags/${seg(id)}") { auth() } }
}
