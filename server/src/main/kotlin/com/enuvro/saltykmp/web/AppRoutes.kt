package com.enuvro.saltykmp.web

import com.enuvro.saltykmp.BuildInfo
import com.enuvro.saltykmp.auth.MIN_PASSWORD_LENGTH
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.mustache.MustacheContent
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The Salty web app (Web Awesome + Alpine) — the default UI, served at `/app`.
 *
 * This route serves a shell and nothing else: no recipe data is rendered here. The page fetches and
 * writes everything through the JSON API the native clients already use, which is why those routes
 * accept the session cookie as well as a Bearer token (see WEB_API_AUTH). Keeping one API means the
 * merge rules, validation and error handling have exactly one implementation rather than a parallel
 * set of form-POST endpoints that would drift from it.
 *
 * The server-rendered Pico pages still exist behind `/classic`, reachable from this app's settings
 * menu, and are expected to be removed once nothing needs them.
 */
fun Route.appRoutes() {
    // `/editor` was this page's address while it was an experiment. Permanent, because a bookmark
    // and an open tab's reload both deserve to land somewhere real rather than on a 404.
    get("/editor") { call.respondRedirect("/app", permanent = true) }

    authenticate(WEB_AUTH) {
        get("/app") {
            val session = call.principal<UserSession>()!!
            call.respond(
                MustacheContent(
                    "app.mustache",
                    mapOf(
                        "pageTitle" to "Salty",
                        "username" to session.username,
                        "isAdmin" to session.isAdmin,
                        // Handed to the page so its API writes can echo it back in X-CSRF-Token.
                        // Safe to embed: it is per-session, already inside the MAC-signed cookie, and
                        // only ever leaves the browser on same-origin requests.
                        "csrfToken" to session.csrfToken,
                        // Feeds the About dialog. Behind auth, as the old /about page was: the exact
                        // build isn't something an anonymous visitor needs to be told.
                        "version" to BuildInfo.version,
                        "buildTime" to BuildInfo.buildTime,
                        // So the password form can state the rule instead of discovering it in a 400.
                        "minPasswordLength" to MIN_PASSWORD_LENGTH,
                    ),
                ),
            )
        }
    }
}
