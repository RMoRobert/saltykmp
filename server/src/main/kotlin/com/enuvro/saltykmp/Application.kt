package com.enuvro.saltykmp

import com.enuvro.saltykmp.auth.JwtService
import com.enuvro.saltykmp.auth.LoginThrottle
import com.enuvro.saltykmp.auth.authRoutes
import com.enuvro.saltykmp.auth.configureAuth
import com.enuvro.saltykmp.db.DatabaseFactory
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.recipe.recipeRoutes
import com.enuvro.saltykmp.util.appJson
import com.enuvro.saltykmp.library.libraryRoutes
import com.enuvro.saltykmp.web.UserSession
import com.enuvro.saltykmp.web.WEB_AUTH
import com.enuvro.saltykmp.web.webRoutes
import com.github.mustachejava.DefaultMustacheFactory
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.session
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.mustache.Mustache
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
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
 * Fails fast (or, for local dev, warns) when the JWT secret or seeded admin password is unset or still a
 * known placeholder/dev value.
 *
 * A default JWT secret is critical: JWTs would be signed with a publicly-known key, so anyone could forge
 * a login token for any user. We therefore **refuse to start** by default. A fresh local/dev run with zero
 * config (or the throwaway H2 profile) is still possible by setting `SALTY_ALLOW_DEFAULT_SECRET=true`, which
 * downgrades the failure to a loud warning. Deployments must set a real `SALTY_JWT_SECRET` (the compose
 * templates ship `CHANGE_ME_*` placeholders precisely so an unedited deploy fails here instead of silently
 * running forgeable).
 */
private fun Application.enforceSecrets() {
    val allowDefault = System.getenv("SALTY_ALLOW_DEFAULT_SECRET").toBoolean()

    if (isDefaultSecret(System.getenv("SALTY_JWT_SECRET"), "dev-secret-change-me")) {
        val msg = "SALTY_JWT_SECRET is unset or a default/placeholder — JWTs would be signed with a publicly known secret, so anyone could forge a login token. Set a long, random SALTY_JWT_SECRET (e.g. `openssl rand -hex 32`)."
        if (allowDefault) log.warn("SECURITY: $msg (allowed only because SALTY_ALLOW_DEFAULT_SECRET=true — do NOT expose this server)")
        else error("SECURITY: $msg Refusing to start. For local/dev use only, set SALTY_ALLOW_DEFAULT_SECRET=true.")
    }
    if (isDefaultSecret(System.getenv("SALTY_DEFAULT_PASSWORD"), "changeit")) {
        val msg = "SALTY_DEFAULT_PASSWORD is unset or a default — the seeded admin account would use a well-known password. Set a strong SALTY_DEFAULT_PASSWORD before first run."
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
        // Make sure user management isn't locked out (e.g. after upgrading a pre-is_admin database).
        UserRepository.ensureAdminExists(defaultUser)
    }

    val jwtService = JwtService(
        secret = System.getenv("SALTY_JWT_SECRET") ?: "dev-secret-change-me",
        issuer = "salty",
        audience = "salty-app",
        validityMs = (System.getenv("SALTY_JWT_DAYS") ?: "30").toLong() * 24 * 60 * 60 * 1000,
    )
    // Pair the image store with the DB profile: the throwaway H2 sandbox gets its own folder so it
    // doesn't inherit the Postgres deployment's leftover image files. Those would make the client's
    // disk-only HEAD existence check report images as already-present and skip uploading them into the
    // (empty) H2 database, so recipe text would sync but images wouldn't. SALTY_IMAGE_DIR overrides.
    val defaultImageDir = if (profile == "h2") "salty-images-h2" else "salty-images"
    val imageStore = ImageStore(Paths.get(System.getenv("SALTY_IMAGE_DIR") ?: defaultImageDir))

    installSalty(
        jwtService,
        imageStore,
        sessionSecret = System.getenv("SALTY_JWT_SECRET") ?: "dev-secret-change-me",
        // Behind a TLS-terminating reverse proxy set SALTY_SECURE_COOKIES=true so the session cookie is only
        // ever sent over HTTPS. Left off by default because the documented LAN/testing mode serves plain HTTP.
        secureCookies = System.getenv("SALTY_SECURE_COOKIES").toBoolean(),
    )
}

/** Installs plugins + routes. Shared by production [module] and tests (which set up their own DB). */
fun Application.installSalty(
    jwtService: JwtService,
    imageStore: ImageStore,
    sessionSecret: String = "dev-session-secret-change-me",
    secureCookies: Boolean = false,
) {
    val loginThrottle = LoginThrottle()
    install(ContentNegotiation) { json(appJson) }
    install(CallLogging)
    // Server-rendered web UI: Mustache templates from resources/templates/ (logic-less; handlers in
    // web/WebRoutes.kt build the view models).
    install(Mustache) {
        mustacheFactory = DefaultMustacheFactory("templates")
    }
    install(StatusPages) {
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
    // JWT for the API (Bearer) + a session provider for the web UI (cookie → redirect to /login).
    configureAuth(jwtService) {
        session<UserSession>(WEB_AUTH) {
            validate { it }
            challenge { call.respondRedirect("/login") }
        }
    }

    routing {
        get("/health") { call.respondText("OK") }
        // Static assets for the web UI (e.g. /static/salty.css) from resources/static/.
        staticResources("/static", "static")
        authRoutes(jwtService, loginThrottle)
        recipeRoutes(imageStore)
        libraryRoutes()
        webRoutes(imageStore, loginThrottle)
    }
}
