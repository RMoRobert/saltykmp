package com.enuvro.saltykmp.auth

import com.enuvro.saltykmp.web.UserSession
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.header
import io.ktor.server.response.respond

/**
 * CSRF guard for the JSON API's *session-authenticated* callers.
 *
 * The API accepts two credentials. A Bearer device sync token is not an ambient credential — a hostile
 * page has no way to make the browser attach it — so those callers need no CSRF protection and are
 * skipped. The session cookie IS ambient, so a state-changing API call authenticated by cookie must
 * additionally prove it came from our own page by echoing the session's CSRF token in a header.
 *
 * This is defence in depth rather than the only line: `SALTY_SESSION` is `SameSite=Strict`, so a
 * cross-site request never carries it in the first place. The header check covers the cases SameSite
 * doesn't — a same-site injection, or a browser that mishandles the attribute — and mirrors the hidden
 * form field the HTML routes already use (see `checkCsrf` in WebRoutes).
 *
 * Safe methods are exempt: they must not change state, and requiring a header on GET would break plain
 * navigation to an API URL.
 */
val ApiCsrfGuard = createRouteScopedPlugin("ApiCsrfGuard") {
    val safeMethods = setOf(HttpMethod.Get, HttpMethod.Head, HttpMethod.Options)

    // AuthenticationChecked (rather than onCall) for two reasons: principals are resolved by the time it
    // runs, and responding from it actually short-circuits the route handler. A plain onCall observer
    // responds *alongside* the handler, which lets the write through -- covered by WebApiAuthTest.
    on(AuthenticationChecked) { call ->
        if (call.request.httpMethod in safeMethods) return@on

        // Bearer-token callers: no ambient credential, nothing to forge.
        if (call.principal<DeviceTokenPrincipal>() != null) return@on

        val session = call.principal<UserSession>() ?: return@on
        val presented = call.request.header(CSRF_HEADER)
        // Constant-time: this is application code comparing a secret the caller supplied against one
        // it does not hold, which is exactly the comparison DeviceTokenService wrote its helper for.
        if (session.csrfToken.isEmpty() ||
            presented == null ||
            !DeviceTokenService.constantTimeEquals(presented, session.csrfToken)
        ) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "Missing or invalid CSRF token"),
            )
        }
    }
}

/** Header the browser UI echoes the session's CSRF token in. */
const val CSRF_HEADER = "X-CSRF-Token"
