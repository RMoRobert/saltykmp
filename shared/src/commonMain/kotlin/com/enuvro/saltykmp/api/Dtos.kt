package com.enuvro.saltykmp.api

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.ShoppingListListContents
import com.enuvro.saltykmp.db.model.Variation
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer

// Wire DTOs shared by the Ktor server and (later) the KMP sync client. The JSON shape MUST match the
// Swift `Server*` Codable structs so the existing Salty Swift app stays compatible. Timestamps are
// carried as ISO-8601 strings (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`) — pure passthrough on the wire; the
// server does all timestamp comparison in java.time internally. The nested list element types reuse
// the existing @Serializable models (compatible with the wire shapes given ignoreUnknownKeys).

@Serializable
data class ServerRecipe(
    val id: String,
    val name: String = "",
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
    val lastPrepared: String? = null,
    // Independent of lastModifiedDate: bumped ONLY when lastPrepared changes, so marking a recipe made
    // doesn't register as a body edit (which would reorder every client's "Date Modified" sort). The
    // server merges lastPrepared by this stamp on every upsert, so the pair must travel together.
    val lastModifiedPreparedDate: String? = null,
    val source: String? = null,
    val sourceDetails: String? = null,
    val introduction: String? = null,
    val difficulty: Int? = null,
    val rating: Int? = null,
    val imageFilename: String? = null,
    // Independent of lastModifiedDate: bumped ONLY when the image itself changes (set/replaced/removed),
    // so a text-only edit never re-transfers the image and an image-only edit never re-transfers the body.
    val lastModifiedImageDate: String? = null,
    val isFavorite: Boolean? = null,
    val wantToMake: Boolean? = null,
    val yield: String? = null,
    val servings: Int? = null,
    val courseId: String? = null,
    val course: ServerCourse? = null,
    val directions: List<Direction>? = null,
    val ingredients: List<Ingredient>? = null,
    val notes: List<Note>? = null,
    val variations: List<Variation>? = null,
    val preparationTimes: List<PreparationTime>? = null,
    val nutrition: NutritionInformation? = null,
    val categoryIds: List<String>? = null,
    val tagIds: List<String>? = null,
)

@Serializable
data class ServerCourse(val id: String, val name: String? = null, val lastModifiedDate: String? = null)

@Serializable
data class ServerCategory(val id: String, val name: String? = null, val lastModifiedDate: String? = null)

@Serializable
data class ServerTag(val id: String, val name: String? = null, val lastModifiedDate: String? = null)

/** Lightweight sync-index entry (GET /api/recipes/sync/manifest). Carries the image filename + image
 * timestamp so clients reconcile image transfer independently of the recipe body, without extra probes —
 * and likewise the prepared-date stamp, so "last made on" reconciles on its own clock. */
@Serializable
data class RecipeManifestEntry(
    val id: String,
    val lastModifiedDate: String? = null,
    val imageFilename: String? = null,
    val lastModifiedImageDate: String? = null,
    // The "last made on" value AND its stamp, so the prepared-date pass can settle a recipe from the
    // manifest alone — the same reason imageFilename rides along with lastModifiedImageDate.
    val lastPrepared: String? = null,
    val lastModifiedPreparedDate: String? = null,
)

// ---- Auth ----

@Serializable
data class AuthRequest(
    val username: String,
    val password: String,
    /**
     * The device to enrol. Required — signing in and enrolling are one act — but nullable on the wire
     * so a client that omits it gets a 400 explaining itself rather than a deserialization failure.
     */
    val deviceId: String? = null,
    val deviceName: String? = null,
)

/**
 * What a successful sign-in or token check returns.
 *
 * `token`/`expiresIn` used to sit here, carrying a short-lived JWT that clients presented on every
 * sync request. There is no JWT any more — the device sync token authenticates those routes itself —
 * so the fields are gone rather than left behind holding nothing.
 */
@Serializable
data class AuthResponse(
    val username: String,
    /**
     * The device sync token, returned exactly once — on the login that enrols the device. Absent
     * from `/api/auth/token/verify`, whose caller is already holding it.
     *
     * Nullable for a second reason too: it is what a client gets back from a server older than
     * mandatory enrolment, and recognising that as "this server cannot enrol me" beats crashing on
     * a missing field.
     */
    val deviceToken: String? = null,
)

/** One row of the devices list. Carries no token and no hash — only what a person needs to decide. */
@Serializable
data class DeviceListEntry(
    val deviceId: String,
    val deviceName: String? = null,
    val firstSyncDate: String? = null,
    val lastSyncDate: String? = null,
    val tokenLastUsed: String? = null,
    /** False once revoked, so the list can show a device that exists but can no longer sync. */
    val hasToken: Boolean = false,
)

@Serializable
data class DeviceRenameRequest(val deviceName: String)

// ---- Device sync ----

@Serializable
data class DeviceRegisterRequest(val deviceId: String, val deviceName: String? = null)

@Serializable
data class DeviceSyncInfo(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val lastSyncDate: String? = null,
    val firstSyncDate: String? = null,
    /**
     * Whether this device has ever FINISHED a sync — equivalently, whether [lastSyncDate] is null.
     * It is not "the server has never seen this device": enrolment creates the row, so a client that
     * has only just signed in has a row and no watermark.
     *
     * SYNC-006 makes this the flag that suppresses deletion inference, so a client that treats it as
     * "returning device" without a watermark deletes data. See `DeviceRepository.getOrCreate`.
     */
    val isFirstSync: Boolean = false,
)

/**
 * A shopping list on the wire. Mirrors the Swift `ShoppingList` and KMP's `shoppingList` table.
 *
 * `contentsForList` is carried as a typed list and stored server-side as JSON text, the same way
 * `ServerRecipe.directions` etc. are. Sync granularity is the whole row (most-recently-modified
 * wins), so list items intentionally have no independent identity on the server.
 *
 * Every field past `id` is optional so an older client that predates a field still round-trips —
 * the same tolerance the recipe DTO relies on, since there is no protocol version field.
 */
@Serializable
data class ServerShoppingList(
    val id: String,
    val name: String? = null,
    val isFreeform: Boolean? = null,
    @Serializable(with = LenientShoppingListItems::class)
    val contentsForList: List<ShoppingListListContents>? = null,
    val contentsForFreeform: String? = null,
    val lastModifiedDate: String? = null,
    /** Server-owned optimistic-concurrency counter: present on every GET/save response, bumped on
     *  every accepted write. Null only from clients or servers that predate revisions. */
    val revision: Long? = null,
    /** Client → server on upload: the [revision] this edit is based on. The server rejects the write
     *  with 409 (+ its current row) when this no longer matches — that mismatch IS conflict
     *  detection. Legacy clients omit it and get timestamp-guarded last-writer-wins instead. */
    val baseRevision: Long? = null,
)

/**
 * Decodes shopping-list items one at a time, dropping any that can't be read instead of failing the
 * whole payload. Without it a single malformed item throws out of `fetchShoppingLists()` and aborts
 * the ENTIRE sync — recipes included — on every attempt. Mirrors the Swift `ServerShoppingList`
 * decoder.
 *
 * Leniency stops here on purpose: the array of *lists* stays strict, because the reconciler infers
 * deletions from absence, so silently dropping an unreadable list would delete it.
 */
internal object LenientShoppingListItems :
    JsonTransformingSerializer<List<ShoppingListListContents>>(ListSerializer(ShoppingListListContents.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        val array = element as? JsonArray ?: return element
        return JsonArray(array.filter { item ->
            val obj = item as? JsonObject ?: return@filter false
            // id and text are the only non-optional fields; everything else already tolerates absence.
            obj["id"] is JsonPrimitive && obj["text"] is JsonPrimitive
        })
    }
}

@Serializable
data class SyncDeleteRequest(val deviceId: String? = null, val recipeIds: List<String> = emptyList())

@Serializable
data class SyncDeleteResponse(val deleted: Int)
