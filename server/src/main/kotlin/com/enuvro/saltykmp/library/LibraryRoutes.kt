package com.enuvro.saltykmp.library

import com.enuvro.saltykmp.api.LibraryDeleteRequest
import com.enuvro.saltykmp.api.LibraryMergeRequest
import com.enuvro.saltykmp.api.LibraryMergeResponse
import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.auth.ApiCsrfGuard
import com.enuvro.saltykmp.auth.DEVICE_TOKEN_AUTH
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.LibraryRepository
import com.enuvro.saltykmp.util.safeId
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

fun Route.libraryRoutes() {
    // DEVICE_TOKEN_AUTH is listed HERE and nowhere else. That omission is the scope
    // enforcement: a device sync token cannot authenticate against account or admin
    // routes because the provider that understands it is not mounted on them.
    authenticate(DEVICE_TOKEN_AUTH, WEB_API_AUTH) {
        // Browser callers arrive with the session cookie; guard their writes against CSRF.
        install(ApiCsrfGuard)

        route("/api/courses") {
            get {
                val userId = call.userId()
                val items = LibraryRepository.listCourses(userId)
                call.response.headers.append("X-Total-Count", LibraryRepository.countCourses(userId).toString())
                call.respond(items)
            }
            post {
                val incoming = call.receive<ServerCourse>()
                call.safeId(incoming.id) ?: return@post
                call.respond(HttpStatusCode.Created, LibraryRepository.upsertCourse(call.userId(), incoming))
            }
            put("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@put
                val c = call.receive<ServerCourse>().copy(id = id)
                call.respond(LibraryRepository.upsertCourse(call.userId(), c))
            }
            delete("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@delete
                val ok = LibraryRepository.deleteCourse(call.userId(), id)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
            post("/merge") {
                val req = call.mergeRequest() ?: return@post
                call.respondMerged("course", LibraryRepository.mergeCourses(call.userId(), req.survivorId, req.duplicateIds))
            }
            post("/delete") {
                val req = call.deleteRequest() ?: return@post
                call.respond(LibraryRepository.deleteCourses(call.userId(), req.ids))
            }
        }

        route("/api/categories") {
            get {
                val userId = call.userId()
                val items = LibraryRepository.listCategories(userId)
                call.response.headers.append("X-Total-Count", LibraryRepository.countCategories(userId).toString())
                call.respond(items)
            }
            post {
                val incoming = call.receive<ServerCategory>()
                call.safeId(incoming.id) ?: return@post
                call.respond(HttpStatusCode.Created, LibraryRepository.upsertCategory(call.userId(), incoming))
            }
            put("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@put
                val c = call.receive<ServerCategory>().copy(id = id)
                call.respond(LibraryRepository.upsertCategory(call.userId(), c))
            }
            delete("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@delete
                val ok = LibraryRepository.deleteCategory(call.userId(), id)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
            post("/merge") {
                val req = call.mergeRequest() ?: return@post
                call.respondMerged("category", LibraryRepository.mergeCategories(call.userId(), req.survivorId, req.duplicateIds))
            }
            post("/delete") {
                val req = call.deleteRequest() ?: return@post
                call.respond(LibraryRepository.deleteCategories(call.userId(), req.ids))
            }
        }

        route("/api/tags") {
            get {
                val userId = call.userId()
                val items = LibraryRepository.listTags(userId)
                call.response.headers.append("X-Total-Count", LibraryRepository.countTags(userId).toString())
                call.respond(items)
            }
            post {
                val incoming = call.receive<ServerTag>()
                call.safeId(incoming.id) ?: return@post
                call.respond(HttpStatusCode.Created, LibraryRepository.upsertTag(call.userId(), incoming))
            }
            put("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@put
                val t = call.receive<ServerTag>().copy(id = id)
                call.respond(LibraryRepository.upsertTag(call.userId(), t))
            }
            delete("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@delete
                val ok = LibraryRepository.deleteTag(call.userId(), id)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
            post("/merge") {
                val req = call.mergeRequest() ?: return@post
                call.respondMerged("tag", LibraryRepository.mergeTags(call.userId(), req.survivorId, req.duplicateIds))
            }
            post("/delete") {
                val req = call.deleteRequest() ?: return@post
                call.respond(LibraryRepository.deleteTags(call.userId(), req.ids))
            }
        }
    }
}

/**
 * The body of a merge, with every id checked the way a path id is: a survivor or duplicate that could
 * not name a row is a 400 before anything is read. Null once the 400 has been sent.
 */
private suspend fun ApplicationCall.mergeRequest(): LibraryMergeRequest? {
    val req = receive<LibraryMergeRequest>()
    safeId(req.survivorId) ?: return null
    for (id in req.duplicateIds) safeId(id) ?: return null
    return req
}

/** The body of a bulk delete, ids checked the same way. Null once the 400 has been sent. */
private suspend fun ApplicationCall.deleteRequest(): LibraryDeleteRequest? {
    val req = receive<LibraryDeleteRequest>()
    for (id in req.ids) safeId(id) ?: return null
    return req
}

/** A merge whose survivor is not the caller's is a 404: there is nothing to fold into. */
private suspend fun ApplicationCall.respondMerged(kind: String, result: LibraryMergeResponse?) {
    if (result == null) respond(HttpStatusCode.NotFound, mapOf("error" to "No such $kind"))
    else respond(result)
}
