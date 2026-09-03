package com.enuvro.saltykmp.web

import com.enuvro.saltykmp.auth.AccountLockout
import com.enuvro.saltykmp.auth.ApiCsrfGuard
import com.enuvro.saltykmp.auth.MAX_USERNAME_LENGTH
import com.enuvro.saltykmp.auth.MIN_PASSWORD_LENGTH
import com.enuvro.saltykmp.auth.RequirePasswordAuth
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.UserRepository
import com.enuvro.saltykmp.image.ImageStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import kotlinx.serialization.Serializable
import java.time.ZoneOffset

/**
 * Account and user administration as JSON, for the web app's account screens.
 *
 * These used to be Mustache pages with form POSTs (`/users`, `/devices`). They are an API now for the
 * same reason the recipe screens are: the app is a single page that has to show the result of a write
 * without a navigation, and a redirect carrying `?error=lastadmin` can't do that. The guards that used
 * to live in those handlers — last-admin, self-delete, password length — moved here unchanged, so
 * there is still exactly one place that enforces them.
 *
 * Mounted on WEB_API_AUTH alone, and [RequirePasswordAuth] says so a second time: a sync credential
 * must never be able to change the password it would then still be holding, nor delete an account.
 * See the same reasoning on `/api/auth/devices`, which this sits alongside.
 */
fun Route.accountRoutes(imageStore: ImageStore, accountLockout: AccountLockout) {
    authenticate(WEB_API_AUTH) {
        install(RequirePasswordAuth)
        install(ApiCsrfGuard)

        /**
         * Changing your own password. Requires the current one: the session cookie alone is an
         * ambient credential, so without this check a borrowed browser is a full account takeover
         * rather than a session someone can revoke.
         *
         * Two consequences the UI has to state plainly, both deliberate:
         *  - every enrolled device is signed out, matching what an admin reset already does;
         *  - **the caller's own session has to be re-minted here.** `revalidateSession` rejects any
         *    cookie issued before the user's `passwordChangedAt`, which now includes the cookie that
         *    authenticated this very request. Re-minting from the stored timestamp rather than
         *    `now()` avoids a same-second race where a fresh cookie is still one second stale.
         */
        post("/api/account/password") {
            val session = call.principal<UserSession>()!!
            val req = call.receive<ChangePasswordRequest>()

            // The password is being guessed through an authenticated endpoint rather than /login, so
            // it shares /login's lockout counter -- an attacker who lands a session cookie shouldn't
            // get an unmetered oracle for the password itself.
            if (accountLockout.retryAfterSeconds(session.username) != null) {
                call.respond(HttpStatusCode.TooManyRequests,
                    ApiError("Too many attempts. Try again later."))
                return@post
            }

            val user = UserRepository.findById(session.userId)
            if (user == null) {
                call.respond(HttpStatusCode.Unauthorized, ApiError("Account no longer exists"))
                return@post
            }
            if (!UserRepository.verifyPassword(req.currentPassword, user.passwordHash)) {
                accountLockout.recordFailure(session.username)
                call.respond(HttpStatusCode.Forbidden, ApiError("That isn't your current password"))
                return@post
            }
            accountLockout.recordSuccess(session.username)

            if (req.newPassword.length < MIN_PASSWORD_LENGTH) {
                call.respond(HttpStatusCode.BadRequest, ApiError(weakPasswordMessage()))
                return@post
            }

            UserRepository.changePassword(user.id, req.newPassword)
            val revoked = DeviceRepository.revokeAllTokens(user.id)

            // Re-mint from the stored stamp, not from now(): revalidateSession compares whole
            // seconds, and a cookie minted a moment before the row was written would be rejected on
            // the caller's very next request -- i.e. changing your password would log you out.
            val changedAt = UserRepository.findById(user.id)?.passwordChangedAt
                ?.toEpochSecond(ZoneOffset.UTC)
                ?: return@post call.respond(HttpStatusCode.InternalServerError,
                    ApiError("Password changed, but the session could not be renewed. Sign in again."))
            call.sessions.set(session.copy(issuedAt = changedAt))

            call.respond(ChangePasswordResponse(devicesSignedOut = revoked))
        }

        // ---- User administration. Every route re-checks admin against the row, not the cookie. ----
        route("/api/users") {
            get {
                call.requireAdmin() ?: return@get
                val self = call.principal<UserSession>()!!.userId
                call.respond(UserRepository.listAll().map {
                    UserSummary(it.id, it.username, it.isAdmin, isSelf = it.id == self)
                })
            }

            post {
                call.requireAdmin() ?: return@post
                val req = call.receive<CreateUserRequest>()
                val username = req.username.trim()
                when {
                    username.isEmpty() ->
                        call.respond(HttpStatusCode.BadRequest, ApiError("A username is required"))
                    // varchar(255): longer reached the insert and came back as "Internal error".
                    username.length > MAX_USERNAME_LENGTH ->
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiError("That username is too long (max $MAX_USERNAME_LENGTH characters)"),
                        )
                    req.password.length < MIN_PASSWORD_LENGTH ->
                        call.respond(HttpStatusCode.BadRequest, ApiError(weakPasswordMessage()))
                    UserRepository.existsByUsername(username) ->
                        call.respond(HttpStatusCode.Conflict, ApiError("That username already exists"))
                    else -> {
                        val created = UserRepository.create(username, req.password, req.isAdmin)
                        call.respond(HttpStatusCode.Created,
                            UserSummary(created.id, created.username, created.isAdmin, isSelf = false))
                    }
                }
            }

            /**
             * An admin resetting someone else's password. Unlike the self-service route above this
             * takes no current password — that is the whole point of an admin reset — so it revokes
             * the target's devices and, via `passwordChangedAt`, their web sessions too.
             */
            post("/{id}/password") {
                call.requireAdmin() ?: return@post
                val id = call.parameters["id"]!!
                val req = call.receive<SetPasswordRequest>()
                when {
                    UserRepository.findById(id) == null ->
                        call.respond(HttpStatusCode.NotFound, ApiError("User not found"))
                    req.password.length < MIN_PASSWORD_LENGTH ->
                        call.respond(HttpStatusCode.BadRequest, ApiError(weakPasswordMessage()))
                    else -> {
                        UserRepository.changePassword(id, req.password)
                        // Explicit revocation on top of the passwordChangedAt check in
                        // findByTokenHash: the check alone would leave dead hashes in the table
                        // looking like live devices on the account screen.
                        DeviceRepository.revokeAllTokens(id)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            patch("/{id}") {
                call.requireAdmin() ?: return@patch
                val id = call.parameters["id"]!!
                val req = call.receive<SetAdminRequest>()
                val target = UserRepository.findById(id)
                when {
                    target == null ->
                        call.respond(HttpStatusCode.NotFound, ApiError("User not found"))
                    // Don't let the last admin (often yourself) drop admin and lock everyone out.
                    !req.isAdmin && target.isAdmin && UserRepository.adminCount() <= 1 ->
                        call.respond(HttpStatusCode.Conflict,
                            ApiError("You can't remove the last administrator"))
                    else -> {
                        UserRepository.setAdmin(id, req.isAdmin)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            delete("/{id}") {
                val session = call.requireAdmin() ?: return@delete
                val id = call.parameters["id"]!!
                val target = UserRepository.findById(id)
                when {
                    target == null ->
                        call.respond(HttpStatusCode.NotFound, ApiError("User not found"))
                    target.id == session.userId ->
                        call.respond(HttpStatusCode.Conflict,
                            ApiError("You can't delete the account you're signed in as"))
                    target.isAdmin && UserRepository.adminCount() <= 1 ->
                        call.respond(HttpStatusCode.Conflict,
                            ApiError("You can't remove the last administrator"))
                    else -> {
                        val images = UserRepository.deleteWithData(id)
                        images.forEach { imageStore.delete(it) }
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun weakPasswordMessage() = "Password must be at least $MIN_PASSWORD_LENGTH characters"

/**
 * Returns the caller's session if they are an admin, else responds 403 and returns null.
 *
 * Reads the flag off the principal, which [revalidateSession] refreshes from the user row on every
 * request — so an admin demoted in another tab loses access on their next call rather than on their
 * next sign-in. That matters more now that demotion is something you can do to yourself from a live
 * screen and keep clicking.
 */
private suspend fun io.ktor.server.application.ApplicationCall.requireAdmin(): UserSession? {
    val session = principal<UserSession>()!!
    if (!session.isAdmin) {
        respond(HttpStatusCode.Forbidden, ApiError("Administrator access required"))
        return null
    }
    return session
}

@Serializable
data class ApiError(val error: String)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class ChangePasswordResponse(val devicesSignedOut: Int)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val isAdmin: Boolean = false)

@Serializable
data class SetPasswordRequest(val password: String)

@Serializable
data class SetAdminRequest(val isAdmin: Boolean)

@Serializable
data class UserSummary(
    val id: String,
    val username: String,
    val isAdmin: Boolean,
    /** So the app can grey out "delete" on your own row without re-deriving who you are. */
    val isSelf: Boolean,
)
