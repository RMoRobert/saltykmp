package com.enuvro.saltykmp.shoppinglist

import com.enuvro.saltykmp.api.ServerShoppingList
import com.enuvro.saltykmp.auth.JWT_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.ShoppingListRepository
import io.ktor.http.HttpStatusCode
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
    authenticate(JWT_AUTH) {
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
            post {
                val saved = ShoppingListRepository.upsert(call.userId(), call.receive<ServerShoppingList>())
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val list = call.receive<ServerShoppingList>().copy(id = call.parameters["id"]!!)
                call.respond(ShoppingListRepository.upsert(call.userId(), list))
            }
            delete("/{id}") {
                val ok = ShoppingListRepository.delete(call.userId(), call.parameters["id"]!!)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
        }
    }
}
