package com.enuvro.saltykmp.api

import com.enuvro.saltykmp.db.model.Direction
import com.enuvro.saltykmp.db.model.Ingredient
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import com.enuvro.saltykmp.db.model.PreparationTime
import com.enuvro.saltykmp.db.model.Variation
import kotlinx.serialization.Serializable

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
 * timestamp so clients reconcile image transfer independently of the recipe body, without extra probes. */
@Serializable
data class RecipeManifestEntry(
    val id: String,
    val lastModifiedDate: String? = null,
    val imageFilename: String? = null,
    val lastModifiedImageDate: String? = null,
)

// ---- Auth ----

@Serializable
data class AuthRequest(val username: String, val password: String)

@Serializable
data class AuthResponse(val token: String, val username: String, val expiresIn: Long)

// ---- Device sync ----

@Serializable
data class DeviceRegisterRequest(val deviceId: String, val deviceName: String? = null)

@Serializable
data class DeviceSyncInfo(
    val deviceId: String? = null,
    val deviceName: String? = null,
    val lastSyncDate: String? = null,
    val firstSyncDate: String? = null,
    val isFirstSync: Boolean = false,
)

@Serializable
data class SyncDeleteRequest(val deviceId: String? = null, val recipeIds: List<String> = emptyList())

@Serializable
data class SyncDeleteResponse(val deleted: Int)
