package com.enuvro.saltykmp.recipe

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.auth.ApiCsrfGuard
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.importer.ParsedRecipe
import com.enuvro.saltykmp.importer.SchemaOrgRecipeParser
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Base64

/**
 * Importing a recipe from a web page, for the browser app.
 *
 * The native clients do this themselves with `RecipeWebImporter`; a browser cannot, because it may
 * not read a third-party page cross-origin. So the server fetches on the user's behalf — and that,
 * not convenience, is why this file exists instead of a call into the shared importer.
 *
 * **Fetching a URL a user typed is a server-side request forgery primitive.** The same code that
 * loads a recipe blog will just as happily load `http://169.254.169.254/` (cloud metadata, and with
 * it the instance's credentials) or a service bound to loopback that is unauthenticated precisely
 * because it is unreachable — and hand the response back to whoever asked. The shared importer's
 * safety (scheme allowlist, size cap, clamped fields) is right for a phone, where the request comes
 * from the user's own machine and reaches only what they could reach anyway. It is not sufficient
 * here. What this adds is the part that only matters server-side:
 *
 *  - every hostname is resolved before it is fetched and rejected unless *every* address it resolves
 *    to is a public one — no loopback, link-local, private, carrier-grade NAT or unique-local address;
 *  - redirects are **not** followed automatically. They are followed by hand, a few hops at most,
 *    with the same check applied to every hop, because a public URL is free to redirect to an
 *    internal one and an auto-following client would follow it without ever being asked;
 *  - the body is read through a hard byte cap rather than trusted to be small.
 *
 * Parsing is the shared [SchemaOrgRecipeParser], untouched, so a page imports identically here and on
 * the phone. That parser being pure and I/O-free is what makes this split possible at all.
 *
 * **Residual risk, stated rather than buried:** the address is checked and the host is then connected
 * to *by name*, so a hostile DNS server that answers differently for the two lookups (DNS rebinding)
 * could still land one request. Closing that means pinning the checked IP into the connection and
 * carrying the hostname in Host/SNI by hand. Given this endpoint is session-only and reachable only
 * by an account that already exists on this server, that trade is deliberate and worth revisiting if
 * the server ever hosts accounts its operator doesn't trust.
 *
 * Nothing is saved. The response is a *draft*: the browser opens it in the editor for review, and it
 * becomes a recipe only when the user saves it — the same contract the desktop import has.
 */
fun Route.recipeImportRoutes(addressPolicy: AddressPolicy = ::addressRefusal) {
    // Session-only, deliberately: the native clients import locally and have no use for this, so
    // there is no reason for a sync credential to be able to make the server fetch anything.
    authenticate(WEB_API_AUTH) {
        install(ApiCsrfGuard)

        post("/api/recipes/import") {
            val requested = call.receive<ImportRequest>().url
            val target = normalize(requested)
            if (target == null) {
                call.respond(HttpStatusCode.BadRequest,
                    mapOf("error" to "Enter a web address starting with http:// or https://."))
                return@post
            }

            // Blocking I/O: the JDK client's synchronous send, plus a capped read.
            val page = withContext(Dispatchers.IO) {
                fetch(target, MAX_HTML_BYTES, "text/html,application/xhtml+xml", addressPolicy)
            }
            when (page) {
                is Fetch.Blocked -> {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to page.message))
                    return@post
                }
                is Fetch.Failed -> {
                    call.respond(HttpStatusCode.BadGateway, mapOf("error" to page.message))
                    return@post
                }
                is Fetch.Ok -> Unit
            }
            val ok = page as Fetch.Ok

            val parsed = SchemaOrgRecipeParser.parse(ok.text()).firstOrNull()
            if (parsed == null) {
                call.respond(
                    HttpStatusCode.UnprocessableEntity,
                    mapOf("error" to "That page doesn't publish recipe data Salty can read. Sites that " +
                        "build their page in the browser often can't be imported."),
                )
                return@post
            }

            // Plenty of sites — AllRecipes among them — publish a Recipe with no `url` in it. Where it
            // came from is the one thing we always know, and it is what sourceDetails is for. The
            // address recorded is the one actually fetched, after redirects.
            val withSource =
                if (parsed.sourceDetails.isBlank()) parsed.copy(sourceDetails = ok.url.toString()) else parsed

            // A photo is a nicety: failing to get one still imports the recipe.
            val image = withSource.imageUrl
                ?.let { withContext(Dispatchers.IO) { fetchImage(it, ok.url, addressPolicy) } }

            call.respond(
                ImportResponse(
                    recipe = withSource.toDraftRecipe(),
                    imageBase64 = image?.let { Base64.getEncoder().encodeToString(it.bytes) },
                    imageContentType = image?.contentType,
                ),
            )
        }
    }
}

@Serializable
data class ImportRequest(val url: String)

@Serializable
data class ImportResponse(
    val recipe: ServerRecipe,
    /** The recipe photo, when the page had one small enough and in a format the upload accepts. */
    val imageBase64: String? = null,
    val imageContentType: String? = null,
)

/**
 * The parsed page as the shape the editor works with.
 *
 * `id` is deliberately blank. This is a draft, not a row: it has no identity until the browser gives
 * it one, and ids are minted by the client so they stay UUIDv7 — which the apps rely on for ordering.
 * The photo is left off the recipe too and travels beside it, so it goes through the editor's own
 * image-save path and gets a thumbnail generated the same way as any other upload.
 */
private fun ParsedRecipe.toDraftRecipe(): ServerRecipe = ServerRecipe(
    id = "",
    name = name,
    source = source.ifBlank { null },
    sourceDetails = sourceDetails.ifBlank { null },
    introduction = introduction.ifBlank { null },
    yield = this.yield.ifBlank { null },
    servings = servings,
    ingredients = ingredients,
    directions = directions,
    preparationTimes = preparationTimes,
)

// ---- fetching ----

private sealed interface Fetch {
    /** Refused before it left the building: the address is not one this server will go to. */
    data class Blocked(val message: String) : Fetch
    /** We tried and the far end didn't cooperate. */
    data class Failed(val message: String) : Fetch
    data class Ok(val bytes: ByteArray, val url: URI, val contentType: String?) : Fetch {
        /** Decoded with the charset the response declared, since not every recipe site is UTF-8. */
        fun text(): String = String(bytes, charsetOf(contentType))
    }
}

private val importClient: HttpClient = HttpClient.newBuilder()
    // NEVER, and this is the point: an auto-followed redirect is an unchecked fetch. Hops are walked
    // by hand below so each one is validated like the address the user actually typed.
    .followRedirects(HttpClient.Redirect.NEVER)
    .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS))
    .build()

private fun fetch(start: URI, maxBytes: Int, accept: String, addressPolicy: AddressPolicy): Fetch {
    var url = start
    repeat(MAX_REDIRECTS + 1) {
        addressPolicy(url.host)?.let { return Fetch.Blocked(it) }

        val request = HttpRequest.newBuilder(url)
            .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            // Some sites serve a stub to unknown agents; identify honestly but recognizably.
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .GET()
            .build()

        val response = runCatching {
            importClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        }.getOrElse {
            return Fetch.Failed("Couldn't load that page: ${it.message ?: "network error"}")
        }

        val code = response.statusCode()
        if (code in 300..399) {
            val location = response.headers().firstValue("location").orElse(null)
            response.body().close()
            if (location.isNullOrBlank()) return Fetch.Failed("That page redirected without saying where.")
            val next = runCatching { url.resolve(location) }.getOrNull()
                ?: return Fetch.Failed("That page redirected somewhere unreadable.")
            if (next.scheme?.lowercase() !in ALLOWED_SCHEMES || next.host.isNullOrBlank()) {
                return Fetch.Blocked("That page redirected to an address Salty won't follow.")
            }
            url = next
            return@repeat
        }

        if (code !in 200..299) return Fetch.Failed(httpMessage(code))

        val bytes = response.body().use { readCapped(it, maxBytes) }
            ?: return Fetch.Failed("That page is too large to import.")
        return Fetch.Ok(bytes, url, response.headers().firstValue("content-type").orElse(null))
    }
    return Fetch.Failed("That page redirected too many times.")
}

private data class FetchedImage(val bytes: ByteArray, val contentType: String)

/**
 * The recipe photo. Relative image URLs are resolved against the page they came from, and the result
 * is only kept when it is a format the recipe image upload actually accepts — anything else would be
 * staged in the editor only to be rejected on save.
 */
private fun fetchImage(raw: String, base: URI, addressPolicy: AddressPolicy): FetchedImage? {
    val absolute = runCatching { base.resolve(raw.trim()) }.getOrNull() ?: return null
    val target = normalize(absolute.toString()) ?: return null
    return when (val result = fetch(target, MAX_IMAGE_BYTES, "image/*", addressPolicy)) {
        is Fetch.Ok -> {
            val type = result.contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type in ACCEPTED_IMAGE_TYPES) FetchedImage(result.bytes, type!!) else null
        }
        else -> null
    }
}

/** Reads at most [max] bytes, or null if the stream had more — the cap is refused, never truncated. */
private fun readCapped(input: InputStream, max: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) return out.toByteArray()
        if (out.size() + read > max) return null
        out.write(buffer, 0, read)
    }
}

// ---- address policy ----

/** Accepts a bare "example.com/recipe", and rejects anything that isn't http(s). */
internal fun normalize(raw: String): URI? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
    if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) return null
    if (uri.host.isNullOrBlank()) return null
    return uri
}

/**
 * Decides whether the server is willing to fetch a host: a reason to refuse, or null to allow.
 *
 * Injected rather than called directly so the round trip can be tested against a page served on
 * loopback, which the real policy exists to refuse. Production never supplies anything but
 * [addressRefusal]; there is deliberately no environment variable that changes it, because a
 * deployment that could turn this off by configuration is a deployment where it will one day be off.
 */
typealias AddressPolicy = (String?) -> String?

/**
 * Why this host must not be fetched, or null if it may be.
 *
 * Every address the name resolves to has to be public, not merely the first: a name that answers with
 * both a public address and 127.0.0.1 is a way to get the second one fetched.
 */
internal fun addressRefusal(host: String?): String? {
    if (host.isNullOrBlank()) return "That web address has no host in it."
    val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull()
    if (addresses.isNullOrEmpty()) return "That address couldn't be looked up."
    if (addresses.any { !it.isPublic() }) {
        return "That address is inside this server's own network, so Salty didn't fetch it."
    }
    return null
}

/**
 * Public as in "somewhere on the internet". The named checks cover loopback, link-local (which is
 * where cloud metadata services live), the RFC 1918 private ranges and IPv6 site-local; the byte
 * checks cover the ranges the JDK has no predicate for.
 */
private fun InetAddress.isPublic(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress ||
        isSiteLocalAddress || isMulticastAddress
    ) return false

    val bytes = address
    return when (this) {
        is Inet4Address -> {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            when {
                first == 0 -> false                             // 0.0.0.0/8 "this network"
                first == 100 && second in 64..127 -> false      // 100.64.0.0/10 carrier-grade NAT
                first >= 240 -> false                           // 240.0.0.0/4 reserved, incl. broadcast
                else -> true
            }
        }
        // fc00::/7 unique-local — IPv6's answer to RFC 1918, and not covered by isSiteLocalAddress.
        is Inet6Address -> (bytes[0].toInt() and 0xfe) != 0xfc
        else -> false
    }
}

// ---- odds and ends ----

private fun charsetOf(contentType: String?): Charset {
    val declared = contentType
        ?.split(';')
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
        ?.substringAfter('=')
        ?.trim('"', ' ')
        ?: return StandardCharsets.UTF_8
    return runCatching { Charset.forName(declared) }.getOrDefault(StandardCharsets.UTF_8)
}

private fun httpMessage(code: Int): String = when (code) {
    401, 403 -> "That site refused the request ($code). Some sites block automated access."
    404 -> "That page wasn't found (404)."
    429 -> "That site is rate-limiting us (429). Try again in a bit."
    in 500..599 -> "That site had an error ($code). Try again later."
    else -> "That page couldn't be loaded ($code)."
}

private val ALLOWED_SCHEMES = setOf("http", "https")
private val ACCEPTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/gif")
private const val MAX_HTML_BYTES = 4 * 1024 * 1024
private const val MAX_IMAGE_BYTES = 10 * 1024 * 1024
private const val MAX_REDIRECTS = 5
private const val CONNECT_TIMEOUT_SECONDS = 10L
private const val REQUEST_TIMEOUT_SECONDS = 15L
private const val USER_AGENT = "Salty/1.0 (recipe import)"
