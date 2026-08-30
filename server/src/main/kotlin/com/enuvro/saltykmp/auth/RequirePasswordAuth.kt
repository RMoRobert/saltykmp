package com.enuvro.saltykmp.auth

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.response.respond

/**
 * Refuses anything a sync credential could have obtained.
 *
 * Managing devices is not syncing: revoking is how you take a stolen phone off the account, so a
 * credential that a stolen phone holds must not be able to do it. Two things are rejected, and the
 * second is the one that is easy to miss:
 *
 *  - a [DeviceTokenPrincipal] — the sync token presented directly;
 *  - a JWT minted **from** a device token, which is otherwise indistinguishable from one minted
 *    with a password. `POST /api/auth/token` exists precisely to trade one for the other, so
 *    without this check every enrolled device could mint its way to a credential that revokes its
 *    siblings. The [CLAIM_SOURCE] claim exists for this.
 *
 * Belt and braces on top of route mounting: these routes list only WEB_API_AUTH today, so neither
 * credential can reach them anyway. This plugin is what keeps that true if someone later adds
 * JWT_AUTH to the group without thinking through where those JWTs came from.
 */
val RequirePasswordAuth = createRouteScopedPlugin("RequirePasswordAuth") {
    on(AuthenticationChecked) { call ->
        val fromDeviceToken = call.principal<DeviceTokenPrincipal>() != null ||
            call.principal<JWTPrincipal>()?.payload?.getClaim(CLAIM_SOURCE)?.asString() == SOURCE_DEVICE_TOKEN

        if (fromDeviceToken) {
            call.respond(
                HttpStatusCode.Forbidden,
                mapOf("error" to "A sync credential cannot manage devices. Sign in with your password."),
            )
        }
    }
}
