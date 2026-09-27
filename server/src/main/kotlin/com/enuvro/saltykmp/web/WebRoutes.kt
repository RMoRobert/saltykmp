package com.enuvro.saltykmp.web

import com.enuvro.saltykmp.auth.AccountLockout
import com.enuvro.saltykmp.auth.LoginThrottle
import com.enuvro.saltykmp.db.UserRepository
import io.ktor.server.auth.authenticate
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant

@Serializable
data class UserSession(
    val userId: String,
    val username: String,
    val isAdmin: Boolean = false,
    /** Anti-CSRF token minted at login; the app echoes it on every API write (see ApiCsrfGuard). */
    val csrfToken: String = "",
    /**
     * When this session was minted, epoch seconds. Compared against the user's passwordChangedAt so
     * a password reset invalidates cookies that predate it, matching what the JWT provider already
     * does with `iat`, and against MAX_SESSION_AGE_SECONDS so that a cookie cannot outlive its
     * welcome even if nothing ever invalidates it explicitly. Defaults to 0 so any session issued
     * before this field existed fails both comparisons and is re-authenticated once -- the safe
     * direction.
     */
    val issuedAt: Long = 0,
)

const val WEB_AUTH = "web-session"

private val csrfRandom = SecureRandom()

/** A fresh, unguessable CSRF token (kept in the MAC-signed session cookie, so it can't be forged). */
private fun newCsrfToken(): String {
    val bytes = ByteArray(32)
    csrfRandom.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Sign-in and sign-out (session-cookie auth): the front door for the app at `/app`.
 */
fun Route.webRoutes(throttle: LoginThrottle, accountLockout: AccountLockout) {
    get("/login") {
        val error = call.request.queryParameters["error"] != null
        call.respond(MustacheContent("login.mustache", loginModel(error)))
    }
    post("/login") {
        val params = call.receiveParameters()
        val username = params["username"].orEmpty()
        val ip = call.request.origin.remoteHost
        // Per-IP throttle (single hammering source) + per-username lockout (slow distributed attack), both
        // checked before the bcrypt verify.
        if (throttle.retryAfterSeconds(ip, username) != null || accountLockout.retryAfterSeconds(username) != null) {
            call.respondRedirect("/login?error=1")
            return@post
        }
        val user = UserRepository.findByUsername(username)
        // Constant-time credential check (dummy bcrypt when the user is absent) to avoid username enumeration
        // via timing; the `&& user != null` gives a non-null smart-cast in the success branch.
        if (UserRepository.verifyCredential(user, params["password"].orEmpty()) && user != null) {
            throttle.recordSuccess(ip, username)
            accountLockout.recordSuccess(username)
            // Mint a fresh CSRF token per session and store it in the (signed) session cookie.
            call.sessions.set(UserSession(user.id, user.username, user.isAdmin, newCsrfToken(),
                                          issuedAt = Instant.now().epochSecond))
            call.respondRedirect("/app")
        } else {
            throttle.recordFailure(ip, username)
            accountLockout.recordFailure(username)
            call.respondRedirect("/login?error=1")
        }
    }
    get("/logout") {
        call.sessions.clear<UserSession>()
        call.respondRedirect("/login")
    }
    authenticate(WEB_AUTH) {
        // The app is the front door. `/` is behind auth only so an anonymous visitor meets the login
        // page directly instead of bouncing through /app to get there.
        get("/") { call.respondRedirect("/app") }
    }
}

/**
 * The sign-in page. It renders no shared chrome: the login screen is a standalone document.
 */
private fun loginModel(error: Boolean): Map<String, Any?> = mapOf("error" to error)
