package com.enuvro.saltykmp.export

import com.enuvro.saltykmp.api.ServerRecipe
import com.enuvro.saltykmp.db.model.NutritionInformation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.floor

/**
 * Serializes a recipe to schema.org/Recipe JSON-LD — the inverse of [SchemaOrgRecipeParser], and a port
 * of the Swift app's `SchemaOrgRecipeJSONLDExporter`, with the same field mapping so a file exported by
 * either app re-imports into the other.
 *
 * This is the interop format: standards-shaped, readable by other recipe tools. For a lossless
 * Salty-to-Salty round trip use [SaltyRecipeExport] instead — schema.org has no representation for
 * section headings (ingredient or direction), the ingredient `isMain` flag, difficulty, rating, added
 * sugars, or the local image bytes, and all of those are deliberately dropped here.
 *
 * Keys are emitted sorted, and the output is indented two spaces, so a file written here diffs cleanly
 * against one written by the Swift app (`JSONSerialization` with `.sortedKeys` + `.prettyPrinted`).
 */
object SchemaOrgRecipeJsonLdExporter {

    private val json = Json { prettyPrint = true; prettyPrintIndent = "  " }

    /** A single schema.org Recipe object, `@context` included, so it stands alone as valid JSON-LD. */
    fun jsonObject(recipe: ServerRecipe, context: RecipeExportContext = RecipeExportContext()): JsonObject {
        val fields = mutableMapOf<String, JsonElement>(
            "@context" to JsonPrimitive("https://schema.org"),
            "@type" to JsonPrimitive("Recipe"),
            "name" to JsonPrimitive(recipe.name),
        )

        recipe.introduction?.takeIf { it.isNotBlank() }?.let { fields["description"] = JsonPrimitive(it) }
        recipe.source?.takeIf { it.isNotBlank() }?.let {
            fields["author"] = sorted(mapOf("@type" to JsonPrimitive("Person"), "name" to JsonPrimitive(it)))
        }
        // On import a source URL lands in sourceDetails; only emit `url` when it really is one. Free-text
        // details ("p. 12") have no standard schema.org home and are dropped rather than mislabelled.
        recipe.sourceDetails?.takeIf { isUrl(it) }?.let { fields["url"] = JsonPrimitive(it.trim()) }

        toSwiftIso8601(recipe.createdDate)?.let { fields["datePublished"] = JsonPrimitive(it) }

        yieldString(recipe)?.let { fields["recipeYield"] = JsonPrimitive(it) }

        val ingredientLines = recipe.ingredients.orEmpty().filterNot { it.isHeading }.map { it.text }
        if (ingredientLines.isNotEmpty()) {
            fields["recipeIngredient"] = JsonArray(ingredientLines.map { JsonPrimitive(it) })
        }

        val steps = recipe.directions.orEmpty()
            .filterNot { it.isHeading == true }
            .map { sorted(mapOf("@type" to JsonPrimitive("HowToStep"), "text" to JsonPrimitive(it.text))) }
        if (steps.isNotEmpty()) fields["recipeInstructions"] = JsonArray(steps)

        recipe.preparationTimes.orEmpty().forEach { time ->
            val iso = iso8601Duration(time.timeString) ?: return@forEach
            when (time.type.lowercase()) {
                "prep" -> fields["prepTime"] = JsonPrimitive(iso)
                "cook" -> fields["cookTime"] = JsonPrimitive(iso)
                "total" -> fields["totalTime"] = JsonPrimitive(iso)
            }
        }

        nutritionObject(recipe.nutrition)?.let { fields["nutrition"] = it }

        // Salty's course is the closest match to schema.org's recipeCategory ("appetizer", "entree"…);
        // tags + categories become free-text keywords.
        context.courseName?.takeIf { it.isNotBlank() }?.let { fields["recipeCategory"] = JsonPrimitive(it) }
        val keywords = (context.tagNames + context.categoryNames).filter { it.isNotBlank() }
        if (keywords.isNotEmpty()) fields["keywords"] = JsonPrimitive(keywords.joinToString(", "))

        return sorted(fields)
    }

    /** The JSON-LD document for one recipe. */
    fun render(recipe: ServerRecipe, context: RecipeExportContext = RecipeExportContext()): String =
        json.encodeToString(JsonObject.serializer(), jsonObject(recipe, context))

    // MARK: - Field helpers

    private fun yieldString(recipe: ServerRecipe): String? =
        recipe.yield?.takeIf { it.isNotBlank() } ?: recipe.servings?.toString()

    private fun isUrl(value: String): Boolean {
        val trimmed = value.trim().lowercase()
        return trimmed.startsWith("http://") || trimmed.startsWith("https://")
    }

    /**
     * A schema.org NutritionInformation object whose values carry the unit suffixes the importer parses
     * back. Null when there is nothing but the `@type` to emit.
     */
    private fun nutritionObject(nutrition: NutritionInformation?): JsonObject? {
        val n = nutrition ?: return null
        val fields = mutableMapOf<String, JsonElement>("@type" to JsonPrimitive("NutritionInformation"))

        n.servingSize?.takeIf { it.isNotBlank() }?.let { fields["servingSize"] = JsonPrimitive(it) }
        n.calories?.let { fields["calories"] = JsonPrimitive("${number(it)} calories") }

        fun measured(key: String, value: Double?, unit: String) {
            value?.let { fields[key] = JsonPrimitive("${number(it)} $unit") }
        }
        measured("proteinContent", n.protein, "g")
        measured("carbohydrateContent", n.carbohydrates, "g")
        measured("fatContent", n.fat, "g")
        measured("saturatedFatContent", n.saturatedFat, "g")
        measured("transFatContent", n.transFat, "g")
        measured("fiberContent", n.fiber, "g")
        measured("sugarContent", n.sugar, "g")
        measured("sodiumContent", n.sodium, "mg")
        measured("cholesterolContent", n.cholesterol, "mg")
        measured("calciumContent", n.calcium, "mg")
        measured("ironContent", n.iron, "mg")
        measured("potassiumContent", n.potassium, "mg")
        measured("vitaminCContent", n.vitaminC, "mg")
        measured("vitaminDContent", n.vitaminD, "µg")
        measured("vitaminAContent", n.vitaminA, "µg")

        return if (fields.size > 1) sorted(fields) else null
    }

    /** Drops a trailing ".0" so whole measurements read "9", not "9.0". */
    private fun number(value: Double): String =
        if (value.isFinite() && value == floor(value)) value.toLong().toString() else value.toString()

    /**
     * "15 min" / "1 hr 30 min" → an ISO-8601 duration ("PT15M", "PT1H30M"). Null when nothing parseable
     * is found, in which case the caller omits the field rather than emitting an invalid duration.
     */
    private fun iso8601Duration(human: String): String? {
        val lower = human.lowercase()
        val hours = HOURS.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = MINUTES.find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val h = hours ?: 0
        val m = minutes ?: 0
        if (h <= 0 && m <= 0) return null
        return buildString {
            append("PT")
            if (h > 0) append(h).append('H')
            if (m > 0) append(m).append('M')
        }
    }

    private val HOURS = Regex("""(\d+)\s*(?:hours?|hrs?|h)\b""")
    private val MINUTES = Regex("""(\d+)\s*(?:minutes?|mins?|m)\b""")

    /**
     * Swift serializes with `.sortedKeys`; a JsonObject keeps insertion order, so sort going in.
     * (`toSortedMap` is JVM-only, and `associate` gives back a LinkedHashMap, which holds the order.)
     */
    private fun sorted(fields: Map<String, JsonElement>): JsonObject =
        JsonObject(fields.entries.sortedBy { it.key }.associate { it.key to it.value })
}
