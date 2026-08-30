package com.enuvro.saltykmp.shoppinglist

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.auth.ApiCsrfGuard
import com.enuvro.saltykmp.auth.DEVICE_TOKEN_AUTH
import com.enuvro.saltykmp.auth.JWT_AUTH
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.ShoppingListRepository
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import com.enuvro.saltykmp.util.newId
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Shopping list sync endpoints, deliberately the same shape as `/api/courses|categories|tags`:
 * a GET returning the COMPLETE list plus `X-Total-Count`, and per-id post/put/delete.
 *
 * The complete-list guarantee matters: clients detect deletions by absence from this response, so it
 * must never be paginated or filtered by `modifiedSince`. `X-Total-Count` is what lets a client
 * notice a short read rather than mistaking it for "everything was deleted".
 */
fun Route.shoppingListRoutes() {
    // DEVICE_TOKEN_AUTH is listed HERE and nowhere else. That omission is the scope
    // enforcement: a device sync token cannot authenticate against account or admin
    // routes because the provider that understands it is not mounted on them.
    authenticate(JWT_AUTH, DEVICE_TOKEN_AUTH, WEB_API_AUTH) {
        // Browser callers arrive with the session cookie; guard their writes against CSRF.
        install(ApiCsrfGuard)

        route("/api/shoppingLists") {
            get {
                val userId = call.userId()
                val items = ShoppingListRepository.list(userId)
                call.response.headers.append("X-Total-Count", ShoppingListRepository.count(userId).toString())
                call.respond(items)
            }
            get("/{id}") {
                val found = ShoppingListRepository.getById(call.userId(), call.parameters["id"]!!)
                if (found == null) call.respond(HttpStatusCode.NotFound) else call.respond(found)
            }
            // Lets a client choose POST vs PUT without a body round-trip, mirroring `/api/recipes/{id}`.
            head("/{id}") {
                val found = ShoppingListRepository.getById(call.userId(), call.parameters["id"]!!)
                call.respond(if (found != null) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }
            // Saves are optimistic-concurrency-checked: a body carrying `baseRevision` that no longer
            // matches the stored revision gets 409 with the CURRENT server row as the body, so the
            // client can merge without another round trip. Bodies without `baseRevision` (legacy
            // clients) always get a 2xx — see ShoppingListRepository.save for why.
            post {
                when (val r = ShoppingListRepository.save(call.userId(), call.receive<ServerShoppingList>())) {
                    is ShoppingListRepository.SaveResult.Saved -> call.respond(HttpStatusCode.Created, r.list)
                    is ShoppingListRepository.SaveResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.current)
                }
            }
            /**
             * Conflict resolution for clients without a local snapshot (the web editor).
             * Merges the caller's base + edit against the stored row using the same shared
             * ShoppingListMerge the native clients run, then saves the result.
             */
            post("/{id}/resolve") {
                val id = call.parameters["id"]!!
                val body = call.receive<ResolveRequest>()
                val result = ShoppingListResolver.resolve(
                    userId = call.userId(),
                    id = id,
                    request = body,
                    newId = { newId() },
                )
                when (result) {
                    is ShoppingListResolver.Result.Resolved -> call.respond(result.response)
                    is ShoppingListResolver.Result.NotFound -> call.respond(HttpStatusCode.NotFound)
                    is ShoppingListResolver.Result.Raced ->
                        call.respond(HttpStatusCode.Conflict, result.current)
                }
            }
            put("/{id}") {
                val list = call.receive<ServerShoppingList>().copy(id = call.parameters["id"]!!)
                when (val r = ShoppingListRepository.save(call.userId(), list)) {
                    is ShoppingListRepository.SaveResult.Saved -> call.respond(r.list)
                    is ShoppingListRepository.SaveResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.current)
                }
            }
            // Optional `If-Match: <revision>`: refused with 409 + current row when the row changed
            // past that revision (edit beats delete — the caller should download instead). Without
            // the header the delete is unconditional, exactly the legacy behavior.
            delete("/{id}") {
                val expected = call.request.headers[HttpHeaders.IfMatch]?.trim('"')?.toLongOrNull()
                when (val r = ShoppingListRepository.delete(call.userId(), call.parameters["id"]!!, expected)) {
                    is ShoppingListRepository.DeleteResult.Deleted -> call.respond(HttpStatusCode.NoContent)
                    is ShoppingListRepository.DeleteResult.NotFound -> call.respond(HttpStatusCode.NotFound)
                    is ShoppingListRepository.DeleteResult.Conflict -> call.respond(HttpStatusCode.Conflict, r.current)
                }
            }
        }
    }
}
