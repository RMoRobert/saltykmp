package com.enuvro.saltykmp.export

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.model.Note
import com.enuvro.saltykmp.db.model.NutritionInformation
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The `.saltyRecipe` document — one recipe, self-contained, as the Swift app's `SaltyRecipeExport`
 * writes and reads it. A file written here opens in the Mac/iOS app and vice versa, so the shape below
 * mirrors that struct field for field.
 *
 * Differences from the stored recipe, all inherited from the Swift struct: row ids are dropped from the
 * nested lists (an importer mints its own), the image travels as bytes rather than a filename, and
 * course/categories/tags travel as NAMES rather than library ids — ids are meaningless in another
 * library, names can be resolved or created there.
 *
 * Two encoding details are load-bearing for Swift compatibility:
 * - **No fractional seconds.** Swift decodes with `JSONDecoder.dateDecodingStrategy = .iso8601`, which
 *   rejects `2026-08-25T12:34:56.789Z`. Our wire dates carry milliseconds, so [toSwiftIso8601] truncates.
 * - **Absent, not null.** Swift's synthesized encoder omits nil optionals and writes non-nil defaults;
 *   [saltyRecipeJson] reproduces that with `explicitNulls = false` + `encodeDefaults = true`.
 *
 * Key ORDER differs from Swift's (which sorts keys); declaration order reads better here and no JSON
 * parser cares.
 */
@Serializable
data class SaltyRecipeExport(
    val version: String = "1.0",
    val id: String,
    val name: String,
    val createdDate: String? = null,
    val lastModifiedDate: String? = null,
    val lastPrepared: String? = null,
    val source: String? = null,
    val sourceDetails: String? = null,
    val introduction: String? = null,
    val difficulty: Int = 0,
    val rating: Int = 0,
    /** Base64, matching Swift's default `Data` encoding. Null when the recipe has no photo. */
    val imageData: String? = null,
    val isFavorite: Boolean = false,
    val wantToMake: Boolean = false,
    val yield: String? = null,
    val servings: Int? = null,
    val course: String? = null,
    val categories: List<String>? = null,
    val tags: List<String>? = null,
    val directions: List<SaltyDirectionExport> = emptyList(),
    val ingredients: List<SaltyIngredientExport> = emptyList(),
    val notes: List<Note> = emptyList(),
    /** Optional in the Swift struct "for backwards compatibility"; kept optional so files match. */
    val variations: List<SaltyVariationExport>? = null,
    val preparationTimes: List<SaltyPreparationTimeExport> = emptyList(),
    val nutrition: NutritionInformation? = null,
)

@Serializable
data class SaltyDirectionExport(val text: String, val isHeading: Boolean? = null)

/** [isHeading]/[isMain] are written only when true — Swift maps `false` to nil before encoding. */
@Serializable
data class SaltyIngredientExport(
    val text: String,
    val isHeading: Boolean? = null,
    val isMain: Boolean? = null,
)

@Serializable
data class SaltyPreparationTimeExport(val type: String, val timeString: String)

@Serializable
data class SaltyVariationExport(val variationName: String, val text: String)

/**
 * Matches Swift's `JSONEncoder` settings for this document: pretty-printed, nil-as-absent, defaults
 * present. Ours also indents with two spaces, as `JSONSerialization`'s pretty printer does.
 */
internal val saltyRecipeJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Builds the export document for [recipe]. The library metadata is passed in rather than looked up,
 * so this stays database-free and testable — [RecipeExport] is what resolves it.
 */
@OptIn(ExperimentalEncodingApi::class)
fun saltyRecipeExport(
    recipe: ServerRecipe,
    context: RecipeExportContext = RecipeExportContext(),
): SaltyRecipeExport = SaltyRecipeExport(
    id = recipe.id,
    name = recipe.name,
    createdDate = toSwiftIso8601(recipe.createdDate),
    lastModifiedDate = toSwiftIso8601(recipe.lastModifiedDate),
    lastPrepared = toSwiftIso8601(recipe.lastPrepared),
    source = recipe.source?.ifBlank { null },
    sourceDetails = recipe.sourceDetails?.ifBlank { null },
    introduction = recipe.introduction?.ifBlank { null },
    difficulty = recipe.difficulty ?: 0,
    rating = recipe.rating ?: 0,
    imageData = context.imageData?.takeIf { it.isNotEmpty() }?.let { Base64.encode(it) },
    isFavorite = recipe.isFavorite == true,
    wantToMake = recipe.wantToMake == true,
    yield = recipe.yield?.ifBlank { null },
    servings = recipe.servings,
    course = context.courseName?.ifBlank { null },
    categories = context.categoryNames.filter { it.isNotBlank() }.ifEmpty { null },
    tags = context.tagNames.filter { it.isNotBlank() }.ifEmpty { null },
    directions = recipe.directions.orEmpty().map {
        SaltyDirectionExport(text = it.text, isHeading = it.isHeading)
    },
    ingredients = recipe.ingredients.orEmpty().map {
        SaltyIngredientExport(
            text = it.text,
            isHeading = true.takeIf { _ -> it.isHeading },
            isMain = true.takeIf { _ -> it.isMain },
        )
    },
    notes = recipe.notes.orEmpty(),
    variations = recipe.variations.orEmpty()
        .map { SaltyVariationExport(variationName = it.variationName, text = it.text) }
        .ifEmpty { null },
    preparationTimes = recipe.preparationTimes.orEmpty().map {
        SaltyPreparationTimeExport(type = it.type, timeString = it.timeString)
    },
    nutrition = recipe.nutrition,
)

/** The document as the bytes of a `.saltyRecipe` file. */
fun SaltyRecipeExport.toJsonString(): String = saltyRecipeJson.encodeToString(this)

/**
 * Readable plain text, for sending a recipe to someone who doesn't have Salty. Ports Swift's
 * `plainTextRepresentation`.
 *
 * One deliberate difference: steps are numbered by counting only non-heading rows, so a recipe with
 * section headings reads "1, 2, 3" rather than skipping the numbers the headings consumed. (Swift
 * numbers by array index and so skips; that side is worth fixing to match.)
 */
fun SaltyRecipeExport.toPlainText(): String = buildString {
    append(name).append('\n')
    append("=".repeat(name.length)).append("\n\n")

    source?.takeIf { it.isNotBlank() }?.let { append("Source: ").append(it).append('\n') }
    sourceDetails?.takeIf { it.isNotBlank() }?.let { append("Source Details: ").append(it).append('\n') }
    yield?.takeIf { it.isNotBlank() }?.let { append("Yield: ").append(it).append('\n') }
    servings?.let { append("Servings: ").append(it).append('\n') }

    introduction?.takeIf { it.isNotBlank() }?.let { append('\n').append(it).append('\n') }

    if (preparationTimes.isNotEmpty()) {
        append("\nPreparation Times:\n")
        preparationTimes.forEach { append("• ").append(it.type).append(": ").append(it.timeString).append('\n') }
    }

    if (ingredients.isNotEmpty()) {
        append("\nIngredients:\n")
        ingredients.forEach {
            if (it.isHeading == true) append('\n').append(it.text).append('\n')
            else append("• ").append(it.text).append('\n')
        }
    }

    if (directions.isNotEmpty()) {
        append("\nDirections:\n")
        var step = 0
        directions.forEach {
            if (it.isHeading == true) {
                append('\n').append(it.text).append('\n')
            } else {
                step++
                append(step).append(". ").append(it.text).append('\n')
            }
        }
    }

    if (notes.isNotEmpty()) {
        append("\nNotes:\n")
        notes.forEach { append("• ").append(it.title).append(": ").append(it.content).append('\n') }
    }

    variations?.takeIf { it.isNotEmpty() }?.let { list ->
        append("\nVariations:\n")
        list.forEach {
            if (it.variationName.isNotBlank()) append("• ").append(it.variationName).append(": ").append(it.text).append('\n')
            else append("• ").append(it.text).append('\n')
        }
    }
}

/**
 * Our wire timestamps down to what Swift's `.iso8601` strategy accepts: whole seconds, `Z`-suffixed.
 * Returns null for absent or unparseable input rather than emitting something the Mac app would refuse
 * to decode — losing a `createdDate` costs nothing, a rejected file costs the whole recipe.
 */
@OptIn(ExperimentalTime::class)
internal fun toSwiftIso8601(wire: String?): String? {
    val value = wire?.takeIf { it.isNotBlank() } ?: return null
    val instant = runCatching { Instant.parse(value) }.getOrNull() ?: return null
    return Instant.fromEpochSeconds(instant.epochSeconds).toString()
}
