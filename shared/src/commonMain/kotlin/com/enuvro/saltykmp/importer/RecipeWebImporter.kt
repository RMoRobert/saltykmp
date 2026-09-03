package com.enuvro.saltykmp.importer

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess

/** Outcome of importing a recipe from a URL — a parsed recipe, or why it couldn't be. */
sealed interface WebImportResult {
    data class Success(val recipe: ParsedRecipe, val imageBytes: ByteArray?) : WebImportResult
    /** The page loaded but carries no schema.org Recipe (hand-written blogs, paywalls, JS-rendered sites). */
    data object NoRecipeFound : WebImportResult
    data class Failed(val message: String) : WebImportResult
}

/**
 * Fetches a web page and parses a recipe out of its schema.org JSON-LD — the simple form of the Swift app's
 * import: paste a URL, get a recipe to review, rather than the macOS in-app browser with field scraping.
 *
 * Safety, mirroring the Swift importer: only http/https is fetched (a file:// or custom-scheme URL would
 * turn an importer that accepts an arbitrary address into a way to read local resources), the response is
 * capped before parsing, and the parser clamps every field it extracts. Imported content is untrusted: it
 * lands in the recipe editor for the user to review, never saved behind their back.
 */
class RecipeWebImporter(engine: HttpClientEngine) {

    private val client = HttpClient(engine) {
        expectSuccess = false
        install(HttpTimeout) {
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
            connectTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }

    fun close() = client.close()

    suspend fun import(url: String): WebImportResult {
        val target = normalize(url) ?: return WebImportResult.Failed("Enter a web address starting with http:// or https://.")

        val html = runCatching {
            val response = client.get(target) {
                // Some sites serve a stub to unknown agents; identify honestly but recognizably.
                header("User-Agent", USER_AGENT)
                header("Accept", "text/html,application/xhtml+xml")
            }
            if (!response.status.isSuccess()) return WebImportResult.Failed(httpMessage(response.status))
            // A BOUNDED read. `bodyAsText()` pulled the whole response into memory first and the parser's
            // cap then rejected it afterwards, which is the wrong order: a hostile or merely enormous
            // page was already resident by the time anything objected. One byte past the cap is enough
            // for the parser to refuse it.
            response.bodyAsChannel()
                .readRemaining((SchemaOrgRecipeParser.Limits.MAX_INPUT_BYTES + 1).toLong())
                .readByteArray()
                .decodeToString()
        }.getOrElse { return WebImportResult.Failed("Couldn't load that page: ${it.message ?: "network error"}") }

        val parsed = SchemaOrgRecipeParser.parse(html).firstOrNull() ?: return WebImportResult.NoRecipeFound

        // Plenty of sites — AllRecipes among them — publish a Recipe with no `url` in it. The address is
        // the one thing about an imported recipe we always know, and recording where it came from is
        // what this field is for.
        val recipe = if (parsed.sourceDetails.isBlank()) parsed.copy(sourceDetails = target) else parsed

        // The photo is a nicety: a failure here still imports the recipe.
        val imageBytes = recipe.imageUrl?.let { downloadImage(it) }
        return WebImportResult.Success(recipe, imageBytes)
    }

    private suspend fun downloadImage(url: String): ByteArray? {
        val target = normalize(url) ?: return null
        return runCatching {
            val response = client.get(target) { header("User-Agent", USER_AGENT) }
            if (!response.status.isSuccess()) return null
            response.readRawBytes().takeIf { it.size <= SchemaOrgRecipeParser.Limits.MAX_IMAGE_BYTES }
        }.getOrNull()
    }

    /** Accepts a bare "example.com/recipe", and rejects anything that isn't http(s). */
    private fun normalize(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val parsed = runCatching { URLBuilder(withScheme).build() }.getOrNull() ?: return null
        if (parsed.protocol.name !in ALLOWED_SCHEMES) return null
        if (parsed.host.isBlank()) return null
        return parsed.toString()
    }

    private fun httpMessage(status: HttpStatusCode): String = when (status.value) {
        403, 401 -> "That site refused the request (${status.value}). Some sites block automated access."
        404 -> "That page wasn't found (404)."
        in 500..599 -> "That site had an error (${status.value}). Try again later."
        else -> "That page couldn't be loaded (${status.value})."
    }

    private companion object {
        val ALLOWED_SCHEMES = setOf("http", "https")
        const val REQUEST_TIMEOUT_MS = 15_000L
        const val USER_AGENT = "Salty/1.0 (recipe import)"
    }
}
