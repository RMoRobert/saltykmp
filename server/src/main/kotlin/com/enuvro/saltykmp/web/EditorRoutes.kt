package com.enuvro.saltykmp.web

import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The recipe editor (Web Awesome + Alpine).
 *
 * This route serves a shell and nothing else: no recipe data is rendered here. The page fetches and
 * writes everything through the JSON API the native clients already use, which is why those routes now
 * accept the session cookie as well as a Bearer token (see WEB_API_AUTH). Keeping one API means the
 * merge rules, validation and error handling have exactly one implementation rather than a parallel
 * set of form-POST endpoints that would drift from it.
 *
 * The existing Mustache pages are untouched — this is an island, not a replacement. `/editor` can be
 * evaluated (or abandoned) without affecting the server-rendered browse UI.
 */
fun Route.editorRoutes() {
    authenticate(WEB_AUTH) {
        get("/editor") {
            val session = call.principal<UserSession>()!!
            call.respond(
                MustacheContent(
                    "editor.mustache",
                    mapOf(
                        "pageTitle" to "Editor",
                        "username" to session.username,
                        "isAdmin" to session.isAdmin,
                        // Handed to the page so its API writes can echo it back in X-CSRF-Token.
                        // Safe to embed: it is per-session, already inside the MAC-signed cookie, and
                        // only ever leaves the browser on same-origin requests.
                        "csrfToken" to session.csrfToken,
                    ),
                ),
            )
        }
    }
}
