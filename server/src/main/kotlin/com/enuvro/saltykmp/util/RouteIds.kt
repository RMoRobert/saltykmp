package com.enuvro.saltykmp.util

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

/**
 * A client-supplied id, checked before it reaches the database or the filesystem — or a 400 and null.
 *
 * Two things made this worth having in one place. Ids name files: a recipe's photo is stored as
 * `<id>.<ext>`, and the router hands a handler the DECODED path segment, so `%2F` arrives as a real
 * separator (see `isSafeId`). And ids are `varchar(64)` columns: a longer one used to travel all the
 * way to the insert and come back as a 500 with a SQL stack trace in the log, where the honest answer
 * is that the request was malformed.
 *
 * Used at every route that accepts an id from outside, whether from the path or from a body.
 */
suspend fun ApplicationCall.safeId(raw: String?): String? {
    if (raw != null && isSafeId(raw)) return raw
    respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))
    return null
}
