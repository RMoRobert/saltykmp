package com.enuvro.saltykmp.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.response.respond

/**
 * Refuses anything a sync credential could have obtained.
 *
 * Managing devices is not syncing: revoking is how you take a stolen phone off the account, so a
 * credential that a stolen phone holds must not be able to do it.
 *
 * This used to have a second, subtler case to reject: a JWT minted *from* a device token, which was
 * otherwise indistinguishable from one minted with a password, and which every enrolled device could
 * mint for itself by trading its token at `POST /api/auth/token`. A `src` claim existed solely so
 * this plugin could tell them apart. Removing JWTs removed the ambiguity with them — a request now
 * arrives holding either a sync token or a session cookie, and those are never confusable.
 *
 * Belt and braces on top of route mounting: these routes list only WEB_API_AUTH today, so a device
 * token cannot reach them anyway. This plugin is what keeps that true if someone later adds
 * DEVICE_TOKEN_AUTH to the group without thinking it through.
 */
val RequirePasswordAuth = createRouteScopedPlugin("RequirePasswordAuth") {
    on(AuthenticationChecked) { call ->
        if (call.principal<DeviceTokenPrincipal>() != null) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "A sync credential cannot manage devices. Sign in with your password."),
            )
        }
    }
}
