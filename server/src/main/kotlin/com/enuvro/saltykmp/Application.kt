package com.enuvro.saltykmp

import com.enuvro.saltykmp.auth.AccountLockout
import com.enuvro.saltykmp.auth.LoginThrottle
import com.enuvro.saltykmp.auth.authRoutes
import com.enuvro.saltykmp.auth.DeviceTokenService
import com.enuvro.saltykmp.auth.configureAuth
import com.enuvro.saltykmp.dev.DevSeed
import com.enuvro.saltykmp.auth.revalidateSession
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.recipe.AddressPolicy
import com.enuvro.saltykmp.recipe.addressRefusal
import com.enuvro.saltykmp.recipe.recipeImportRoutes
import com.enuvro.saltykmp.recipe.recipeRoutes
import com.enuvro.saltykmp.util.appJson
import com.enuvro.saltykmp.library.libraryRoutes
import com.enuvro.saltykmp.shoppinglist.shoppingListRoutes
import com.enuvro.saltykmp.web.UserSession
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.web.WEB_AUTH
import com.enuvro.saltykmp.web.accountRoutes
import com.enuvro.saltykmp.web.appRoutes
import com.enuvro.saltykmp.web.webRoutes
import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.session
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.mustache.Mustache
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentType
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import kotlinx.coroutines.runBlocking
import java.nio.file.Paths

/**
 * Max size of a non-multipart request body. Recipe/library payloads are plain JSON text and stay well under
 * this; the recipe-image upload is multipart and exempt (it enforces its own larger, streamed cap). Keeps an
 * unbounded body — including on the unauthenticated login endpoints — from exhausting memory.
 */
private const val MAX_REQUEST_BODY_BYTES = 2L * 1024 * 1024

fun main() {
    val port = (System.getenv("PORT") ?: "8080").toInt()
    embeddedServer(Netty, port = port, host = "0.0.0.0", module = Application::module).start(wait = true)
}

/** Per-profile database defaults; see [dbDefaultsFor]. */
private data class DbDefaults(val url: String, val driver: String, val user: String, val password: String)

/**
 * Sensible DB defaults for a named profile. Default profile is **H2** (file-based) so the server runs
 * with zero setup for local/manual testing; set `SALTY_DB=postgres` to use a real Postgres instead.
 * Deployments (Docker) set the individual `SALTY_DB_*` vars explicitly, which always override these.
 */
private fun dbDefaultsFor(profile: String): DbDefaults = when (profile.lowercase()) {
    "postgres", "postgresql", "pg" -> DbDefaults(
        url = "jdbc:postgresql://localhost:5432/salty",
        driver = "org.postgresql.Driver",
        user = "salty",
        password = "salty",
    )
    // File-based (persists across restarts, unlike the in-memory DB the tests use). MODE=PostgreSQL
    // makes H2 accept the Postgres-flavoured SQL Exposed emits; AUTO_SERVER lets a DB tool connect
    // to the same file while the server is running. Data lives in ./salty-db/ (gitignored).
    "h2" -> DbDefaults(
        // TRACE_LEVEL_FILE=0 silences H2's on-disk *.trace.db (Exposed's upsert read-back logs benign
        // "Column not found" exceptions on H2 that otherwise bloat it to megabytes; writes still succeed).
        url = "jdbc:h2:file:./salty-db/salty;MODE=PostgreSQL;AUTO_SERVER=TRUE;TRACE_LEVEL_FILE=0",
        driver = "org.h2.Driver",
        user = "sa",
        password = "",
    )
    else -> error("Unknown SALTY_DB profile '$profile' (expected 'h2' or 'postgres')")
}

private fun isDefaultSecret(value: String?, knownDev: String) =
    value.isNullOrBlank() || value == knownDev || value.startsWith("CHANGE_ME")

/**
 * The key that device sync tokens are HMAC'd with.
 *
 * `SALTY_JWT_SECRET` is read as a fallback, and that fallback is not tidiness — it is the upgrade
 * path. This one key names itself after what it now protects, but it is the same key it always was:
 * device tokens were keyed on the JWT secret from the day they shipped, so a deployment that renamed
 * the variable and generated a fresh value in the same step would silently sign every enrolled
 * device out. Reading the old name keeps existing tokens valid across the rename, and
 * [enforceSecrets] warns when it is doing so.
 *
 * Rotating the value on purpose is still the way to revoke everything at once.
 */
private fun syncTokenSecret(): String =
    System.getenv("SALTY_TOKEN_SECRET")?.takeIf { it.isNotBlank() }
        ?: System.getenv("SALTY_JWT_SECRET")?.takeIf { it.isNotBlank() }
        ?: "dev-secret-change-me"

/**
 * Fails fast (or, for local dev, warns) when the sync-token secret, session secret or seeded admin
 * password is unset or still a known placeholder/dev value.
 *
 * A default sync-token secret is critical: device sync tokens are verified by HMAC against this key,
 * so a publicly-known one lets anyone forge a token for any account. We therefore **refuse to start**
 * by default. A fresh local/dev run with zero config (or the throwaway H2 profile) is still possible
 * by setting `SALTY_ALLOW_DEFAULT_SECRET=true`, which downgrades the failure to a loud warning.
 * Deployments must set a real `SALTY_TOKEN_SECRET` (the compose templates ship `CHANGE_ME_*`
 * placeholders precisely so an unedited deploy fails here instead of silently running forgeable).
 */
private fun Application.enforceSecrets() {
    val allowDefault = System.getenv("SALTY_ALLOW_DEFAULT_SECRET").toBoolean()

    if (isDefaultSecret(syncTokenSecret(), "dev-secret-change-me")) {
        val msg = "SALTY_TOKEN_SECRET is unset or a default/placeholder susceptible to forgery. Set a long, random SALTY_TOKEN_SECRET (e.g., `openssl rand -hex 32` to generate)."
        if (allowDefault) log.warn("SECURITY: $msg (allowed only because SALTY_ALLOW_DEFAULT_SECRET=true -- do not publicly expose this server)")
        else error("SECURITY: $msg Refusing to start. For local/dev use only, set SALTY_ALLOW_DEFAULT_SECRET=true.")
    }
    if (System.getenv("SALTY_TOKEN_SECRET").isNullOrBlank() && !System.getenv("SALTY_JWT_SECRET").isNullOrBlank()) {
        log.warn(
            "SALTY_JWT_SECRET is deprecated and is being used as SALTY_TOKEN_SECRET. Rename it in your " +
                "environment. Do NOT change its value at the same time: it keys the HMAC over every " +
                "device sync token, so a different value signs every enrolled device out.",
        )
    }
    if (isDefaultSecret(System.getenv("SALTY_SESSION_SECRET"), "dev-secret-change-me")) {
        val msg = "SALTY_SESSION_SECRET is unset or a default/placeholder susceptible to forgery. Set a long, random SALTY_SESSION_SECRET (e.g. `openssl rand -hex 32` to generate), distinct from SALTY_TOKEN_SECRET."
        if (allowDefault) log.warn("SECURITY: $msg (allowed only because SALTY_ALLOW_DEFAULT_SECRET=true -- do not publicly expose this server)")
        else error("SECURITY: $msg Refusing to start. For local/dev use only, set SALTY_ALLOW_DEFAULT_SECRET=true.")
    }
    if (isDefaultSecret(System.getenv("SALTY_DEFAULT_PASSWORD"), "changeit")) {
        val msg = "SALTY_DEFAULT_PASSWORD is unset or using default. Set a strong SALTY_DEFAULT_PASSWORD before first run."
        if (allowDefault) log.warn("SECURITY: $msg (allowed only because SALTY_ALLOW_DEFAULT_SECRET=true)")
        else error("SECURITY: $msg Refusing to start. For local/dev use only, set SALTY_ALLOW_DEFAULT_SECRET=true.")
    }
}

fun Application.module() {
    // Fail fast on insecure secrets before opening any DB connection or seeding an account.
    enforceSecrets()
    val profile = (System.getenv("SALTY_DB") ?: "h2").lowercase()
    val db = dbDefaultsFor(profile)
    DatabaseFactory.init(
        jdbcUrl = System.getenv("SALTY_DB_URL") ?: db.url,
        driverClassName = System.getenv("SALTY_DB_DRIVER") ?: db.driver,
        username = System.getenv("SALTY_DB_USER") ?: db.user,
        password = System.getenv("SALTY_DB_PASSWORD") ?: db.password,
    )
    val defaultUser = System.getenv("SALTY_DEFAULT_USER") ?: "admin"
    runBlocking {
        UserRepository.seedIfEmpty(
            username = defaultUser,
            password = System.getenv("SALTY_DEFAULT_PASSWORD") ?: "changeit",
        )
    }

    // Pair the image store with the DB profile: the throwaway H2 sandbox gets its own folder so it
    // doesn't inherit the Postgres deployment's leftover image files. Those would make the client's
    // disk-only HEAD existence check report images as already-present and skip uploading them into the
    // (empty) H2 database, so recipe text would sync but images wouldn't. SALTY_IMAGE_DIR overrides.
    val defaultImageDir = if (profile == "h2") "salty-images-h2" else "salty-images"
    val maxImagePixels = System.getenv("SALTY_MAX_IMAGE_PIXELS")?.toLongOrNull()?.takeIf { it > 0 }
        ?: ImageStore.DEFAULT_MAX_PIXELS
    val imageStore = ImageStore(Paths.get(System.getenv("SALTY_IMAGE_DIR") ?: defaultImageDir), maxImagePixels)

    // Local-development convenience, inert unless a seed directory exists next to the server. See
    // DevSeed: it fills an EMPTY library from .saltyrecipe files and does nothing otherwise, so a
    // checkout without that (gitignored) directory never notices it is here.
    runBlocking {
        UserRepository.findByUsername(defaultUser)?.let { user ->
            DevSeed.seedIfRequested(imageStore, user.id, System.getenv("SALTY_SEED_DIR"))
        }
    }

    // Set SALTY_TRUST_PROXY=true ONLY when the server sits behind a trusted reverse proxy (and is not
    // directly reachable). It makes the app honour X-Forwarded-* so the real client IP — not the proxy's —
    // drives the login throttle. Enabling it while the app is directly reachable would let a client spoof
    // its IP and evade throttling, so it defaults off.
    val trustProxy = System.getenv("SALTY_TRUST_PROXY").toBoolean()

    installSalty(
        imageStore,
        deviceTokens = DeviceTokenService(syncTokenSecret()),
        // Key separation: the web session cookie's MAC uses its own secret, distinct from the one
        // that keys sync tokens. Required (enforced in enforceSecrets); its own long random value,
        // e.g. `openssl rand -hex 32`.
        sessionSecret = System.getenv("SALTY_SESSION_SECRET") ?: "dev-secret-change-me",
        // Behind a TLS-terminating reverse proxy the session cookie must be HTTPS-only. Default to the
        // trust-proxy setting (a proxy deployment is normally TLS-terminated) unless explicitly overridden;
        // the plain-HTTP LAN/testing mode leaves both off.
        secureCookies = System.getenv("SALTY_SECURE_COOKIES")?.toBoolean() ?: trustProxy,
        trustProxy = trustProxy,
    )
}

/** Installs plugins + routes. Shared by production [module] and tests (which set up their own DB). */
fun Application.installSalty(
    imageStore: ImageStore,
    // Injected rather than read from the environment here: a test needs to hash a token with the
    // same key the server verifies it against, and reaching for System.getenv from inside would
    // make that impossible to arrange.
    deviceTokens: DeviceTokenService = DeviceTokenService("dev-secret-change-me"),
    sessionSecret: String = "dev-session-secret-change-me",
    secureCookies: Boolean = false,
    trustProxy: Boolean = false,
    maxRequestBodyBytes: Long = MAX_REQUEST_BODY_BYTES,
    accountLockout: AccountLockout = AccountLockout(),
    // Which hosts the web importer may fetch. Only a test ever passes anything else; see AddressPolicy.
    importAddressPolicy: AddressPolicy = ::addressRefusal,
) {
    val loginThrottle = LoginThrottle()
    // Only trust X-Forwarded-* when explicitly told we're behind a trusted proxy; otherwise a direct
    // client could spoof its source IP to defeat the login throttle. See [module].
    if (trustProxy) install(XForwardedHeaders)
    install(ContentNegotiation) { json(appJson) }
    install(CallLogging)
    // Server-rendered web UI: Mustache templates from resources/templates/ (logic-less; handlers in
    // web/WebRoutes.kt build the view models).
    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory("templates")
    }
    install(StatusPages) {
        // A malformed request body is the client's mistake, not a server fault. Answering 500
        // "Internal error" for it hid which field was wrong and buried the cause in the log; a
        // decimal in an Int field, for instance, looked identical to a crash.
        exception<BadRequestException> { call, cause ->
            call.application.log.info("Rejected malformed request for ${call.request.local.uri}: ${cause.message}")
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: "Malformed request")))
        }
        exception<Throwable> { call, cause ->
            // Log the real cause server-side; return a generic body so internal details (SQL, stack traces,
            // file paths) aren't leaked to clients.
            call.application.log.error("Unhandled exception for ${call.request.local.uri}", cause)
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Internal error"))
        }
    }
    install(Sessions) {
        cookie<UserSession>("SALTY_SESSION") {
            cookie.path = "/"
            cookie.httpOnly = true
            // SameSite=Strict stops the browser from sending the session cookie on cross-site requests,
            // which (together with the per-form CSRF token, see WebRoutes) blocks CSRF against the admin UI.
            cookie.extensions["SameSite"] = "Strict"
            cookie.secure = secureCookies
            transform(SessionTransportTransformerMessageAuthentication(sessionSecret.encodeToByteArray()))
        }
    }
    // Device sync tokens for the API (Bearer) + a session provider for the web UI (cookie → redirect
    // to /login). Rotating SALTY_TOKEN_SECRET invalidates every device token at once, which is the
    // emergency lever: it signs every client out without touching anyone's password.
    configureAuth(deviceTokens) {
        session<UserSession>(WEB_AUTH) {
            validate { revalidateSession(it) }
            challenge { call.respondRedirect("/login") }
        }
        // Same cookie, but answers `fetch` with 401 JSON instead of an HTML redirect.
        session<UserSession>(WEB_API_AUTH) {
            validate { revalidateSession(it) }
            challenge {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Not signed in"))
            }
        }
    }

    // Reject oversized request bodies before any handler (including the unauthenticated login endpoints)
    // reads them into memory — an unbounded body is otherwise a trivial memory-exhaustion DoS. Multipart
    // requests are exempt here: the only one is the recipe-image upload, which streams with its own,
    // larger cap (see MAX_IMAGE_UPLOAD_BYTES in RecipeRoutes). Guards the declared Content-Length, matching
    // how the image endpoint pre-checks length.
    intercept(ApplicationCallPipeline.Plugins) {
        val isMultipart = call.request.contentType().match(ContentType.MultiPart.FormData)
        val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (!isMultipart && declaredLength != null && declaredLength > maxRequestBodyBytes) {
            call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Request body too large"))
            return@intercept finish()
        }
    }

    routing {
        get("/health") { call.respondText("OK") }
        // Static assets for the web UI (e.g. /static/salty.css) from resources/static/.
        staticResources("/static", "static")
        authRoutes(deviceTokens, loginThrottle, accountLockout)
        recipeRoutes(imageStore)
        recipeImportRoutes(importAddressPolicy)
        libraryRoutes()
        shoppingListRoutes()
        webRoutes(imageStore, loginThrottle, accountLockout)
        accountRoutes(imageStore, accountLockout)
        appRoutes()
    }
}
