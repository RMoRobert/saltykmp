package com.enuvro.saltykmp.library

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
        }
    }
}
