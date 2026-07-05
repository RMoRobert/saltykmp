package com.enuvro.saltykmp.recipe

import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.SyncDeleteRequest
import com.enuvro.saltykmp.api.SyncDeleteResponse
import com.enuvro.saltykmp.auth.JWT_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.image.ImageTooLargeException
import com.enuvro.saltykmp.util.WireDate
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

private const val DEFAULT_PAGE_SIZE = 100
// Upper bound on a client-requested page size, so a `size=10000000` request can't make the server
// query and serialize the whole library at once.
private const val MAX_PAGE_SIZE = 500
// Cap on an uploaded recipe image. The server — not the client — is the trust boundary; an unbounded
// multipart upload would read straight into memory. Generous for a prepared recipe photo.
private const val MAX_IMAGE_UPLOAD_BYTES = 25L * 1024 * 1024

fun Route.recipeRoutes(imageStore: ImageStore) {
    authenticate(JWT_AUTH) {
        route("/api/recipes") {

            // List — optional modifiedSince delta + page/size pagination. The delta omits unchanged
            // recipes, so clients reconcile deletions against /sync/manifest, never this list.
            get {
                val userId = call.userId()
                val modifiedSinceStr = call.request.queryParameters["modifiedSince"]
                val since = if (!modifiedSinceStr.isNullOrBlank()) {
                    val parsed = runCatching { WireDate.parse(modifiedSinceStr) }.getOrNull()
                    if (parsed == null) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid modifiedSince (expected ISO-8601)"))
                        return@get
                    }
                    parsed
                } else null
                val page = call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(0)
                val size = call.request.queryParameters["size"]?.toIntOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE

                val result = RecipeRepository.listForSync(userId, since, page, size)
                call.response.headers.append("X-Total-Count", result.total.toString())
                if (page != null) {
                    call.response.headers.append("X-Total-Pages", result.totalPages.toString())
                    call.response.headers.append("X-Page-Number", result.pageNumber.toString())
                }
                call.respond(result.recipes)
            }

            // Lightweight sync index (all ids + timestamps for the user).
            get("/sync/manifest") {
                // Don't advertise an image whose bytes we don't actually have: a recipe-body upload sets
                // image_filename (the body carries the filename) without the image ever being POSTed to
                // /{id}/image. If the manifest reported it as present, a client comparing image state would
                // see "both sides have this image" (equal/null dates) and never upload it — the image would
                // stay permanently missing. Reporting it absent makes the client push the bytes it holds.
                val manifest = RecipeRepository.manifest(call.userId()).map { entry ->
                    val fn = entry.imageFilename
                    if (fn != null && !imageStore.exists(fn)) {
                        entry.copy(imageFilename = null, lastModifiedImageDate = null)
                    } else entry
                }
                call.response.headers.append("X-Total-Count", manifest.size.toString())
                call.respond(manifest)
            }

            // Device sync registration / state.
            post("/sync/device") {
                val req = call.receive<DeviceRegisterRequest>()
                call.respond(DeviceRepository.getOrCreate(call.userId(), req.deviceId, req.deviceName))
            }
            get("/sync/device/{deviceId}") {
                val info = DeviceRepository.get(call.userId(), call.parameters["deviceId"]!!)
                call.respond(info ?: DeviceSyncInfo(isFirstSync = true))
            }
            post("/sync/device/{deviceId}/complete") {
                DeviceRepository.completeSync(call.userId(), call.parameters["deviceId"]!!)
                call.respond(HttpStatusCode.OK)
            }

            // Bulk delete (recipes deleted on a client).
            post("/sync/delete") {
                val req = call.receive<SyncDeleteRequest>()
                val deleted = RecipeRepository.deleteMany(call.userId(), req.recipeIds)
                call.respond(SyncDeleteResponse(deleted))
            }

            // Image serving (filename-addressed). Images are named "<recipeId>.<ext>"; we only serve a
            // filename that belongs to a recipe owned by the caller, so one user can't read another user's
            // images by guessing/knowing a filename (the rest of the API is already user-scoped).
            get("/images/{filename}") {
                val filename = call.parameters["filename"]!!
                if (!ownsImage(call.userId(), filename)) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val bytes = imageStore.load(filename)
                if (bytes == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val ct = when (filename.substringAfterLast('.', "").lowercase()) {
                    "png" -> ContentType.Image.PNG
                    "gif" -> ContentType.Image.GIF
                    else -> ContentType.Image.JPEG
                }
                call.respondBytes(bytes, ct)
            }
            // Existence check the client uses before deciding to (re)upload an image. The Swift sync
            // client sends HEAD here; Ktor does NOT auto-answer HEAD for a `get` route, so without this
            // the check always failed (404/405) and every image re-uploaded on every sync.
            head("/images/{filename}") {
                val filename = call.parameters["filename"]!!
                val exists = ownsImage(call.userId(), filename) && imageStore.exists(filename)
                call.respond(if (exists) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }
            // Bandwidth-friendly thumbnail (generated + disk-cached on demand), always JPEG.
            get("/images/{filename}/thumbnail") {
                val filename = call.parameters["filename"]!!
                if (!ownsImage(call.userId(), filename)) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                val bytes = imageStore.loadThumbnail(filename)
                if (bytes == null) call.respond(HttpStatusCode.NotFound)
                else call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            // CRUD by id.
            get("/{id}") {
                val recipe = RecipeRepository.getById(call.userId(), call.parameters["id"]!!)
                if (recipe == null) call.respond(HttpStatusCode.NotFound) else call.respond(recipe)
            }
            // Existence check (HEAD) the client uses to choose create-vs-update; see the images HEAD note.
            head("/{id}") {
                val recipe = RecipeRepository.getById(call.userId(), call.parameters["id"]!!)
                call.respond(if (recipe != null) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }
            post {
                val recipe = call.receive<ServerRecipe>()
                val userId = call.userId()
                val oldFilename = RecipeRepository.imageFilename(userId, recipe.id)
                val saved = RecipeRepository.upsert(userId, recipe)
                deleteOrphanedImage(imageStore, oldFilename, saved.imageFilename)
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val incoming = call.receive<ServerRecipe>()
                val recipe = incoming.copy(id = call.parameters["id"]!!)
                val userId = call.userId()
                val oldFilename = RecipeRepository.imageFilename(userId, recipe.id)
                val saved = RecipeRepository.upsert(userId, recipe)
                // When a sync upload clears or changes imageFilename, remove the now-unreferenced file
                // (the dedicated image endpoints already clean up; this covers the recipe-upsert path).
                deleteOrphanedImage(imageStore, oldFilename, saved.imageFilename)
                call.respond(saved)
            }
            delete("/{id}") {
                val ok = RecipeRepository.delete(call.userId(), call.parameters["id"]!!)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }

            // Recipe image upload / delete / redirect.
            post("/{id}/image") {
                val userId = call.userId()
                val id = call.parameters["id"]!!
                if (RecipeRepository.getById(userId, id) == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@post
                }
                // Reject an oversized upload up front (when the client declares a length) before reading
                // anything into memory.
                val declaredLength = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                if (declaredLength != null && declaredLength > MAX_IMAGE_UPLOAD_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Image exceeds ${MAX_IMAGE_UPLOAD_BYTES / (1024 * 1024)} MB limit"))
                    return@post
                }
                var stored: String? = null
                var oversized = false
                var dimTooLarge = false
                // Client-authoritative image timestamp (optional form field). Stored verbatim so the
                // uploading device doesn't see the server as "newer" and re-download its own image.
                var imageDateStr: String? = null
                call.receiveMultipart().forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            val bytes = part.provider().readRemaining().readByteArray()
                            // Guard the case where Content-Length was absent or understated.
                            if (bytes.size > MAX_IMAGE_UPLOAD_BYTES) {
                                oversized = true
                            } else {
                                val ext = when (part.contentType?.withoutParameters()) {
                                    ContentType.Image.PNG -> "png"
                                    ContentType.Image.GIF -> "gif"
                                    else -> "jpg"
                                }
                                try {
                                    // Store first, then remove any previous image — but only when it had a
                                    // different name (a same-name store already overwrote it). This way a
                                    // rejected upload (e.g. an over-resolution bomb) can't destroy the
                                    // existing good image.
                                    val newName = imageStore.store(id, bytes, ext)
                                    RecipeRepository.imageFilename(userId, id)
                                        ?.takeIf { it != newName }
                                        ?.let { imageStore.delete(it) }
                                    stored = newName
                                } catch (e: ImageTooLargeException) {
                                    dimTooLarge = true
                                }
                            }
                        }
                        is PartData.FormItem -> if (part.name == "lastModifiedImageDate") imageDateStr = part.value
                        else -> {}
                    }
                    part.dispose()
                }
                if (oversized) {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Image exceeds ${MAX_IMAGE_UPLOAD_BYTES / (1024 * 1024)} MB limit"))
                    return@post
                }
                if (dimTooLarge) {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Image resolution exceeds the allowed maximum"))
                    return@post
                }
                val filename = stored
                if (filename == null) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to "No file provided"))
                    return@post
                }
                val imageDate = imageDateStr?.let { runCatching { WireDate.parse(it) }.getOrNull() }
                RecipeRepository.setImageFilename(userId, id, filename, imageDate)
                call.respond(mapOf("filename" to filename, "url" to "/api/recipes/images/$filename"))
            }
            delete("/{id}/image") {
                val userId = call.userId()
                val id = call.parameters["id"]!!
                // Client-authoritative removal timestamp so other devices detect the deletion via the manifest.
                val imageDate = call.request.queryParameters["lastModifiedImageDate"]
                    ?.let { runCatching { WireDate.parse(it) }.getOrNull() }
                RecipeRepository.imageFilename(userId, id)?.let { imageStore.delete(it) }
                // Stamp even when no file existed, so the cleared state propagates with a fresh timestamp.
                RecipeRepository.setImageFilename(userId, id, null, imageDate)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/{id}/image") {
                val fn = RecipeRepository.imageFilename(call.userId(), call.parameters["id"]!!)
                if (fn == null || !imageStore.exists(fn)) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respondRedirect("/api/recipes/images/$fn", permanent = false)
            }
        }
    }
}

/** Delete a previously-stored image file once it's no longer referenced by the recipe. */
private fun deleteOrphanedImage(imageStore: ImageStore, old: String?, new: String?) {
    if (!old.isNullOrBlank() && old != new) imageStore.delete(old)
}

/**
 * True if [filename] is the image of a recipe owned by [userId]. Images are stored as "<recipeId>.<ext>",
 * so we derive the recipe id from the name and confirm that recipe (a) belongs to the user and (b) actually
 * references this exact filename — matching on the stored value, not just the id, so a stale/guessed name
 * can't slip through.
 */
private suspend fun ownsImage(userId: String, filename: String): Boolean {
    val recipeId = filename.substringBeforeLast('.', "")
    if (recipeId.isEmpty()) return false
    return RecipeRepository.imageFilename(userId, recipeId) == filename
}
