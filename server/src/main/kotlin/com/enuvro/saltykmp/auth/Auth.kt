package com.enuvro.saltykmp.auth

import com.enuvro.saltykmp.api.AuthRequest
import com.enuvro.saltykmp.api.DeviceRenameRequest
import com.enuvro.saltykmp.api.AuthResponse
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.UserRepository
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.principal
import com.enuvro.saltykmp.web.UserSession
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.route
import io.ktor.server.routing.post

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

/**
 * How long a browser session stays valid, measured from login.
 *
 * Enforced here rather than as the cookie's `Max-Age` because that is client-side state: it tells the
 * browser when to stop sending the cookie, which an attacker replaying a copied value simply ignores.
 * Checked against `issuedAt` on every request it bounds a leaked cookie for real -- one that escaped
 * in a backup, a synced browser profile or a machine you no longer have stops working on its own.
 * It also costs nothing extra, because [revalidateSession] is already reading the user row anyway.
 *
 * Absolute, not idle. Ktor only re-sends `Set-Cookie` when the session is *modified*, so nothing
 * slides this forward on ordinary traffic: it is 15 days from sign-in however active the tab was,
 * and the next request after that lands on /login.
 */
const val MAX_SESSION_AGE_SECONDS: Long = 15L * 24 * 60 * 60

/**
 * Installs the two credentials Salty has: per-device sync tokens, and the browser session (via
 * [extra]).
 *
 * There used to be a third. A JWT sat between the device token and the sync routes: a client traded
 * its token for a short-lived JWT and presented that instead. Removing it took nothing away, because
 * the JWT provider was mounted on exactly the three route groups the device token already reached —
 * and the web app never used one at all, it has always been a cookie session. What it took away was
 * a real hole: the JWT validator checked that the user still existed and that the token predated no
 * password change, but it never checked the device token was still valid, so revoking one device
 * left its last JWT syncing until it expired. A token checked against the row on every request makes
 * revocation take effect on the next one.
 *
 * The cost is a hash and a row lookup per request instead of a stateless signature check. For a
 * personal recipe server that is not a trade worth making the other way.
 */
fun Application.configureAuth(
    deviceTokens: DeviceTokenService,
    extra: io.ktor.server.auth.AuthenticationConfig.() -> Unit = {},
) {
    install(Authentication) {
        /**
         * Per-device sync tokens — the only credential a native client ever holds.
         *
         * Declining a non-`salty_` token by returning null (rather than failing) leaves Ktor free to
         * try the next provider, which is what lets a sync route accept either this or the browser's
         * session cookie on the same request path.
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
 * API routes accept either a Bearer device token (native clients) or the web session cookie (the
 * browser UI), so resolve whichever principal the matching provider installed. Both are checked
 * against the database on every request -- the token in [DeviceRepository.findByTokenHash], the
 * cookie in [revalidateSession] -- so a deleted user, a password reset or a revoked device takes
 * effect immediately on both.
 */
fun ApplicationCall.userId(): String {
    principal<DeviceTokenPrincipal>()?.let { return it.userId }
    principal<UserSession>()?.let { return it.userId }
    error("userId() called outside an authenticated route")
}

fun Route.authRoutes(
    deviceTokens: DeviceTokenService,
    throttle: LoginThrottle,
    accountLockout: AccountLockout,
) {
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

        // Enrolment rides on the login the client already performs, and is now the ONLY thing this
        // endpoint is for: sign in once with the password, leave holding a sync token.
        //
        // deviceId is required. It was optional while clients that predated device tokens still had
        // to work, and that optionality was the whole bug: such a client got no token, kept the
        // password, and synced with it forever, while the device_sync row registerDevice created for
        // it sat there with a null hash looking exactly like a revoked device. Rejecting the request
        // is what makes "every syncing client is an enrolled client" true rather than merely
        // intended -- and with it, a null token_hash now has exactly one meaning (see
        // [DeviceRepository.revokeAllTokens]).
        val deviceId = req.deviceId?.takeIf { it.isNotBlank() }
            ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to "This client is too old to sign in. Update it and try again."),
            )
        val minted = deviceTokens.generate()
        DeviceRepository.issueToken(user.id, deviceId, req.deviceName, deviceTokens.hash(minted))
        call.respond(AuthResponse(username = user.username, deviceToken = minted))
    }

    /**
     * Confirms a device token is still good, and says who it belongs to.
     *
     * This used to trade the token for a short-lived JWT, which is what a client then presented on
     * every sync request. The trade is gone — the token authenticates the sync routes directly — but
     * the round trip it provided is still worth one call: a client starting up needs to tell "the
     * server disowned me, ask for the password again" apart from "the network is down", and a 401
     * here says the first unambiguously.
     *
     * Reaching it at all requires a live token, so there is nothing to check in the body.
     */
    authenticate(DEVICE_TOKEN_AUTH) {
        post("/api/auth/token/verify") {
            val principal = call.principal<DeviceTokenPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            val user = UserRepository.findById(principal.userId)
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            call.respond(AuthResponse(username = user.username))
        }

        /**
         * A device revoking its own token — what "forget this device" does in a client, so signing
         * out of a device actually ends its access rather than merely forgetting it locally.
         *
         * Reachable by DEVICE_TOKEN_AUTH while /api/auth/devices deliberately is not, and the
         * difference is the entire security argument: this route can only destroy the credential
         * that authenticated the call. There is no deviceId parameter to aim somewhere else, so a
         * stolen device can revoke itself and nothing whatsoever besides — de-escalation, not the
         * escalation [RequirePasswordAuth] exists to stop. A thief using this only logs themselves
         * out, which is a strictly better outcome than the alternative.
         *
         * Answers 204 whether or not a row was removed: the caller is un-enrolling either way, and
         * the only way to reach here at all is to have presented a live token.
         */
        post("/api/auth/token/revoke") {
            val principal = call.principal<DeviceTokenPrincipal>()
                ?: return@post call.respond(HttpStatusCode.Unauthorized)
            DeviceRepository.removeDevice(principal.userId, principal.deviceId)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    /**
     * Managing devices — listing, renaming, and revoking devices *other than* the caller. Session
     * only, deliberately: revoking is how a stolen device is removed, so it must not be reachable by
     * a credential that device holds. [RequirePasswordAuth] enforces the same rule a second way, in
     * case this mount list gains a sync credential later.
     *
     * The exception is /api/auth/token/revoke above, which takes no deviceId and so can only revoke
     * the caller itself. Revoking anyone else stays here, behind a password.
     */
    authenticate(WEB_API_AUTH) {
        install(RequirePasswordAuth)
        // Browser callers arrive with the session cookie; guard their writes against CSRF.
        install(ApiCsrfGuard)

        route("/api/auth/devices") {
            get {
                call.respond(DeviceRepository.listForUser(call.userId()))
            }
            patch("/{deviceId}") {
                val name = call.receive<DeviceRenameRequest>().deviceName.trim()
                if (name.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "A device needs a name"))
                    return@patch
                }
                val renamed = DeviceRepository.renameDevice(call.userId(), call.parameters["deviceId"]!!, name)
                if (renamed) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
            }
            // Removes the row outright rather than clearing its token: revoking and forgetting are one
            // act (see [DeviceRepository.removeDevice]). 404 now means what it says -- there was no such
            // device -- where before it could not be reached at all, because nulling an already-null
            // hash still matched the row and reported success.
            delete("/{deviceId}") {
                val removed = DeviceRepository.removeDevice(call.userId(), call.parameters["deviceId"]!!)
                if (removed) call.respond(HttpStatusCode.NoContent) else call.respond(HttpStatusCode.NotFound)
            }
            post("/revoke-all") {
                val count = DeviceRepository.revokeAllTokens(call.userId())
                call.respond(mapOf("revoked" to count))
            }
        }
    }
}

/**
 * Re-checks a session cookie against the user table on every request, the way the device-token
 * provider re-checks a token against its row.
 *
 * A signed cookie proves only that we minted it, not that the account still exists or that its
 * password hasn't since been reset. Without this, deleting a user or resetting a compromised
 * password left their open browser tab writing recipes, images and library rows for the cookie's
 * full lifetime, while native clients were locked out immediately.
 *
 * It is also where the cookie's lifetime is bounded at all -- see [MAX_SESSION_AGE_SECONDS]. The
 * cookie carries no `Max-Age`, so the browser keeps it until the profile is cleared; "session
 * cookies die with the browser" has not been true since browsers started restoring tabs on start-up.
 *
 * Returns null to reject, which routes the request to the provider's challenge.
 */
suspend fun revalidateSession(session: UserSession): UserSession? {
    val user = UserRepository.findById(session.userId) ?: return null
    // Whole-second granularity: session issuedAt is epoch seconds.
    val changedSec = user.passwordChangedAt.toEpochSecond(java.time.ZoneOffset.UTC)
    if (session.issuedAt < changedSec) return null
    if (java.time.Instant.now().epochSecond - session.issuedAt > MAX_SESSION_AGE_SECONDS) return null
    // isAdmin comes from the row, not the cookie. The cookie's copy is a snapshot from login, and
    // user administration now happens in a live screen you can demote yourself from -- so a stale
    // `true` would leave the demoted admin managing users until they happened to sign out.
    return if (user.isAdmin == session.isAdmin) session else session.copy(isAdmin = user.isAdmin)
}
