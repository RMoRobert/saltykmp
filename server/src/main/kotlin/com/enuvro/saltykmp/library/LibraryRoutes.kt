package com.enuvro.saltykmp.library

import com.enuvro.saltykmp.api.ServerCategory
import com.enuvro.saltykmp.api.ServerCourse
import com.enuvro.saltykmp.api.ServerTag
import com.enuvro.saltykmp.auth.JWT_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.LibraryRepository
import io.ktor.http.HttpStatusCode
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
    authenticate(JWT_AUTH) {
        route("/api/courses") {
            get {
                val userId = call.userId()
                val items = LibraryRepository.listCourses(userId)
                call.response.headers.append("X-Total-Count", LibraryRepository.countCourses(userId).toString())
                call.respond(items)
            }
            post {
                val saved = LibraryRepository.upsertCourse(call.userId(), call.receive<ServerCourse>())
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val c = call.receive<ServerCourse>().copy(id = call.parameters["id"]!!)
                call.respond(LibraryRepository.upsertCourse(call.userId(), c))
            }
            delete("/{id}") {
                val ok = LibraryRepository.deleteCourse(call.userId(), call.parameters["id"]!!)
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
                val saved = LibraryRepository.upsertCategory(call.userId(), call.receive<ServerCategory>())
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val c = call.receive<ServerCategory>().copy(id = call.parameters["id"]!!)
                call.respond(LibraryRepository.upsertCategory(call.userId(), c))
            }
            delete("/{id}") {
                val ok = LibraryRepository.deleteCategory(call.userId(), call.parameters["id"]!!)
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
                val saved = LibraryRepository.upsertTag(call.userId(), call.receive<ServerTag>())
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val t = call.receive<ServerTag>().copy(id = call.parameters["id"]!!)
                call.respond(LibraryRepository.upsertTag(call.userId(), t))
            }
            delete("/{id}") {
                val ok = LibraryRepository.deleteTag(call.userId(), call.parameters["id"]!!)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }
        }
    }
}
