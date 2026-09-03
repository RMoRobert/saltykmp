package com.enuvro.saltykmp.recipe

import com.enuvro.saltykmp.api.DeviceRegisterRequest
import com.enuvro.saltykmp.api.DeviceSyncInfo
import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.api.SyncDeleteRequest
import com.enuvro.saltykmp.api.SyncDeleteResponse
import com.enuvro.saltykmp.auth.ApiCsrfGuard
import com.enuvro.saltykmp.auth.DEVICE_TOKEN_AUTH
import com.enuvro.saltykmp.auth.DeviceTokenPrincipal
import com.enuvro.saltykmp.auth.MAX_DEVICE_ID_LENGTH
import com.enuvro.saltykmp.auth.WEB_API_AUTH
import com.enuvro.saltykmp.auth.userId
import com.enuvro.saltykmp.db.DeviceRepository
import com.enuvro.saltykmp.db.RecipeRepository
import com.enuvro.saltykmp.image.ImageStore
import com.enuvro.saltykmp.image.ImageTooLargeException
import com.enuvro.saltykmp.image.UnsupportedImageFormatException
import com.enuvro.saltykmp.util.WireDate
import com.enuvro.saltykmp.util.safeId
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.install
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
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
    // DEVICE_TOKEN_AUTH is listed HERE and nowhere else. That omission is the scope
    // enforcement: a device sync token cannot authenticate against account or admin
    // routes because the provider that understands it is not mounted on them.
    authenticate(DEVICE_TOKEN_AUTH, WEB_API_AUTH) {
        // Browser callers arrive with the session cookie; guard their writes against CSRF.
        install(ApiCsrfGuard)

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

                /*
                 * `fields=summary` drops the recipe bodies. Opt-in, and left out of every other
                 * shape of this request on purpose: this is the endpoint the Swift and Compose
                 * clients sync against, and a sync that quietly stopped receiving ingredients
                 * would be a data-loss bug rather than a slow one. The browser asks for it because
                 * the browser is drawing a list and fetches the recipe it opens anyway.
                 */
                val summaryOnly = call.request.queryParameters["fields"] == "summary"

                fun ApplicationCall.reportPaging(total: Long, totalPages: Int, pageNumber: Int) {
                    response.headers.append("X-Total-Count", total.toString())
                    if (page != null) {
                        response.headers.append("X-Total-Pages", totalPages.toString())
                        response.headers.append("X-Page-Number", pageNumber.toString())
                    }
                }

                if (summaryOnly) {
                    val result = RecipeRepository.listSummaries(userId, since, page, size)
                    call.reportPaging(result.total, result.totalPages, result.pageNumber)
                    call.respond(result.recipes)
                } else {
                    val result = RecipeRepository.listForSync(userId, since, page, size)
                    call.reportPaging(result.total, result.totalPages, result.pageNumber)
                    call.respond(result.recipes)
                }
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
            /*
             * A sync token may only act on the device it was issued for (SYNC-019).
             *
             * The rows here are the caller's own devices, so this was never a way across accounts --
             * but `/complete` stamps `lastSyncDate`, and `isFirstSync` is defined as that column being
             * null. That flag is what suppresses deletion inference (see DeviceRepository.getOrCreate),
             * so marking a SIBLING device's untouched row as synced could make that device treat its
             * first real sync as a returning one and delete local rows it has no agreement about.
             *
             * Safe to require because all three clients pair the token with the id it was issued for,
             * which was checked rather than assumed: the Compose app keeps one `deviceId` per install
             * and passes it to both login and SyncService; the Swift app's `syncDeviceId` in
             * UserDefaults does the same; and Salty.NET derives an id PER LIBRARY but stores the token
             * under that same id (`DeviceTokenFor(deviceId)`) and enrols with it, so its ids and tokens
             * move together too. A client that ever diverges gets a 403 saying exactly what is wrong,
             * rather than silently operating on another device's row.
             *
             * A browser session carries no device and is unaffected -- it has no DeviceTokenPrincipal
             * to compare against, and the account's own devices page is where it manages these rows.
             */
            post("/sync/device") {
                val req = call.receive<DeviceRegisterRequest>()
                val deviceId = call.ownDeviceId(req.deviceId) ?: return@post
                call.respond(DeviceRepository.getOrCreate(call.userId(), deviceId, req.deviceName))
            }
            get("/sync/device/{deviceId}") {
                val deviceId = call.ownDeviceId(call.parameters["deviceId"]) ?: return@get
                val info = DeviceRepository.get(call.userId(), deviceId)
                call.respond(info ?: DeviceSyncInfo(isFirstSync = true))
            }
            post("/sync/device/{deviceId}/complete") {
                val deviceId = call.ownDeviceId(call.parameters["deviceId"]) ?: return@post
                DeviceRepository.completeSync(call.userId(), deviceId)
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
                val stamp = imageStamp(call.userId(), filename)
                if (stamp == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.cacheImageFor(stamp)
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
                // The thumbnail is derived from the image, so it is valid for exactly as long.
                val stamp = imageStamp(call.userId(), filename)
                if (stamp == null) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.cacheImageFor(stamp)
                val bytes = imageStore.loadThumbnail(filename)
                if (bytes == null) call.respond(HttpStatusCode.NotFound)
                else call.respondBytes(bytes, ContentType.Image.JPEG)
            }

            // CRUD by id.
            get("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@get
                val recipe = RecipeRepository.getById(call.userId(), id)
                if (recipe == null) call.respond(HttpStatusCode.NotFound) else call.respond(recipe)
            }
            // Existence check (HEAD) the client uses to choose create-vs-update; see the images HEAD note.
            head("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@head
                val recipe = RecipeRepository.getById(call.userId(), id)
                call.respond(if (recipe != null) HttpStatusCode.OK else HttpStatusCode.NotFound)
            }
            post {
                val recipe = call.receive<ServerRecipe>()
                // The body's own id, on the same terms as one from the path: it is what names the
                // recipe's image file.
                call.safeId(recipe.id) ?: return@post
                val userId = call.userId()
                val oldFilename = RecipeRepository.imageFilename(userId, recipe.id)
                // imageStore::exists makes the upsert ignore an incoming imageFilename this server does
                // not hold — the uploader's local name after a client-side conversion — which would
                // otherwise point the row at a missing file and make deleteOrphanedImage remove the real one.
                val saved = RecipeRepository.upsert(userId, recipe, imageStore::exists)
                deleteOrphanedImage(imageStore, oldFilename, saved.imageFilename)
                call.respond(HttpStatusCode.Created, saved)
            }
            put("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@put
                val incoming = call.receive<ServerRecipe>()
                val recipe = incoming.copy(id = id)
                val userId = call.userId()
                val oldFilename = RecipeRepository.imageFilename(userId, recipe.id)
                val saved = RecipeRepository.upsert(userId, recipe, imageStore::exists)
                // When a sync upload clears or changes imageFilename, remove the now-unreferenced file
                // (the dedicated image endpoints already clean up; this covers the recipe-upsert path).
                deleteOrphanedImage(imageStore, oldFilename, saved.imageFilename)
                call.respond(saved)
            }
            delete("/{id}") {
                val id = call.safeId(call.parameters["id"]) ?: return@delete
                val ok = RecipeRepository.delete(call.userId(), id)
                call.respond(if (ok) HttpStatusCode.NoContent else HttpStatusCode.NotFound)
            }

            // Recipe image upload / delete / redirect.
            post("/{id}/image") {
                val userId = call.userId()
                val id = call.safeId(call.parameters["id"]) ?: return@post
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
                var unsupportedFormat = false
                // Client-authoritative image timestamp (optional form field). Stored verbatim so the
                // uploading device doesn't see the server as "newer" and re-download its own image.
                var imageDateStr: String? = null
                call.receiveMultipart().forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            // Bounded read: one byte past the cap is enough to know it was exceeded,
                            // and reading no further is what keeps a chunked or understated upload
                            // from pulling gigabytes into memory before the check below runs.
                            val bytes = part.provider()
                                .readRemaining(MAX_IMAGE_UPLOAD_BYTES + 1)
                                .readByteArray()
                            // Guard the case where Content-Length was absent or understated.
                            if (bytes.size > MAX_IMAGE_UPLOAD_BYTES) {
                                oversized = true
                            } else {
                                try {
                                    // The stored extension comes from the BYTES (ImageStore.store), not
                                    // from this part's Content-Type — a mislabelled upload used to write
                                    // e.g. HEIC content to "<id>.jpg", which nothing downstream can read.
                                    //
                                    // Store first, then remove any previous image — but only when it had a
                                    // different name (a same-name store already overwrote it). This way a
                                    // rejected upload (e.g. an over-resolution bomb) can't destroy the
                                    // existing good image.
                                    val newName = imageStore.store(id, bytes)
                                    RecipeRepository.imageFilename(userId, id)
                                        ?.takeIf { it != newName }
                                        ?.let { imageStore.delete(it) }
                                    stored = newName
                                } catch (e: ImageTooLargeException) {
                                    dimTooLarge = true
                                } catch (e: UnsupportedImageFormatException) {
                                    unsupportedFormat = true
                                }
                            }
                        }
                        is PartData.FormItem -> if (part.name == "lastModifiedImageDate") imageDateStr = part.value
                        else -> {}
                    }
                    part.release()
                }
                if (oversized) {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Image exceeds ${MAX_IMAGE_UPLOAD_BYTES / (1024 * 1024)} MB limit"))
                    return@post
                }
                if (dimTooLarge) {
                    call.respond(HttpStatusCode.PayloadTooLarge, mapOf("error" to "Image resolution exceeds the allowed maximum"))
                    return@post
                }
                if (unsupportedFormat) {
                    // 415, not 400: the request was well-formed, the payload's media type isn't one we can
                    // serve. A client that can't convert locally gets a clear answer instead of silently
                    // uploading something no other device will be able to open.
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        mapOf("error" to "Unsupported image format. The server stores JPEG, PNG and GIF; convert the image first."),
                    )
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
                val id = call.safeId(call.parameters["id"]) ?: return@delete
                // Client-authoritative removal timestamp so other devices detect the deletion via the manifest.
                val imageDate = call.request.queryParameters["lastModifiedImageDate"]
                    ?.let { runCatching { WireDate.parse(it) }.getOrNull() }
                RecipeRepository.imageFilename(userId, id)?.let { imageStore.delete(it) }
                // Stamp even when no file existed, so the cleared state propagates with a fresh timestamp.
                RecipeRepository.setImageFilename(userId, id, null, imageDate)
                call.respond(HttpStatusCode.NoContent)
            }
            get("/{id}/image") {
                val id = call.safeId(call.parameters["id"]) ?: return@get
                val fn = RecipeRepository.imageFilename(call.userId(), id)
                if (fn == null || !imageStore.exists(fn)) {
                    call.respond(HttpStatusCode.NotFound)
                    return@get
                }
                call.respondRedirect("/api/recipes/images/$fn", permanent = false)
            }
        }
    }
}

/**
 * A device id this caller may act on, or null after responding.
 *
 * Two checks in one. The length is what the `device_sync.device_id` column can hold, and a longer one
 * used to surface as a 500 from the insert -- unlike a recipe id it names no file, so nothing else
 * about its shape is constrained. The identity check is that a sync token may only name the device it
 * was issued for; see the note at the routes for why, and why every client already satisfies it.
 */
private suspend fun ApplicationCall.ownDeviceId(raw: String?): String? {
    if (raw == null || raw.isBlank() || raw.length > MAX_DEVICE_ID_LENGTH) {
        respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid device id"))
        return null
    }
    val enrolled = principal<DeviceTokenPrincipal>()?.deviceId
    if (enrolled != null && enrolled != raw) {
        respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to "This sync token belongs to a different device"),
        )
        return null
    }
    return raw
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
private suspend fun ownsImage(userId: String, filename: String): Boolean =
    imageStamp(userId, filename) != null

/**
 * Proof of ownership and a cache validator in one lookup.
 *
 * Returns null when the caller does not own this filename -- which is the access check the image
 * routes have always made -- and otherwise the recipe's `lastModifiedImageDate`, or the empty string
 * when the row predates that column. The stamp is bumped when and only when the image bytes change,
 * which is exactly what an ETag has to promise, so there is nothing to hash.
 */
private suspend fun imageStamp(userId: String, filename: String): String? {
    val recipeId = filename.substringBeforeLast('.', "")
    if (recipeId.isEmpty()) return null
    val (stored, stamp) = RecipeRepository.imageIdentity(userId, recipeId) ?: return null
    return if (stored == filename) stamp.orEmpty() else null
}

/**
 * How long the caller may keep these bytes.
 *
 * A bare URL gets the app-wide `no-cache`: revalidate every time, and the ETag turns that into a
 * 304 rather than a re-download. A URL carrying the current stamp as `?v=` gets a year and
 * `immutable`, because that URL cannot come to mean different bytes -- changing the image changes
 * the stamp and therefore the URL. A `?v=` that does *not* match is a stale link, and gets the
 * cautious answer rather than being pinned for a year.
 */
private fun ApplicationCall.cacheImageFor(stamp: String) {
    if (stamp.isNotEmpty() && request.queryParameters["v"] == stamp) {
        response.header(HttpHeaders.CacheControl, "private, max-age=31536000, immutable")
    }
    if (stamp.isNotEmpty()) response.header(HttpHeaders.ETag, "\"$stamp\"")
}
