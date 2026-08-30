package com.enuvro.saltykmp.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import com.enuvro.saltykmp.web.UserSession
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.ZoneOffset
import java.util.Date

const val JWT_REALM = "salty"
const val JWT_AUTH = "auth-jwt"

/**
 * Session provider for browser calls to the JSON API.
 *
 * Deliberately separate from [WEB_AUTH]: that one answers an unauthenticated request by redirecting to
 * /login, which is right for a page navigation and wrong for `fetch` — an expired session would hand the
 * caller a 200 full of HTML instead of a 401 it can act on. Same cookie, same validation, different
 * challenge.
 */
const val WEB_API_AUTH = "auth-web-api"

/** Minimum length enforced when an admin sets/resets a user's password via the web UI. */
const val MIN_PASSWORD_LENGTH = 8

class JwtService(
    private val secret: String,
    private val issuer: String,
    private val audience: String,
    val validityMs: Long,
) {
    private val algorithm = Algorithm.HMAC256(secret)

    val verifier = JWT.require(algorithm).withIssuer(issuer).withAudience(audience).build()

    fun generate(userId: String, username: String): String {
        val now = System.currentTimeMillis()
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("uid", userId)
            .withClaim("username", username)
            // issuedAt lets the server invalidate tokens minted before a password change (see configureAuth).
            .withIssuedAt(Date(now))
            .withExpiresAt(Date(now + validityMs))
            .sign(algorithm)
    }
}

fun Application.configureAuth(
    jwtService: JwtService,
    deviceTokens: DeviceTokenService,
    extra: io.ktor.server.auth.AuthenticationConfig.() -> Unit = {},
) {
    install(Authentication) {
        jwt(JWT_AUTH) {
            realm = JWT_REALM
            verifier(jwtService.verifier)
            validate { credential ->
                val uid = credential.payload.getClaim("uid").asString() ?: return@validate null
                // Signature/expiry are already checked by the verifier. Additionally reject tokens whose
                // user was deleted, or that were minted before the user's last password change — so deleting
                // a user or resetting a password takes effect immediately instead of lingering for the
                // token's (up to 30-day) lifetime.
                val user = UserRepository.findById(uid) ?: return@validate null
                val issuedAt = credential.payload.issuedAt
                if (issuedAt != null) {
                    // Compare at whole-second granularity (JWT iat is seconds) so a token minted in the same
                    // second as the change isn't falsely rejected.
                    val issuedSec = issuedAt.toInstant().epochSecond
                    val changedSec = user.passwordChangedAt.toEpochSecond(ZoneOffset.UTC)
                    if (issuedSec < changedSec) return@validate null
                }
                JWTPrincipal(credential.payload)
            }
        }
        /**
         * Per-device sync tokens, presented the same way a JWT is.
         *
         * Declining a non-`salty_` token by returning null (rather than failing) is what lets both
         * credential types share the Authorization header: Ktor moves on to the next provider, so a
         * JWT is still handled by the jwt provider above whichever order they are listed in.
         *
         * This provider is deliberately NOT attached to every authenticated route. Scope is enforced
         * by omission: a device token can only authenticate where sync happens, because that is the
         * only place this provider is listed. See recipeRoutes/libraryRoutes/shoppingListRoutes.
         */
        bearer(DEVICE_TOKEN_AUTH) {
            authenticate { credential ->
                val presented = credential.token
                if (!deviceTokens.looksLikeToken(presented)) return@authenticate null
                val owner = DeviceRepository.findByTokenHash(deviceTokens.hash(presented))
                    ?: return@authenticate null
                // Best-effort, and after validation: this drives the devices page, not access.
                DeviceRepository.touchTokenUse(owner.userId, owner.deviceId)
                DeviceTokenPrincipal(owner.userId, owner.deviceId)
            }
        }
        extra()
    }
}

/**
 * The authenticated user's id. Only valid inside authenticated routes.
 *
 * API routes accept either a Bearer JWT (native clients) or the web session cookie (the browser UI),
 * so resolve whichever principal the matching provider installed. Both are freshness-checked against
 * the user table on every request -- the JWT in [configureAuth]'s validator, the cookie in
 * [revalidateSession] -- so a deleted user or a password reset takes effect immediately on both.
 */
fun ApplicationCall.userId(): String {
    principal<JWTPrincipal>()?.let { return it.payload.getClaim("uid").asString() }
    principal<DeviceTokenPrincipal>()?.let { return it.userId }
    principal<UserSession>()?.let { return it.userId }
    error("userId() called outside an authenticated route")
}

fun Route.authRoutes(jwtService: JwtService, throttle: LoginThrottle, accountLockout: AccountLockout) {
    post("/api/auth/login") {
        val req = call.receive<AuthRequest>()
        val ip = call.request.origin.remoteHost
        // Both throttles are checked before the bcrypt verify, so a locked-out flood is cheap to reject:
        // the per-IP throttle stops a single hammering source; the per-username lockout stops a slow
        // distributed attack spread across many IPs.
        val retryAfter = throttle.retryAfterSeconds(ip, req.username) ?: accountLockout.retryAfterSeconds(req.username)
        if (retryAfter != null) {
            call.response.headers.append(io.ktor.http.HttpHeaders.RetryAfter, retryAfter.toString())
            call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to "Too many login attempts. Try again later."))
            return@post
        }
        val user = UserRepository.findByUsername(req.username)
        // Always runs a bcrypt verify (against a dummy hash when the user is absent) so response timing
        // doesn't reveal whether the username exists. `user == null` in the guard keeps the smart-cast below.
        if (!UserRepository.verifyCredential(user, req.password) || user == null) {
            throttle.recordFailure(ip, req.username)
            accountLockout.recordFailure(req.username)
            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid username or password"))
            return@post
        }
        throttle.recordSuccess(ip, req.username)
        accountLockout.recordSuccess(req.username)
        val token = jwtService.generate(user.id, user.username)
        call.respond(AuthResponse(token = token, username = user.username, expiresIn = jwtService.validityMs))
    }
}

/**
 * Re-checks a session cookie against the user table on every request, the way the JWT provider
 * re-checks a token.
 *
 * A signed cookie proves only that we minted it, not that the account still exists or that its
 * password hasn't since been reset. Without this, deleting a user or resetting a compromised
 * password left their open browser tab writing recipes, images and library rows for the cookie's
 * full lifetime, while native clients were locked out immediately.
 *
 * Returns null to reject, which routes the request to the provider's challenge.
 */
suspend fun revalidateSession(session: UserSession): UserSession? {
    val user = UserRepository.findById(session.userId) ?: return null
    // Whole-second granularity: session issuedAt is epoch seconds, as JWT `iat` is.
    val changedSec = user.passwordChangedAt.toEpochSecond(java.time.ZoneOffset.UTC)
    if (session.issuedAt < changedSec) return null
    return session
}
