package com.enuvro.saltykmp.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
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
        extra()
    }
}

/** The authenticated user's id (from the verified JWT). Only valid inside authenticated routes. */
fun ApplicationCall.userId(): String =
    principal<JWTPrincipal>()!!.payload.getClaim("uid").asString()

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
